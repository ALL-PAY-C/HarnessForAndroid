// Android 上没有独立的 node 可执行文件：nodejs-mobile 只提供 libnode.so，由 Java 侧 JNI 启动一次
// （runtime-host NodeEngineHost.kt:37-41）；第二次 node::Start 直接 SIGABRT。但插件用
// execFile(process.execPath, [script, ...args]) 起子进程时，process.execPath 在 Android 上是
// /system/bin/app_process64（AndroidRuntime 启动器），它的 argv 约定是：
//   argv[0] 丢弃 → 选项扫描结束处那个参数当 "parent dir" 丢弃 → 第一个未知参数当 JVM 启动类名
// 于是 cli.mjs 路径被当 parent dir 丢掉、第一个 CLI 实参成了类名：
//   java.lang.ClassNotFoundException: list → AndroidRuntime abort(SIGABRT)，设备实测 31 次。
// 证据：.mock/t10-skill-selftest-crash.txt:521（Cmdline）、:496。
//
// 本垫片把"起 node 跑 JS"翻译成"在 Worker 线程里跑同一份 JS"：
//   - 每次调用一个全新 Worker = 全新模块图（等价子进程隔离；CLI 的 process.exit 与 ESM 缓存都不串）；
//   - 脚本看到的 process.argv / cwd / env 与真实 node 子进程一致（argv[0]=execPath, argv[1]=脚本）；
//   - stdout / stderr / 退出码 / options.timeout / options.maxBuffer 按 child_process 语义回报；
//   - 原生 spawn 数保持 0（真机上本来也没有 node 可执行文件可起）。
// 真要做成真子进程，需要在 APK 里加一个 JVM 主类入口（runtime-host 侧改造），那是另一条线。
//
// ponytail: 只覆盖本缺陷涉及的异步面：execFile / spawn。
//   同步变体（execFileSync / spawnSync）无法在 Worker 里同步等结果 → 显式抛错，不退化成 SIGABRT；
//   exec(cmd) 是 shell 字符串，原样交给原生（不在这里解析引号）；
//   忽略的 options：uid / gid / detached / stdio / windowsHide / shell。
//
// 顺序提醒（host 实测）：node 内置模块的 ESM 具名导出是"首次链接时的快照"，
// 先 'import { execFile } from "node:child_process"' 再去改 require('node:child_process').execFile，
// 具名导入看不到改动。所以本模块必须比任何会 import child_process 的模块更早被链接：
//   engine/bin.js 第一行 import 它；插件是运行时 dynamic import 的，天然更晚。
//   本模块自己只用 createRequire 取内置模块，避免提前把 ESM 门面（facade）实例化。

import { createRequire } from 'node:module';
import { existsSync } from 'node:fs';
import { basename, isAbsolute, resolve as resolvePath } from 'node:path';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { Worker } from 'node:worker_threads';

const require = createRequire(import.meta.url);
// 取内置模块的 CJS 对象（= ESM 门面读快照的来源），不通过 import 触发门面实例化。
const childProcess = require('node:child_process');
const util = require('node:util');

export const SYNC_UNSUPPORTED_CODE = 'ERR_DSH_ANDROID_NO_SYNC_EXEC';
const DEFAULT_MAX_BUFFER = 1024 * 1024;
const APP_PROCESS_NAMES = new Set(['app_process', 'app_process32', 'app_process64']);
const SCRIPT_EXT = /\.(?:c|m)?js$/i;

const originals = {
  execFile: childProcess.execFile,
  spawn: childProcess.spawn,
  execFileSync: childProcess.execFileSync,
  spawnSync: childProcess.spawnSync,
};
const state = { selfExecRuns: 0, nativeSpawns: 0, installed: false };

export function getAndroidNodeExecState() {
  return { selfExecRuns: state.selfExecRuns, nativeSpawns: state.nativeSpawns, installed: state.installed };
}

/** 设备交接时先跑这个，确认 Worker 这条路线在 nodejs-mobile 18.20.4 上可用。 */
export function probeAndroidNodeExecSupport() {
  let workerThreads = true;
  try { workerThreads = typeof Worker === 'function'; } catch { workerThreads = false; }
  return { workerThreads, node: process.version, platform: process.platform, execPath: process.execPath };
}

