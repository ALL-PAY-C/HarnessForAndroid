// loader: 把插件的裸 import 接到 engine/node_modules，并动态加载真实 cordis 插件。
import { accessSync, constants, mkdirSync, realpathSync, statSync } from 'node:fs';
import { readFile, readdir, stat } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { join, resolve, delimiter } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
// node 18 没有 registerHooks；具名 import 缺导出会直接 SyntaxError，所以走命名空间再取。
import * as nodeModule from 'node:module';
import { parse as parseYaml } from 'yaml';
// Node 18 的运行时缺口（RegExp 属性转义 / File / 源码级正则字面量 / util.styleText）统一收在
// node18-compat.js；这里 re-export，既有调用方（bin.js、engine/test/regexp-compat.test.mjs）导入路径不变。
import {
  installGlobals,
  installNode18Compat,
  installNode18EsmCompat,
  installRegExpCompat,
} from './node18-compat.js';

export { installGlobals, installNode18Compat, installNode18EsmCompat, installRegExpCompat };

const { createRequire, register, registerHooks } = nodeModule;
const Module = nodeModule.default ?? nodeModule;

export const ENGINE_ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const ENGINE_MODULES = join(ENGINE_ROOT, 'node_modules');
const IS_BARE = (specifier) => !specifier.startsWith('.') && !specifier.startsWith('/')
  && !specifier.startsWith('#') && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(specifier);

/**
 * 裸模块别名：生态包的 npm 名与代码里 import 的裸名不一致（插件写 'schemastery'，
 * 真包是 @deepseek-ai/schemastery）。表里只放「正常解析一定失败、真实包一定存在」的名字；
 * 命中后按引擎根解析一次，解析不到仍抛原始错误，不吞错。
 * semver 是真包，列在这里只为把这条通路显式化（正常解析会先命中，别名不生效）。
 */
const BARE_ALIASES = {
  schemastery: '@deepseek-ai/schemastery',
  cosmokit: '@deepseek-ai/cosmokit',
  cordis: '@deepseek-ai/cordis',
  semver: 'semver',
};

/** 'schemastery' → '@deepseek-ai/schemastery'；带子路径的 'x/y' 只替换包名部分。 */
function aliasOf(specifier) {
  if (!IS_BARE(specifier)) return undefined;
  const slash = specifier.indexOf('/');
  const name = slash === -1 ? specifier : specifier.slice(0, slash);
  const target = BARE_ALIASES[name];
  if (!target || target === name) return undefined;
  return target + specifier.slice(name.length);
}

let installed = false;

/**
 * Android 的 HOME 常常是 /data（不可写），插件按 os.homedir() 落盘会 EACCES
 * （真机证据：dsh-boot-video-manager mkdir '/data/.dsh/boot-video-manager' 失败）。
 * 桌面环境 HOME 可写时不打扰它；不可写（或不存在）就把 HOME 指到引擎 home，
 * 插件用 os.homedir()/DSH_HOME 推导的状态目录就都落在可写区。
 */
export function ensureWritableHome(home) {
  const target = resolve(home);
  try {
    mkdirSync(target, { recursive: true });
  } catch {
    // 建不出来就当它不可写，下面统一回退
  }
  // os.homedir() 才是插件实际用的判据：Linux/Android 读 HOME，Windows 读 USERPROFILE。
  let current = null;
  try {
    current = homedir();
  } catch {
    current = null; // USERPROFILE 指向不存在的路径时 homedir() 直接抛 ENOENT
  }
  if (current && isWritableDir(current)) return current;
  process.env.HOME = target;
  process.env.USERPROFILE = target;
  return target;
}

/**
 * Android 上 os.tmpdir() 是 /tmp（不存在、不可写），skills CLI 的 mkdtemp 直接 ENOENT
 * （设备证据：mkdtemp /tmp/dsh-skill-manager-source-*）。这里选一个真实可写的临时根：
 * TMPDIR/TMP/TEMP 优先（宿主与测试显式指定），其次 os.tmpdir()，都没有才落到 home/tmp。
 * 任何情况下都不创建 /tmp；选不出可写目录返回 null，让调用方保持原样。
 */
export function ensureWritableTemp(home) {
  const candidates = [process.env.TMPDIR, process.env.TMP, process.env.TEMP];
  try {
    candidates.push(tmpdir());
  } catch {
    // 极端环境里 os.tmpdir() 会抛，忽略
  }
  let chosen = null;
  for (const candidate of candidates) {
    if (candidate && isWritableDir(candidate)) {
      chosen = candidate;
      break;
    }
  }
  if (chosen === null) {
    const fallback = resolve(home, 'tmp');
    try {
      mkdirSync(fallback, { recursive: true });
    } catch {
      // 建不出来当没有
    }
    if (!isWritableDir(fallback)) return null;
    chosen = fallback;
  }
  process.env.TMPDIR = chosen;
  process.env.TMP = chosen;
  process.env.TEMP = chosen;
  return chosen;
}

