// 内置技能管理器自检：走真 host context（createContext + connection.rpc.call）跑全部 endpoint，
// 再真起 bin.js 走一遍 socket rpc，证明 bin.js 里确实注册了这条路径（不是只测模块）。
// 用法: cd engine && node test/skill-manager.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { connect } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createContext } from '../host/cordis.js';
import { installSkillManager } from '../host/skill-manager.js';

const here = dirname(fileURLToPath(import.meta.url));
const engineRoot = resolve(here, '..');

function skillMd(name, description, extra = '') {
  return '---\nname: ' + name + '\ndescription: ' + description + '\n' + extra + '---\n\n# ' + name + '\n\nBody line.\n';
}

async function seed(dir, name, description, extra) {
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, 'SKILL.md'), skillMd(name, description, extra), 'utf8');
}

/** 干净 home；[skills] 在引擎启动**之前**落进技能库，用来模拟「首启时已有技能」。 */
async function makeHome(skills = []) {
  const home = await mkdtemp(join(tmpdir(), 'dsh-skill-manager-'));
  for (const [name, description] of skills) {
    await seed(join(home, 'skill-manager', '.agents', 'skills', name), name, description);
  }
  return home;
}

function start(home) {
  const ctx = createContext({ home });
  const { manager } = installSkillManager(ctx, { home });
  return { ctx, manager, rpc: (endpoint, payload) => ctx.connection.rpc.call('/skill-manager', endpoint, payload) };
}

test('首启建目录 + 自动启用已有技能，list 回包形状对得上 SkillCatalog.kt', async () => {
  const home = await makeHome([['alpha', 'First demo skill.'], ['beta', 'Second demo skill.']]);
  const { ctx, rpc } = start(home);
  const reply = await rpc('list', {});
  assert.equal(reply.ok, true);
  assert.deepEqual(reply.value.entries.map((entry) => entry.name), ['alpha', 'beta']);
  for (const entry of reply.value.entries) {
    assert.deepEqual(Object.keys(entry).sort(), [
      'description', 'effectiveEnabled', 'globalEnabled', 'modelInvocable', 'name', 'projectEnabled', 'updateSupported',
    ]);
    assert.equal(entry.globalEnabled, true, '首启应自动点亮已有技能');
    assert.equal(entry.effectiveEnabled, true);
    assert.equal(entry.updateSupported, false);
    assert.equal(entry.modelInvocable, true);
  }
  assert.deepEqual(reply.value.projectRoots, []);
  assert.equal(reply.value.entries[0].description, 'First demo skill.');
  console.log('list 回包: ' + JSON.stringify(reply));
  await ctx.dispose();
});

test('set-enabled / enable / disable 改状态并落盘，重启后不复活', async () => {
  const home = await makeHome([['alpha', 'First.'], ['beta', 'Second.']]);
  const { ctx, rpc } = start(home);
  await rpc('set-enabled', { name: 'alpha', scope: 'global', enabled: false });
  let entries = (await rpc('list', {})).value.entries;
  assert.deepEqual(entries.map((entry) => [entry.name, entry.effectiveEnabled]), [['alpha', false], ['beta', true]]);

  await rpc('enable', { name: 'alpha' });
  assert.equal((await rpc('list', {})).value.entries[0].effectiveEnabled, true);
  await rpc('disable', { name: 'alpha' });
  assert.equal((await rpc('list', {})).value.entries[0].effectiveEnabled, false);

  // 重启：状态从 state.json 读回，首启的自动启用不会把 alpha 再点亮
  const restarted = start(home);
  const after = (await restarted.rpc('list', {})).value.entries;
  assert.equal(after.find((entry) => entry.name === 'alpha').effectiveEnabled, false);
  const saved = JSON.parse(await readFile(join(home, 'skill-manager', 'state.json'), 'utf8'));
  assert.deepEqual(saved, { version: 1, globalEnabled: ['beta'], projectEnabled: {}, modelInvocation: {} });
  await ctx.dispose();
  await restarted.ctx.dispose();
});

