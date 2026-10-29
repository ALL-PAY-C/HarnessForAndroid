// T5 真机缺陷回归：Android 上 process.execPath=/system/bin/app_process64，skills-manager 用
// execFile(process.execPath, [cli.mjs, 'list', ...]) 起 skills CLI，被 app_process 当成 JVM 启动器：
// cli.mjs 路径被当作 "parent dir" 丢弃，'list' 被当作 Java 类名 → ClassNotFoundException: list → SIGABRT。
// 证据：.mock/t10-skill-selftest-crash.txt:521（Cmdline + 31 次 abort）、:496。
//
// 四层断言：
//   1) 夹具证明 app_process 的 argv 约定确实产出 ClassNotFoundException: list；
//   2) 宿主上未打垫片时把 app_process 当 node 起就是不可用（host: ENOENT，设备: SIGABRT）；
//   3) 垫片语义（argv/cwd/env/退出码/stdout/stderr/promisify/超时/同步报错/委托）；
//   4) 跨进程用例复刻引擎真实拓扑（bin.js 先装载垫片 → 运行时 dynamic import 插件 → 插件里
//      import { execFile } from 'node:child_process'），证明具名导出的"首次链接快照"被覆盖。
//
// 注意：本文件用 createRequire 取内置 child_process（活的 CJS 对象），不静态 import 它 ——
// 否则门面在本进程里被本文件抢先实例化，垫片在进程内就观测不到了。
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const require = createRequire(import.meta.url);
const childProcess = require('node:child_process');
// 垫片是下面 await import 时装上的；这里先留住原生函数，用来证明"没垫片时就是坏的"
const nativeSpawn = childProcess.spawn;

const here = dirname(fileURLToPath(import.meta.url));
const engineRoot = resolve(here, '..');
const fixtureDir = join(here, 'fixtures');
const simPath = join(fixtureDir, 'app-process-sim.mjs');
const echoPath = join(fixtureDir, 'echo-child.mjs');
const exitPath = join(fixtureDir, 'exit-child.mjs');
const sleepPath = join(fixtureDir, 'sleep-child.mjs');
const pluginHostPath = join(fixtureDir, 'plugin-host.mjs');
// 真机 process.execPath（设备证据 .mock/t10-skill-selftest-crash.txt:521）
const APP_PROCESS = '/system/bin/app_process64';
// 真机 skills CLI（engine 依赖 skills@1.5.22）
const SKILLS_CLI = join(engineRoot, 'node_modules', 'skills', 'bin', 'cli.mjs');

const shim = await import('../host/android-node-exec.js');
const execFileAsync = promisify(childProcess.execFile);

function spawnAndCollect(file, args, options = {}) {
  return new Promise((res, rej) => {
    const child = childProcess.spawn(file, args, { stdio: ['ignore', 'pipe', 'pipe'], ...options });
    let out = '';
    let err = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.on('error', rej);
    child.on('close', (code) => res({ code, out, err }));
  });
}

test('夹具：app_process argv 约定把第一个 CLI 实参当 Java 类名（复现设备崩溃签名）', async () => {
  // 设备 tombstone 的完整 Cmdline（.mock/t10-skill-selftest-crash.txt:521）
  const { out } = await spawnAndCollect(process.execPath, [simPath, APP_PROCESS, SKILLS_CLI, 'list', '--json', '--agent', 'universal']);
  const parsed = JSON.parse(out);
  assert.equal(parsed.parentDir, SKILLS_CLI, 'cli.mjs 路径被当作 parent dir 丢弃');
  assert.equal(parsed.className, 'list', '第一个未知参数被当作 JVM 启动类名');
  assert.deepEqual(parsed.mainArgs, ['--json', '--agent', 'universal']);
  assert.equal(parsed.exitCode, 134, 'SIGABRT 退出码');
  assert.match(parsed.crash, /java\.lang\.ClassNotFoundException: list$/);

  // 换成别的 CLI 也一样塌陷：类名永远是第一个 CLI 实参
  const other = JSON.parse((await spawnAndCollect(process.execPath, [simPath, APP_PROCESS, '/data/local/tmp/x.mjs', 'install', 'foo'])).out);
  assert.equal(other.className, 'install');
  assert.equal(other.crash, 'java.lang.ClassNotFoundException: install');
});

