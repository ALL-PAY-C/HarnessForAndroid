// 端到端：真实 bin.js 子进程 + 同一条 socket 上的反向请求（引擎 → 宿主）。
// 覆盖 host/native-bridge.js 与 bin.js handleLine 的 bridge.consume 分支。
// 用法: node --test test/llm-e2e.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const engineRoot = resolve(here, '..');
const fixture = resolve(here, 'fixtures', 'llm-provider-plugin');

function freePort() {
  return new Promise((ok, fail) => {
    const server = createServer();
    server.once('error', fail);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => ok(port));
    });
  });
}

test('引擎在 socket 上反向调用宿主 llm.chat 并把结果回给插件', async () => {
  const port = await freePort();
  const home = mkdtempSync(join(tmpdir(), 'dsh-engine-e2e-'));
  const child = spawn(process.execPath, ['bin.js', '--port', String(port), '--home', home], {
    cwd: engineRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const stderr = [];
  child.stderr.on('data', (chunk) => stderr.push(String(chunk)));
  await new Promise((ok, fail) => {
    let out = '';
    const timer = setTimeout(() => fail(new Error('引擎未在 15s 内监听: ' + stderr.join(''))), 15000);
    child.stdout.on('data', (chunk) => {
      out += String(chunk);
      if (out.includes('DSH_ENGINE_LISTEN')) { clearTimeout(timer); ok(); }
    });
  });

  const socket = await import('node:net').then(({ connect }) =>
    new Promise((ok, fail) => {
      const s = connect(port, '127.0.0.1', () => ok(s));
      s.once('error', fail);
    }));

  let buffer = '';
  const waiters = new Map();
  let nextId = 1;
  const hostCalls = [];
  socket.on('data', (chunk) => {
    buffer += String(chunk);
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.trim() === '') continue;
      const message = JSON.parse(line);
      // 引擎发来的反向请求：有 method、没有 ok（与宿主请求同一形状，方向相反）。
      if (!Object.hasOwn(message, 'ok') && message.method === 'host.call') {
        // 反向请求：宿主在这里扮演 Kotlin 那一侧。
        hostCalls.push(message.params);
        const { method, payload } = message.params;
        if (method === 'llm.chat') {
          socket.write(JSON.stringify({
            id: message.id,
            ok: true,
            result: { content: 'gateway-e2e:' + payload.messages[0].content },
          }) + '\n');
        } else {
          socket.write(JSON.stringify({ id: message.id, ok: false, error: { stage: 'host', message: '未实现 ' + method } }) + '\n');
        }
        continue;
      }
      const waiter = waiters.get(message.id);
      if (waiter !== undefined) { waiters.delete(message.id); waiter(message); }
    }
  });
  const call = (method, params) => new Promise((ok, fail) => {
    const id = String(nextId++);
    const timer = setTimeout(() => { waiters.delete(id); fail(new Error('超时 ' + method)); }, 10000);
    waiters.set(id, (message) => {
      clearTimeout(timer);
      if (message.ok) ok(message.result);
      else fail(new Error(message.error?.message ?? 'error'));
    });
    socket.write(JSON.stringify({ id, method, params }) + '\n');
  });

  try {
    await call('plugins.load', { dir: fixture });
    const list = await call('plugins.list', {});
    assert.deepEqual(list.plugins.map((p) => p.name), ['dsh-llm-provider-fixture']);
    assert.ok(list.rpcPaths.includes('/llm-fixture'));

    const chat = await call('rpc', { path: '/llm-fixture', endpoint: 'chat' });
    assert.equal(chat.result.content, 'gateway-e2e:ping');
    assert.equal(hostCalls.length, 1);
    assert.equal(hostCalls[0].method, 'llm.chat');

    // 宿主不实现的方法：插件应收到 stage=host 的错误，而不是挂死。
    await assert.rejects(call('rpc', { path: '/llm-fixture', endpoint: 'secret' }), /未实现|宿主/);
  } finally {
    socket.end();
    child.kill('SIGTERM');
  }
});
