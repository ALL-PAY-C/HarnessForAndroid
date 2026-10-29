// cordis: 真 @deepseek-ai/cordis 容器 + dsh 宿主的服务注册表。
//
// 为什么换成真 cordis：插件市场里的插件是按真 cordis 写的（inject 依赖、fiber 生命周期、
// Config schema 校验、ctx.effect 清理）。手写兼容层只能对上被用到的子集，一旦插件用了
// ctx.inject/ctx.fiber/子插件就崩，所以运行时入口必须是真 @deepseek-ai/cordis。
//
// 宿主侧仍然保留三件 lite 时期的东西（插件与 bin/loader 都依赖它们）：
//  - 服务注册表：home/skills/connection/tools/commands/agents/webServer/loader/typert/subprocess
//    + llm + credentials 共 12 个，形状与 lite 完全一致；
//  - ctx.set(name, value) 的「注册或覆盖」语义（cordis 原生 set 只能改已 provide 的服务）；
//  - ctx.plugins / ctx.dispose()（cordis 的 root context 没有这两个）。
// 宿主状态放模块级 WeakMap，不往 context 上挂字符串属性（cordis 的属性访问 = 服务解析）。
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { Context } from '@deepseek-ai/cordis';
import { createCredentialsService, createLlmService } from './llm.js';

/** ctx -> 宿主状态。symbol 属性在 cordis 的 proxy 上也能写，但 WeakMap 更省心。 */
const HOST = new WeakMap();

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

export function createLogger(name) {
  const write = (level, args) => {
    if (LEVELS[level] < LEVELS[process.env.DSH_ENGINE_LOG_LEVEL || 'info']) return;
    const line = '[' + level + '] ' + name + ': ' + args.map(String).join(' ');
    (LEVELS[level] >= LEVELS.warn ? process.stderr : process.stdout).write(line + '\n');
  };
  const logger = {};
  for (const level of Object.keys(LEVELS)) logger[level] = (...args) => write(level, args);
  logger.child = (sub) => createLogger(name + '/' + sub);
  return logger;
}

function createSkillsService() {
  const providers = [];
  return {
    providers,
    registerProvider(fn) {
      if (typeof fn !== 'function') throw Object.assign(new TypeError('skills.registerProvider 需要函数'), { stage: 'apply' });
      providers.push(fn);
      return () => {
        const index = providers.indexOf(fn);
        if (index >= 0) providers.splice(index, 1);
      };
    },
    /** 真 cordis 里是 provider 聚合；这里只按注册顺序展开，供 plugins 之外的调用方探测。 */
    async list(options = {}) {
      const control = { signal: new AbortController().signal, invalidate: () => {} };
      const collected = [];
      for (const factory of providers) {
        const provider = factory(control);
        if (!provider || typeof provider.list !== 'function') continue;
        const items = await provider.list(options);
        collected.push(...(Array.isArray(items) ? items : (items?.candidates ?? [])));
      }
      return collected;
    },
  };
}

function createConnectionService() {
  const handlers = new Map();
  return {
    rpc: {
      handlers,
      handle(path, handler) {
        if (typeof path !== 'string' || typeof handler !== 'function') {
          throw Object.assign(new TypeError('connection.rpc.handle(path, handler) 参数无效'), { stage: 'apply' });
        }
        handlers.set(path, handler);
        return () => {
          if (handlers.get(path) === handler) handlers.delete(path);
        };
      },
      has(path) { return handlers.has(path); },
      paths() { return [...handlers.keys()]; },
      async call(path, endpoint, payload) {
        const handler = handlers.get(path);
        if (handler === undefined) {
          throw Object.assign(new Error('未注册 RPC 路径 ' + JSON.stringify(path)), { stage: 'rpc' });
        }
        return handler(endpoint, payload);
      },
    },
  };
}

