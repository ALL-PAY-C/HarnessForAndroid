// 任务 E 验收：dshLoader 门面（.dsh/.llm/.settings）+ credentials.resolve。
// 断言分三层：① 符号存在且是可调用/可构造的；② installSection 真跑通 hooks 与 disposer；
// ③ 转不出的桌面内部面必须抛 DSH_UNSUPPORTED_MEMBER，不返回 undefined。
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createContext } from '../host/cordis.js';

const freshHome = () => mkdtemp(join(tmpdir(), 'dsh-dshloader-'));

const DSH_SYMBOLS = ['defineTool', 'ToolArgsError', 'deadline', 'credentialRef', 'delegationDepthOf', 'BasicCompactionEngine', 'BlockAssembler', 'createUserMessage'];
const LLM_SYMBOLS = ['createUserMessage', 'deepFreeze', 'BlockAssembler'];

test('dshLoader.dsh 的 8 个扁平符号 + 命名空间形状都在', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    const { dsh } = ctx.dshLoader;
    for (const name of DSH_SYMBOLS) {
      assert.notEqual(dsh[name], undefined, 'dsh.' + name + ' 是 undefined');
      assert.ok(['function'].includes(typeof dsh[name]), 'dsh.' + name + ' 应该是函数/类');
    }
    // 命名空间形状：插件真实调用的是 dsh().tools.defineTool 这一层
    assert.equal(dsh.tools.defineTool, dsh.defineTool);
    assert.equal(dsh.tools.ToolArgsError, dsh.ToolArgsError);
    assert.equal(dsh.timeout.deadline, dsh.deadline);
    assert.equal(dsh.credentials.credentialRef, dsh.credentialRef);
    assert.equal(dsh.subagent.delegationDepthOf, dsh.delegationDepthOf);
    assert.equal(dsh.compaction.BasicCompactionEngine, dsh.BasicCompactionEngine);
    assert.equal(dsh.llm.createUserMessage, dsh.createUserMessage);
    assert.equal(typeof dsh.llm.BlockAssembler, 'function');
  } finally {
    await ctx.dispose();
  }
});

test('dshLoader.llm 的 3 个符号在，且 deepFreeze 真冻结', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    for (const name of LLM_SYMBOLS) {
      assert.equal(typeof ctx.dshLoader.llm[name], 'function', 'dshLoader.llm.' + name);
    }
    const target = { a: { b: 1 } };
    const frozen = ctx.dshLoader.llm.deepFreeze(target);
    assert.equal(Object.isFrozen(frozen), true);
    assert.equal(Object.isFrozen(frozen.a), true);
  } finally {
    await ctx.dispose();
  }
});

test('BasicCompactionEngine 可被继承且构造不抛，基类方法抛 DSH_UNSUPPORTED_MEMBER', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    const Base = ctx.dshLoader.dsh.compaction.BasicCompactionEngine;
    const base = new Base({ log: console }, { enabled: true });
    assert.equal(base.config.enabled, true);
    assert.throws(() => base.summarize(), (error) => error.code === 'DSH_UNSUPPORTED_MEMBER' && /summarize/.test(error.member));
    // 插件真实用法：extends + super(ctx, config)，随后覆写
    class Impl extends Base {
      constructor(innerCtx, config) { super(innerCtx, config); this.ready = true; }
      summarize() { return 'summarized'; }
    }
    const impl = new Impl({ log: console }, { engine: { enabled: true } });
    assert.equal(impl.ready, true);
    assert.equal(impl.summarize(), 'summarized');
  } finally {
    await ctx.dispose();
  }
});

test('delegationDepthOf 读 depth，退到 parent 链累加', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    const depthOf = ctx.dshLoader.dsh.subagent.delegationDepthOf;
    assert.equal(depthOf({ depth: 0 }), 0);
    assert.equal(depthOf({ delegationDepth: 2 }), 2);
    const root = { name: 'root' };
    const child = { name: 'child', parent: root };
    const grand = { name: 'grand', parent: child };
    assert.equal(depthOf(grand), 2);
    assert.equal(depthOf(null), 0);
  } finally {
    await ctx.dispose();
  }
});

