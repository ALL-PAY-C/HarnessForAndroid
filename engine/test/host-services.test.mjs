// 任务 B 验收：createContext() 补齐 fs/settings/systemPrompt/attachments/sessions/
// sessionPersistence/workspaceRegistry/jobs/timer/dshLoader 十个服务。
// 断言分两层：① 服务名都在 ctx.registry.services 且插件 inject 能解析到 apply；
// ② 能真做的服务跑真用例，做不到的（会话类）断言抛错且 message 非空。
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createContext } from '../host/cordis.js';

const NAMES = ['fs', 'settings', 'systemPrompt', 'attachments', 'sessions', 'sessionPersistence', 'workspaceRegistry', 'jobs', 'timer', 'dshLoader'];

async function freshHome() {
  return mkdtemp(join(tmpdir(), 'dsh-host-services-'));
}

test('registry 里能看到十个新服务，dshLoader 与 loader 是同一对象', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    for (const name of NAMES) {
      assert.ok(ctx.registry.services.has(name), 'ctx.registry.services 缺 ' + name);
      assert.notEqual(ctx.get(name), undefined, 'ctx.get(' + name + ') 为空');
    }
    assert.equal(ctx.dshLoader, ctx.loader, 'dshLoader 必须是 loader 的别名（同一对象）');
    assert.equal(typeof ctx.fs.readFile, 'function');
    assert.equal(typeof ctx.settings.set, 'function');
    assert.equal(typeof ctx.timer.setTimeout, 'function');
  } finally {
    await ctx.dispose();
  }
});

test('插件 inject 点名十个服务后 apply 能进去', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    let seen = null;
    const record = ctx.plugin({
      name: 'services-consumer',
      inject: NAMES,
      apply(inner) { seen = NAMES.map((name) => typeof inner[name]); },
    });
    await record.settled();
    assert.deepEqual(seen, NAMES.map(() => 'object'), 'apply 没拿到全部服务');
  } finally {
    await ctx.dispose();
  }
});

test('sessionProjections 服务可注册投影，restore/hydrate 明确返回 null', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    const service = ctx.get('sessionProjections');
    assert.ok(service, 'sessionProjections 没 provide');
    assert.equal(typeof service.register, 'function');
    const projection = { name: 'demo', project() {} };
    const dispose = service.register(projection);
    assert.deepEqual(service.list(), ['demo']);
    assert.equal(service.get('demo'), projection);
    assert.equal(service.restore(), null);
    assert.equal(await service.hydrate(), null);
    dispose();
    assert.deepEqual(service.list(), []);
    assert.throws(() => service.register(null), /需要一个投影对象/);
  } finally {
    await ctx.dispose();
  }
});

test('sessionQuery 走原生反向通道，没接通时抛 stage=transport', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    const service = ctx.get('sessionQuery');
    assert.ok(service, 'sessionQuery 没 provide');
    await assert.rejects(() => service.listSessions(), (error) => error.stage === 'transport' && /nativeCall/.test(error.message));
    ctx.setNativeCall(async (method, payload) => {
      if (method === 'dsh.sessions.list') return [{ id: 's1', title: '会话一' }];
      if (method === 'dsh.sessions.observe') return { header: { id: payload.id }, events: [{ seq: 1 }] };
      throw new Error('未实现 ' + method);
    });
    assert.deepEqual(await service.listSessions(), [{ id: 's1', title: '会话一' }]);
    const detail = await service.observeSession('s1', { projectionMode: 'none', signal: new AbortController().signal });
    assert.deepEqual(detail.header, { id: 's1' });
    assert.deepEqual(detail.events, [{ seq: 1 }]);
    assert.equal(detail.inheritedEventCount, 0);
    await assert.rejects(() => service.observeSession(''), /需要非空会话 id/);
  } finally {
    await ctx.dispose();
  }
});

