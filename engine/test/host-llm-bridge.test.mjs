// C 的验收：provider 插件能通过 ctx.llm / ctx.credentials 走宿主网关。
// 用法: node --test test/host-llm-bridge.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createContext, listPlugins } from '../host/cordis.js';
import { loadPlugin } from '../host/loader.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixture = resolve(here, 'fixtures', 'llm-provider-plugin');

/** 模拟宿主的反向通道：llm.chat 回显请求，credentials 落在内存里。 */
function createNativeCallStub() {
  const store = new Map();
  const calls = [];
  const nativeCall = async (method, payload) => {
    calls.push({ method, payload });
    if (method === 'llm.chat') return { content: 'gateway:' + payload.messages[0].content, calls: calls.length };
    if (method === 'credentials.set') { store.set(payload.key, payload.value); return { ok: true }; }
    if (method === 'credentials.get') return { value: store.get(payload.key) ?? null };
    if (method === 'credentials.list') return { keys: [...store.keys()] };
    if (method === 'credentials.delete') return { ok: true };
    throw Object.assign(new Error('未实现 ' + method), { stage: 'host' });
  };
  return { nativeCall, calls, store };
}

test('样例 provider 插件：注册 provider 后出模型走宿主网关', async () => {
  const stub = createNativeCallStub();
  const ctx = createContext({ home: resolve(here, '..', '.mock', 'llm-bridge'), nativeCall: stub.nativeCall });
  try {
    const info = await loadPlugin(ctx, fixture);
    assert.equal(info.name, 'dsh-llm-provider-fixture');
    assert.deepEqual(listPlugins(ctx).map((p) => p.name), ['dsh-llm-provider-fixture']);
    assert.deepEqual(ctx.llm.names(), ['fixture']);
    assert.deepEqual(await ctx.llm.providers.get('fixture').models(), ['fixture-model']);

    // 宿主网关优先：即使 provider 自带 chat，也走 nativeCall。
    const result = await ctx.llm.chat({ messages: [{ role: 'user', content: 'hello' }] });
    assert.equal(result.content, 'gateway:hello');
    assert.equal(stub.calls[0].method, 'llm.chat');
    assert.equal(stub.calls[0].payload.messages[0].content, 'hello');

    // 插件自带的 rpc 入口也能出模型（宿主 plugins.load → rpc 调用的真实形态）。
    const chat = await ctx.get('connection').rpc.call('/llm-fixture', 'chat', {});
    assert.equal(chat.result.content, 'gateway:ping');
  } finally {
    await ctx.dispose();
  }
});

test('样例 provider 插件：credentials 走宿主存储，不落地到进程内存', async () => {
  const stub = createNativeCallStub();
  const ctx = createContext({ home: resolve(here, '..', '.mock', 'llm-bridge'), nativeCall: stub.nativeCall });
  try {
    await loadPlugin(ctx, fixture);
    const secret = await ctx.get('connection').rpc.call('/llm-fixture', 'secret', {});
    assert.equal(secret.value, 'demo');
    assert.equal(stub.store.get('fixture.token'), 'demo');
    assert.deepEqual(await ctx.credentials.list(), ['fixture.token']);
    assert.equal(ctx.credentials.degraded, false, '有网关时不该退到内存');
  } finally {
    await ctx.dispose();
  }
});

test('没有宿主网关时 provider 自带 chat 兜底，credentials 降级并记 warn', async () => {
  const ctx = createContext({ home: resolve(here, '..', '.mock', 'llm-bridge') });
  try {
    await loadPlugin(ctx, fixture);
    const result = await ctx.llm.chat({ messages: [{ role: 'user', content: 'offline' }] });
    assert.equal(result.content, 'fixture:offline');
    await loadPlugin(ctx, fixture); // 幂等：重复 load 不重复 apply
    await ctx.credentials.set('k', 'v');
    assert.equal(await ctx.credentials.get('k'), 'v');
    assert.equal(ctx.credentials.degraded, true);
  } finally {
    await ctx.dispose();
  }
});
