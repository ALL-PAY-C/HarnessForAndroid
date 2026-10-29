// 校验：插件目录（engine 之外）能解析并执行 skills CLI —— 这是 catalog 依赖但会静默 catch 的一条路径。
import { execFile } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { installModuleFallback } from '../host/loader.js';

const engineRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pluginDir = resolve(engineRoot, '..', '.mock', 'npmpkg', 'dsh-skills-manager', 'package');

installModuleFallback();
const requireFromPlugin = createRequire(join(pluginDir, 'package.json'));
const pkgPath = requireFromPlugin.resolve('skills/package.json');
const cli = join(dirname(pkgPath), 'bin', 'cli.mjs');
console.log('resolved skills/package.json -> ' + pkgPath);
const { stdout } = await promisify(execFile)(process.execPath, [cli, '--version'], { cwd: pluginDir });
console.log('skills CLI --version -> ' + stdout.trim());
