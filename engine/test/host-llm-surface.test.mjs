// llm 服务的 adapter 面：对齐 @deepseek-ai/dsh-llm 的 LlmRuntime 成员表。
// 两件事必须钉死：成员一个都不能是 undefined（插件会 bind 它），
// 未实现路径抛的是带 member 名的说明性 Error，不是 TypeError。
import test from 'node:test';
import assert from 'node:assert/strict';
import { createLlmService } from '../host/llm.js';

const RUNTIME_MEMBERS = [
  'emitAdaptersUpdated', 'warnAdaptersListenerFailure', 'registerAdapter', 'prepareRoutes',
  'commitRoutes', 'listProviders', 'registerConfigurableProviders', 'listConfigurableProviders',
  'registerModelDiscovery', 'discoverModels', 'providerRetryPolicy', 'detachedModalities',
  'listModels', 'resolveModelInfo', 'resolveModelInfoFor', 'resolveCallConfig', 'resolveCallFor',
  'prepareCall', 'registration', 'forAdapter', 'stream', 'streamWithRegistration',
];

const LEGACY_MEMBERS = ['registerProvider', 'unregisterProvider', 'names', 'models', 'chat', 'resolve'];

test('llm 服务暴露 LlmRuntime 全部成员，没有一个是 undefined', () => {
  const llm = createLlmService({});
  for (const member of [...RUNTIME_MEMBERS, ...LEGACY_MEMBERS]) {
    assert.notEqual(llm[member], undefined, 'llm.' + member + ' 不存在');
  }
  assert.ok(llm.providers instanceof Map);
});

test('未实现的成员抛说明性 Error（带 member 名与 code），不是 TypeError', () => {
  const llm = createLlmService({});
  const stubs = RUNTIME_MEMBERS.filter((member) => [
    'registerAdapter', 'listProviders', 'registerConfigurableProviders', 'listConfigurableProviders',
    'registerModelDiscovery', 'discoverModels', 'providerRetryPolicy', 'detachedModalities',
    'listModels', 'resolveModelInfo', 'stream',
  ].includes(member) === false);
  assert.ok(stubs.length >= 10);
  for (const member of stubs) {
    assert.throws(() => llm[member](), (error) => {
      assert.equal(error instanceof TypeError, false, member + ' 抛了 TypeError');
      assert.equal(error.code, 'LLM_UNSUPPORTED');
      assert.equal(error.member, member);
      assert.match(error.message, new RegExp('llm\\.' + member + ' 未实现'));
      return true;
    });
  }
});

/** 走完五个真实成员：registerAdapter → registerModelDiscovery → registerConfigurableProviders → resolveModelInfo → stream。 */
function fakeAdapter() {
  const streamed = [];
  return {
    streamed,
    providerInfo: (provider) => ({ id: provider, name: 'Fake ' + provider, description: 'fixture' }),
    listModels: async (provider) => [
      { provider, id: 'm1', name: 'Model 1', inputModalities: ['text'] },
      { provider, id: 'm2', name: 'Model 2' },
    ],
    resolveModel: async (provider, model) => ({
      provider, id: model, name: 'Fake ' + model,
      inputModalities: ['text', 'image'],
      context: { contextWindow: 8192 },
      defaultMaxTokens: 1024,
    }),
    async *stream(request) {
      streamed.push(request);
      yield { type: 'delta', text: 'he' };
      yield { type: 'delta', text: 'llo' };
      yield { type: 'finish', reason: { kind: 'stop' } };
    },
  };
}

test('假 adapter 走通 registerAdapter → discovery → directory → resolveModelInfo → stream', async () => {
  const llm = createLlmService({});
  const adapter = fakeAdapter();
  const registration = llm.registerAdapter(['fake'], adapter);
  assert.equal(typeof registration, 'function');
  assert.equal(typeof registration.replace, 'function');
  assert.deepEqual(llm.listProviders(), [{ id: 'fake', name: 'Fake fake', description: 'fixture' }]);

  const offDiscovery = llm.registerModelDiscovery('fake-settings', async (request) => ([
    { id: 'm1', name: 'Model 1', contextWindow: 8192 },
    { id: 'm1' },
    { id: 'm3', maxTokens: 2048 },
  ]));
  const discovered = await llm.discoverModels('fake-settings', { apiKey: 'k' });
  assert.deepEqual(discovered, [
    { id: 'm1', name: 'Model 1', contextWindow: 8192 },
    { id: 'm3', maxTokens: 2048 },
  ]);

  const directory = llm.registerConfigurableProviders([{
    provider: 'fake', displayName: 'Fake settings', settingsNs: 'fake-settings', settingsPath: ['providers', 'fake'],
  }]);
  assert.deepEqual(llm.listConfigurableProviders(), [{
    provider: 'fake', displayName: 'Fake settings', settingsNs: 'fake-settings', settingsPath: ['providers', 'fake'],
  }]);

  const info = await llm.resolveModelInfo('fake', 'm1');
  assert.deepEqual(info, {
    provider: 'fake', id: 'm1', name: 'Fake m1',
    inputModalities: ['text', 'image'],
    context: { contextWindow: 8192 },
    defaultMaxTokens: 1024,
  });
  assert.deepEqual(await llm.listModels('fake'), [
    { provider: 'fake', id: 'm1', name: 'Model 1', inputModalities: ['text'] },
    { provider: 'fake', id: 'm2', name: 'Model 2' },
  ]);

  const chunks = [];
  for await (const chunk of llm.stream({ provider: 'fake', model: 'm1', messages: [{ role: 'user', content: 'hi' }] })) chunks.push(chunk);
  assert.deepEqual(chunks.map((chunk) => chunk.type), ['delta', 'delta', 'finish']);
  assert.equal(adapter.streamed.length, 1);

  directory();
  offDiscovery();
  registration();
  assert.deepEqual(llm.listProviders(), []);
  assert.deepEqual(llm.listConfigurableProviders(), []);
});