function createRegistryService(label, kind) {
  const items = new Map();
  return {
    items,
    register(definition) {
      if (!definition || typeof definition.name !== 'string' || definition.name === '') {
        throw Object.assign(new TypeError(label + '.register 需要带 name 的 ' + kind), { stage: 'apply' });
      }
      items.set(definition.name, definition);
      return () => {
        if (items.get(definition.name) === definition) items.delete(definition.name);
      };
    },
    get(name) { return items.get(name); },
    has(name) { return items.has(name); },
    list() { return [...items.values()]; },
    names() { return [...items.keys()]; },
  };
}

/** cordis 里 agents 只被插件用来取当前发起者；宿主没有会话上下文时返回 undefined 即可。 */
function createAgentsService() {
  return {
    currentInitiator() { return undefined; },
    list() { return []; },
  };
}

/**
 * 诚实桩：只提供注册/查找语义（register/registerProvider/get/has/list/names），
 * 绝不假装实现编译或进程执行。桌面 cordis 宿主有 loader/typert/subprocess 服务，
 * 市场插件（如 dsh-plugin-diraud）会在 inject 里点名它们；移动端引擎没有对应实现，
 * 挂一个能通过 inject 校验的最小注册表；actions 里点名的业务方法调用时抛明确错误，
 * 便于在 logcat 里定位真正缺失的能力。
 */
function createHonestRegistry(label, options = {}) {
  const items = new Map();
  const providers = [];
  const actions = options.actions ?? [];
  const stub = {
    items,
    providers,
    register(definition) {
      const name = definition?.name ?? definition?.id ?? null;
      if (typeof name === 'string' && name !== '') {
        items.set(name, definition);
        return () => { if (items.get(name) === definition) items.delete(name); };
      }
      if (typeof definition === 'function') {
        providers.push(definition);
        return () => { const i = providers.indexOf(definition); if (i >= 0) providers.splice(i, 1); };
      }
      throw Object.assign(new TypeError(label + '.register 需要带 name 的定义或函数'), { stage: 'apply' });
    },
    registerProvider(fn) {
      if (typeof fn !== 'function') throw Object.assign(new TypeError(label + '.registerProvider 需要函数'), { stage: 'apply' });
      providers.push(fn);
      return () => { const i = providers.indexOf(fn); if (i >= 0) providers.splice(i, 1); };
    },
    get(name) { return items.get(name); },
    has(name) { return items.has(name); },
    list() { return [...providers, ...items.values()]; },
    names() { return [...items.keys()]; },
  };
  for (const method of actions) {
    stub[method] = () => {
      throw Object.assign(new Error(label + '.' + method + ' 未在移动端实现（诚实桩，不伪造执行）'), { stage: 'apply', notImplemented: true });
    };
  }
  return stub;
}

/**
 * webServer：真起一个 127.0.0.1 的 http server（懒启动，首次 register 才 listen），
 * 路由形态与 dsh 宿主一致：{ kind: 'exact' | 'prefix', path, handler(req,res) }。
 */
function createWebServerService(logger) {
  const routes = [];
  let server = null;
  let port = 0;

  const matches = (route, url) => {
    const path = url.split('?')[0];
    if (route.kind === 'exact') return path === route.path;
    const base = route.path.endsWith('/') ? route.path.slice(0, -1) : route.path;
    return path === base || path.startsWith(base + '/');
  };

  const ensureServer = () => {
    if (server) return server;
    server = createServer((req, res) => {
      const url = req.url || '/';
      const route = routes.find((item) => matches(item, url));
      if (!route) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('not found');
        return;
      }
      Promise.resolve()
        .then(() => route.handler(req, res))
        .catch((error) => {
          logger.warn('webServer 路由失败 ' + route.path + ':', error?.message ?? error);
          if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
          if (!res.writableEnded) res.end('route error');
        });
    });
    server.on('error', (error) => logger.warn('webServer 监听失败:', error?.message ?? error));
    server.listen(0, '127.0.0.1', () => {
      port = server.address()?.port ?? 0;
      logger.info('webServer listening on 127.0.0.1:' + port);
    });
    if (typeof server.unref === 'function') server.unref();
    return server;
  };

  return {
    register(route) {
      if (!route || typeof route.path !== 'string' || typeof route.handler !== 'function') {
        throw Object.assign(new TypeError('webServer.register 需要 { path, handler }'), { stage: 'apply' });
      }
      const entry = { kind: route.kind === 'exact' ? 'exact' : 'prefix', path: route.path, handler: route.handler };
      routes.push(entry);
      ensureServer();
      return () => {
        const index = routes.indexOf(entry);
        if (index >= 0) routes.splice(index, 1);
      };
    },
    routes() { return routes.map((item) => ({ kind: item.kind, path: item.path })); },
    get port() { return port; },
    get url() { return port ? 'http://127.0.0.1:' + port : null; },
    close() {
      if (!server) return;
      server.close();
      server = null;
      port = 0;
    },
  };
}