/** file/args 是否属于"拿 Android 启动器当 node 使"的调用；返回脚本绝对路径或 null。 */
function detectSelfExec(file, args, options) {
  if (typeof file !== 'string' || !file) return null;
  const looksLikeLauncher = APP_PROCESS_NAMES.has(basename(file).toLowerCase());
  const isAndroidSelfExec = process.platform === 'android' && file === process.execPath;
  if (!looksLikeLauncher && !isAndroidSelfExec) return null;
  const script = args && args[0];
  if (typeof script !== 'string' || !SCRIPT_EXT.test(script)) return null;
  if (options && options.shell) return null;
  const base = options && options.cwd ? String(options.cwd) : process.cwd();
  const scriptPath = isAbsolute(script) ? script : resolvePath(base, script);
  // 脚本不存在时原样交给原生，让错误形态保持原样（不在这里编造成功）
  return existsSync(scriptPath) ? scriptPath : null;
}

// 临时目录变量必须与主线程一致：插件在 Worker 里调 mkdtemp，Android 上 os.tmpdir() 是 /tmp
// （不存在、不可写），只认 TMPDIR/TMP/TEMP。调用方没显式传时按当前进程补齐，别让 Worker
// 退回 os.tmpdir()。
function inheritTempEnv(out, has) {
  for (const key of ['TMPDIR', 'TMP', 'TEMP']) {
    if (has(key)) continue;
    const value = process.env[key];
    if (value) out[key] = value;
  }
  return out;
}

function normalizeEnv(env) {
  const out = {};
  for (const [key, value] of Object.entries(env)) if (value !== undefined) out[key] = String(value);
  return inheritTempEnv(out, (key) => key in env);
}

function normalizeOptions(options) {
  const opts = options && typeof options === 'object' ? options : {};
  return {
    cwd: opts.cwd ? String(opts.cwd) : process.cwd(),
    env: opts.env && typeof opts.env === 'object' ? normalizeEnv(opts.env) : null,
    timeout: Number.isFinite(opts.timeout) ? Number(opts.timeout) : 0,
    maxBuffer: Number.isFinite(opts.maxBuffer) ? Number(opts.maxBuffer) : DEFAULT_MAX_BUFFER,
    encoding: opts.encoding === undefined ? 'utf8' : opts.encoding,
  };
}

// Worker 侧引导（CJS eval 源，node 18 可用；不走 ESM 的 eval type，那需要 node 22+）
const WORKER_SOURCE = [
  "const { parentPort, workerData } = require('node:worker_threads');",
  "const { pathToFileURL } = require('node:url');",
  'const cap = workerData.maxBuffer || 0;',
  'let total = 0;',
  'let capped = false;',
  'function emit(stream, chunk, encoding) {',
  '  if (capped) return true;',
  '  let bytes;',
  "  if (typeof chunk === 'string') bytes = Buffer.from(chunk, typeof encoding === 'string' ? encoding : 'utf8');",
  '  else if (chunk instanceof Uint8Array) bytes = Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);',
  "  else bytes = Buffer.from(String(chunk), 'utf8');",
  '  total += bytes.length;',
  "  if (cap && total > cap) { capped = true; parentPort.postMessage({ type: 'capped' }); return true; }",
  "  parentPort.postMessage({ type: 'chunk', stream, data: bytes.toString('base64') });",
  '  return true;',
  '}',
  "process.stdout.write = (chunk, encoding) => emit('stdout', chunk, encoding);",
  "process.stderr.write = (chunk, encoding) => emit('stderr', chunk, encoding);",
  'process.argv.length = 0;',
  'for (const value of workerData.argv) process.argv.push(value);',
  'if (workerData.env) {',
  '  for (const key of Object.keys(process.env)) if (!(key in workerData.env)) delete process.env[key];',
  '  Object.assign(process.env, workerData.env);',
  '}',
  "if (workerData.cwd) Object.defineProperty(process, 'cwd', { value: () => workerData.cwd, configurable: true, writable: true });",
  'function bail(error) {',
  '  const text = error && error.stack ? error.stack : String(error);',
  "  emit('stderr', text + '\\n', 'utf8');",
  '  process.exit(1);',
  '}',
  "process.on('uncaughtException', bail);",
  "process.on('unhandledRejection', bail);",
  'async function boot() {',
  '  if (workerData.compat) {',
  '    const compat = await import(workerData.compat);',
  '    compat.installNode18Compat();',
  '  }',
  '  await import(pathToFileURL(workerData.script).href);',
  '}',
  'boot().catch(bail);',
].join('\n');