test('sessionProjectionCache 读返回明确空值，写抛 notImplemented（不伪造缓存成功）', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    const cache = ctx.get('sessionProjectionCache');
    assert.ok(cache, 'sessionProjectionCache 没 provide');
    assert.equal(cache.cachedSnapshot({ id: 's1' }, ['contextActivity']), null);
    assert.equal(cache.stateOf({ id: 's1' }, 'contextTimeline'), undefined);
    assert.throws(() => cache.coldSnapshot({ id: 's1' }, 0, []), (error) => error.notImplemented === true);
    assert.throws(() => cache.write({}), (error) => error.notImplemented === true);
  } finally {
    await ctx.dispose();
  }
});

test('userQuestions 转给原生提问面板，没接通时抛 stage=transport', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    const service = ctx.get('userQuestions');
    assert.ok(service, 'userQuestions 没 provide');
    await assert.rejects(() => service.ask({ questions: [{ id: 'q1', question: '继续吗' }] }), (error) => error.stage === 'transport');
    await assert.rejects(() => service.ask({}), /至少需要一个 question/);
    ctx.setNativeCall(async (method, payload) => {
      assert.equal(method, 'dsh.userQuestions.ask');
      assert.deepEqual(payload.questions, [{ id: 'q1', question: '继续吗' }]);
      return { answers: [{ id: 'q1', selected: ['是'] }] };
    });
    assert.deepEqual(await service.ask({ questions: [{ id: 'q1', question: '继续吗' }] }), { answers: [{ id: 'q1', selected: ['是'] }] });
  } finally {
    await ctx.dispose();
  }
});

test('tools.guard 收下拦截钩子并给 disposer，非函数抛 apply 错', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    const dispose = ctx.tools.guard((execution) => {
      if (execution?.name === 'send_email') return '不允许模型直接发送';
      return undefined;
    });
    assert.equal(ctx.tools.guards.length, 1);
    assert.equal(ctx.tools.guards[0]({ name: 'send_email' }), '不允许模型直接发送');
    assert.equal(ctx.tools.guards[0]({ name: 'read' }), undefined);
    dispose();
    assert.equal(ctx.tools.guards.length, 0);
    assert.throws(() => ctx.tools.guard(null), (error) => error.stage === 'apply');
  } finally {
    await ctx.dispose();
  }
});

test('fs 写入再读出，越出 home 的路径被拒', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    await ctx.fs.writeFile('notes/a.txt', 'hello 引擎');
    assert.equal(await ctx.fs.readFile('notes/a.txt'), 'hello 引擎');
    assert.equal(await ctx.fs.exists('notes/a.txt'), true);
    assert.deepEqual(await ctx.fs.readdir('notes'), ['a.txt']);
    await ctx.fs.writeFile('data.json', { ok: true });
    assert.deepEqual(await ctx.fs.readJson('data.json'), { ok: true });
    await assert.rejects(() => ctx.fs.readFile('../../etc/passwd'), /拒绝访问 home 之外的路径/);
    await assert.rejects(() => ctx.fs.writeFile(join(home, '..', 'escape.txt'), 'x'), /拒绝访问 home 之外的路径/);
    await assert.rejects(() => ctx.fs.resolve('../../x'), (error) => error.code === 'FS_OUTSIDE_HOME');
  } finally {
    await ctx.dispose();
  }
});

test('fs 的 dsh FileSystem 接口（resolve/listDir/stat/readText）可用', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    await ctx.fs.writeFile('skills/demo/SKILL.md', '---\nname: demo\n---\nbody');
    const target = await ctx.fs.resolve(join(home, 'skills'));
    assert.equal(target.path, join(home, 'skills'));
    assert.match(target.displayPath, /skills$/);
    assert.equal(String(target), join(home, 'skills'));
    const entries = await ctx.fs.listDir(target);
    assert.deepEqual(entries.map((entry) => [entry.name, entry.type]), [['demo', 'directory']]);
    const fileTarget = entries[0].target;
    assert.equal((await ctx.fs.stat(fileTarget)).type, 'directory');
    const skillTarget = await ctx.fs.resolve(join(home, 'skills', 'demo', 'SKILL.md'));
    const info = await ctx.fs.stat(skillTarget);
    assert.equal(info.type, 'file');
    assert.equal(info.isFile(), true);
    assert.match(await ctx.fs.readText(skillTarget), /name: demo/);
    await ctx.fs.writeFile('bin.dat', Buffer.from([1, 0, 2]));
    await assert.rejects(() => ctx.fs.readText('bin.dat'), (error) => error.code === 'FS_NOT_TEXT');
  } finally {
    await ctx.dispose();
  }
});

