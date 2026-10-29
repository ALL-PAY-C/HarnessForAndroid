// llm / credentials：宿主能力服务（引擎自己不落模型、也不落密钥）。
//
// 移动端的分工是固定的：模型配置与密钥在 Kotlin 侧（AppContainer 的 LlmConfig / SecretStore），
// Node 引擎只负责插件编排。所以这两个服务都通过 nativeCall(method, payload) 反向请求 Kotlin：
//   llm.chat({ messages, model?, ... })  -> { content, usage? }（走 AppContainer 的模型网关）
//   credentials.get/set/delete/list      -> SecretStore
// nativeCall 由 bin.js 在 socket 连上后注入（host/native-bridge.js），单测直接注入桩。
//
// provider 插件不需要碰 nativeCall：它只 registerProvider 声明自己怎么出模型，
// 宿主（或 ctx.llm.chat）再决定走 Kotlin 网关还是本地 provider。

function applyError(message, extra = {}) {
  return Object.assign(new Error(message), { stage: 'apply', ...extra });
}

function nativeError(message, extra = {}) {
  return Object.assign(new Error(message), { stage: 'transport', ...extra });
}

/** 从 models() 的条目里取模型 id：字符串，或 { model } / { id } 形式的对象。 */
function modelIdOf(entry) {
  if (typeof entry === 'string') return entry;
  if (entry && typeof entry === 'object') {
    if (typeof entry.model === 'string') return entry.model;
    if (typeof entry.id === 'string') return entry.id;
  }
  return null;
}

/**
 * 按 request.model 挑 provider：模型名命中某个 provider.models() 且该 provider 自带 chat。
 * 命中后由 provider 出模型，不再转发 Kotlin 网关 —— 设备上 nativeCall 永远存在，
 * 不先挑 provider 的话插件注册的 provider 一次也不会被用到。
 * providers 收 Map 或普通对象；models() 抛错只跳过该 provider，不拖垮整次选择。
 */
export async function pickProvider(providers, model) {
  const wanted = typeof model === 'string' ? model.trim() : '';
  if (wanted === '' || providers === null || providers === undefined) return null;
  const entries = providers instanceof Map ? providers.entries() : Object.entries(providers);
  for (const [name, provider] of entries) {
    if (!provider || typeof provider.chat !== 'function' || typeof provider.models !== 'function') continue;
    let list;
    try {
      list = await provider.models();
    } catch {
      continue;
    }
    for (const entry of Array.isArray(list) ? list : []) {
      if (modelIdOf(entry) === wanted) return { name, provider };
    }
  }
  return null;
}

/** provider 的返回结构各家不同：统一成 { content, usage? }（content 一定是字符串）。 */
export function normalizeChatResult(value) {
  if (typeof value === 'string') return { content: value };
  const content =
    (typeof value?.content === 'string' && value.content) ||
    (typeof value?.text === 'string' && value.text) ||
    (typeof value?.choices?.[0]?.message?.content === 'string' && value.choices[0].message.content) ||
    '';
  if (content === '') {
    throw Object.assign(new Error('llm.chat 返回里没有 content / text / choices[0].message.content'), { stage: 'provider' });
  }
  const result = { content };
  if (value?.usage !== undefined) result.usage = value.usage;
  if (value?.provider !== undefined) result.provider = value.provider;
  if (value?.model !== undefined) result.model = value.model;
  return result;
}

/** handleLlmRpc 的第一个参数：llm 服务本身，或持有 llm 的 ctx（两种都收，方便单测直连）。 */
function resolveLlm(target) {
  if (target && typeof target.chat === 'function' && target.providers !== undefined) return target;
  const viaProp = target?.llm;
  if (viaProp && typeof viaProp.chat === 'function') return viaProp;
  const viaGet = typeof target?.get === 'function' ? target.get('llm') : undefined;
  if (viaGet && typeof viaGet.chat === 'function') return viaGet;
  throw Object.assign(new Error('context 上没有 llm 服务（需要 createContext 之后的 ctx）'), { stage: 'apply' });
}

/** /llm models：聚合所有 provider 声明的模型。 */
async function collectProviders(llm) {
  const registry = llm?.providers;
  if (!(registry instanceof Map)) return { providers: [], models: [] };
  const providers = [];
  const models = [];
  for (const [name, provider] of registry) {
    if (!provider || typeof provider.models !== 'function') continue;
    let list;
    try {
      list = await provider.models();
    } catch {
      continue;
    }
    const ids = [];
    for (const entry of Array.isArray(list) ? list : []) {
      const id = modelIdOf(entry);
      if (id !== null) ids.push(id);
    }
    providers.push({ name, models: ids });
    for (const id of ids) if (!models.includes(id)) models.push(id);
  }
  return { providers, models };
}

/**
 * /llm 的 endpoint 实现（bin.js 注册的 handler 和端到端脚本共用这一份）。
 * - models → { ok:true, value:{ providers:[{name,models}], models } }
 * - chat   → payload { messages, model?, temperature?, maxTokens? } → { ok:true, value:{ content, usage? } }
 */
export async function handleLlmRpc(target, endpoint, payload = {}) {
  const llm = resolveLlm(target);
  if (endpoint === 'models') {
    return { ok: true, value: await collectProviders(llm) };
  }
  if (endpoint === 'chat') {
    if (!payload || !Array.isArray(payload.messages) || payload.messages.length === 0) {
      throw Object.assign(new Error('/llm chat 需要非空 messages 数组'), { stage: 'rpc' });
    }
    return { ok: true, value: normalizeChatResult(await llm.chat(payload)) };
  }
  throw Object.assign(new Error('未实现的 /llm endpoint ' + JSON.stringify(endpoint)), { stage: 'rpc' });
}