/** 宿主状态（服务名集合、已装载插件、反向通道）——不放 context 上。 */
export function hostState(ctx) {
  const host = HOST.get(ctx);
  if (host === undefined) throw Object.assign(new TypeError('不是本模块创建的 cordis context'), { stage: 'internal' });
  return host;
}

/** 已装载插件记录（bin.js 的 ping / plugins.list 用）。 */
export function listPlugins(ctx) {
  return hostState(ctx).plugins;
}

function definitionOf(mod) {
  if (typeof mod === 'function') {
    return { apply: mod, inject: mod.inject ?? [], name: mod.name || 'anonymous', Config: mod.Config };
  }
  return { apply: mod?.apply, inject: mod?.inject ?? [], name: mod?.name || 'anonymous', Config: mod?.Config };
}

/**
 * ESM 入口拿到的永远是命名空间对象：'default' in mod 说明插件本体在 default 上
 * （export default class Service 是社区插件的常见写法），不拆包就会报「缺少 apply 函数」。
 */
function unwrapModule(mod) {
  if (mod && typeof mod === 'object' && mod.default !== undefined && (mod.default !== null)) {
    return mod.default;
  }
  return mod;
}

/** cordis 只认「函数」或「{ apply }」两种形状；class 被当函数调用会抛 cannot be invoked without 'new'，包一层。 */
function asCallable(plugin) {
  if (typeof plugin !== 'function') return plugin;
  const text = Function.prototype.toString.call(plugin).trimStart();
  const looksLikeClass = text.startsWith('class') || (plugin.prototype && Object.getOwnPropertyNames(plugin.prototype).length > 1);
  if (!looksLikeClass) return plugin;
  return function classPlugin(ctx, config) {
    return new plugin(ctx, config);
  };
}

/**
 * ctx.plugin 的宿主包装：真 fiber 交给 cordis 跑，宿主只记账。
 *
 * 返回的记录**不是 thenable**——注入没齐的插件在 cordis 里是延迟状态，await 它会一直等；
 * 想拿 apply 结果用 record.settled()（cordis fiber.await()，配置校验/apply 抛错都在这里抛）。
 */
function applyPlugin(root, host, mod, config) {
  const target = asCallable(unwrapModule(mod));
  const definition = definitionOf(target);
  // 命名空间上的 name/inject 是插件作者的权威声明，default 上的 static 字段可能没有。
  if (mod && typeof mod === 'object') {
    if (definition.name === 'anonymous' && typeof mod.name === 'string') definition.name = mod.name;
    if (definition.inject.length === 0 && Array.isArray(mod.inject)) definition.inject = mod.inject;
  }
  if (typeof definition.apply !== 'function') {
    throw Object.assign(new Error('插件 ' + definition.name + ' 缺少 apply 函数'), { stage: 'apply' });
  }
  const fiber = root.plugin(target, config ?? {});
  const record = {
    name: definition.name,
    inject: [...definition.inject],
    config: config ?? {},
    appliedAt: Date.now(),
    status: 'pending',
    fiber,
    settled: () => fiber.await(),
  };
  host.plugins.push(record);
  fiber.await().then(
    () => { record.status = fiber.state === 2 ? 'applied' : 'deferred'; },
    (error) => {
      record.status = 'failed';
      record.error = error;
    },
  );
  return record;
}

async function disposeHost(root, host) {
  host.disposed = true;
  try {
    await root.fiber.dispose();
  } finally {
    host.services.clear();
    host.plugins.length = 0;
  }
}

