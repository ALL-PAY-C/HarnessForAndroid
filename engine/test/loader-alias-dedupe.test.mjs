// T5 收口回归：同一物理目录的两个别名（设备上 /data/data 与 /data/user/0）只能装载一次。
// 背景：loader.js:267 用 resolve(dir) 字面路径做去重 key，别名路径拿到两个 key → 同一插件被 apply 两次、
// plugins.list 出现两条。修法是按真实物理路径（realpath）做 canonical key，而不是按插件 name 去重。
// 用法: node --test test/loader-alias-dedupe.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync, rmdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { connect } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createContext, listPlugins } from '../host/cordis.js';
import { loadPlugin } from '../host/loader.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureRoot = resolve(here, '..', '..', '.mock', 't5-final-loader');

/** 写一个最小插件目录：apply 时给 globalThis 上的计数器 +1（模块实例不同也共享同一计数器）。 */
function seedPlugin(dir, name) {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name, version: '0.0.0', type: 'module', main: 'index.js' }, null, 2) + '\n', 'utf8');
  writeFileSync(join(dir, 'index.js'), [
    'export const name = ' + JSON.stringify(name) + ';',
    'export const inject = [];',
    'globalThis.__t5LoaderApply = globalThis.__t5LoaderApply || { count: 0, dirs: [] };',
    'export function apply() {',
    '  globalThis.__t5LoaderApply.count += 1;',
    '  globalThis.__t5LoaderApply.dirs.push(import.meta.url);',
    '}',
  ].join('\n') + '\n', 'utf8');
}

/** 建一个指向真实目录的别名（Linux 用 dir 软链，Windows 用 junction，都不需要管理员）。 */
function linkAlias(target, alias) {
  symlinkSync(target, alias, process.platform === 'win32' ? 'junction' : 'dir');
}

/**
 * 删 fixture 目录：rm(recursive) 删不掉悬空 junction（会在父目录报 ENOTEMPTY），
 * 所以先把顶层条目逐个 rmdir（链接被摘掉，真目录有内容时抛 ENOTEMPTY 忽略），再递归删。
 */
function forceRemove(root) {
  let entries = [];
  try {
    entries = readdirSync(root);
  } catch {
    return;
  }
  for (const entry of entries) {
    try {
      rmdirSync(join(root, entry));
    } catch {
      /* 普通目录留着给下面的递归删 */
    }
  }
  rmSync(root, { recursive: true, force: true });
}

test('别名二次 load 不重复 apply（真实路径先加载，别名后加载）', async (t) => {
  const root = join(fixtureRoot, 'real-first');
  forceRemove(root);
  const real = join(root, 'plugin');
  const alias = join(root, 'plugin-alias');
  seedPlugin(real, 'alias-fixture-plugin');
  linkAlias(real, alias);
  t.after(() => forceRemove(root));

  const ctx = createContext({ home: join(root, 'home') });
  globalThis.__t5LoaderApply = { count: 0, dirs: [] };

  const first = await loadPlugin(ctx, real);
  const second = await loadPlugin(ctx, alias);

  assert.equal(globalThis.__t5LoaderApply.count, 1, '别名第二次 load 又 apply 了一次');
  assert.equal(listPlugins(ctx).length, 1, 'ctx.plugins 出现两条同名插件');
  assert.equal(second.deduped, true, '第二次 load 没被识别为重复');
  assert.equal(second.dir, first.dir, '两次 load 的 dir 不一致');
  assert.equal(second.entry, first.entry, '两次 load 的 entry 不一致');
  assert.equal(second.name, 'alias-fixture-plugin');
  ctx.dispose();
});

test('别名二次 load 不重复 apply（别名先加载，真实路径后加载）', async (t) => {
  const root = join(fixtureRoot, 'alias-first');
  forceRemove(root);
  const real = join(root, 'plugin');
  const alias = join(root, 'plugin-alias');
  seedPlugin(real, 'alias-fixture-plugin');
  linkAlias(real, alias);
  t.after(() => forceRemove(root));

  const ctx = createContext({ home: join(root, 'home') });
  globalThis.__t5LoaderApply = { count: 0, dirs: [] };

  await loadPlugin(ctx, alias);
  const second = await loadPlugin(ctx, real);

  assert.equal(globalThis.__t5LoaderApply.count, 1, '真实路径第二次 load 又 apply 了一次');
  assert.equal(listPlugins(ctx).length, 1);
  assert.equal(second.deduped, true);
  ctx.dispose();
});

test('同名但不同目录的插件各自 apply（不按 name 去重）', async (t) => {
  const root = join(fixtureRoot, 'same-name');
  forceRemove(root);
  const a = join(root, 'a');
  const b = join(root, 'b');
  seedPlugin(a, 'alias-fixture-plugin');
  seedPlugin(b, 'alias-fixture-plugin');
  t.after(() => forceRemove(root));

  const ctx = createContext({ home: join(root, 'home') });
  globalThis.__t5LoaderApply = { count: 0, dirs: [] };

  await loadPlugin(ctx, a);
  await loadPlugin(ctx, b);

  assert.equal(globalThis.__t5LoaderApply.count, 2, '不同物理目录的同名插件被错误去重');
  assert.equal(listPlugins(ctx).length, 2);
  ctx.dispose();
});