// Worker 有独立的 ESM loader，主线程装的 load hook 不会继承过来，必须在 Worker 里再装一次。
const NODE18_COMPAT_URL = new URL('./node18-compat.js', import.meta.url).href;

function runInWorker(job, hooks) {
  return new Promise((settle) => {
    const chunks = { stdout: [], stderr: [] };
    let capped = false;
    let timedOut = false;
    let settled = false;
    let timer = null;
    let worker = null;
    const finish = (payload) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      settle({ chunks, capped, timedOut, ...payload });
    };
    try {
      worker = new Worker(WORKER_SOURCE, {
        eval: true,
        stdout: false,
        stderr: false,
        workerData: { script: job.script, argv: job.argv, cwd: job.cwd, env: job.env, maxBuffer: job.maxBuffer, compat: NODE18_COMPAT_URL },
      });
    } catch (error) {
      finish({ spawnError: error });
      return;
    }
    if (hooks && hooks.onWorker) hooks.onWorker(worker);
    worker.on('message', (message) => {
      if (!message || settled) return;
      if (message.type === 'capped') {
        capped = true;
        try { worker.terminate(); } catch { /* 已结束 */ }
        finish({ code: null });
        return;
      }
      if (message.type === 'chunk') {
        const buffer = Buffer.from(message.data, 'base64');
        chunks[message.stream].push(buffer);
        if (hooks && hooks.onChunk) hooks.onChunk(message.stream, buffer);
      }
    });
    worker.on('error', (error) => finish({ code: null, error }));
    worker.on('exit', (code) => finish({ code }));
    if (job.timeout > 0) {
      timer = setTimeout(() => {
        timedOut = true;
        try { worker.terminate(); } catch { /* 已结束 */ }
        finish({ code: null });
      }, job.timeout);
    }
  });
}

function decode(streams, encoding) {
  const buffer = Buffer.concat(streams);
  if (encoding === 'buffer' || encoding === null) return buffer;
  return buffer.toString(typeof encoding === 'string' ? encoding : 'utf8');
}

function makeError({ file, args, code, killed, signal, stdout, stderr, cause }) {
  const detail = typeof stderr === 'string' ? stderr : stderr ? stderr.toString('utf8') : '';
  const error = new Error('Command failed: ' + file + (args.length ? ' ' + args.join(' ') : '') + (detail ? '\n' + detail : ''));
  error.code = code;
  error.killed = killed === true;
  error.signal = signal || null;
  error.cmd = [file, ...args].join(' ');
  error.stdout = stdout;
  error.stderr = stderr;
  if (cause) error.cause = cause;
  return error;
}

