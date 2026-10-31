// 会话级「模型侧状态」：todo 表 / goal / 上下文压缩统计。
//
// 桌面侧是三件套（dsh-tool-todo 整表替换、dsh-tool-goal get/create/update、
// 压缩后端 BasicCompactionEngine）。移动端引擎没有那套包，这里给一份真实现：
//   - todo 整表替换语义（同桌面 dsh-tool-todo：写就是替换，不合并）
//   - goal get/create/update（同桌面 dsh-tool-goal：带 revision 乐观锁、complete/blocked 是终态）
//     ponytail: 只做「一份目标 + 一条 revision 链」，不做桌面的 goal 历史账本；需要多目标时按 sessionId 扩展。
//   - 压缩统计（次数 / 前后 token 估算 / 摘要长度），压缩事件因此对前端可见。
//
// 状态用 /tool-state RPC 暴露（Kotlin LocalEngineTransport 走 engineRpc 调），
// /commands 暴露 compact 命令入口（ChatRepository.compact → 这里）。
// 两条路径都走 connection.rpc.handle，与 /llm、/skill-manager 同一条注册链。

/** 字符 → token 的粗估（中文约 1 字 0.6 token，英文 4 字符 1 token，取 4 字符/token 的保守口径）。 */
export const CHARS_PER_TOKEN = 4;

/** token 粗估：只用于「压缩前后」对比与预算判断，不用于计费。 */
export function estimateTokens(text) {
  if (typeof text === 'string') return Math.ceil(text.length / CHARS_PER_TOKEN);
  if (Array.isArray(text)) return text.reduce((sum, item) => sum + estimateTokens(item), 0);
  if (text === null || text === undefined) return 0;
  return estimateTokens(JSON.stringify(text));
}

const TODO_STATUS = new Set(['pending', 'in_progress', 'completed']);

/** todo 归一化：整表替换的入参校验（形状错就抛，不静默丢项）。 */
export function normalizeTodos(raw) {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) throw Object.assign(new Error('todos 必须是数组'), { stage: 'rpc' });
  return raw.map((item, index) => {
    if (item === null || typeof item !== 'object') {
      throw Object.assign(new Error('todos[' + index + '] 必须是对象'), { stage: 'rpc' });
    }
    const content = String(item.content ?? '').trim();
    if (content === '') {
      throw Object.assign(new Error('todos[' + index + '].content 不能为空'), { stage: 'rpc' });
    }
    const status = String(item.status ?? 'pending');
    if (!TODO_STATUS.has(status)) {
      throw Object.assign(new Error('todos[' + index + '].status 只能是 pending/in_progress/completed，收到 ' + JSON.stringify(status)), { stage: 'rpc' });
    }
    return { content, status };
  });
}

const GOAL_ACTIONS = new Set(['edit', 'pause', 'resume', 'complete', 'blocked']);

/**
 * 会话状态仓库。一个 store 实例管所有 session；默认导出那份给 BasicCompactionEngine 复用，
 * 这样「插件侧的压缩」和「Kotlin 侧读到的统计」是同一份数。
 */