test('settings.installSection 跑通 hooks、挂 scoped fiber、disposer 幂等回退', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    const installSection = ctx.dshLoader.settings.installSection;
    assert.equal(typeof installSection, 'function');

    const calls = { setSource: 0, onChange: 0, validate: [] };
    const disposers = [];
    let source = null;
    const fakeCtx = {
      effect: (fn) => { disposers.push(fn()); return () => {}; },
      get: () => undefined, // settings 服务不存在 → 只用组合入口
    };
    const entry = { model: 'entry-model' };
    const dispose = installSection(fakeCtx, 'plugin.ns', class Config {}, entry, {
      setSource: (fn) => { calls.setSource += 1; source = fn; },
      onChange: () => { calls.onChange += 1; },
      validate: (value) => { calls.validate.push(value); },
    });

    assert.equal(typeof dispose, 'function', 'installSection 必须返回 disposer，不能是 undefined');
    assert.equal(calls.setSource, 1);
    assert.ok(calls.onChange >= 1, 'apply 后要立即 onChange 一次');
    assert.deepEqual(calls.validate[0], entry, 'validate 必须被回调且参数是组合入口配置');
    assert.equal(typeof source, 'function', 'setSource 拿到的是同步 thunk');
    assert.deepEqual(source(), entry, 'settings 缺失时 source() 直接返回组合入口');
    assert.equal(disposers.length, 1, '必须挂到 scoped fiber（ctx.effect）');

    assert.equal(dispose(), true);
    assert.equal(dispose(), false, 'disposer 必须幂等');
    assert.deepEqual(source(), entry, 'dispose 后回退组合入口');
  } finally {
    await ctx.dispose();
  }
});

test('settings.installSection 在有 settings 服务时读取持久化值并合并', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    let source = null;
    let changes = 0;
    const fakeCtx = {
      effect: (fn) => { fn(); return () => {}; },
      get: (name) => (name === 'settings' ? { get: async () => JSON.stringify({ temperature: 0.5 }) } : undefined),
    };
    const entry = { model: 'entry-model', temperature: 1 };
    const dispose = ctx.dshLoader.settings.installSection(fakeCtx, 'plugin.ns2', class Config {}, entry, {
      setSource: (fn) => { source = fn; },
      onChange: () => { changes += 1; },
      validate: () => {},
    });
    assert.deepEqual(source(), entry, '同步阶段先给组合入口');
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.deepEqual(source(), { model: 'entry-model', temperature: 0.5 }, '持久化值要覆盖同名键');
    assert.ok(changes >= 2, '持久化值到位后要再 onChange 一次');
    dispose();
  } finally {
    await ctx.dispose();
  }
});

test('credentials.resolve 取到 / 取不到两条路径', async () => {
  const ctx = createContext({ home: await freshHome() });
  const envKey = 'DSH_TEST_ENV_ONLY_KEY';
  const missingKey = 'DSH_TEST_DEFINITELY_MISSING_KEY';
  delete process.env[envKey];
  delete process.env[missingKey];
  try {
    assert.equal(typeof ctx.credentials.resolve, 'function');
    // 取不到 → undefined，不抛
    assert.equal(await ctx.credentials.resolve(missingKey), undefined);
    assert.equal(await ctx.credentials.resolve(''), undefined);
    assert.equal(await ctx.credentials.resolve(null), undefined);
    // 取到（存储层）
    await ctx.credentials.set('DSH_TEST_STORE_KEY', 'store-secret');
    assert.deepEqual(await ctx.credentials.resolve('DSH_TEST_STORE_KEY'), { value: 'store-secret', source: 'store' });
    // seam 规则：空串等于未配置
    await ctx.credentials.set('DSH_TEST_EMPTY_KEY', '');
    assert.equal(await ctx.credentials.resolve('DSH_TEST_EMPTY_KEY'), undefined);
    // 取到（环境变量兜底）
    process.env[envKey] = 'env-secret';
    assert.deepEqual(await ctx.credentials.resolve(envKey), { value: 'env-secret', source: 'env' });
    // describe 对齐 CredentialInfo
    assert.deepEqual(await ctx.credentials.describe(envKey), { configured: true, source: 'env', writable: true });
    assert.deepEqual(await ctx.credentials.describe(missingKey), { configured: false, writable: true });
    // dsh().credentials.resolve 走同一个服务
    assert.deepEqual(await ctx.dshLoader.dsh.credentials.resolve('DSH_TEST_STORE_KEY'), { value: 'store-secret', source: 'store' });
  } finally {
    delete process.env[envKey];
    await ctx.dispose();
  }
});

test('credentials.resolve 不改 degraded getter（对象是原地补方法）', async () => {
  const ctx = createContext({ home: await freshHome() });
  try {
    assert.equal(ctx.credentials.degraded, false);
    await ctx.credentials.resolve('DSH_TEST_DEGRADE_KEY');
    assert.equal(ctx.credentials.degraded, true, '无 Kotlin 网关时读一次就要降级为 true（getter 没被复制成快照）');
  } finally {
    await ctx.dispose();
  }
});