test('project 范围只看 projectRoot 子树', async () => {
  const home = await makeHome([['alpha', 'First.']]);
  const { ctx, rpc } = start(home);
  await rpc('set-enabled', { name: 'alpha', scope: 'global', enabled: false });
  const projectRoot = join(home, 'workspace');
  await rpc('set-enabled', { name: 'alpha', scope: 'project', projectRoot, enabled: true });

  // projectEnabled 只对「就是该根」成立；子目录靠 effectiveEnabled 生效（与插件语义一致）
  const atRoot = (await rpc('list', { projectRoot })).value.entries[0];
  assert.equal(atRoot.projectEnabled, true);
  assert.equal(atRoot.effectiveEnabled, true);
  const inside = (await rpc('list', { projectRoot: join(projectRoot, 'nested') })).value.entries[0];
  assert.equal(inside.projectEnabled, false);
  assert.equal(inside.effectiveEnabled, true);
  const outside = (await rpc('list', { projectRoot: join(home, 'other') })).value.entries[0];
  assert.equal(outside.projectEnabled, false);
  assert.equal(outside.effectiveEnabled, false);
  assert.deepEqual((await rpc('list', {})).value.projectRoots, [projectRoot]);
  await ctx.dispose();
});

test('install 认单技能目录 / 多技能集合 / 已解压的 .agents/skills 结构，新技能默认关闭', async () => {
  const home = await makeHome();
  const { ctx, rpc } = start(home);
  const stage = join(home, 'stage');
  const single = join(stage, 'single');
  await seed(single, 'gamma', 'Single-bundle source.');
  await writeFile(join(single, 'extra.md'), 'payload\n', 'utf8');
  let entries = (await rpc('install', { path: single })).value.entries;
  assert.deepEqual(entries.map((entry) => [entry.name, entry.effectiveEnabled]), [['gamma', false]]);
  // 复制的是整个包，不只是 SKILL.md
  assert.equal(await readFile(join(home, 'skill-manager', '.agents', 'skills', 'gamma', 'extra.md'), 'utf8'), 'payload\n');

  const collection = join(stage, 'collection');
  await seed(join(collection, 'delta'), 'delta', 'Delta.');
  await seed(join(collection, 'epsilon'), 'epsilon', 'Epsilon.');
  entries = (await rpc('install', { path: collection })).value.entries;
  assert.deepEqual(entries.map((entry) => entry.name), ['delta', 'epsilon', 'gamma']);

  const packed = join(stage, 'packed');
  await seed(join(packed, '.agents', 'skills', 'zeta'), 'zeta', 'Zeta.');
  entries = (await rpc('install', { path: packed })).value.entries;
  assert.deepEqual(entries.map((entry) => entry.name), ['delta', 'epsilon', 'gamma', 'zeta']);

  // 重装同名技能覆盖旧内容，不留下垃圾目录
  await writeFile(join(single, 'extra.md'), 'payload2\n', 'utf8');
  await rpc('install', { path: single });
  assert.equal(await readFile(join(home, 'skill-manager', '.agents', 'skills', 'gamma', 'extra.md'), 'utf8'), 'payload2\n');
  await ctx.dispose();
});