function isWritableDir(dir) {
  try {
    if (!statSync(dir).isDirectory()) return false;
    accessSync(dir, constants.W_OK);
    return true;
  } catch {
    return false;
  }
}

installGlobals();
installRegExpCompat();

/**
 * 插件目录在 engine 之外（assets 里也是兄弟目录），Node 的裸 import 解析链够不到
 * engine/node_modules。这里补两条路：
 *  - ESM：resolve hook 兜底，从 engine 根再解析一次；
 *  - createRequire（插件用它定位 skills CLI 的 package.json）：NODE_PATH 全局目录。
 */
export function installModuleFallback() {
  if (installed) return;
  installed = true;

  const existing = process.env.NODE_PATH ? process.env.NODE_PATH.split(delimiter) : [];
  if (!existing.includes(ENGINE_MODULES)) {
    process.env.NODE_PATH = [ENGINE_MODULES, ...existing].filter(Boolean).join(delimiter);
  }
  if (typeof Module._initPaths === 'function') Module._initPaths();

  // 关键：用 ESM 解析器在 engine 目录下再解一次，而不是 createRequire.resolve —
  // 后者走 require 条件，会把 schemastery 之类的双格式包解析到 .cjs（node 18 上必炸）。
  const ENGINE_BASE_URL = pathToFileURL(join(ENGINE_ROOT, 'package.json')).href;

  if (typeof registerHooks === 'function') {
    registerHooks({
      resolve(specifier, context, nextResolve) {
        try {
          return nextResolve(specifier, context);
        } catch (error) {
          if (!IS_BARE(specifier)) throw error;
          const alias = aliasOf(specifier);
          if (alias) {
            try {
              return nextResolve(alias, { ...context, parentURL: ENGINE_BASE_URL });
            } catch {
              // 别名指不到包时继续走原有回退，最后仍抛最原始的解析错误
            }
          }
          try {
            return nextResolve(specifier, { ...context, parentURL: ENGINE_BASE_URL });
          } catch {
            throw error;
          }
        }
      },
    });
    return;
  }

  // node 18.19 / 20.x：只有异步 register()
  const source = 'const base = ' + JSON.stringify(ENGINE_BASE_URL) + ';\n'
    + 'const aliases = ' + JSON.stringify(BARE_ALIASES) + ';\n'
    + 'const isBare = (s) => !s.startsWith(".") && !s.startsWith("/") && !s.startsWith("#") && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(s);\n'
    + 'const aliasOf = (s) => { const i = s.indexOf("/"); const n = i === -1 ? s : s.slice(0, i); const t = aliases[n]; return t && t !== n ? t + s.slice(n.length) : undefined; };\n'
    + 'export async function resolve(specifier, context, nextResolve) {\n'
    + '  try { return await nextResolve(specifier, context); } catch (error) {\n'
    + '    if (!isBare(specifier)) throw error;\n'
    + '    const alias = aliasOf(specifier);\n'
    + '    if (alias) { try { return await nextResolve(alias, { ...context, parentURL: base }); } catch {} }\n'
    + '    try { return await nextResolve(specifier, { ...context, parentURL: base }); } catch { throw error; }\n'
    + '  }\n'
    + '}\n';
  register('data:text/javascript,' + encodeURIComponent(source));
}

function stageError(stage, error) {
  const message = error instanceof Error ? error.message : String(error);
  return Object.assign(error instanceof Error ? error : new Error(message), { stage, cause: error });
}

/**
 * 插件失败时的诊断信息：异常堆栈 + 包内根层条目。
 *
 * 设备上只有 logcat，插件加载失败最常见的原因是「包里根本不是那个入口」（缺 package.json、
 * 解包后被扁平化、内置插件目录名不对）。只回一句 import 失败没法定位，所以把栈和目录条目
 * 一起挂到错误上，由 bin.js 原样回给宿主落日志。
 */
async function withDiagnostics(error, root) {
  const target = error instanceof Error ? error : new Error(String(error));
  target.dir = root;
  target.stack = target.stack ?? String(target);
  try {
    const dirents = await readdir(root, { withFileTypes: true });
    target.entries = dirents.slice(0, 40).map((entry) => entry.name + (entry.isDirectory() ? '/' : ''));
    if (dirents.length > target.entries.length) target.entries.push('…共 ' + dirents.length + ' 项');
  } catch (listError) {
    target.entries = ['<读取目录失败: ' + (listError?.message ?? listError) + '>'];
  }
  return target;
}

/** 宿主侧缺少的服务名：cordis 自己会延迟等待，但行协议必须当场给出结论。 */
function missingServices(ctx, inject) {
  const missing = [];
  for (const key of inject ?? []) {
    if (typeof key !== 'string') continue;
    let present = false;
    try {
      present = typeof ctx.has === 'function' ? ctx.has(key) : ctx.get(key) !== undefined;
    } catch {
      present = false;
    }
    if (!present) missing.push(key);
  }
  return missing;
}

