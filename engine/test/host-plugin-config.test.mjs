// applyPlugin 的 config 传递：schema 必须回到插件的运行时，默认值才会生效。
// 现场故障是 oss-prompt-optimizer 这类「export default class Service + export { Config }」的插件
// 拿到裸 config（templateId undefined）。这里直接钉住三条行为。
import test from 'node:test';
import assert from 'node:assert/strict';
import z from '@deepseek-ai/schemastery';
import { createContext } from '../host/cordis.js';

const Config = z.object({
  templateId: z.string().default('default'),
  retries: z.number().default(3),
});

test('命名空间挂 Config 的 default-class 插件：无配置也拿到 schema 默认值', async () => {
  const ctx = createContext();
  let received;
  class Service {
    constructor(context, config) { received = config; }
  }
  const mod = { default: Service, Config };
  const record = ctx.plugin(mod);
  await record.settled();
  assert.deepEqual(received, { templateId: 'default', retries: 3 });
  assert.equal(record.status, 'applied');
  await ctx.dispose();
});

test('显式配置原样透传，不被 schema 默认值或 {} 覆盖', async () => {
  const ctx = createContext();
  let received;
  class Service {
    constructor(context, config) { received = config; }
  }
  const record = ctx.plugin({ default: Service, Config }, { templateId: 'custom' });
  await record.settled();
  assert.equal(received.templateId, 'custom');
  assert.equal(received.retries, 3);
  assert.equal(record.config.templateId, 'custom');
  await ctx.dispose();
});

test('函数插件带 static Config：无配置也拿到默认值', async () => {
  const ctx = createContext();
  let received;
  function plugin(context, config) { received = config; }
  plugin.Config = Config;
  const record = ctx.plugin(plugin);
  await record.settled();
  assert.deepEqual(received, { templateId: 'default', retries: 3 });
  await ctx.dispose();
});

test('没有 Config 的插件仍拿到 {}（回退不破）', async () => {
  const ctx = createContext();
  let received;
  const record = ctx.plugin({ name: 'plain', apply(context, config) { received = config; } });
  await record.settled();
  assert.deepEqual(received, {});
  await ctx.dispose();
});

test('schema 校验失败时 settled() 抛错，插件不会被 apply', async () => {
  const ctx = createContext();
  let applied = false;
  class Service {
    constructor() { applied = true; }
  }
  const record = ctx.plugin({ default: Service, Config }, { templateId: 42 });
  await assert.rejects(record.settled());
  assert.equal(applied, false);
  assert.equal(record.status, 'failed');
  await ctx.dispose();
});