test('install 认 skills/<name>/SKILL.md 两层布局、子目录里的 .agents/skills、以及最深 4 层', async () => {
  const home = await makeHome();
  const { ctx, rpc } = start(home);
  const stage = join(home, 'stage');

  // anthropics/skills、vercel-labs/agent-skills 的布局：<repo>/skills/<name>/SKILL.md
  await seed(join(stage, 'two-level', 'skills', 'docx'), 'docx', 'DOCX 工具.');
  await seed(join(stage, 'two-level', 'skills', 'pdf'), 'pdf', 'PDF 工具.');
  let value = (await rpc('install', { path: join(stage, 'two-level') })).value;
  assert.deepEqual(value.installed, ['docx', 'pdf']);
  assert.deepEqual(value.skipped, []);
  assert.equal(value.name, 'docx');

  // 已解压的 DSH_HOME 藏在任意子目录里：<pkg>/.agents/skills/<name>/SKILL.md
  await seed(join(stage, 'packed', 'pkg', '.agents', 'skills', 'deep'), 'deep', 'Deep.');
  value = (await rpc('install', { path: join(stage, 'packed') })).value;
  assert.deepEqual(value.installed, ['deep']);

  // 第 4 层（a/b/c/d）仍然认；第 5 层不再深入，来源里就没有技能了
  await seed(join(stage, 'depth4', 'a', 'b', 'c', 'd'), 'depth-four', 'Depth four.');
  value = (await rpc('install', { path: join(stage, 'depth4') })).value;
  assert.deepEqual(value.installed, ['depth-four']);
  await seed(join(stage, 'depth5', 'a', 'b', 'c', 'd', 'e'), 'depth-five', 'Depth five.');
  await assert.rejects(() => rpc('install', { path: join(stage, 'depth5') }), /没有 <name>\/SKILL.md/);
  await ctx.dispose();
});

test('一次装多个：installed/skipped/name 明细，同批重名跳过，node_modules 不扫', async () => {
  const home = await makeHome();
  const { ctx, rpc } = start(home);
  const stage = join(home, 'multi');
  await seed(join(stage, 'skills', 'one'), 'one', 'One.');
  await seed(join(stage, 'skills', 'two'), 'two', 'Two.');
  // 两个目录的 frontmatter 都写 name: one —— 先到先得（zeta 排序在后，进 skipped）
  await seed(join(stage, 'skills', 'zeta'), 'one', 'Duplicate.');
  await seed(join(stage, 'node_modules', 'pkg'), 'ghost', 'Ghost.');

  const value = (await rpc('install', { path: stage })).value;
  assert.deepEqual(value.installed, ['one', 'two']);
  assert.deepEqual(value.skipped, ['one']);
  assert.equal(value.name, 'one');
  assert.deepEqual(value.entries.map((entry) => entry.name), ['one', 'two']);
  assert.equal(value.entries.find((entry) => entry.name === 'one').description, 'One.');
  await assert.rejects(() => stat(join(home, 'skill-manager', '.agents', 'skills', 'ghost')), /ENOENT/);
  await ctx.dispose();
});

test('bundlesIn 超限抛明确错误：技能包 > 200、目录 > 4000', async () => {
  const home = await makeHome();
  const { ctx, rpc } = start(home);

  // 201 个技能包：第 201 个被发现时立刻报错，不做半截安装
  const many = join(home, 'many');
  for (let index = 0; index < 201; index += 1) {
    await seed(join(many, 'skills', 'skill-' + index), 'skill-' + index, 'Bulk.');
  }
  await assert.rejects(() => rpc('install', { path: many }), /技能包超过 200 个/);
  await assert.rejects(() => stat(join(home, 'skill-manager', '.agents', 'skills', 'skill-0')), /ENOENT/);

  // 4001 个空目录（没有 SKILL.md）撞目录上限，而不是把磁盘走穿
  const wide = join(home, 'wide');
  for (let index = 0; index < 4001; index += 500) {
    await Promise.all(Array.from({ length: Math.min(500, 4001 - index) }, (_, offset) => (
      mkdir(join(wide, 'd' + (index + offset)), { recursive: true })
    )));
  }
  await assert.rejects(() => rpc('install', { path: wide }), /目录数超过 4000/);
  await ctx.dispose();
});