test('settings 存取 + 原子落盘 + 点号嵌套 + delete', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    assert.deepEqual(await ctx.settings.all(), {});
    await ctx.settings.set('theme', 'dark');
    await ctx.settings.set('nested.flag', 42);
    assert.equal(await ctx.settings.get('theme'), 'dark');
    assert.equal(await ctx.settings.get('nested.flag'), 42);
    assert.deepEqual(await ctx.settings.all(), { theme: 'dark', nested: { flag: 42 } });
    const onDisk = JSON.parse(await readFile(join(home, 'settings.json'), 'utf8'));
    assert.deepEqual(onDisk, { theme: 'dark', nested: { flag: 42 } });
    const leftovers = (await readdir(home)).filter((name) => name.includes('.tmp-'));
    assert.deepEqual(leftovers, [], 'tmp 文件没被 rename 掉');
    assert.equal(await ctx.settings.delete('theme'), true);
    assert.equal(await ctx.settings.get('theme'), undefined);
    assert.equal(await ctx.settings.delete('nope'), false);
  } finally {
    await ctx.dispose();
  }
});

test('systemPrompt 收字符串/函数/对象三种贡献者，未知方法抛说明性错误', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    ctx.systemPrompt.add('第一段');
    ctx.systemPrompt.register(() => '第二段');
    ctx.systemPrompt.contribute({ name: 'obj', content: '第三段' });
    assert.equal(await ctx.systemPrompt.collected(), '第一段\n\n第二段\n\n第三段');
    assert.deepEqual(ctx.systemPrompt.names(), ['contributor-1', 'contributor-2', 'obj']);
    assert.throws(() => ctx.systemPrompt.add(123), /需要字符串、函数/);
    assert.throws(() => ctx.systemPrompt.render(), /不认识的调用 systemPrompt\.render\(\)/);
  } finally {
    await ctx.dispose();
  }
});

test('attachments 列目录/取元数据，缺文件抛错', async () => {
  const home = await freshHome();
  await mkdir(join(home, 'attachments'), { recursive: true });
  await writeFile(join(home, 'attachments', 'a.txt'), 'attach', 'utf8');
  const ctx = createContext({ home });
  try {
    const items = await ctx.attachments.list();
    assert.equal(items.length, 1);
    assert.equal(items[0].id, 'a.txt');
    assert.equal(items[0].size, 6);
    assert.equal((await ctx.attachments.get('a.txt')).name, 'a.txt');
    assert.equal(ctx.attachments.path('a.txt'), join(home, 'attachments', 'a.txt'));
    assert.equal(await ctx.attachments.read('a.txt'), 'attach');
    await assert.rejects(() => ctx.attachments.get('missing.txt'), /不存在/);
    assert.throws(() => ctx.attachments.path('../escape'), /attachments 目录内/);
  } finally {
    await ctx.dispose();
  }
});

test('sessions/sessionPersistence 未实现时抛错且 message 非空', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    await assert.rejects(() => ctx.sessions.list(), (error) => {
      assert.ok(error.message.length > 0);
      assert.match(error.message, /nativeCall/);
      return true;
    });
    await assert.rejects(() => ctx.sessionPersistence.load('s1'), /dsh\.sessions\.load/);
    assert.throws(() => ctx.sessions.save(), /移动端引擎没有实现/);
  } finally {
    await ctx.dispose();
  }
});

test('sessions 在 nativeCall 接通时转发到 dsh.sessions.*', async () => {
  const home = await freshHome();
  const calls = [];
  const ctx = createContext({
    home,
    nativeCall: async (method, payload) => {
      calls.push([method, payload]);
      if (method === 'dsh.sessions.list') return { items: [{ id: 's1' }] };
      if (method === 'dsh.sessions.messages') return [{ role: 'user', content: 'hi' }];
      throw new Error('未实现 ' + method);
    },
  });
  try {
    assert.deepEqual(await ctx.sessions.list(), [{ id: 's1' }]);
    assert.deepEqual(await ctx.sessions.messages('s1'), [{ role: 'user', content: 'hi' }]);
    assert.deepEqual(calls.map((entry) => entry[0]), ['dsh.sessions.list', 'dsh.sessions.messages']);
    await assert.rejects(() => ctx.sessions.get('s1'), /原生端 dsh\.sessions\.get 失败/);
  } finally {
    await ctx.dispose();
  }
});

