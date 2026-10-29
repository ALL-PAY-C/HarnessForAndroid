// T5 边界回归：Android 上插件状态文件必须落在可写目录（HOME 修正），且重复 plugins.load 不重复注册。
// 背景：设备实测 dsh-boot-video-manager 用 os.homedir() 定位 playlist.json，Android 的 HOME=/data 不可写 →
// [error] dsh-engine: EACCES: permission denied, mkdir '/data/.dsh/boot-video-manager'（.mock/t5-three-plugins/logs/bootvideo-http.txt）。
// 另：bin.js 启动会按 --plugin 装载，NodeEngineHost 随后又 plugins.load 同一目录，设备 plugins.list 里出现两条。
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { connect } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const engineRoot = resolve(here, '..');
const probeDir = resolve(engineRoot, '..', '.mock', 'npws', 'home-probe');

/** 探针插件：把 process.env.HOME 与"能否在其下建目录写文件"回报给测试。 */
async function seedProbe() {
  await mkdir(probeDir, { recursive: true });
  await writeFile(join(probeDir, 'package.json'), JSON.stringify({ name: 'home-probe', version: '0.0.0', type: 'module', main: 'index.js' }, null, 2) + '\n', 'utf8');
  await writeFile(join(probeDir, 'index.js'), [
    'import { mkdirSync, writeFileSync } from "node:fs";',
    'import { homedir } from "node:os";',
    'import { join } from "node:path";',
    'export const name = "home-probe";',
    'export const inject = [];',
    'const report = { home: process.env.HOME ?? null, homedir: null, dshHome: process.env.DSH_HOME ?? null, writeOk: false, writeError: null, file: null };',
    'try { report.homedir = homedir(); } catch (error) { report.homedir = "throws: " + error.message; }',
    'export function apply() {',
    '  // 模拟 dsh-boot-video-manager：状态文件按 os.homedir() 定位',
    '  try {',
    '    const dir = join(homedir(), ".dsh", "boot-video-manager");',
    '    mkdirSync(dir, { recursive: true });',
    '    writeFileSync(join(dir, "playlist.json"), "{\\"probe\\":true}");',
    '    report.writeOk = true;',
    '  } catch (error) { report.writeError = String(error && error.message); }',
    '  // 报告写到 DSH_HOME（= --home），测试进程另跑一份，不能用模块内状态断言',
    '  try {',
    '    report.file = join(String(process.env.DSH_HOME), "probe-report.json");',
    '    writeFileSync(report.file, JSON.stringify(report));',
    '  } catch { /* 写不了就让断言失败在读取处 */ }',
    '}',
    '',
  ].join('\n'), 'utf8');
}

function once(emitter, event) {
  return new Promise((res, rej) => { emitter.once(event, res); emitter.once('error', rej); });
}

async function startEngine(home, extraEnv = {}) {
  const portFile = join(home, 'engine.port');
  const env = { ...process.env, ...extraEnv };
  const child = spawn(process.execPath, [join(engineRoot, 'bin.js'), '--port', '0', '--home', home, '--port-file', portFile], {
    stdio: ['ignore', 'pipe', 'pipe'], env,
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
  await once(socket, 'connect');
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
    await new Promise((res) => { child.once('exit', res); child.kill(); setTimeout(res, 500).unref(); });
  };
  return { call, stop, stderr: () => stderr };
}

await seedProbe();

test('HOME 不可写时引擎把 HOME 指到 --home，插件状态文件能落盘', async () => {
  const home = await mkdtemp(join(tmpdir(), 'dsh-home-fix-'));
  // 模拟 Android：HOME 指向一个不存在（因此不可写）的路径
  const fakeHome = join(home, 'android-data');
  // Android: homedir() 读 HOME；Windows: 读 USERPROFILE。两个都指向不可写路径才算等价模拟。
  const engine = await startEngine(home, { HOME: fakeHome, USERPROFILE: fakeHome });
  try {
    const loaded = await engine.call('plugins.load', { dir: probeDir });
    assert.equal(loaded.ok, true, JSON.stringify(loaded.error));
    const report = JSON.parse(await readFile(join(home, 'probe-report.json'), 'utf8'));
    assert.equal(report.home, home, 'HOME 必须等于 --home：' + report.home);
    assert.equal(report.homedir, home, 'os.homedir() 必须落在 --home：' + report.homedir);
    assert.equal(report.writeOk, true, '在 HOME 下建目录写文件应成功：' + report.writeError);
    assert.equal(report.dshHome, home, 'DSH_HOME 必须是 --home');
    const written = await readFile(join(home, '.dsh', 'boot-video-manager', 'playlist.json'), 'utf8');
    assert.match(written, /probe/);
  } finally {
    await engine.stop();
  }
});

test('HOME 本来可写时不被改写（不打扰桌面环境）', async () => {
  const home = await mkdtemp(join(tmpdir(), 'dsh-home-keep-'));
  const realHome = await mkdtemp(join(tmpdir(), 'dsh-fake-home-'));
  const engine = await startEngine(home, { HOME: realHome, USERPROFILE: realHome });
  try {
    const loaded = await engine.call('plugins.load', { dir: probeDir });
    assert.equal(loaded.ok, true, JSON.stringify(loaded.error));
    const report = JSON.parse(await readFile(join(home, 'probe-report.json'), 'utf8'));
    assert.equal(report.homedir, realHome, '可写的 homedir 应原样保留：' + report.homedir);
    assert.equal(report.home, realHome, '可写的 HOME 应原样保留：' + report.home);
  } finally {
    await engine.stop();
  }
});

test('同一插件目录重复 plugins.load 不重复注册（app 启动装载 + 显式 load 的去重）', async () => {
  const home = await mkdtemp(join(tmpdir(), 'dsh-dedupe-'));
  const engine = await startEngine(home);
  try {
    const first = await engine.call('plugins.load', { dir: probeDir });
    const second = await engine.call('plugins.load', { dir: probeDir });
    assert.equal(first.ok, true, JSON.stringify(first.error));
    assert.equal(second.ok, true, JSON.stringify(second.error));
    assert.equal(second.result.appliedAt, first.result.appliedAt, '第二次 load 应复用同一实例');
    const list = await engine.call('plugins.list');
    const names = list.result.plugins.map((plugin) => plugin.name);
    assert.deepEqual(names, ['home-probe'], 'plugins 里只能有一条：' + JSON.stringify(names));
    assert.equal(list.result.loaded.length, 1, 'loaded 里也只能有一条：' + JSON.stringify(list.result.loaded.length));
  } finally {
    await engine.stop();
  }
});