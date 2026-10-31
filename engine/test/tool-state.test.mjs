// 会话状态仓库 + 压缩后端 + /tool-state /commands RPC 的验收。
// 断言分三层：① store 的 todo 整表替换 / goal 生命周期 / 压缩记账；
// ② summarizeMessages 五段结构与 token 粗估；③ RPC 端点真的挂在 connection 上（同一注册链）。
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createContext } from '../host/cordis.js';
import {
  CHARS_PER_TOKEN,
  compactSession,
  createToolStateStore,
  estimateTokens,
  installToolState,
  normalizeTodos,
  summarizeMessages,
} from '../host/tool-state.js';

async function freshHome() {
  return mkdtemp(join(tmpdir(), 'dsh-tool-state-'));
}

test('token 粗估按 4 字符 1 token 算，空值与数组都不炸', () => {
  assert.equal(CHARS_PER_TOKEN, 4);
  assert.equal(estimateTokens(''), 0);
  assert.equal(estimateTokens(null), 0);
  assert.equal(estimateTokens('abcd'), 1);
  assert.equal(estimateTokens('abcdefgh'), 2);
  // 数组按元素累加；消息对象按 JSON 序列化算（比对整体 JSON 少算一层方括号）。
  assert.equal(estimateTokens(['abcd', 'abcd']), 2);
  assert.ok(estimateTokens([{ role: 'user', content: 'abcd' }]) >= estimateTokens('abcd'));
});

test('todo_write 是整表替换：传两遍第二遍覆盖第一遍，空数组清空', () => {
  const store = createToolStateStore();
  const first = store.writeTodos('s1', [
    { content: '读差距报告', status: 'completed' },
    { content: '写压缩', status: 'in_progress' },
  ]);
  assert.equal(first.length, 2);
  const second = store.writeTodos('s1', [{ content: '只剩这条', status: 'pending' }]);
  assert.deepEqual(second, [{ content: '只剩这条', status: 'pending' }]);
  assert.deepEqual(store.todos('s1'), [{ content: '只剩这条', status: 'pending' }]);
  assert.deepEqual(store.writeTodos('s1', []), []);
  assert.deepEqual(store.todos('s1'), []);
});

test('todo 入参形状错就抛，不静默丢项', () => {
  const store = createToolStateStore();
  assert.throws(() => store.writeTodos('s1', 'not-an-array'), /todos 必须是数组/);
  assert.throws(() => store.writeTodos('s1', [{ status: 'pending' }]), /content 不能为空/);
  assert.throws(() => store.writeTodos('s1', [{ content: 'x', status: 'done' }]), /status 只能是/);
  assert.deepEqual(normalizeTodos(undefined), []);
});

test('goal 生命周期：create → edit(带 revision 校验) → pause → resume → complete', () => {
  const store = createToolStateStore();
  assert.equal(store.goal('s1'), null);
  const created = store.createGoal('s1', '把 P0 做完', 6);
  assert.equal(created.phase, 'active');
  assert.equal(created.revision, 1);
  assert.equal(created.maxGoalRounds, 6);
  const edited = store.updateGoal('s1', 'edit', { objective: '把 P0 全部做完', revision: 1 });
  assert.equal(edited.objective, '把 P0 全部做完');
  assert.equal(edited.revision, 2);
  assert.throws(() => store.updateGoal('s1', 'edit', { objective: 'x', revision: 1 }), /revision 不匹配/);
  assert.equal(store.updateGoal('s1', 'pause', { revision: 2 }).phase, 'paused');
  assert.equal(store.updateGoal('s1', 'resume', {}).phase, 'active');
  assert.equal(store.updateGoal('s1', 'complete', {}).phase, 'complete');
  assert.throws(() => store.updateGoal('s1', 'nope', {}), /未知 goal action/);
  assert.throws(() => store.createGoal('s1', '   '), /非空 objective/);
  assert.throws(() => store.updateGoal('s2', 'complete', {}), /没有 goal/);
});