export function createToolStateStore(options = {}) {
  const now = typeof options.now === 'function' ? options.now : () => Date.now();
  const sessions = new Map();

  const blank = () => ({
    todos: [],
    goal: null,
    compaction: { count: 0, beforeTokens: 0, afterTokens: 0, summaryChars: 0, lastAt: 0 },
  });

  const entry = (sessionId) => {
    const key = String(sessionId ?? '') || 'default';
    let row = sessions.get(key);
    if (row === undefined) {
      row = blank();
      sessions.set(key, row);
    }
    return row;
  };

  return {
    /** 整表替换：同桌面 todo_write，传什么就是什么（空数组 = 清空）。 */
    writeTodos(sessionId, todos) {
      const list = normalizeTodos(todos);
      const row = entry(sessionId);
      row.todos = list;
      return list;
    },
    todos(sessionId) { return entry(sessionId).todos.slice(); },
    goal(sessionId) { return entry(sessionId).goal; },
    createGoal(sessionId, objective, maxRounds) {
      const text = String(objective ?? '').trim();
      if (text === '') throw Object.assign(new Error('create_goal 需要非空 objective'), { stage: 'rpc' });
      const row = entry(sessionId);
      const goal = {
        id: 'g' + (row.goal === null ? 1 : 2),
        revision: 1,
        objective: text,
        phase: 'active',
        roundsStarted: 0,
        maxGoalRounds: Number.isFinite(maxRounds) ? maxRounds : 0,
        createdAt: now(),
        updatedAt: now(),
      };
      row.goal = goal;
      return goal;
    },
    updateGoal(sessionId, action, patch = {}) {
      if (!GOAL_ACTIONS.has(action)) {
        throw Object.assign(new Error('未知 goal action: ' + JSON.stringify(action)), { stage: 'rpc' });
      }
      const row = entry(sessionId);
      if (row.goal === null) throw Object.assign(new Error('当前会话没有 goal'), { stage: 'rpc' });
      const goal = row.goal;
      if (patch.revision !== undefined && patch.revision !== null && patch.revision !== goal.revision) {
        throw Object.assign(new Error('goal revision 不匹配：期望 ' + goal.revision + '，收到 ' + patch.revision), { stage: 'rpc' });
      }
      const next = { ...goal, revision: goal.revision + 1, updatedAt: now() };
      if (action === 'edit') {
        if (patch.objective !== undefined) {
          const text = String(patch.objective ?? '').trim();
          if (text === '') throw Object.assign(new Error('edit 需要非空 objective'), { stage: 'rpc' });
          next.objective = text;
        }
        if (Number.isFinite(patch.maxGoalRounds)) next.maxGoalRounds = patch.maxGoalRounds;
      } else if (action === 'pause') {
        next.phase = 'paused';
      } else if (action === 'resume') {
        next.phase = 'active';
      } else if (action === 'complete') {
        next.phase = 'complete';
      } else {
        next.phase = 'blocked';
        next.blockedReason = String(patch.blockedReason ?? '').trim();
      }
      row.goal = next;
      return next;
    },
    /** 记一次压缩：前端据此显示「压缩了几次 / 省了多少」。 */
    recordCompaction(sessionId, stats = {}) {
      const row = entry(sessionId);
      const before = Number(stats.beforeTokens ?? 0) || 0;
      const after = Number(stats.afterTokens ?? 0) || 0;
      const summaryChars = Number(stats.summaryChars ?? 0) || 0;
      row.compaction = {
        count: row.compaction.count + 1,
        beforeTokens: before,
        afterTokens: after,
        summaryChars,
        lastAt: now(),
      };
      return row.compaction;
    },
    compaction(sessionId) { return { ...entry(sessionId).compaction }; },
    /** 只读投影：Kotlin 一次取走 todo/goal/压缩三块。 */
    snapshot(sessionId) {
      const row = entry(sessionId);
      return {
        todos: row.todos.slice(),
        goal: row.goal,
        compaction: { ...row.compaction },
      };
    },
    clear(sessionId) { sessions.delete(String(sessionId ?? '') || 'default'); },
    sessions() { return [...sessions.keys()]; },
  };
}

/** 进程内默认仓库：BasicCompactionEngine 与 /tool-state 共用同一份，避免两处统计对不上。 */
export const toolStateStore = createToolStateStore();

const DECISION_HINTS = ['决定', '改用', '采用', '方案是', '结论是', '定下', 'settled', 'decided'];
const PENDING_HINTS = ['待办', '未完成', '下一步', '还需要', 'todo', 'todo:', 'next step', 'remaining'];
const PATH_RE = /(?:[A-Za-z]:\\|\/)[A-Za-z0-9_.\-\/\\]*\.(?:kt|kts|js|mjs|cjs|ts|tsx|json|md|yml|yaml|gradle|java|xm)/g;

const flat = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();

const lineOf = (message) => {
  if (typeof message === 'string') return message;
  if (message === null || typeof message !== 'object') return '';
  const content = message.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === 'string' ? part : (part?.text ?? part?.content ?? '')))
      .join(' ');
  }
  if (content === undefined && typeof message.text === 'string') return message.text;
  return content === undefined ? '' : JSON.stringify(content);
};

/**
 * 结构化摘要：把被丢弃的最老若干条消息压成
 * 「角色 / 任务 / 已决定 / 未完成 / 关键文件路径」五段。
 *
 * 这是 Node 侧不依赖 LLM 的确定性摘要（BasicCompactionEngine.summarize 的默认实现）。
 * Kotlin 侧真压缩用的是模型生成的摘要（AgentThreads.compactWithLlm），两者形状一致，
 * 所以前端读摘要时不必区分是谁写的。
 */