test('registerAdapter 的 replace 换路由，dispose 幂等', () => {
  const llm = createLlmService({});
  const adapter = fakeAdapter();
  const registration = llm.registerAdapter(['a'], adapter);
  registration.replace(['b']);
  assert.deepEqual(llm.listProviders().map((entry) => entry.id), ['b']);
  registration();
  registration();
  assert.deepEqual(llm.listProviders(), []);
});

test('registerAdapter 拒绝坏输入与重复 provider', () => {
  const llm = createLlmService({});
  assert.throws(() => llm.registerAdapter([], fakeAdapter()), /非空 provider 数组/);
  assert.throws(() => llm.registerAdapter(['ok'], {}), /缺少 stream 方法/);
  assert.throws(() => llm.registerAdapter(['bad'], {
    ...fakeAdapter(),
    providerInfo: () => ({ id: 'other', name: 'x' }),
  }), (error) => error.code === 'INVALID_PROVIDER_INFO');
  llm.registerAdapter(['dup'], fakeAdapter());
  assert.throws(() => llm.registerAdapter(['dup'], fakeAdapter()), (error) => error.code === 'DUPLICATE_ADAPTER');
});

test('registerConfigurableProviders / registerModelDiscovery 拒绝重复注册', () => {
  const llm = createLlmService({});
  const entry = { provider: 'fake', displayName: 'Fake', settingsNs: 'ns', settingsPath: ['a'] };
  llm.registerConfigurableProviders([entry]);
  assert.throws(() => llm.registerConfigurableProviders([entry]), (error) => error.code === 'DUPLICATE_DIRECTORY');
  assert.throws(() => llm.registerConfigurableProviders([{ ...entry, provider: 'p2', displayName: '' }]), /缺少非空 displayName/);
  assert.throws(() => llm.registerConfigurableProviders([{ ...entry, provider: 'p3', settingsPath: [] }]), /settingsPath/);
  llm.registerModelDiscovery('ns', async () => []);
  assert.throws(() => llm.registerModelDiscovery('ns', async () => []), (error) => error.code === 'DUPLICATE_DISCOVERY');
  assert.throws(() => llm.registerModelDiscovery('', async () => []), /非空 settingsNs/);
});

test('没有 adapter 时 resolveModelInfo / stream / listModels 抛说明性 Error', async () => {
  const llm = createLlmService({});
  await assert.rejects(llm.resolveModelInfo('nope', 'm1'), (error) => {
    assert.equal(error instanceof TypeError, false);
    assert.equal(error.code, 'NO_ADAPTER');
    assert.match(error.message, /没有注册 adapter/);
    return true;
  });
  await assert.rejects(llm.listModels('nope'), (error) => error.code === 'NO_ADAPTER');
  const iterator = llm.stream({ provider: 'nope', model: 'm1' });
  await assert.rejects(iterator.next(), (error) => {
    assert.equal(error instanceof TypeError, false);
    assert.equal(error.code, 'NO_ADAPTER');
    assert.match(error.message, /出不了流式结果/);
    return true;
  });
  await assert.rejects(llm.discoverModels('missing'), (error) => error.code === 'NO_DISCOVERY');
});

test('adapter 抛错时 stream 收束成 terminal finish chunk，不把错误丢给调用方', async () => {
  const llm = createLlmService({});
  llm.registerAdapter(['boom'], {
    stream: async function* () {
      yield { type: 'delta', text: 'x' };
      throw Object.assign(new Error('upstream 502'), { code: 'HTTP_502' });
    },
  });
  const chunks = [];
  for await (const chunk of llm.stream({ provider: 'boom', model: 'm' })) chunks.push(chunk);
  assert.equal(chunks.length, 2);
  assert.deepEqual(chunks[1], { type: 'finish', reason: { kind: 'error', failure: { code: 'HTTP_502', message: 'upstream 502' } } });
});

test('老 registerProvider 仍能用 listModels 列出模型', async () => {
  const llm = createLlmService({});
  llm.registerProvider('legacy', { models: () => ['demo', { id: 'demo2', name: 'Demo 2' }] });
  assert.deepEqual(await llm.listModels('legacy'), [
    { provider: 'legacy', id: 'demo', name: 'demo' },
    { provider: 'legacy', id: 'demo2', name: 'Demo 2' },
  ]);
  assert.deepEqual(llm.listProviders(), [{ id: 'legacy', name: 'legacy' }]);
  assert.deepEqual(llm.providerRetryPolicy('legacy'), { mode: 'normal', maxRetries: 0, retryableCodes: [] });
});
