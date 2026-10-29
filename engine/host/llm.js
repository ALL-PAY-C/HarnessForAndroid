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

/**
 * provider 的返回结构各家不同：统一成 { content, toolCalls?, usage? }。
 * 只回工具调用、正文为空是合法的（模型这一轮只调工具），所以 toolCalls 非空时不因空正文报错。
 */
export function normalizeChatResult(value) {
  if (typeof value === 'string') return { content: value };
  const content =
    (typeof value?.content === 'string' && value.content) ||
    (typeof value?.text === 'string' && value.text) ||
    (typeof value?.choices?.[0]?.message?.content === 'string' && value.choices[0].message.content) ||
    '';
  const toolCalls =
    Array.isArray(value?.toolCalls) ? value.toolCalls
      : Array.isArray(value?.choices?.[0]?.message?.tool_calls) ? value.choices[0].message.tool_calls
        : [];
  if (content === '' && toolCalls.length === 0) {
    throw Object.assign(new Error('llm.chat 返回里没有 content / text / choices[0].message.content'), { stage: 'provider' });
  }
  const result = { content };
  if (toolCalls.length > 0) result.toolCalls = toolCalls;
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

// ---------------------------------------------------------------------------
// adapter 面：对齐 @deepseek-ai/dsh-llm 的 LlmRuntime 成员表。
//
// 移动端引擎里没有 dsh-llm 的运行时，但社区插件是按 LlmRuntime 的形状写的：
//   registerAdapter / registerConfigurableProviders / registerModelDiscovery /
//   listProviders / listModels / resolveModelInfo / stream …
// 少一个成员，插件就崩在 ctx.llm.X.bind(ctx.llm) 或 ctx.llm.X(...) is not a function。
// 所以这一层的规矩是：能真转发的真转发，转发不了的抛带 member 名的说明性 Error，
// 绝不返回 undefined —— undefined 到了插件里只会变成更难查的 TypeError。
// ---------------------------------------------------------------------------

function llmError(message, code, extra = {}) {
  return Object.assign(new Error(message), { stage: 'llm', code, ...extra });
}

/** 未实现成员的统一出口：调用即抛，错误里带 member 名，插件读得到原因。 */
function unsupported(member, reason) {
  return function unsupportedMember() {
    throw Object.assign(new Error('llm.' + member + ' 未实现：' + reason), {
      stage: 'llm',
      code: 'LLM_UNSUPPORTED',
      member,
    });
  };
}

/** inputModalities：只收非空字符串数组；undefined 表示「adapter 没说」，不是「没有」。 */
function modalitiesOf(value) {
  if (value === undefined || value === null) return undefined;
  if (!Array.isArray(value) || value.length === 0) {
    throw llmError('llm: adapter 返回的 inputModalities 必须是非空字符串数组', 'INVALID_MODALITIES');
  }
  if (value.some((item) => typeof item !== 'string' || item === '')) {
    throw llmError('llm: adapter 返回的 inputModalities 必须是非空字符串数组', 'INVALID_MODALITIES');
  }
  return [...value];
}

function assertProviders(routes, member) {
  if (!Array.isArray(routes) || routes.length === 0) throw applyError('llm.' + member + ' 需要非空 provider 数组');
  for (const provider of routes) {
    if (typeof provider !== 'string' || provider === '') throw applyError('llm.' + member + ': provider 必须是非空字符串');
  }
}

/** adapter.providerInfo(provider) 的校验：id 必须等于 provider，name 必须非空。 */
function providerInfoOf(adapter, provider) {
  if (typeof adapter.providerInfo !== 'function') return { id: provider, name: provider };
  const info = adapter.providerInfo(provider);
  if (!info || info.id !== provider || typeof info.name !== 'string' || info.name === '') {
    throw applyError(
      'llm.registerAdapter: adapter.providerInfo("' + provider + '") 必须返回 { id: "' + provider + '", name: 非空字符串 }',
      { code: 'INVALID_PROVIDER_INFO', provider },
    );
  }
  return {
    id: provider,
    name: info.name,
    ...(typeof info.description === 'string' && info.description !== '' ? { description: info.description } : {}),
  };
}

/** adapter.listModels() 的返回值校验与脱壳，字段与 dsh-llm 的 listModels 一致。 */
function normalizeCatalog(provider, models) {
  if (!Array.isArray(models)) {
    throw llmError('llm.listModels: provider "' + provider + '" 的 adapter 没有返回模型数组', 'INVALID_CATALOG', { provider });
  }
  const seen = new Set();
  return models.map((model) => {
    const bad = !model
      || typeof model.provider !== 'string' || model.provider !== provider
      || typeof model.id !== 'string' || model.id === ''
      || typeof model.name !== 'string' || model.name === ''
      || (model.description !== undefined && typeof model.description !== 'string')
      || seen.has(model.id);
    if (bad) {
      throw llmError('llm.listModels: provider "' + provider + '" 的 adapter 返回了非法或重复的模型元信息', 'INVALID_CATALOG', { provider });
    }
    seen.add(model.id);
    const inputModalities = modalitiesOf(model.inputModalities);
    return {
      provider,
      id: model.id,
      name: model.name,
      ...(model.description === undefined ? {} : { description: model.description }),
      ...(inputModalities === undefined ? {} : { inputModalities }),
    };
  });
}

/** adapter.resolveModel() 的返回值校验与脱壳，字段与 dsh-llm 的 resolveModelInfo 一致。 */
function normalizeModelInfo(provider, model, resolved) {
  const bad = !resolved
    || typeof resolved.provider !== 'string' || resolved.provider !== provider
    || typeof resolved.id !== 'string' || resolved.id !== model
    || typeof resolved.name !== 'string' || resolved.name === ''
    || (resolved.description !== undefined && typeof resolved.description !== 'string');
  if (bad) {
    throw llmError(
      'llm.resolveModelInfo: adapter 返回的模型元信息不合法（provider 必须为 "' + provider + '"，id 必须为 "' + model + '"，name 非空）',
      'INVALID_MODEL_INFO',
      { provider, model },
    );
  }
  const context = resolved.context;
  if (context !== undefined && (!Number.isInteger(context.contextWindow) || context.contextWindow <= 0)) {
    throw llmError('llm.resolveModelInfo: adapter 返回的 context.contextWindow 非法', 'INVALID_MODEL_CONTEXT', { provider, model });
  }
  const inputModalities = modalitiesOf(resolved.inputModalities);
  const defaultMaxTokens = resolved.defaultMaxTokens;
  if (defaultMaxTokens !== undefined && (!Number.isSafeInteger(defaultMaxTokens) || defaultMaxTokens <= 0)) {
    throw llmError('llm.resolveModelInfo: adapter 返回的 defaultMaxTokens 非法', 'INVALID_MODEL_MAX_TOKENS', { provider, model });
  }
  const info = {
    provider,
    id: model,
    name: resolved.name,
    ...(resolved.description === undefined ? {} : { description: resolved.description }),
    ...(inputModalities === undefined ? {} : { inputModalities }),
    ...(context === undefined ? {} : { context: { contextWindow: context.contextWindow } }),
    ...(defaultMaxTokens === undefined ? {} : { defaultMaxTokens }),
  };
  const reasoning = resolved.reasoning;
  if (reasoning === undefined) return info;
  const seen = new Set();
  const efforts = (Array.isArray(reasoning.efforts) ? reasoning.efforts : []).map((effort) => {
    if (!effort || typeof effort.id !== 'string' || effort.id === '' || typeof effort.name !== 'string' || effort.name === '' || seen.has(effort.id)) {
      throw llmError('llm.resolveModelInfo: adapter 返回的 reasoning.efforts 非法或重复', 'INVALID_MODEL_REASONING', { provider, model });
    }
    seen.add(effort.id);
    return { id: effort.id, name: effort.name, ...(effort.description === undefined ? {} : { description: effort.description }) };
  });
  if (efforts.length === 0) throw llmError('llm.resolveModelInfo: reasoning.efforts 不能为空', 'INVALID_MODEL_REASONING', { provider, model });
  if (reasoning.defaultEffort !== undefined && !seen.has(reasoning.defaultEffort)) {
    throw llmError('llm.resolveModelInfo: reasoning.defaultEffort 不在 efforts 里', 'INVALID_MODEL_REASONING', { provider, model });
  }
  return {
    ...info,
    reasoning: { efforts, ...(reasoning.defaultEffort === undefined ? {} : { defaultEffort: reasoning.defaultEffort }) },
  };
}

/** registerConfigurableProviders 的条目校验：四个字段都要求非空（settingsPath 是段数组）。 */
function normalizeDirectoryEntry(entry) {
  if (!entry || typeof entry !== 'object') throw applyError('llm.registerConfigurableProviders 的条目必须是对象');
  for (const field of ['provider', 'displayName', 'settingsNs']) {
    if (typeof entry[field] !== 'string' || entry[field] === '') {
      throw applyError('llm.registerConfigurableProviders 的条目缺少非空 ' + field);
    }
  }
  const settingsPath = entry.settingsPath;
  if (!Array.isArray(settingsPath) || settingsPath.length === 0 || settingsPath.some((seg) => typeof seg !== 'string' || seg === '')) {
    throw applyError('llm.registerConfigurableProviders 的 settingsPath 必须是非空字符串数组');
  }
  return { provider: entry.provider, displayName: entry.displayName, settingsNs: entry.settingsNs, settingsPath: [...settingsPath] };
}

/** 引擎自己不重试（重试策略在 Kotlin 网关侧），这里只回显一个诚实的零重试策略。 */
const DEFAULT_RETRY_POLICY = Object.freeze({ mode: 'normal', maxRetries: 0, retryableCodes: Object.freeze([]) });

/** 与 dsh-llm 的 adapterStream 对齐：adapter 抛错时收束成 terminal finish chunk。 */
function failureChunk(error, signal) {
  const failure = {
    code: typeof error?.code === 'string' ? error.code : 'LLM_STREAM_FAILED',
    message: error instanceof Error ? error.message : String(error),
  };
  const aborted = signal?.aborted === true || failure.code === 'ABORTED';
  return { type: 'finish', reason: aborted ? { kind: 'aborted', failure } : { kind: 'error', failure } };
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
  // adapter 面：provider -> { adapter, info }；插件的设置目录；设置项 → discover 函数。
  const adapters = new Map();
  const directory = new Map();
  const discoveries = new Map();

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

    // --- adapter 面（LlmRuntime 兼容）-----------------------------------------

    /** registerAdapter(routes, adapter) → handle；handle 本身是 disposer，另带 replace(routes)。 */
    registerAdapter(routes, adapter) {
      assertProviders(routes, 'registerAdapter');
      if (!adapter || typeof adapter !== 'object') throw applyError('llm.registerAdapter 需要 adapter 对象');
      if (typeof adapter.stream !== 'function') throw applyError('llm.registerAdapter 的 adapter 缺少 stream 方法');
      const commit = (next) => {
        assertProviders(next, 'registerAdapter');
        const infos = new Map();
        for (const provider of next) {
          const holder = adapters.get(provider);
          if (holder && holder.adapter !== adapter) {
            throw applyError('llm.registerAdapter: provider "' + provider + '" 已经被另一个 adapter 注册', { code: 'DUPLICATE_ADAPTER', provider });
          }
          infos.set(provider, providerInfoOf(adapter, provider));
        }
        for (const [provider, holder] of [...adapters]) {
          if (holder.adapter === adapter && !infos.has(provider)) adapters.delete(provider);
        }
        for (const [provider, info] of infos) adapters.set(provider, { adapter, info });
        return infos;
      };
      if (routes.some((provider) => adapters.has(provider))) {
        throw applyError('llm.registerAdapter: provider "' + routes.find((p) => adapters.has(p)) + '" 已经被另一个 adapter 注册', { code: 'DUPLICATE_ADAPTER' });
      }
      commit(routes);
      const handle = () => {
        for (const [provider, holder] of [...adapters]) if (holder.adapter === adapter) adapters.delete(provider);
      };
      handle.replace = (next) => { commit(next); };
      return handle;
    },

    /** 已注册 provider 的目录（adapter 优先，老 registerProvider 也列出来）。 */
    listProviders() {
      const listed = [];
      const seen = new Set();
      for (const [provider, holder] of adapters) {
        listed.push({ ...holder.info });
        seen.add(provider);
      }
      for (const name of providers.keys()) if (!seen.has(name)) listed.push({ id: name, name });
      return listed;
    },

    /** 插件把自己的设置目录挂到 llm 侧（设置页据此渲染 provider 分区）。 */
    registerConfigurableProviders(entries) {
      if (!Array.isArray(entries) || entries.length === 0) throw applyError('llm.registerConfigurableProviders 需要非空数组');
      const owner = {};
      const owned = new Map();
      const commit = (next) => {
        if (!Array.isArray(next)) throw applyError('llm.registerConfigurableProviders 需要数组');
        const normalized = next.map(normalizeDirectoryEntry);
        for (const entry of normalized) {
          const holder = directory.get(entry.provider);
          if (holder && holder.owner !== owner) {
            throw applyError('llm.registerConfigurableProviders: provider "' + entry.provider + '" 已经被别的插件注册', { code: 'DUPLICATE_DIRECTORY', provider: entry.provider });
          }
        }
        for (const provider of [...owned.keys()]) {
          if (!normalized.some((entry) => entry.provider === provider)) {
            directory.delete(provider);
            owned.delete(provider);
          }
        }
        for (const entry of normalized) {
          directory.set(entry.provider, { ...entry, settingsPath: [...entry.settingsPath], owner });
          owned.set(entry.provider, entry);
        }
      };
      commit(entries);
      const handle = () => {
        for (const provider of [...owned.keys()]) directory.delete(provider);
        owned.clear();
      };
      handle.replace = (next) => { commit(next); };
      return handle;
    },

    listConfigurableProviders() {
      return [...directory.values()].map(({ owner, ...entry }) => ({ ...entry, settingsPath: [...entry.settingsPath] }));
    },

    /** 插件提供「按设置项动态拉模型目录」的函数，返回 disposer。 */
    registerModelDiscovery(settingsNs, discover) {
      if (typeof settingsNs !== 'string' || settingsNs === '') throw applyError('llm.registerModelDiscovery 需要非空 settingsNs');
      if (typeof discover !== 'function') throw applyError('llm.registerModelDiscovery 需要 discover 函数');
      if (discoveries.has(settingsNs)) {
        throw applyError('llm.registerModelDiscovery: settingsNs "' + settingsNs + '" 已经注册过', { code: 'DUPLICATE_DISCOVERY', settingsNs });
      }
      discoveries.set(settingsNs, discover);
      return () => { if (discoveries.get(settingsNs) === discover) discoveries.delete(settingsNs); };
    },

    /** 真转发到插件的 discover，并把条目归一成 { id, name?, contextWindow?, maxTokens? } 去重。 */
    async discoverModels(settingsNs, request) {
      const discover = discoveries.get(settingsNs);
      if (discover === undefined) {
        throw llmError('llm.discoverModels: settingsNs "' + settingsNs + '" 没有注册 discover 函数', 'NO_DISCOVERY', { settingsNs });
      }
      const list = await discover(request ?? {});
      if (!Array.isArray(list)) {
        throw llmError('llm.discoverModels: discover 必须返回数组', 'INVALID_CATALOG', { settingsNs });
      }
      const seen = new Set();
      const out = [];
      for (const model of list) {
        if (!model || typeof model.id !== 'string' || model.id === '') {
          throw llmError('llm.discoverModels: discover 返回的条目缺少非空 id', 'INVALID_CATALOG', { settingsNs });
        }
        if (seen.has(model.id)) continue;
        seen.add(model.id);
        out.push({
          id: model.id,
          ...(typeof model.name === 'string' && model.name !== '' ? { name: model.name } : {}),
          ...(Number.isInteger(model.contextWindow) && model.contextWindow > 0 ? { contextWindow: model.contextWindow } : {}),
          ...(Number.isSafeInteger(model.maxTokens) && model.maxTokens > 0 ? { maxTokens: model.maxTokens } : {}),
        });
      }
      return out;
    },

    /** 某个 provider 的模型目录：有 adapter 走 adapter.listModels，老 registerProvider 也能列。 */
    async listModels(provider) {
      if (typeof provider !== 'string' || provider === '') throw applyError('llm.listModels 需要非空 provider');
      const holder = adapters.get(provider);
      if (holder !== undefined) {
        if (typeof holder.adapter.listModels !== 'function') {
          throw llmError('llm.listModels: provider "' + provider + '" 的 adapter 没有 listModels', 'NO_CATALOG', { provider });
        }
        return normalizeCatalog(provider, await holder.adapter.listModels(provider));
      }
      const legacy = providers.get(provider);
      if (legacy === undefined) throw llmError('llm.listModels: provider "' + provider + '" 没有注册', 'NO_ADAPTER', { provider });
      const list = typeof legacy.models === 'function' ? await legacy.models() : [];
      const seen = new Set();
      const out = [];
      for (const entry of Array.isArray(list) ? list : []) {
        const id = modelIdOf(entry);
        if (id === null || seen.has(id)) continue;
        seen.add(id);
        out.push({ provider, id, name: typeof entry?.name === 'string' ? entry.name : id });
      }
      return out;
    },

    /** 模型元信息：adapter.resolveModel 真转发，缺失时退到 listModels 的目录条目，绝不返回 undefined。 */
    async resolveModelInfo(provider, model, signal) {
      if (typeof provider !== 'string' || provider === '') throw applyError('llm.resolveModelInfo 需要非空 provider');
      if (typeof model !== 'string' || model === '') throw applyError('llm.resolveModelInfo 需要非空 model');
      const holder = adapters.get(provider);
      if (holder === undefined) {
        throw llmError('llm.resolveModelInfo: provider "' + provider + '" 没有注册 adapter，解析不了模型信息', 'NO_ADAPTER', { provider });
      }
      if (typeof holder.adapter.resolveModel === 'function') {
        return normalizeModelInfo(provider, model, await holder.adapter.resolveModel(provider, model, signal));
      }
      const catalog = typeof holder.adapter.listModels === 'function'
        ? normalizeCatalog(provider, await holder.adapter.listModels(provider))
        : [];
      const hit = catalog.find((entry) => entry.id === model);
      if (hit === undefined) {
        throw llmError('llm.resolveModelInfo: provider "' + provider + '" 的目录里没有模型 "' + model + '"', 'UNKNOWN_MODEL', { provider, model });
      }
      return { ...hit };
    },

    /** 流式出模型：真转发 adapter.stream；adapter 抛错时收束成 terminal finish chunk。 */
    async *stream(request) {
      if (!request || typeof request !== 'object') throw applyError('llm.stream 需要 options 对象');
      const provider = request.provider;
      if (typeof provider !== 'string' || provider === '') throw applyError('llm.stream 需要非空 provider');
      const holder = adapters.get(provider);
      if (holder === undefined) {
        throw llmError('llm.stream: provider "' + provider + '" 没有注册 adapter，出不了流式结果', 'NO_ADAPTER', { provider });
      }
      try {
        for await (const chunk of holder.adapter.stream(request)) yield chunk;
      } catch (error) {
        yield failureChunk(error, request.signal);
      }
    },

    providerRetryPolicy(provider) {
      const holder = typeof provider === 'string' ? adapters.get(provider) : undefined;
      if (holder && typeof holder.adapter.providerRetryPolicy === 'function') return holder.adapter.providerRetryPolicy(provider);
      return DEFAULT_RETRY_POLICY;
    },

    detachedModalities(value) { return modalitiesOf(value); },

    // --- 未实现的 LlmRuntime 成员：调用即抛说明性错误（不返回 undefined）------
    emitAdaptersUpdated: unsupported('emitAdaptersUpdated', '移动端没有 adapter 事件总线，插件改动直接生效'),
    warnAdaptersListenerFailure: unsupported('warnAdaptersListenerFailure', '同 emitAdaptersUpdated：没有事件总线'),
    prepareRoutes: unsupported('prepareRoutes', 'adapter 路由预解析属于 dsh-llm 内部流程，这里注册即生效'),
    commitRoutes: unsupported('commitRoutes', '同 prepareRoutes'),
    registration: unsupported('registration', '引擎只对外暴露 listModels/resolveModelInfo/stream，不暴露内部 registration 记录'),
    forAdapter: unsupported('forAdapter', '引擎不做 request 重放态扫描，直接把 options 交给 adapter.stream'),
    prepareCall: unsupported('prepareCall', '引擎不做 call 级能力校验，直接 stream'),
    resolveCallConfig: unsupported('resolveCallConfig', '引擎不做 call 级能力校验，直接 stream'),
    resolveCallFor: unsupported('resolveCallFor', '同 resolveCallConfig'),
    resolveModelInfoFor: unsupported('resolveModelInfoFor', '用 resolveModelInfo(provider, model, signal) 代替'),
    streamWithRegistration: unsupported('streamWithRegistration', '用 stream(options) 代替'),
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