function resolveEntry(pkg) {
  const exported = pkg?.exports;
  if (typeof exported === 'string') return exported;
  const dot = exported?.['.'];
  if (typeof dot === 'string') return dot;
  if (typeof dot?.default === 'string') return dot.default;
  if (typeof dot?.import === 'string') return dot.import;
  if (typeof pkg?.main === 'string') return pkg.main;
  return 'index.js';
}

/**
 * cordis.patch.yml 只记录、不执行：它是 DSH 侧装配器（bundle patch）的插入清单，
 * 描述"把插件插到哪"，由宿主 loader 消费；插件自己的 apply 已经拿到了同一份语义。
 * 在 engine 里执行它等于自己给自己排装配顺序，会和宿主的装配结果冲突。
 */
async function readPatch(root) {
  const file = join(root, 'cordis.patch.yml');
  let raw;
  try {
    raw = await readFile(file, 'utf8');
  } catch (error) {
    if (error?.code === 'ENOENT') return { file: null, entries: [] };
    throw stageError('load', error);
  }
  try {
    return { file, entries: parseYaml(raw) ?? [] };
  } catch (error) {
    return { file, entries: [], parseError: String(error?.message ?? error) };
  }
}

/**
 * 同一 ctx 下同一目录只装载一次：app 启动按 --plugin 装载后，宿主还会显式 plugins.load
 * 同一目录（NodeEngineHost.kt:157-165），不去重会在 plugins.list 里出现两条同名插件。
 */
const loadedByContext = new WeakMap();

/**
 * 去重 key 必须是同一物理目录的 canonical 路径：设备上 /data/data/<pkg> 与 /data/user/0/<pkg> 是同一目录
 * 的两个别名，字面 resolve 得到两个 key → 同一插件 apply 两次、plugins.list 出现两条。
 * realpath 不可用或目录不存在时退回字面路径，让后面的 stat 报出原本的错误（"插件目录不存在"）。
 */
function canonicalDirKey(root) {
  const realpath = typeof realpathSync.native === 'function' ? realpathSync.native : realpathSync;
  try {
    return realpath(root);
  } catch {
    return root;
  }
}

/** 加载一个插件目录：读 package.json → 解析入口 → 动态 import → ctx.plugin()。 */
export async function loadPlugin(ctx, dir, config = {}) {
  const root = resolve(dir);
  const key = canonicalDirKey(root);
  const cached = loadedByContext.get(ctx)?.get(key);
  if (cached) return { ...cached, deduped: true };
  let info;
  try {
    info = await stat(root);
  } catch (error) {
    throw stageError('load', new Error('插件目录不存在: ' + root));
  }
  if (!info.isDirectory()) throw stageError('load', new Error('插件路径不是目录: ' + root));

  let pkg;
  try {
    pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  } catch (error) {
    throw stageError('load', new Error('读取 ' + join(root, 'package.json') + ' 失败: ' + (error?.message ?? error)));
  }

  const entry = resolve(root, resolveEntry(pkg));
  let mod;
  try {
    mod = await import(pathToFileURL(entry).href);
  } catch (error) {
    throw stageError('load', new Error('import ' + entry + ' 失败: ' + (error?.message ?? error)));
  }

  const patch = await readPatch(root);

  // 缺服务必须在装载时给出结论：cordis 自己会把这类插件挂成"等待注入"，
  // 但行协议调用方（NodeEngineHost / smoke.mjs）等的是 ok:false + stage=apply。
  const missing = missingServices(ctx, mod?.inject ?? mod?.default?.inject);
  if (missing.length > 0) {
    const message = '缺少服务 ' + missing.map((key) => String.fromCharCode(34) + key + String.fromCharCode(34)).join(', ');
    throw await withDiagnostics(stageError('apply', new Error(message)), root);
  }

  let record;
  try {
    record = ctx.plugin(mod, config);
  } catch (error) {
    throw await withDiagnostics(stageError('apply', error), root);
  }
  // 真 cordis 的配置校验在 fiber 生命周期里，ctx.plugin 不抛；settled() 把它取出来。
  if (record && typeof record.settled === 'function') {
    try {
      await record.settled();
    } catch (error) {
      throw await withDiagnostics(stageError('apply', error), root);
    }
  }

  const loaded = {
    dir: root,
    package: { name: pkg.name ?? null, version: pkg.version ?? null },
    entry,
    name: record.name,
    inject: record.inject,
    exports: Object.keys(mod),
    patch: { file: patch.file, entries: patch.entries, executed: false },
    appliedAt: record.appliedAt,
  };
  if (!loadedByContext.has(ctx)) loadedByContext.set(ctx, new Map());
  loadedByContext.get(ctx).set(key, loaded);
  return loaded;
}