test('remove 删目录 + 清状态；坏参数给可读错误', async () => {
  const home = await makeHome([['alpha', 'First.'], ['beta', 'Second.']]);
  const { ctx, rpc } = start(home);
  const after = await rpc('remove', { name: 'alpha' });
  assert.deepEqual(after.value.entries.map((entry) => entry.name), ['beta']);
  await assert.rejects(() => stat(join(home, 'skill-manager', '.agents', 'skills', 'alpha')), /ENOENT/);
  assert.deepEqual(JSON.parse(await readFile(join(home, 'skill-manager', 'state.json'), 'utf8')).globalEnabled, ['beta']);

  // frontmatter 的 name 和目录名不一致时，删的是真目录
  await seed(join(home, 'skill-manager', '.agents', 'skills', 'dir-name'), 'declared-name', 'Renamed.');
  await rpc('remove', { name: 'declared-name' });
  await assert.rejects(() => stat(join(home, 'skill-manager', '.agents', 'skills', 'dir-name')), /ENOENT/);

  await assert.rejects(() => rpc('remove', { name: '../escape' }), /不合法/);
  await assert.rejects(() => rpc('set-enabled', { name: 'nope', scope: 'global', enabled: true }), /未安装/);
  await assert.rejects(() => rpc('set-enabled', { name: 'beta', scope: 'global' }), /enabled 必须是布尔值/);
  await assert.rejects(() => rpc('set-enabled', { name: 'beta', scope: 'bogus', enabled: true }), /scope 必须是/);
  await assert.rejects(() => rpc('install', { path: join(home, 'missing') }), /必须是含 SKILL.md 的目录/);
  await assert.rejects(() => rpc('install', {}), /缺少技能来源 path/);
  await assert.rejects(() => rpc('nope', {}), /未知 skill-manager endpoint/);
  await ctx.dispose();
});

test('frontmatter 缺 name/description 时退到目录名与正文，disable-model-invocation 生效', async () => {
  const home = await makeHome();
  const library = join(home, 'skill-manager', '.agents', 'skills');
  await mkdir(join(library, 'no-frontmatter'), { recursive: true });
  await writeFile(join(library, 'no-frontmatter', 'SKILL.md'), '# Title\n\nPlain body summary.\n', 'utf8');
  await seed(join(library, 'manual-only'), 'manual-only', 'Manual.', 'disable-model-invocation: true\n');
  const { ctx, rpc } = start(home);
  const entries = (await rpc('list', {})).value.entries;
  const plain = entries.find((entry) => entry.name === 'no-frontmatter');
  assert.equal(plain.description, 'Plain body summary.');
  assert.equal(entries.find((entry) => entry.name === 'manual-only').modelInvocable, false);
  await ctx.dispose();
});

test('bin.js 真起进程后 /skill-manager 可用（外部插件不在时）', async () => {
  const home = await makeHome([['online', 'Registered by bin.js.']]);
  const child = spawn(process.execPath, [join(engineRoot, 'bin.js'), '--port', '0', '--home', home], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  try {
    const port = await new Promise((res, rej) => {
      const timer = setTimeout(() => rej(new Error('引擎 15s 内没有报端口: ' + stderr)), 15000);
      let buffered = '';
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (chunk) => {
        buffered += chunk;
        const line = buffered.split('\n').find((entry) => entry.startsWith('DSH_ENGINE_LISTEN '));
        if (line !== undefined) {
          clearTimeout(timer);
          res(Number(line.trim().split(' ')[1]));
        }
      });
    });
    const socket = connect({ host: '127.0.0.1', port });
    await new Promise((res, rej) => { socket.once('connect', res); socket.once('error', rej); });
    socket.setEncoding('utf8');
    const reply = await new Promise((res, rej) => {
      const timer = setTimeout(() => rej(new Error('15s 内没有收到 rpc 回复: ' + stderr)), 15000);
      let buffer = '';
      socket.on('data', (chunk) => {
        buffer += chunk;
        const index = buffer.indexOf('\n');
        if (index >= 0) {
          clearTimeout(timer);
          res(JSON.parse(buffer.slice(0, index)));
        }
      });
      socket.once('error', rej);
      socket.write(JSON.stringify({ id: '1', method: 'rpc', params: { path: '/skill-manager', endpoint: 'list', payload: {} } }) + '\n');
    });
    assert.equal(reply.ok, true);
    assert.deepEqual(reply.result.value.entries.map((entry) => [entry.name, entry.effectiveEnabled]), [['online', true]]);
    console.log('bin.js 直连回包: ' + JSON.stringify(reply.result));
    socket.destroy();
  } finally {
    child.kill();
    await rm(home, { recursive: true, force: true });
  }
});