test('基线：未打垫片时把 app_process 当 node 起不可用（host: ENOENT，设备: SIGABRT）', async () => {
  assert.equal(existsSync(SKILLS_CLI), true, 'skills CLI 应随依赖存在');
  // nativeSpawn 是本文件顶部（垫片 import 之前）取到的原生函数：不经过补丁
  const result = await new Promise((res) => {
    const child = nativeSpawn(APP_PROCESS, [SKILLS_CLI, 'list', '--json'], { stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', () => {});
    child.stderr.on('data', () => {});
    child.on('error', (error) => res({ error, code: null }));
    child.on('close', (code) => res({ error: null, code, signal: null }));
  });
  if (result.error) assert.equal(result.error.code, 'ENOENT', '宿主上根本没有 /system/bin/app_process64');
  else assert.notEqual(result.code, 0, '设备上这一步会以 SIGABRT(134) 结束，绝不会是 0');
});

// skills CLI 1.5.22 用到两个 node 20+ 才有的内置 API：util.styleText（20.12+）与 zlib.crc32（20.15+）。
// 它们在 node 18 上是链接期 SyntaxError，engine Worker 里由 host/node18-compat.js 的 load hook 换
// specifier 补上（不是跳过执行）。所以 node 18 与 node 20+ 现在都必须真的跑通。

test('垫片：execFile 起真 skills CLI，参数转换落到真实执行阶段（零原生 spawn）', async () => {
  const before = shim.getAndroidNodeExecState();
  const args = [SKILLS_CLI, 'list', '--json', '--agent', 'universal'];
  const options = { cwd: engineRoot, env: { ...process.env, NO_COLOR: '1', DISABLE_TELEMETRY: '1', DO_NOT_TRACK: '1' }, timeout: 120000, maxBuffer: 4 * 1024 * 1024 };
  const { stdout } = await execFileAsync(APP_PROCESS, args, options);
  assert.ok(Array.isArray(JSON.parse(stdout)), 'skills CLI 的 list --json 必须返回数组');
  const after = shim.getAndroidNodeExecState();
  assert.equal(after.nativeSpawns, before.nativeSpawns, '自执行必须不走原生 spawn');
  assert.equal(after.selfExecRuns, before.selfExecRuns + 1);
});

test('语义对齐：argv / cwd / env 与真实 node 子进程一致', async () => {
  const cwd = dirname(echoPath);
  const { stdout } = await execFileAsync(APP_PROCESS, [echoPath, 'a', 'b'], { cwd, env: { NO_COLOR: '1', DSH_ONLY_IN_CHILD: 'yes' } });
  const seen = JSON.parse(stdout);
  assert.deepEqual(seen.argv.slice(1), [echoPath, 'a', 'b'], 'argv = [execPath, script, ...args]');
  assert.equal(seen.cwd, cwd);
  assert.equal(seen.noColor, '1');
  assert.equal(seen.onlyInChild, 'yes');
});

test('退出码：非零退出映射成 error.code，并带回 stdout / stderr', async () => {
  await assert.rejects(execFileAsync(APP_PROCESS, [exitPath, '3'], { env: { ...process.env } }), (error) => {
    assert.equal(error.code, 3);
    assert.equal(error.stdout, 'out:3');
    assert.equal(error.stderr, 'err:3');
    assert.equal(error.killed, false);
    return true;
  });
});

test('退出码 0：promisify 形态给 {stdout, stderr}', async () => {
  const result = await execFileAsync(APP_PROCESS, [exitPath, '0'], { env: { ...process.env } });
  assert.equal(typeof result.stdout, 'string');
  assert.equal(result.stdout, 'out:0');
  assert.equal(result.stderr, 'err:0');
});

test('超时：按 options.timeout 终止本次执行', async () => {
  await assert.rejects(execFileAsync(APP_PROCESS, [sleepPath], { env: { ...process.env }, timeout: 300 }), (error) => {
    assert.equal(error.killed, true);
    assert.equal(error.signal, 'SIGTERM');
    return true;
  });
});

test('maxBuffer：超限报 ERR_CHILD_PROCESS_STDIO_MAXBUFFER', async () => {
  await assert.rejects(execFileAsync(APP_PROCESS, [exitPath, '0'], { env: { ...process.env }, maxBuffer: 2 }), (error) => {
    assert.equal(error.code, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
    return true;
  });
});

test('非自执行调用原样委托给原生 child_process', async () => {
  const before = shim.getAndroidNodeExecState().nativeSpawns;
  const { stdout } = await execFileAsync(process.execPath, ['-e', 'process.stdout.write("native")'], { env: { ...process.env } });
  assert.equal(stdout, 'native');
  assert.equal(shim.getAndroidNodeExecState().nativeSpawns, before + 1, '普通脚本必须仍走原生');
});

test('spawn 形态：流式 stdout 与 exit/close 事件可用', async () => {
  const child = childProcess.spawn(APP_PROCESS, [exitPath, '0'], { env: { ...process.env } });
  let out = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (c) => { out += c; });
  const code = await new Promise((res, rej) => { child.on('exit', res); child.on('error', rej); });
  assert.equal(code, 0);
  assert.equal(out, 'out:0');
});

test('同步变体显式报错，不退化成 SIGABRT', () => {
  assert.throws(() => childProcess.execFileSync(APP_PROCESS, [SKILLS_CLI, 'list']), (error) => {
    assert.equal(error.code, shim.SYNC_UNSUPPORTED_CODE);
    assert.match(error.message, /同步/);
    return true;
  });
  assert.throws(() => childProcess.spawnSync(APP_PROCESS, [SKILLS_CLI, 'list']), (error) => {
    assert.equal(error.code, shim.SYNC_UNSUPPORTED_CODE);
    return true;
  });
});

test('安装幂等：重复安装不重复打补丁', () => {
  const before = childProcess.execFile;
  assert.equal(shim.installAndroidNodeExecCompat(), shim.installAndroidNodeExecCompat());
  assert.equal(childProcess.execFile, before);
});

test('跨进程复刻引擎拓扑：bin.js 先装载垫片，插件里 import { execFile } 拿到的是补丁版', async () => {
  const { out } = await spawnAndCollect(process.execPath, [pluginHostPath, APP_PROCESS, SKILLS_CLI]);
  const result = JSON.parse(out);
  // 任何 node 版本都必须成功：node 18 的 util.styleText / zlib.crc32 缺口由 Worker 里的 load hook 补。
  assert.equal(result.ok, true, '插件形态必须成功: ' + out);
  assert.equal(result.isArray, true);
});

test('能力探针：worker_threads 可用（设备交接时需在真机复跑）', () => {
  const support = shim.probeAndroidNodeExecSupport();
  assert.equal(support.workerThreads, true);
  assert.equal(typeof support.node, 'string');
});