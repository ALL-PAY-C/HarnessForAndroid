// E-engine-llm-rpc：provider 优先于 Kotlin 网关 + /llm RPC 端点。
// 用法: node test/llm-rpc.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createContext } from '../host/cordis.js';
import {
  createLlmService,
  handleLlmRpc,
  installLlmRpc,
  normalizeChatResult,
  pickProvider,
} from '../host/llm.js';

const here = dirname(fileURLToPath(import.meta.url));
const userMessage = [{ role: 'user', content: 'hi' }];

/** nativeCall 桩：任何调用都算错——用来证明「provider 优先」时网关一条都没发。 */
function poisonNativeCall() {
  const calls = [];
  const nativeCall = async (method, payload) => {
    calls.push({ method, payload });
    throw Object.assign(new Error('不该走到网关: ' + method), { stage: 'transport' });
  };
  return { nativeCall, calls };
}

test('pickProvider：命中 models() 里的模型名，且 provider 必须有 chat', async () => {
  const providers = new Map([
    ['slow', { models: async () => ({ list: [] }) }], // 没有 chat：跳过
    ['demo', { models: async () => ['demo-small', { model: 'demo-big' }], chat: async () => ({ content: 'x' }) }],
  ]);
  const hit = await pickProvider(providers, 'demo-big');
  assert.equal(hit?.name, 'demo');
  assert.equal(typeof hit.provider.chat, 'function');
  // 普通对象形态也收
  const objHit = await pickProvider({ demo: { models: () => ['demo-small'], chat: async () => ({}) } }, 'demo-small');
  assert.equal(objHit?.name, 'demo');
});

test('pickProvider：不命中返回 null（空 model / 未知 model / 只有 models 没 chat）', async () => {
  const providers = new Map([
    ['demo', { models: async () => ['demo-small'], chat: async () => ({ content: 'x' }) }],
    ['listonly', { models: async () => ['ghost'] }],
  ]);
  assert.equal(await pickProvider(providers, undefined), null);
  assert.equal(await pickProvider(providers, '   '), null);
  assert.equal(await pickProvider(providers, 'unknown'), null);
  assert.equal(await pickProvider(providers, 'ghost'), null);
  assert.equal(await pickProvider(null, 'demo-small'), null);
});

test('pickProvider：某个 provider 的 models() 抛错只跳过它', async () => {
  const providers = new Map([
    ['broken', { models: async () => { throw new Error('boom'); }, chat: async () => ({ content: 'no' }) }],
    ['demo', { models: async () => ['demo-small'], chat: async () => ({ content: 'ok' }) }],
  ]);
  const hit = await pickProvider(providers, 'demo-small');
  assert.equal(hit?.name, 'demo');
});

test('llm.chat：model 命中 provider 时不再走 nativeCall（桩一调用就炸）', async () => {
  const stub = poisonNativeCall();
  const llm = createLlmService({ host: { nativeCall: stub.nativeCall }, logger: null });
  llm.registerProvider('demo', {
    models: async () => ['demo-small'],
    chat: async (request) => ({ content: 'provider:' + request.messages[0].content, model: 'demo-small' }),
  });
  const result = await llm.chat({ messages: userMessage, model: 'demo-small' });
  assert.equal(result.content, 'provider:hi');
  assert.equal(stub.calls.length, 0, '命中 provider 时不该碰网关');
});

test('llm.chat：model 不命中时维持原行为（有 nativeCall 走网关）', async () => {
  const stub = poisonNativeCall();
  const llm = createLlmService({ host: { nativeCall: async (method, payload) => ({ content: 'gateway:' + payload.messages[0].content }) }, logger: null });
  llm.registerProvider('demo', { models: async () => ['demo-small'], chat: async () => ({ content: 'provider' }) });
  assert.equal((await llm.chat({ messages: userMessage })).content, 'gateway:hi');
  assert.equal((await llm.chat({ messages: userMessage, model: 'other' })).content, 'gateway:hi');
  assert.equal(stub.calls.length, 0);
});

test('llm.chat：provider 抛错时原错误带 stage=provider 抛出去，不改走网关', async () => {
  let gatewayHits = 0;
  const llm = createLlmService({
    host: { nativeCall: async () => { gatewayHits += 1; return { content: 'gateway' }; } },
    logger: null,
  });
  llm.registerProvider('demo', {
    models: async () => ['demo-small'],
    chat: async () => { throw new Error('上游 401'); },
  });
  await assert.rejects(
    () => llm.chat({ messages: userMessage, model: 'demo-small' }),
    (error) => {
      assert.equal(error.message, '上游 401');
      assert.equal(error.stage, 'provider');
      assert.equal(error.provider, 'demo');
      return true;
    },
  );
  assert.equal(gatewayHits, 0, 'provider 失败不该被吞成网关调用');
});

