// 复刻引擎的真实拓扑：先静态 import 垫片（= engine/bin.js 第一行），
// 再在运行时 dynamic import 插件（= loadPlugin 的 dynamic import）。
// 插件内部才 import { execFile } from 'node:child_process' —— 此时门面实例化，快照的必须是打过补丁的函数。
import '../../host/android-node-exec.js';

const [appProcess, cliPath] = process.argv.slice(2);
const { listSkills } = await import('./plugin-child-process.mjs');
try {
  const catalog = await listSkills(appProcess, cliPath, process.cwd());
  process.stdout.write(JSON.stringify({ ok: true, isArray: Array.isArray(catalog), count: catalog.length }));
} catch (error) {
  process.stdout.write(JSON.stringify({ ok: false, code: error.code ?? null, message: String(error.message).slice(0, 400) }));
}