/** 在 Worker 里跑脚本；成功返回 {stdout, stderr}，否则抛 child_process 形态的错误。 */
async function runScript(file, script, args, opts) {
  state.selfExecRuns += 1;
  const result = await runInWorker({
    script,
    argv: [process.execPath, script, ...args.slice(1)],
    cwd: opts.cwd,
    env: opts.env,
    maxBuffer: opts.maxBuffer,
    timeout: opts.timeout,
  });
  const stdout = decode(result.chunks.stdout, opts.encoding);
  const stderr = decode(result.chunks.stderr, opts.encoding);
  if (result.spawnError) throw makeError({ file, args, code: 'ERR_WORKER_START', stdout, stderr, cause: result.spawnError });
  if (result.capped) throw makeError({ file, args, code: 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER', killed: true, signal: 'SIGTERM', stdout, stderr });
  if (result.timedOut) throw makeError({ file, args, code: null, killed: true, signal: 'SIGTERM', stdout, stderr });
  const code = result.code === undefined ? null : result.code;
  if (code !== 0) throw makeError({ file, args, code, killed: false, signal: null, stdout, stderr, cause: result.error });
  return { stdout, stderr };
}

function normalizeCall(args, options, callback) {
  if (typeof args === 'function') return { args: [], options: undefined, callback: args };
  if (!Array.isArray(args)) return { args: [], options: args, callback: typeof options === 'function' ? options : callback };
  if (typeof options === 'function') return { args, options: undefined, callback: options };
  return { args, options, callback };
}

function patchedExecFile(file, args, options, callback) {
  const call = normalizeCall(args, options, callback);
  const script = detectSelfExec(file, call.args, call.options);
  if (!script) {
    state.nativeSpawns += 1;
    return originals.execFile.call(childProcess, file, call.args, call.options, call.callback);
  }
  const promise = runScript(file, script, call.args, normalizeOptions(call.options));
  if (call.callback) {
    promise.then((result) => call.callback(null, result.stdout, result.stderr), (error) => call.callback(error, error.stdout, error.stderr));
    return undefined;
  }
  return promise;
}

// 插件用法是 promisify(execFile)(...)，拿到的必须是 {stdout, stderr}
patchedExecFile[util.promisify.custom] = (file, args, options) => {
  const call = normalizeCall(args, options, undefined);
  const script = detectSelfExec(file, call.args, call.options);
  if (!script) {
    state.nativeSpawns += 1;
    return originals.execFile[util.promisify.custom](file, call.args, call.options);
  }
  return runScript(file, script, call.args, normalizeOptions(call.options));
};

function patchedSpawn(file, args, options) {
  const call = normalizeCall(args, options, undefined);
  const script = detectSelfExec(file, call.args, call.options);
  if (!script) {
    state.nativeSpawns += 1;
    return originals.spawn.call(childProcess, file, call.args, call.options);
  }
  const opts = normalizeOptions(call.options);
  state.selfExecRuns += 1;
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.stdin = new PassThrough();
  child.pid = -1;
  child.killed = false;
  child.exitCode = null;
  child.signalCode = null;
  child.spawnfile = file;
  child.spawnargs = [file, ...call.args];
  let worker = null;
  child.kill = () => {
    child.killed = true;
    if (worker) { try { worker.terminate(); } catch { /* 已结束 */ } }
    return true;
  };
  runInWorker({
    script,
    argv: [process.execPath, script, ...call.args.slice(1)],
    cwd: opts.cwd,
    env: opts.env,
    maxBuffer: opts.maxBuffer,
    timeout: opts.timeout,
  }, {
    onWorker: (handle) => { worker = handle; },
    onChunk: (stream, buffer) => { child[stream].write(buffer); },
  }).then((result) => {
    if (result.spawnError) { child.emit('error', result.spawnError); return; }
    const code = result.timedOut || result.capped ? null : (result.code === undefined ? null : result.code);
    const signal = result.timedOut || result.capped ? 'SIGTERM' : null;
    child.exitCode = code;
    child.signalCode = signal;
    let pending = 2;
    const emitExit = () => {
      child.emit('exit', code, signal);
      child.emit('close', code, signal);
    };
    const done = () => { pending -= 1; if (pending === 0) emitExit(); };
    child.stdout.end(done);
    child.stderr.end(done);
  });
  child.stdin.on('error', () => {});
  return child;
}

function syncUnsupported(file, args, script) {
  state.selfExecRuns += 1;
  const error = new Error('Android 上没有独立 node 可执行文件（只有 libnode.so），无法同步执行 node 脚本: '
    + file + ' ' + args.join(' ') + '；改用异步 execFile / spawn（会走 Worker）。脚本: ' + script);
  error.code = SYNC_UNSUPPORTED_CODE;
  error.path = file;
  error.spawnargs = args;
  return error;
}

function patchedExecFileSync(file, args, options) {
  const call = normalizeCall(args, options, undefined);
  const script = detectSelfExec(file, call.args, call.options);
  if (!script) {
    state.nativeSpawns += 1;
    return originals.execFileSync.call(childProcess, file, call.args, call.options);
  }
  throw syncUnsupported(file, call.args, script);
}

function patchedSpawnSync(file, args, options) {
  const call = normalizeCall(args, options, undefined);
  const script = detectSelfExec(file, call.args, call.options);
  if (!script) {
    state.nativeSpawns += 1;
    return originals.spawnSync.call(childProcess, file, call.args, call.options);
  }
  throw syncUnsupported(file, call.args, script);
}

/** 幂等：重复调用不会重复打补丁。 */
export function installAndroidNodeExecCompat() {
  if (!state.installed) {
    childProcess.execFile = patchedExecFile;
    childProcess.spawn = patchedSpawn;
    childProcess.execFileSync = patchedExecFileSync;
    childProcess.spawnSync = patchedSpawnSync;
    state.installed = true;
  }
  return childProcess.execFile;
}

// 导入即安装：bin.js 只要把它放第一行 import，就比任何插件更早（插件是运行时 dynamic import）。
installAndroidNodeExecCompat();