/** 在宿主 context 上挂 /llm。位置与 installSkillManager 一致：createContext 之后、插件加载之前。 */
export function installLlmRpc(ctx, options = {}) {
  const connection = ctx.get('connection');
  if (connection?.rpc?.handle === undefined) {
    throw Object.assign(new Error('context 上没有 connection.rpc 服务'), { stage: 'apply' });
  }
  const llm = resolveLlm(ctx);
  const dispose = connection.rpc.handle('/llm', (endpoint, payload) => handleLlmRpc(llm, endpoint, payload));
  if (typeof options.logger?.info === 'function') options.logger.info('/llm 已注册：endpoints = models, chat');
  return { dispose, llm };
}

/**
 * llm 服务。
 *
 * - providers: Map<name, provider>，provider 形如 { models?(): string[] | Promise, chat?(request) }
 * - registerProvider(name, provider) → disposer
 * - chat(request)：优先转发 Kotlin 网关（nativeCall 'llm.chat'），没有网关时退到 provider.chat
 */
export function createLlmService(options = {}) {
  const host = options.host ?? {};
  const logger = options.logger;
  const providers = new Map();

  const service = {
    providers,
    registerProvider(name, provider) {
      if (typeof name !== 'string' || name === '') throw applyError('llm.registerProvider 需要非空 name');
      if (!provider || typeof provider !== 'object') throw applyError('llm.registerProvider 需要 provider 对象');
      providers.set(name, provider);
      return () => { if (providers.get(name) === provider) providers.delete(name); };
    },
    unregisterProvider(name) { return providers.delete(name); },
    names() { return [...providers.keys()]; },
    /** 所有 provider 声明的模型并集（插件自检用；真网关的模型列表在 Kotlin 侧）。 */
    async models() {
      const collected = [];
      for (const [name, provider] of providers) {
        if (typeof provider.models !== 'function') continue;
        const list = await provider.models();
        for (const model of Array.isArray(list) ? list : []) {
          collected.push(typeof model === 'string' ? { provider: name, model } : { provider: name, ...model });
        }
      }
      return collected;
    },
    /**
     * 出模型。request 形状与只取 content 的调用方对齐：{ messages, model?, temperature?, maxTokens?, tools? }
     * 顺序固定：request.model 命中某个 provider → 该 provider 自己出模型（不看 nativeCall）；
     * 命中不了 → 有 Kotlin 网关就走网关；再没有就退到第一个带 chat 的 provider。
     * provider 自己失败时把原错误抛出去（带 stage='provider'），绝不悄悄改走网关。
     */
    async chat(request) {
      if (!request || !Array.isArray(request.messages) || request.messages.length === 0) {
        throw applyError('llm.chat 需要非空 messages 数组');
      }
      const picked = await pickProvider(providers, request.model);
      if (picked !== null) {
        try {
          return await picked.provider.chat(request);
        } catch (error) {
          const failure = error instanceof Error ? error : new Error(String(error));
          failure.stage = 'provider';
          failure.provider = picked.name;
          throw failure;
        }
      }
      if (typeof host.nativeCall === 'function') return host.nativeCall('llm.chat', request);
      for (const provider of providers.values()) {
        if (typeof provider.chat === 'function') return provider.chat(request);
      }
      if (logger) logger.warn('llm.chat 没有 Kotlin 网关也没有可用 provider');
      throw nativeError('llm 网关未接入：宿主没有注入 nativeCall，也没有注册 chat provider');
    },
    /** 让 Kotlin 侧按模型/会话给出可用配置（插件做能力探测时用）。 */
    async resolve(model) {
      if (typeof host.nativeCall !== 'function') return { model: model ?? null };
      return host.nativeCall('llm.resolve', { model: model ?? null });
    },
  };
  return service;
}

/**
 * credentials 服务：插件存取密钥。
 *
 * 真实现走 Kotlin SecretStore（nativeCall）；没有网关时退到进程内存，
 * 并把这次降级记一条 warn —— 引擎重启就丢，绝不悄悄假装存住了。
 */
export function createCredentialsService(options = {}) {
  const host = options.host ?? {};
  const logger = options.logger;
  const memory = new Map();
  let degraded = false;

  const remember = () => {
    if (degraded) return;
    degraded = true;
    if (logger) logger.warn('credentials 没有 Kotlin 网关，退到进程内存（引擎重启即丢）');
  };

  const call = async (method, payload) => {
    if (typeof host.nativeCall === 'function') return host.nativeCall(method, payload);
    remember();
    return undefined;
  };

  return {
    async get(key) {
      if (typeof key !== 'string' || key === '') throw applyError('credentials.get 需要非空 key');
      const value = await call('credentials.get', { key });
      if (typeof value === 'string') return value;
      if (value && typeof value.value === 'string') return value.value;
      return memory.has(key) ? memory.get(key) : null;
    },
    async set(key, value) {
      if (typeof key !== 'string' || key === '') throw applyError('credentials.set 需要非空 key');
      if (typeof value !== 'string') throw applyError('credentials.set 的 value 必须是字符串');
      memory.set(key, value);
      await call('credentials.set', { key, value });
      return true;
    },
    async delete(key) {
      if (typeof key !== 'string' || key === '') throw applyError('credentials.delete 需要非空 key');
      const had = memory.delete(key);
      await call('credentials.delete', { key });
      return had;
    },
    async list() {
      const remote = await call('credentials.list', {});
      const keys = new Set([...memory.keys()]);
      for (const key of Array.isArray(remote) ? remote : (remote?.keys ?? [])) {
        if (typeof key === 'string') keys.add(key);
      }
      return [...keys];
    },
    get degraded() { return degraded; },
  };
}