test('normalizeChatResult：content / text / choices[0].message.content 三种都归一化', () => {
  assert.deepEqual(normalizeChatResult({ content: 'A' }), { content: 'A' });
  assert.equal(normalizeChatResult({ text: 'B' }).content, 'B');
  assert.equal(normalizeChatResult({ choices: [{ message: { content: 'C' } }] }).content, 'C');
  assert.equal(normalizeChatResult('D').content, 'D');
  // usage 有就带上，没有就不编
  assert.deepEqual(normalizeChatResult({ content: 'A', usage: { total: 3 } }), { content: 'A', usage: { total: 3 } });
  assert.equal('usage' in normalizeChatResult({ content: 'A' }), false);
  assert.throws(() => normalizeChatResult({}), /没有 content/);

  // 只调工具的一轮正文为空是合法的：toolCalls 非空就不算空回复。
  const calls = [{ id: 'c1', type: 'function', function: { name: 'shell', arguments: '{}' } }];
  assert.deepEqual(normalizeChatResult({ content: '', toolCalls: calls }), { content: '', toolCalls: calls });
  assert.deepEqual(
    normalizeChatResult({ choices: [{ message: { content: '', tool_calls: calls } }] }),
    { content: '', toolCalls: calls },
  );
  assert.equal('toolCalls' in normalizeChatResult({ content: 'A' }), false);
});

test('/llm chat：三种 provider 返回结构都能取到 content', async () => {
  const shapes = [
    [{ content: 'shape-content' }, 'shape-content'],
    [{ text: 'shape-text' }, 'shape-text'],
    [{ choices: [{ message: { content: 'shape-choices' } }] }, 'shape-choices'],
  ];
  for (const [raw, expected] of shapes) {
    const llm = createLlmService({ host: {}, logger: null });
    llm.registerProvider('demo', { models: () => ['demo-small'], chat: async () => raw });
    const reply = await handleLlmRpc(llm, 'chat', { messages: userMessage, model: 'demo-small' });
    assert.equal(reply.ok, true);
    assert.equal(reply.value.content, expected);
  }
});

test('/llm chat：tools 透传到 provider，toolCalls 原样带回（engine:// 的工具通道）', async () => {
  const seen = [];
  const llm = createLlmService({ host: {}, logger: null });
  llm.registerProvider('demo', {
    models: () => ['demo-small'],
    chat: async (request) => {
      seen.push(request);
      if (!request.tools) return { content: '没有工具表就不调工具' };
      return { content: '', toolCalls: [{ id: 'c1', type: 'function', function: { name: 'shell', arguments: '{"command":"ls"}' } }] };
    },
  });
  const tools = [{ type: 'function', function: { name: 'shell', parameters: { type: 'object' } } }];
  const reply = await handleLlmRpc(llm, 'chat', { messages: userMessage, model: 'demo-small', tools });
  assert.equal(seen[0].tools.length, 1, 'tools 没透传到 provider');
  assert.equal(reply.value.content, '');
  assert.equal(reply.value.toolCalls.length, 1);
  assert.equal(reply.value.toolCalls[0].function.name, 'shell');
});

test('/llm chat：messages 为空 / endpoint 未知都报错', async () => {
  const llm = createLlmService({ host: {}, logger: null });
  await assert.rejects(() => handleLlmRpc(llm, 'chat', { messages: [] }), /非空 messages/);
  await assert.rejects(() => handleLlmRpc(llm, 'nope', {}), /未实现的 \/llm endpoint/);
});

test('/llm models：聚合两个 provider 的模型（去重、保留顺序）', async () => {
  const llm = createLlmService({ host: {}, logger: null });
  llm.registerProvider('a', { models: async () => ['a-1', 'shared'], chat: async () => ({ content: 'a' }) });
  llm.registerProvider('b', { models: () => [{ model: 'b-1' }, 'shared'], chat: async () => ({ content: 'b' }) });
  const reply = await handleLlmRpc(llm, 'models', {});
  assert.equal(reply.ok, true);
  assert.deepEqual(reply.value.providers, [
    { name: 'a', models: ['a-1', 'shared'] },
    { name: 'b', models: ['b-1', 'shared'] },
  ]);
  assert.deepEqual(reply.value.models, ['a-1', 'shared', 'b-1']);
});

test('installLlmRpc：/llm 挂上 connection.rpc，走 rpc.call 也能出模型', async () => {
  const stub = poisonNativeCall();
  const ctx = createContext({ home: resolve(here, '..', '.mock', 'llm-rpc'), nativeCall: stub.nativeCall });
  try {
    const { dispose } = installLlmRpc(ctx, { logger: null });
    assert.equal(ctx.get('connection').rpc.has('/llm'), true);
    ctx.get('llm').registerProvider('demo', {
      models: async () => ['demo-small'],
      chat: async (request) => ({ choices: [{ message: { content: 'rpc:' + request.messages[0].content } }] }),
    });
    const reply = await ctx.get('connection').rpc.call('/llm', 'chat', { messages: userMessage, model: 'demo-small' });
    assert.equal(reply.value.content, 'rpc:hi');
    const models = await ctx.get('connection').rpc.call('/llm', 'models', {});
    assert.deepEqual(models.value.models, ['demo-small']);
    dispose();
    assert.equal(ctx.get('connection').rpc.has('/llm'), false);
    assert.equal(stub.calls.length, 0);
  } finally {
    await ctx.dispose();
  }
});
