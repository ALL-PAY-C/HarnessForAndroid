#!/usr/bin/env node
// DSH engine：Node 侧插件宿主。加载真实 cordis 插件，对外提供 JSON 行 TCP 协议。
// 兼容 node 18（nodejs-mobile v18.20.4）：不使用 toSorted / fs.glob / import attributes 等 20+ API。
// 放在第一行 import：内置模块的 ESM 具名导出是首次链接时的快照，垫片必须比任何插件更早被链接
// （Android 上 process.execPath 是 app_process64，插件拿它当 node 起子进程会被 JVM 启动器当成类名）。
import './host/android-node-exec.js';
// Node 18 缺口的运行期部分（File/Blob、RegExp 属性转义、util.styleText）也必须在任何插件被链接前装好。
import { installNode18Compat } from './host/node18-compat.js';
import { createServer } from 'node:net';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { createContext, createLogger, listPlugins } from './host/cordis.js';
import { createNativeBridge } from './host/native-bridge.js';
import { installLlmRpc } from './host/llm.js';
import { installSkillManager } from './host/skill-manager.js';
import { installToolState } from './host/tool-state.js';
import { ensureWritableHome, ensureWritableTemp, installModuleFallback, loadPlugin } from './host/loader.js';


const VERSION = '0.1.0';
const USAGE = [
  'dsh-engine ' + VERSION,
  '',
  '用法: node bin.js [选项]',
  '',
  '  --port <n>            监听端口，0 = 由系统分配（默认 0）',
  '  --home <dir>          插件 home（默认 $DSH_HOME 或 cwd）',
  '  --plugin <dir>        启动时加载一个插件目录（可重复）',
  '  --port-file <path>    监听成功后把实际端口写进该文件（原子写），失败写 ERR <message>',
  '  --help, -h            显示本帮助',
  '  --version, -v         打印版本',
  '',
  '环境变量: DSH_ENGINE_PORT_FILE（等价于 --port-file）、DSH_HOME、DSH_ENGINE_LOG_LEVEL',
  '',
  '行协议（UTF-8，一行一个 JSON）:',
  '  请求 {"id":"1","method":"ping"}',
  '       {"id":"2","method":"plugins.load","params":{"dir":"/abs/path","config":{}}}',
  '       {"id":"3","method":"rpc","params":{"path":"/skill-manager","endpoint":"list","payload":{}}}',
  '       {"id":"4","method":"plugins.list"}',
  '       {"id":"5","method":"shutdown"}',
  '  响应 {"id":"1","ok":true,"result":{...}}',
  '       {"id":"1","ok":false,"error":{"stage":"load|apply|rpc","message":"..."}}',
].join('\n');

function fail(message) {
  process.stderr.write('dsh-engine: ' + message + '\n');
  process.exit(1);
}

function parseArgv(argv) {
  const options = { port: 0, home: null, plugins: [], portFile: process.env.DSH_ENGINE_PORT_FILE || null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (value === undefined) fail(arg + ' 缺少参数值');
      index += 1;
      return value;
    };
    if (arg === '--port') {
      const raw = next();
      const port = Number(raw);
      if (!Number.isInteger(port) || port < 0 || port > 65535) fail('--port 必须是 0-65535 的整数: ' + raw);
      options.port = port;
    } else if (arg === '--home') {
      options.home = resolve(next());
    } else if (arg === '--plugin') {
      options.plugins.push(resolve(next()));
    } else if (arg === '--port-file') {
      options.portFile = resolve(next());
    } else if (arg === '--help' || arg === '-h') {
      process.stdout.write(USAGE + '\n');
      process.exit(0);
    } else if (arg === '--version' || arg === '-v') {
      process.stdout.write(VERSION + '\n');
      process.exit(0);
    } else {
      fail('未知参数: ' + arg + '（用 --help 看用法）');
    }
  }
  return options;
}

/** 原子写：先写同目录临时文件再 rename，Kotlin 侧读到的永远是完整内容。 */
async function writePortFile(file, text) {
  await mkdir(dirname(file), { recursive: true });
  const temp = file + '.tmp-' + process.pid;
  await writeFile(temp, text + '\n', 'utf8');
  await rename(temp, file);
}

function send(socket, payload) {
  if (socket.destroyed || socket.writableEnded) return;
  socket.write(JSON.stringify(payload) + '\n');
}