test('workspaceRegistry 登记/查询，并落到 settings.json', async () => {
  const home = await freshHome();
  const project = join(home, 'proj');
  await mkdir(project, { recursive: true });
  const ctx = createContext({ home });
  try {
    ctx.workspaceRegistry.register(project);
    ctx.workspaceRegistry.register('relative-dir');
    assert.deepEqual(ctx.workspaceRegistry.list(), [project, join(home, 'relative-dir')]);
    assert.equal(ctx.workspaceRegistry.has(project), true);
    // persist 是 fire-and-forget：轮询到落盘为止
    for (let i = 0; i < 100 && (await ctx.settings.get('workspaces')) === undefined; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    assert.deepEqual(await ctx.settings.get('workspaces'), [project, join(home, 'relative-dir')]);
  } finally {
    await ctx.dispose();
  }
  const next = createContext({ home });
  try {
    assert.deepEqual(next.workspaceRegistry.list(), [project, join(home, 'relative-dir')], '新 context 没从 settings 恢复');
  } finally {
    await next.dispose();
  }
});

test('jobs 注册+运行+失败带原因+取消', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  try {
    ctx.jobs.register('sum', (a, b) => a + b);
    const pending = ctx.jobs.run('sum', [1, 2]);
    assert.match(pending.id, /^job-/);
    assert.equal(await pending, 3);
    assert.equal(ctx.jobs.get(pending.id).status, 'done');
    assert.deepEqual(ctx.jobs.list().map((job) => job.status), ['done']);

    ctx.jobs.register('boom', () => { throw new Error('炸了'); });
    await assert.rejects(() => ctx.jobs.run('boom'), /运行失败：炸了/);
    const failed = ctx.jobs.list().find((job) => job.name === 'boom');
    assert.equal(failed.status, 'failed');
    assert.match(failed.error, /炸了/);

    ctx.jobs.register('slow', () => new Promise(() => {}));
    const slow = ctx.jobs.run('slow');
    assert.equal(slow.cancel(), true);
    assert.equal(ctx.jobs.get(slow.id).status, 'cancelled');
    assert.throws(() => ctx.jobs.run('nope'), /找不到已注册任务 "nope"/);
    assert.throws(() => ctx.jobs.cancel('job-999'), /找不到任务 id/);
  } finally {
    await ctx.dispose();
  }
});

test('timer 定时触发、interval 可取消、dispose 清空', async () => {
  const home = await freshHome();
  const ctx = createContext({ home });
  const fired = [];
  let timer = null;
  try {
    const once = ctx.timer.setTimeout(() => fired.push('once'), 5);
    assert.equal(once.kind, 'timeout');
    await new Promise((resolve) => setTimeout(resolve, 40));
    assert.deepEqual(fired, ['once']);
    assert.equal(once.active, false);

    let ticks = 0;
    const interval = ctx.timer.setInterval(() => { ticks += 1; }, 5);
    await new Promise((resolve) => setTimeout(resolve, 40));
    interval.cancel();
    const seen = ticks;
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.ok(seen >= 1, 'interval 没触发');
    assert.equal(ticks, seen, 'cancel 之后 interval 还在跑');
    assert.equal(ctx.timer.size(), 0);
    assert.throws(() => ctx.timer.setTimeout('not-fn'), /需要函数/);
    ctx.timer.setInterval(() => {}, 1000);
    assert.equal(ctx.timer.size(), 1);
    timer = ctx.timer;
  } finally {
    await ctx.dispose();
  }
  // ctx.dispose 必须跑到 timer.dispose（host.cleanups），否则定时器与 exit 监听会泄漏
  assert.equal(timer.size(), 0);
});