function setService(root, host, key, value) {
  if (typeof key !== 'string' || key === '') {
    throw Object.assign(new TypeError('ctx.set(key, value) 需要非空字符串 key'), { stage: 'apply' });
  }
  if (host.services.has(key)) {
    // 已 provide 过的服务：同 fiber（root）内覆盖，cordis 的 set 允许。
    try { root.set(key, value); } catch { /* 不同 fiber 提供时退化成重新 provide */ }
  } else {
    root.provide(key, value);
  }
  host.services.add(key);
  return value;
}

/**
 * 创建宿主 context。
 *
 * options: { home, name, nativeCall, logger }
 * 返回的是「真 cordis context 的 facade」：所有属性/方法转发给真 context（Context.is 为 true，
 * 插件拿到的也仍是 cordis 自己生成的子 context），只额外补 dispose/plugins/set/has/plugin 五个宿主语义。
 */
export function createContext(options = {}) {
  const home = options.home ?? process.cwd();
  const name = options.name ?? 'engine';
  const logger = options.logger ?? createLogger(name);
  const root = new Context();
  const host = {
    home,
    name,
    logger,
    events: new EventEmitter(),
    services: new Set(),
    plugins: [],
    nativeCall: options.nativeCall ?? null,
    bridge: options.bridge ?? null,
    disposed: false,
  };
  HOST.set(root, host);

  const provide = (key, value) => { root.provide(key, value); host.services.add(key); };
  provide('home', home);
  provide('skills', createSkillsService());
  provide('connection', createConnectionService());
  provide('tools', createRegistryService('tools', '工具定义'));
  provide('commands', createRegistryService('commands', '命令定义'));
  provide('agents', createAgentsService());
  provide('webServer', createWebServerService(logger));
  provide('loader', createHonestRegistry('loader', { actions: ['load', 'unload', 'resolve'] }));
  provide('typert', createHonestRegistry('typert', { actions: ['resolve'] }));
  provide('subprocess', createHonestRegistry('subprocess', { actions: ['run', 'spawn', 'exec', 'execFile', 'execSync'] }));
  // 新增两服务：llm（转发 Kotlin 模型网关）、credentials（插件存取密钥，落 Kotlin SecretStore）。
  provide('llm', createLlmService({ host, logger }));
  provide('credentials', createCredentialsService({ host, logger }));

  const overrides = {
    dispose: () => disposeHost(root, host),
    set: (key, value) => setService(root, host, key, value),
    has: (key) => (typeof key === 'string' && (host.services.has(key) || root.get(key) !== undefined)),
    plugin: (mod, config) => applyPlugin(root, host, mod, config),
    on: (event, listener) => {
      host.events.on(event, listener);
      return () => host.events.off(event, listener);
    },
    emit: (event, ...args) => host.events.emit(event, ...args),
    /** 反向通道晚于 context 就绪（socket 连上才有），所以留一个注入口。 */
    setNativeCall: (fn) => { host.nativeCall = fn; return host.nativeCall; },
    nativeCall: (method, payload) => {
      if (typeof host.nativeCall !== 'function') {
        throw Object.assign(new Error('宿主反向通道未接入：' + method), { stage: 'transport' });
      }
      return host.nativeCall(method, payload);
    },
  };

  const facade = new Proxy(root, {
    get(target, key) {
      if (key === 'plugins') return host.plugins;
      if (key === 'name') return host.name;
      if (key === 'log') return host.logger;
      if (key === 'registry') return { services: host.services };
      if (typeof key === 'string' && Object.prototype.hasOwnProperty.call(overrides, key)) return overrides[key];
      return Reflect.get(target, key, target);
    },
    has(target, key) {
      if (key === 'plugins' || (typeof key === 'string' && Object.prototype.hasOwnProperty.call(overrides, key))) return true;
      return Reflect.has(target, key);
    },
    set(target, key, value) {
      return Reflect.set(target, key, value, target);
    },
  });
  HOST.set(facade, host);
  return facade;
}