test('目录不存在仍报 插件目录不存在（realpath 失败要回退，不吞错改名）', async (t) => {
  const root = join(fixtureRoot, 'missing');
  forceRemove(root);
  t.after(() => forceRemove(root));

  const ctx = createContext({ home: join(root, 'home') });
  await assert.rejects(() => loadPlugin(ctx, join(root, 'nope')), (error) => {
    assert.equal(error.stage, 'load');
    assert.match(error.message, /插件目录不存在/);
    return true;
  });
  ctx.dispose();
});

test('嵌套别名（两跳软链）也归一到同一 key', async (t) => {
  const root = join(fixtureRoot, 'two-hop');
  forceRemove(root);
  const real = join(root, 'plugin');
  const hop = join(root, 'hop');
  const alias = join(root, 'hop-alias');
  seedPlugin(real, 'alias-fixture-plugin');
  linkAlias(real, hop);
  linkAlias(hop, alias);
  t.after(() => forceRemove(root));

  const ctx = createContext({ home: join(root, 'home') });
  globalThis.__t5LoaderApply = { count: 0, dirs: [] };

  await loadPlugin(ctx, real);
  await loadPlugin(ctx, alias);

  assert.equal(globalThis.__t5LoaderApply.count, 1, '两跳别名没归一到同一物理目录');
  assert.equal(listPlugins(ctx).length, 1);
  ctx.dispose();
});

/** 起真引擎（bin.js）并按 JSON 行协议发请求：验证 plugins.list 不出现两条。 */
async function startEngine(home, pluginDir) {
  const portFile = join(home, 'engine.port');
  mkdirSync(home, { recursive: true });
  const child = spawn(process.execPath, [join(here, '..', 'bin.js'), '--port', '0', '--home', home, '--port-file', portFile, '--plugin', pluginDir], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, DSH_HOME: home },
  });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const port = await new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('引擎 15s 内没有报端口: ' + stderr)), 15000);
    let buffered = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      buffered += chunk;
      const line = buffered.split('\n').find((entry) => entry.startsWith('DSH_ENGINE_LISTEN '));
      if (line) { clearTimeout(timer); res(Number(line.split(' ')[1])); }
    });
  });
  const socket = connect({ host: '127.0.0.1', port });
  await new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej); });
  socket.setEncoding('utf8');
  const pending = new Map();
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      const raw = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      index = buffer.indexOf('\n');
      if (raw.trim() === '') continue;
      const parsed = JSON.parse(raw);
      const settle = pending.get(parsed.id);
      if (settle) { pending.delete(parsed.id); settle(parsed); }
    }
  });
  let nextId = 0;
  const call = (method, params) => {
    const id = String(++nextId);
    return new Promise((res) => { pending.set(id, res); socket.write(JSON.stringify({ id, method, params }) + '\n'); });
  };
  const stop = async () => {
    try { await call('shutdown'); } catch { /* 关不掉就 kill */ }
    socket.destroy();
    await new Promise((res) => {
      const timer = setTimeout(res, 500);
      child.once('exit', () => { clearTimeout(timer); res(); });
      child.kill();
    });
  };
  return { call, stop, stderr: () => stderr };
}

test('引擎 --plugin 用别名启动、plugins.load 用真实路径 → plugins.list 只有一条', async (t) => {
  const root = join(fixtureRoot, 'engine-e2e');
  forceRemove(root);
  const real = join(root, 'plugin');
  const alias = join(root, 'plugin-alias');
  const home = join(root, 'home');
  seedPlugin(real, 'alias-fixture-plugin');
  linkAlias(real, alias);
  t.after(() => forceRemove(root));

  const engine = await startEngine(home, alias);
  try {
    const before = await engine.call('plugins.list', {});
    const loaded = await engine.call('plugins.load', { dir: real });
    assert.equal(loaded.ok, true, JSON.stringify(loaded.error));
    const after = await engine.call('plugins.list', {});
    const names = (reply) => (reply.result?.plugins ?? []).map((entry) => entry.name);
    const loadedDirs = (reply) => (reply.result?.loaded ?? []).map((entry) => entry.dir);
    assert.deepEqual(names(before), ['alias-fixture-plugin'], '启动装载应只有一条: ' + JSON.stringify(names(before)));
    assert.deepEqual(names(after), ['alias-fixture-plugin'], 'plugins.load 别名路径后出现重复: ' + JSON.stringify(names(after)));
    assert.equal(loadedDirs(after).length, 1, 'loaded 列表出现重复: ' + JSON.stringify(loadedDirs(after)));
  } finally {
    await engine.stop();
  }
});