async function dispatch(ctx, method, params, state) {
  switch (method) {
    case 'ping':
      return {
        ok: true,
        pong: true,
        pid: process.pid,
        node: process.version,
        engine: VERSION,
        home: state.home,
        port: state.port,
        uptimeMs: Date.now() - state.startedAt,
        plugins: listPlugins(ctx).map((plugin) => plugin.name),
      };
    case 'plugins.load': {
      if (typeof params?.dir !== 'string' || params.dir.trim() === '') {
        throw Object.assign(new Error('params.dir 必须是非空字符串'), { stage: 'load' });
      }
      const info = await loadPlugin(ctx, params.dir, params.config ?? {});
      // 已装载过（启动 --plugin 或上一次 plugins.load）就不再记一条，否则 plugins.list 会出现重复条目
      if (!info.deduped) state.loaded.push(info);
      return info;
    }
    case 'plugins.list':
      return {
        plugins: listPlugins(ctx).map((plugin) => ({
          name: plugin.name,
          inject: plugin.inject,
          appliedAt: plugin.appliedAt,
          config: plugin.config,
        })),
        loaded: state.loaded,
        rpcPaths: ctx.get('connection')?.rpc.paths() ?? [],
        startupError: state.startupError,
      };
    case 'rpc': {
      if (typeof params?.path !== 'string') {
        throw Object.assign(new Error('params.path 必须是非空字符串'), { stage: 'rpc' });
      }
      const connection = ctx.get('connection');
      const handler = connection?.rpc.handlers.get(params.path);
      if (handler === undefined) {
        throw Object.assign(new Error('未注册 RPC 路径 ' + JSON.stringify(params.path)), { stage: 'rpc' });
      }
      return await handler(params.endpoint, params.payload);
    }
    case 'shutdown':
      return { ok: true, bye: true };
    default:
      throw Object.assign(new Error('未知 method: ' + JSON.stringify(method)), { stage: 'request' });
  }
}

function tryParse(line) {
  try {
    return JSON.parse(line);
  } catch {
    return undefined;
  }
}

async function handleLine(ctx, socket, line, state) {
  let request;
  try {
    request = JSON.parse(line);
  } catch (error) {
    send(socket, { id: null, ok: false, error: { stage: 'parse', message: '非法 JSON: ' + error.message } });
    return;
  }
  // 带 ok 字段的行是宿主对 nativeCall 的反向回复，不能当成请求再回一遍。
  if (state.bridge !== undefined && state.bridge !== null && state.bridge.consume(request)) return;
  const id = request?.id ?? null;
  const method = request?.method;
  try {
    const result = await dispatch(ctx, method, request?.params ?? {}, state);
    send(socket, { id, ok: true, result });
    if (method === 'shutdown') {
      // 先让响应落到 socket，再关服务
      setTimeout(() => shutdown(state), 10);
    }
  } catch (error) {
    send(socket, { id, ok: false, error: describeError(error) });
  }
}

/**
 * 失败回显：message 给结论，stack/dir/entries 给现场。
 * 设备上插件装不上时宿主只有 logcat 一个出口，光看 "import 失败" 定位不到具体文件/目录。
 */
function describeError(error) {
  const payload = {
    stage: error?.stage ?? 'internal',
    message: error?.message ?? String(error),
  };
  if (typeof error?.stack === 'string') payload.stack = error.stack;
  if (typeof error?.dir === 'string') payload.dir = error.dir;
  if (Array.isArray(error?.entries)) payload.entries = error.entries;
  if (typeof error?.cause?.stack === 'string' && error.cause.stack !== error.stack) payload.cause = error.cause.stack;
  return payload;
}

function attachSocket(ctx, socket, state) {
  let buffer = '';
  let chain = Promise.resolve();
  socket.setEncoding('utf8');
  socket.on('data', (chunk) => {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      const line = buffer.slice(0, index).replace(/\r$/, '');
      buffer = buffer.slice(index + 1);
      index = buffer.indexOf('\n');
      if (line.trim() === '') continue;
      // 反向通道的回复必须当场喂给 bridge：它解开的是正在 await 的 nativeCall，
      // 排进串行 chain 就会与那条等它的请求互相等死（插件 rpc 调 llm.chat 时必现）。
      const parsed = tryParse(line);
      if (parsed !== undefined && state.bridge !== undefined && state.bridge !== null && state.bridge.consume(parsed)) continue;
      chain = chain.then(() => handleLine(ctx, socket, line, state)).catch((error) => {
        process.stderr.write('dsh-engine: 请求处理异常: ' + (error?.stack ?? error) + '\n');
      });
    }
  });
  socket.on('error', (error) => {
    process.stderr.write('dsh-engine: socket 错误: ' + error.message + '\n');
  });
  // 宿主反连通道绑在这条 socket 上：断开时立刻拒绝所有 pending，插件不会挂到超时。
  if (state.bridge !== undefined && state.bridge !== null) {
    state.bridge.attach(socket);
    socket.on('close', () => state.bridge.detach(socket));
  }
}

function shutdown(state) {
  try { state.ctx.dispose(); } catch (error) {
    process.stderr.write('dsh-engine: dispose 异常: ' + (error?.message ?? error) + '\n');
  }
  state.server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 500).unref();
}

