// 校验：插件目录（engine 之外）能解析并执行 skills CLI —— 这是 catalog 依赖但会静默 catch 的一条路径。
import { execFile } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';
import { ENGINE_ROOT, installModuleFallback } from '../host/loader.js';

// 夹具：在 os.tmpdir() 下现造一个 engine 之外的插件目录，node_modules/skills 用目录联接指回引擎自带的
// skills 包 —— 不再假设仓库里有 .mock/npmpkg。写到 test/fixtures/ 会多出一个测试文件，所以就地造。
const fixtureRoot = mkdtempSync(join(tmpdir(), 'dsh-skills-plugin-'));
const pluginDir = join(fixtureRoot, 'plugin');
mkdirSync(join(pluginDir, 'node_modules'), { recursive: true });
writeFileSync(join(pluginDir, 'package.json'), JSON.stringify({ name: 'dsh-skills-manager', version: '0.0.0-fixture', private: true, type: 'module', main: 'index.js' }, null, 2) + '\n', 'utf8');
// Windows 的 junction 不需要管理员权限；删链接不会跟着删 target。
symlinkSync(join(ENGINE_ROOT, 'node_modules', 'skills'), join(pluginDir, 'node_modules', 'skills'), process.platform === 'win32' ? 'junction' : 'dir');
// 失败路径也会走到 exit 事件：先删链接（只删链接），再删整棵临时目录。
process.once('exit', () => {
  try { rmSync(join(pluginDir, 'node_modules', 'skills'), { force: true }); } catch { /* 链接已不在 */ }
  try { rmSync(fixtureRoot, { recursive: true, force: true }); } catch { /* 占用中留给系统清理 */ }
});

installModuleFallback();
const requireFromPlugin = createRequire(join(pluginDir, 'package.json'));
const pkgPath = requireFromPlugin.resolve('skills/package.json');
const cli = join(dirname(pkgPath), 'bin', 'cli.mjs');
console.log('resolved skills/package.json -> ' + pkgPath);
const { stdout } = await promisify(execFile)(process.execPath, [cli, '--version'], { cwd: pluginDir });
console.log('skills CLI --version -> ' + stdout.trim());