test('压缩记账：count 自增、前后 token 与摘要长度都留下', () => {
  const store = createToolStateStore();
  assert.equal(store.compaction('s1').count, 0);
  store.recordCompaction('s1', { beforeTokens: 900, afterTokens: 300, summaryChars: 120 });
  const stats = store.compaction('s1');
  assert.equal(stats.count, 1);
  assert.equal(stats.beforeTokens, 900);
  assert.equal(stats.afterTokens, 300);
  assert.equal(stats.summaryChars, 120);
  assert.ok(stats.lastAt > 0);
});

test('summarizeMessages 出五段结构，路径与未完成项被抽出来', () => {
  const summary = summarizeMessages([
    { role: 'user', content: '改 D:\\DSH\\dsh-mobile\\core\\protocol\\ToolExecutor.kt 的截断；下一步：落盘' },
    { role: 'assistant', content: '决定用 SharedPreferences 存摘要。' },
  ]);
  assert.match(summary, /上下文摘要（早期 2 条已压缩/);
  assert.match(summary, /角色：/);
  assert.match(summary, /任务：/);
  assert.match(summary, /已决定：/);
  assert.match(summary, /未完成：/);
  assert.match(summary, /关键路径：/);
  assert.match(summary, /ToolExecutor\.kt/);
  assert.ok(summary.length > 0);
});

test('compactSession 保留尾部、压掉早期，token 估算下降', () => {
  const store = createToolStateStore();
  const messages = Array.from({ length: 20 }, (_, i) => ({ role: i % 2 === 0 ? 'user' : 'assistant', content: '第' + i + ' 条：' + 'x'.repeat(200) }));
  const result = compactSession(store, 's1', messages, { keepRecent: 4 });
  assert.equal(result.compacted, true);
  assert.equal(result.droppedMessages, 16);
  assert.ok(result.afterTokens < result.beforeTokens);
  assert.ok(result.summaryChars > 0);
  assert.equal(result.stats.count, 1);
});

test('/tool-state 与 /commands 挂上 connection，能真读写', async () => {
  const ctx = createContext({ home: await freshHome() });
  const store = createToolStateStore();
  const dispose = installToolState(ctx, { store });
  try {
    const rpc = ctx.get('connection').rpc;
    assert.equal(rpc.has('/tool-state'), true);
    assert.equal(rpc.has('/commands'), true);

    const written = await rpc.call('/tool-state', 'todo-write', { sessionId: 'sess', todos: [{ content: '接线', status: 'pending' }] });
    assert.deepEqual(written.value.todos, [{ content: '接线', status: 'pending' }]);

    const goal = await rpc.call('/tool-state', 'goal-create', { sessionId: 'sess', objective: '做完 P0' });
    assert.equal(goal.value.goal.phase, 'active');
    const updated = await rpc.call('/tool-state', 'goal-update', { sessionId: 'sess', action: 'complete' });
    assert.equal(updated.value.goal.phase, 'complete');

    const snapshot = await rpc.call('/tool-state', 'snapshot', { sessionId: 'sess' });
    assert.equal(snapshot.value.todos.length, 1);
    assert.equal(snapshot.value.goal.phase, 'complete');
    assert.equal(snapshot.value.compaction.count, 0);

    const commands = await rpc.call('/commands', 'list', {});
    assert.deepEqual(commands.value.commands, ['compact']);

    const compacted = await rpc.call('/commands', 'compact', {
      sessionId: 'sess',
      messages: Array.from({ length: 12 }, (_, i) => ({ role: 'user', content: '第' + i + ' 条 ' + 'y'.repeat(120) })),
      options: { keepRecent: 3 },
    });
    assert.equal(compacted.value.compacted, true);
    assert.ok(compacted.value.beforeTokens > compacted.value.afterTokens);
    const stats = await rpc.call('/tool-state', 'compaction-stats', { sessionId: 'sess' });
    assert.equal(stats.value.compaction.count, 1);
    assert.equal(stats.value.compaction.summaryChars, compacted.value.summaryChars);

    await assert.rejects(() => rpc.call('/tool-state', 'nope', {}), /未知 tool-state endpoint/);
    await assert.rejects(() => rpc.call('/commands', 'nope', {}), /未知 commands endpoint/);
  } finally {
    dispose();
    assert.equal(ctx.get('connection').rpc.has('/tool-state'), false);
    await ctx.dispose();
  }
});