async function main() {
  const options = parseArgv(process.argv.slice(2));
  // 早于任何插件被链接：File/Blob、运行期 RegExp、ESM load hook（正则字面量 + util.styleText）。
  installNode18Compat();
  const home = options.home ?? resolve(process.env.DSH_HOME || process.cwd());
  // 插件用 resolveDshHome() 推导 storageDir，让它跟 --home 一致。
  process.env.DSH_HOME = home;
  // 插件用 os.homedir() 定位状态文件：Android 的 HOME=/data 不可写，指到 home 才能落盘。
  const envHome = process.env.HOME;
  const envProfile = process.env.USERPROFILE;
  const pluginHome = ensureWritableHome(home);
  // Android 上 os.tmpdir() 是 /tmp（不存在、不可写）：skills CLI 的 mkdtemp 会 ENOENT，改指真实可写目录。
  ensureWritableTemp(pluginHome);
  if (process.env.HOME !== envHome || process.env.USERPROFILE !== envProfile) {
    process.stderr.write('dsh-engine: HOME ' + (envHome ?? 'null') + ' 不可写，插件 homedir 改用 ' + pluginHome + '\n');
  }

  installModuleFallback();

  const logger = createLogger('dsh-engine');
  // 引擎 → Kotlin 的反向通道（llm/credentials 要用）。socket 连上后才 attach。
  const bridge = createNativeBridge(logger);
  const ctx = createContext({
    home,
    name: 'dsh-engine',
    logger,
    bridge,
    nativeCall: (method, payload) => bridge.call(method, payload),
  });
  // 内置技能管理器：没装外部 dsh-skills-manager 插件时「技能中心」也有数据。
  // 必须在插件加载之前注册——插件里的 rpc.handle 同名路径会覆盖它，外部插件仍然优先。
  try {
    installSkillManager(ctx, { home, logger });
  } catch (error) {
    process.stderr.write('dsh-engine: 内置技能管理器注册失败: ' + (error?.message ?? error) + '\n');
  }
  // /llm：插件 provider 的列表与对话入口（Kotlin 走 rpc method 直接调，不经 App 主对话）。
  // 失败只写 stderr，不阻断引擎启动——引擎没有它也能跑，插件仍可通过 ctx.llm 用。
  try {
    installLlmRpc(ctx, { logger });
  } catch (error) {
    process.stderr.write('dsh-engine: /llm RPC 注册失败: ' + (error?.message ?? error) + '\n');
  }
  // /tool-state + /commands：模型侧待办（todo_write）、目标（goal）、上下文压缩统计与 compact 命令。
  // 与上面两条同一注册链（connection.rpc.handle）；失败只写 stderr，不阻断引擎启动。
  try {
    installToolState(ctx, { logger });
  } catch (error) {
    process.stderr.write('dsh-engine: /tool-state RPC 注册失败: ' + (error?.message ?? error) + '\n');
  }
  const state = {
    ctx,
    bridge,
    home,
    port: null,
    server: null,
    startedAt: Date.now(),
    loaded: [],
    startupError: null,
  };

  for (const dir of options.plugins) {
    try {
      const info = await loadPlugin(ctx, dir, {});
      state.loaded.push(info);
      process.stderr.write('dsh-engine: 已加载插件 ' + info.name + ' <- ' + info.dir + '\n');
    } catch (error) {
      state.startupError = describeError(error);
      process.stderr.write('dsh-engine: 启动加载插件失败 ' + dir + ': ' + state.startupError.message + '\n');
      if (state.startupError.stack) process.stderr.write(state.startupError.stack + '\n');
      if (state.startupError.entries) {
        process.stderr.write('dsh-engine: 包内根层条目 ' + state.startupError.entries.join(' ') + '\n');
      }
    }
  }

  const server = createServer((socket) => attachSocket(ctx, socket, state));
  state.server = server;

  server.on('error', async (error) => {
    process.stderr.write('dsh-engine: 监听失败: ' + error.message + '\n');
    if (options.portFile) {
      try { await writePortFile(options.portFile, 'ERR ' + error.message); } catch { /* 端口文件也写不了就只留 stderr */ }
    }
    process.exit(1);
  });

  server.listen(options.port, '127.0.0.1', async () => {
    state.port = server.address().port;
    if (options.portFile) {
      try {
        await writePortFile(options.portFile, String(state.port));
      } catch (error) {
        process.stderr.write('dsh-engine: 写端口文件失败: ' + (error?.message ?? error) + '\n');
      }
    }
    process.stdout.write('DSH_ENGINE_LISTEN ' + state.port + '\n');
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => shutdown(state));
  }
  process.on('uncaughtException', (error) => {
    process.stderr.write('dsh-engine: uncaughtException: ' + (error?.stack ?? error) + '\n');
  });
  process.on('unhandledRejection', (reason) => {
    process.stderr.write('dsh-engine: unhandledRejection: ' + (reason?.stack ?? reason) + '\n');
  });
}

main().catch((error) => {
  process.stderr.write('dsh-engine: 启动失败: ' + (error?.stack ?? error) + '\n');
  process.exit(1);
});
