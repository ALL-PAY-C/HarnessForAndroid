// 最小可运行检查：起 bin.js → 走通 apply→registerProvider→rpc handler → 坏目录/缺服务不弄死进程。
// 用法: node test/smoke.mjs [--plugin <dir>] [--home <dir>]
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { connect, createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const engineRoot = resolve(here, '..');

function arg(name, fallback) {
  const index = process.argv.indexOf(name);
  return index >= 0 && process.argv[index + 1] ? resolve(process.argv[index + 1]) : fallback;
}

const home = arg('--home', join(tmpdir(), 'dsh-engine-smoke'));
const plugin = arg('--plugin', resolve(engineRoot, '..', '.mock', 'npmpkg', 'dsh-skills-manager', 'package'));
const portFile = join(home, 'engine.port');

const SKILLS = {
  'hello-world': 'Demo skill installed by the engine smoke test.',
  'engine-smoke': 'Second demo skill so the catalog has more than one entry.',
};

async function seedHome() {
  for (const [name, description] of Object.entries(SKILLS)) {
    const dir = join(home, 'skill-manager', '.agents', 'skills', name);
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, 'SKILL.md'), '---\nname: ' + name + '\ndescription: ' + description + '\n---\n\n# ' + name + '\n\nSmoke-test body.\n', 'utf8');
  }
  // 缺服务的插件：inject 里有不存在的服务，apply 前必须被拦下
  const broken = join(home, 'plugin-missing-service');
  await mkdir(broken, { recursive: true });
  await writeFile(join(broken, 'package.json'), JSON.stringify({ name: 'plugin-missing-service', version: '0.0.0', type: 'module', main: 'index.js' }, null, 2), 'utf8');
  await writeFile(join(broken, 'index.js'), 'export const name = "plugin-missing-service";\nexport const inject = ["skills", "nope"];\nexport function apply() {}\n', 'utf8');
}

function once(emitter, event) {
  return new Promise((res, rej) => {
    emitter.once(event, res);
    emitter.once('error', rej);
  });
}

function collectStdout(child) {
  return new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('引擎 15s 内没有报端口')), 15000);
    let buffered = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      buffered += chunk;
      const line = buffered.split('\n').find((entry) => entry.startsWith('DSH_ENGINE_LISTEN '));
      if (line) {
        clearTimeout(timer);
        res({ line: line.trim(), port: Number(line.split(' ')[1]) });
      }
    });
  });
}

await seedHome();

const child = spawn(process.execPath, [join(engineRoot, 'bin.js'), '--port', '0', '--home', home, '--plugin', plugin, '--port-file', portFile], {
  stdio: ['ignore', 'pipe', 'pipe'],
});
child.stderr.setEncoding('utf8');
child.stderr.on('data', (chunk) => process.stderr.write('[engine] ' + chunk));

const { line, port } = await collectStdout(child);
console.log('stdout: ' + line);
const portFileValue = (await readFile(portFile, 'utf8')).trim();
console.log('port-file: ' + portFileValue);

const socket = connect({ host: '127.0.0.1', port });
await once(socket, 'connect');
socket.setEncoding('utf8');

const pending = new Map();
let buffer = '';
socket.on('data', (chunk) => {
  buffer += chunk;
  let index = buffer.indexOf('\n');
  while (index >= 0) {
    const raw = buffer.slice(0, index);
    buffer = buffer.slice(index + 1);
    index = buffer.indexOf('\n');
    if (raw.trim() === '') continue;
    const parsed = JSON.parse(raw);
    const settle = pending.get(parsed.id);
    if (settle) { pending.delete(parsed.id); settle(parsed); }
  }
});

let nextId = 0;
function call(method, params) {
  const id = String(++nextId);
  return new Promise((res) => {
    pending.set(id, res);
    socket.write(JSON.stringify({ id, method, params }) + '\n');
  });
}

const results = {};
results.ping = await call('ping');
console.log('ping -> ' + JSON.stringify(results.ping));

results.rpc = await call('rpc', { path: '/skill-manager', endpoint: 'list', payload: {} });
console.log('rpc list -> ' + JSON.stringify(results.rpc));

results.badLoad = await call('plugins.load', { dir: join(home, 'no-such-plugin-dir') });
console.log('plugins.load(bad) -> ' + JSON.stringify(results.badLoad));

results.missingService = await call('plugins.load', { dir: join(home, 'plugin-missing-service') });
console.log('plugins.load(missing service) -> ' + JSON.stringify(results.missingService));

results.aliveAfterBad = await call('ping');
console.log('ping after bad loads -> ' + JSON.stringify(results.aliveAfterBad));

results.list = await call('plugins.list');
console.log('plugins.list -> ' + JSON.stringify(results.list));

results.shutdown = await call('shutdown');
console.log('shutdown -> ' + JSON.stringify(results.shutdown));

// 端口被占用：第二个引擎必须写 ERR 并以非 0 退出
const blocker = createServer(() => {});
await new Promise((res) => blocker.listen(0, '127.0.0.1', res));
const busyPort = blocker.address().port;
const errPortFile = join(home, 'engine.busy.port');
const second = spawn(process.execPath, [join(engineRoot, 'bin.js'), '--port', String(busyPort), '--home', home, '--port-file', errPortFile], { stdio: ['ignore', 'ignore', 'pipe'] });
let secondErr = '';
second.stderr.setEncoding('utf8');
second.stderr.on('data', (chunk) => { secondErr += chunk; });
const secondExit = await new Promise((res) => second.on('exit', res));
const busyFileValue = (await readFile(errPortFile, 'utf8')).trim();
console.log('busy port: exit=' + secondExit + ' port-file=' + JSON.stringify(busyFileValue) + ' stderr=' + JSON.stringify(secondErr.trim()));
blocker.close();

const checks = [
  ['端口文件与 stdout 一致', portFileValue === String(port)],
  ['ping ok', results.ping.ok === true],
  ['rpc list ok:true', results.rpc.ok === true && results.rpc.result?.ok === true],
  ['catalog 非空且来自真实 provider', (results.rpc.result?.value?.entries?.length ?? 0) >= 2],
  ['坏目录 ok:false + stage=load', results.badLoad.ok === false && results.badLoad.error?.stage === 'load'],
  ['缺服务 ok:false + stage=apply + 缺少服务', results.missingService.ok === false && results.missingService.error?.stage === 'apply' && results.missingService.error?.message?.includes('缺少服务')],
  ['坏加载后进程仍活', results.aliveAfterBad.ok === true],
  ['plugins.list 记录插件与 patch', results.list.result?.plugins?.length === 1 && results.list.result?.loaded?.[0]?.patch?.entries?.length === 1],
  ['端口占用写 ERR 且非 0 退出', secondExit !== 0 && busyFileValue.startsWith('ERR ')],
];
let failed = 0;
for (const [label, pass] of checks) {
  if (!pass) failed += 1;
  console.log((pass ? 'PASS ' : 'FAIL ') + label);
}
await new Promise((res) => setTimeout(res, 200));
child.kill();
process.exit(failed === 0 ? 0 : 1);