export function summarizeMessages(messages, options = {}) {
  const items = Array.isArray(messages) ? messages : [];
  const maxChars = Number(options.maxChars ?? 1200) || 1200;
  const roles = { user: 0, assistant: 0, system: 0, tool: 0 };
  const tasks = [];
  const decisions = [];
  const pending = [];
  const paths = new Set();
  for (const message of items) {
    const role = String((message && typeof message === 'object' ? message.role : '') ?? 'user');
    if (roles[role] !== undefined) roles[role] += 1;
    const text = lineOf(message);
    if (text === '') continue;
    for (const match of text.match(PATH_RE) ?? []) paths.add(match);
    for (const raw of text.split(/[\n。；;]/)) {
      const line = flat(raw);
      if (line === '') continue;
      if (role === 'user' && tasks.length < 5) tasks.push(line.slice(0, 120));
      else if (DECISION_HINTS.some((hint) => line.includes(hint)) && decisions.length < 5) decisions.push(line.slice(0, 120));
      else if (PENDING_HINTS.some((hint) => line.toLowerCase().includes(hint)) && pending.length < 5) pending.push(line.slice(0, 120));
    }
  }
  const lines = ['上下文摘要（早期 ' + items.length + ' 条已压缩，原文不再重放）：'];
  lines.push('角色：用户 ' + roles.user + ' / 助手 ' + roles.assistant + ' / 系统 ' + roles.system + ' / 工具 ' + roles.tool + ' 条');
  lines.push('任务：' + (tasks.length === 0 ? '（早期没有明确的用户请求）' : tasks.join('；')));
  lines.push('已决定：' + (decisions.length === 0 ? '（无）' : '\n- ' + decisions.join('\n- ')));
  lines.push('未完成：' + (pending.length === 0 ? '（无）' : '\n- ' + pending.join('\n- ')));
  lines.push('关键路径：' + (paths.size === 0 ? '（无）' : [...paths].slice(0, 12).join(', ')));
  const text = lines.join('\n');
  return text.length <= maxChars ? text : text.slice(0, maxChars) + '…';
}

/**
 * compactSession：把「早期消息」压成摘要，返回压缩前后 token 估算并记账。
 * Kotlin 侧 /commands compact 走这里；BasicCompactionEngine.compactIfNeeded 也走这里。
 */
export function compactSession(store, sessionId, messages, options = {}) {
  const items = Array.isArray(messages) ? messages : [];
  const keepRecent = Number(options.keepRecent ?? 8) || 8;
  const older = items.slice(0, Math.max(0, items.length - keepRecent));
  const summary = summarizeMessages(older, { maxChars: options.maxChars ?? 1200 });
  const beforeTokens = estimateTokens(items);
  const afterTokens = estimateTokens(summary) + estimateTokens(items.slice(Math.max(0, items.length - keepRecent)));
  const stats = store.recordCompaction(sessionId, {
    beforeTokens,
    afterTokens,
    summaryChars: summary.length,
  });
  return {
    compacted: older.length > 0,
    droppedMessages: older.length,
    beforeTokens,
    afterTokens,
    savedTokens: Math.max(0, beforeTokens - afterTokens),
    summaryChars: summary.length,
    summary,
    stats,
  };
}

/**
 * installToolState：在宿主 context 上挂 /tool-state 与 /commands。
 * 位置与 installLlmRpc 一致：createContext 之后、插件加载之前（插件同名路径可覆盖）。
 */
export function installToolState(ctx, options = {}) {
  const connection = typeof ctx?.get === 'function' ? ctx.get('connection') : undefined;
  if (connection?.rpc?.handle === undefined) {
    throw Object.assign(new Error('context 上没有 connection.rpc 服务'), { stage: 'apply' });
  }
  const store = options.store ?? toolStateStore;
  const logger = options.logger ?? null;

  const disposers = [];
  disposers.push(connection.rpc.handle('/tool-state', async (endpoint, payload = {}) => {
    const sessionId = String(payload.sessionId ?? 'default');
    switch (endpoint) {
      case 'snapshot': return { ok: true, value: store.snapshot(sessionId) };
      case 'todo-write': return { ok: true, value: { todos: store.writeTodos(sessionId, payload.todos) } };
      case 'todo-read': return { ok: true, value: { todos: store.todos(sessionId) } };
      case 'goal-get': return { ok: true, value: { goal: store.goal(sessionId) } };
      case 'goal-create': return { ok: true, value: { goal: store.createGoal(sessionId, payload.objective, payload.maxRounds) } };
      case 'goal-update': return { ok: true, value: { goal: store.updateGoal(sessionId, payload.action, payload) } };
      case 'compaction-record': return { ok: true, value: { compaction: store.recordCompaction(sessionId, payload) } };
      case 'compaction-stats': return { ok: true, value: { compaction: store.compaction(sessionId) } };
      default:
        throw Object.assign(new Error('未知 tool-state endpoint: ' + JSON.stringify(endpoint)), { stage: 'rpc' });
    }
  }));

  disposers.push(connection.rpc.handle('/commands', async (endpoint, payload = {}) => {
    const sessionId = String(payload.sessionId ?? 'default');
    if (endpoint === 'list') return { ok: true, value: { commands: ['compact'] } };
    if (endpoint === 'compact') {
      const result = compactSession(store, sessionId, payload.messages, payload.options ?? {});
      if (typeof logger?.info === 'function') {
        logger.info('/commands compact：' + result.beforeTokens + ' → ' + result.afterTokens + ' tokens');
      }
      return { ok: true, value: result };
    }
    throw Object.assign(new Error('未知 commands endpoint: ' + JSON.stringify(endpoint)), { stage: 'rpc' });
  }));

  return () => {
    for (const dispose of disposers) {
      try { dispose(); } catch { /* 已卸载 */ }
    }
  };
}
