// 插件侧的取用形态（与 .mock/t5-real-plugins/.../dsh-skills-manager/package/lib/index.js:6 一致）：
// 在模块里静态 import { execFile } from 'node:child_process'，再用 promisify 包一层。
// 内置模块的具名导出是"首次链接时的快照" —— 所以它看到的 execFile 取决于垫片是不是更早被链接。
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

export const execFileAsync = promisify(execFile);

export async function listSkills(appProcess, cliPath, cwd) {
  const { stdout } = await execFileAsync(appProcess, [cliPath, 'list', '--json', '--agent', 'universal'], {
    cwd,
    env: { ...process.env, NO_COLOR: '1', DISABLE_TELEMETRY: '1', DO_NOT_TRACK: '1' },
    timeout: 120000,
    maxBuffer: 4 * 1024 * 1024,
  });
  return JSON.parse(stdout);
}
