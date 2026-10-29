// Node 18 兼容层（nodejs-mobile v18.20.4 = 无 ICU、无 util.styleText）。
//
// 三处真实缺口，都在"模块被 V8 编译/链接"这一层，运行期 PATCH 够不到：
//  1) 正则字面量里的 \p{Script=Han}：V8 无 ICU → 模块解析期 SyntaxError
//     "Invalid regular expression: ... Invalid property name in character class"
//     （设备证据 .mock/t10-skill-final-cli-probe.log，skills/dist/_chunks/libs/@clack/core.mjs:24）。
//  2) import { styleText } from "node:util"：styleText 是 node 20.12+，18 上具名导出不存在 →
//     链接期 SyntaxError "does not provide an export named 'styleText'"（prompts.mjs:2）。
//  3) 运行期 new RegExp('\\p{So}...') 无 u flag：loader 原先只处理这一种（原实现搬到这里，行为不变）。
//
// 手段：module.register 的 load hook 在源码级别改写（正则字面量展开成 \u{...} 区间、node:util 的
// specifier 换成垫片模块），不改依赖本体、不改 CLI、不升级 node。未知属性名不猜，原样保留并在
// strict 模式下按 V8 原话抛错（真机上它同样会抛）。
import * as nodeModule from 'node:module';
import * as nodeBuffer from 'node:buffer';
import { classString } from './unicode-properties.js';

const { createRequire } = nodeModule;

/** \p{...}/\P{...} 判定：按反斜杠奇偶，源码里 "\\p{"（字符串字面量里的常规写法）不算。 */
export function hasUnicodePropertyEscape(source) {
  for (let i = 0; i < source.length - 1; i += 1) {
    if ((source[i] === 'p' || source[i] === 'P') && source[i + 1] === '{') {
      let backslashes = 0;
      for (let j = i - 1; j >= 0 && source[j] === '\\'; j -= 1) backslashes += 1;
      if (backslashes % 2 === 1) return true;
    }
  }
  return false;
}

/**
 * 把 pattern 字符串里的 \p{X} / \P{X} 就地换成 unicode-properties.js 的具体区间。
 * 类内 \p{X} -> 裸区间；类内 \P{X} 无法内联表达，保留原样（真机上仍会抛，不伪装成功）。
 */
export function rewritePropertyEscapes(source) {
  let out = '';
  let i = 0;
  let changed = false;
  let inClass = false;
  while (i < source.length) {
    const ch = source[i];
    if (ch === '\\') {
      const next = source[i + 1];
      if ((next === 'p' || next === 'P') && source[i + 2] === '{') {
        const end = source.indexOf('}', i + 3);
        if (end > 0) {
          const body = classString(source.slice(i + 3, end));
          if (body) {
            const neg = next === 'P';
            if (inClass) {
              if (!neg) { out += body; changed = true; }
              else { out += ch + next + source.slice(i + 2, end + 1); }
            } else {
              out += neg ? '[^' + body + ']' : '[' + body + ']';
              changed = true;
            }
            i = end + 1;
            continue;
          }
        }
      }
      out += ch + (next ?? '');
      i += 2;
      continue;
    }
    if (ch === '[') inClass = true;
    else if (ch === ']') inClass = false;
    out += ch;
    i += 1;
  }
  return { pattern: out, changed };
}

/** Node 18 缺 File（市场插件普遍当 Web 环境用）。 */
export function installGlobals() {
  if (typeof globalThis.Blob === 'undefined' && typeof nodeBuffer.Blob === 'function') {
    globalThis.Blob = nodeBuffer.Blob;
  }
  if (typeof globalThis.File === 'undefined') {
    const Base = globalThis.Blob;
    if (typeof Base === 'function') {
      globalThis.File = class File extends Base {
        constructor(parts, fileName, options = {}) {
          super(parts, options);
          Object.defineProperty(this, 'name', { value: String(fileName ?? ''), enumerable: true });
          Object.defineProperty(this, 'lastModified', { value: Number(options.lastModified ?? Date.now()), enumerable: true });
        }
      };
    }
  }
}

/**
 * Node 18.20.4 的 V8 要求 Unicode 属性转义 \p{...}/\P{...} 显式带 u flag。
 * 市场插件里普遍写 new RegExp('\\p{So}...') 不传 flags（billion-context 解析 [ACP] 就踩了）。
 * 这里包一层：pattern 是字符串、含未转义 \p{/\P{、且未传 flags 时自动补 'u'。
 * Reflect.construct 保留 new/函数两种调用与 instanceof；不碰显式 flags、正则字面量与无属性转义的正则。
 */
export function installRegExpCompat() {
  const Original = globalThis.RegExp;
  if (typeof Original !== 'function' || Original.__dshMobileRegExpCompat) return;
  function CompatRegExp(pattern, flags) {
    if (typeof pattern === 'string') {
      const rw = rewritePropertyEscapes(pattern);
      if (rw.changed) {
        pattern = rw.pattern;
        if (flags === undefined) flags = 'u';
        else if (!flags.includes('u')) flags += 'u';
      } else if (flags === undefined && hasUnicodePropertyEscape(pattern)) {
        flags = 'u';
      }
    }
    // RegExp 也允许不带 new 调用（插件里 RegExp(p) 很常见）：此时 new.target 是 undefined，
    // 直接交给 Reflect.construct 会抛 "undefined is not a constructor"，回退到 Original 作 newTarget。
    return Reflect.construct(Original, flags === undefined ? [pattern] : [pattern, flags], new.target ?? Original);
  }
  Object.setPrototypeOf(CompatRegExp, Original);
  CompatRegExp.prototype = Original.prototype;
  CompatRegExp.__dshMobileRegExpCompat = true;
  globalThis.RegExp = CompatRegExp;
  return Original;
}

// ── 缺失的具名导出（node 20+ 才有的内置 API）────────────────────────────────
// node 18 上 `import { crc32 } from 'node:zlib'` 是**链接期** SyntaxError（"does not provide an
// export named 'crc32'"），运行期补 module.exports 也来不及，所以只能在源码层换 specifier：
// data: 模块接管缺失的那个函数，其余导出用 export * 透传，同一个文件里的其它具名导入不受影响。
// 全库（engine/node_modules，671 个文件、51 个具名内置导入）扫出来只有这两个名字缺，别提前扩表。
// node util.styleText 的格式表（node 源 lib/internal/util/inspect.js 的 kColorInspectOptions 近亲；
// 只列 clack/prompts 与实际终端会用到的名字，未知名字按 node 语义抛 ERR_INVALID_ARG_VALUE）。
const STYLE_TEXT_CODES = [
  'reset: [0, 0]',
  'bold: [1, 22]',
  'dim: [2, 22]',
  'italic: [3, 23]',
  'underline: [4, 24]',
  'blink: [5, 25]',
  'inverse: [7, 27]',
  'hidden: [8, 28]',
  'strikethrough: [9, 29]',
  'doubleunderline: [21, 21]',
  'black: [30, 39]',
  'red: [31, 39]',
  'green: [32, 39]',
  'yellow: [33, 39]',
  'blue: [34, 39]',
  'magenta: [35, 39]',
  'cyan: [36, 39]',
  'white: [37, 39]',
  'gray: [90, 39]',
  'grey: [90, 39]',
  'blackBright: [90, 39]',
  'redBright: [91, 39]',
  'greenBright: [92, 39]',
  'yellowBright: [93, 39]',
  'blueBright: [94, 39]',
  'magentaBright: [95, 39]',
  'cyanBright: [96, 39]',
  'whiteBright: [97, 39]',
  'bgBlack: [40, 49]',
  'bgRed: [41, 49]',
  'bgGreen: [42, 49]',
  'bgYellow: [43, 49]',
  'bgBlue: [44, 49]',
  'bgMagenta: [45, 49]',
  'bgCyan: [46, 49]',
  'bgWhite: [47, 49]',
  'bgGray: [100, 49]',
  'bgGrey: [100, 49]',
];
const MODULE_SHIMS = [
  {
    specifier: 'node:util',
    local: 'nodeUtil',
    provides: ['styleText'],
    body: [
      'const STYLE_CODES = {' + STYLE_TEXT_CODES.join(',') + '};',
      'function colorEnabled(stream) {',
      '  const noColor = process.env.NO_COLOR;',
      "  if (noColor !== undefined && noColor !== '' && noColor !== '0') return false;",
      '  const force = process.env.FORCE_COLOR;',
      "  if (force !== undefined && force !== '' && force !== '0' && force !== 'false') return true;",
      '  return Boolean(stream && stream.isTTY && typeof stream.getColorDepth === "function" && stream.getColorDepth() > 1);',
      '}',
      'export function styleText(format, text, options) {',
      '  const opts = options || {};',
      '  const stream = opts.stream || process.stdout;',
      '  const formats = Array.isArray(format) ? format : [format];',
      '  const value = typeof text === "string" ? text : String(text);',
      '  if (formats.length === 0) return value;',
      '  const pairs = formats.map((name) => {',
      '    const pair = STYLE_CODES[name];',
      '    if (pair === undefined) {',
      '      throw Object.assign(new TypeError("The argument \'format\' is invalid. Received " + JSON.stringify(name)), { code: "ERR_INVALID_ARG_VALUE" });',
      '    }',
      '    return pair;',
      '  });',
      '  if (!colorEnabled(stream)) return value;',
      '  let open = "";',
      '  let close = "";',
      '  for (const pair of pairs) {',
      '    open += "\\u001b[" + pair[0] + "m";',
      '    close = "\\u001b[" + pair[1] + "m" + close;',
      '  }',
      '  return open + value + close;',
      '}',
    ],
  },
  {
    specifier: 'node:zlib',
    local: 'nodeZlib',
    provides: ['crc32'],
    body: [
      '// CRC-32（IEEE 802.3，反射，多项式 0xEDB88320），与 node 20 `zlib.crc32` 逐字节一致。',
      'const CRC_TABLE = (() => {',
      '  const table = new Int32Array(256);',
      '  for (let i = 0; i < 256; i += 1) {',
      '    let c = i;',
      '    for (let k = 0; k < 8; k += 1) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);',
      '    table[i] = c;',
      '  }',
      '  return table;',
      '})();',
      'export function crc32(data, value) {',
      '  let buf;',
      '  if (typeof data === "string") {',
      '    buf = Buffer.from(data, "utf8");',
      '  } else if (data instanceof ArrayBuffer) {',
      '    buf = Buffer.from(data);',
      '  } else if (ArrayBuffer.isView(data)) {',
      '    buf = Buffer.from(data.buffer, data.byteOffset, data.byteLength);',
      '  } else {',
      '    throw Object.assign(new TypeError("The \\"data\\" argument must be of type string or an instance of Buffer, TypedArray, DataView, or ArrayBuffer"), { code: "ERR_INVALID_ARG_TYPE" });',
      '  }',
      '  let crc = ((value === undefined ? 0 : value) ^ -1) >>> 0;',
      '  for (let i = 0; i < buf.length; i += 1) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xff];',
      '  return (crc ^ -1) >>> 0;',
      '}',
    ],
  },
];

const shimUrls = new Map();
function shimUrl(shim) {
  let url = shimUrls.get(shim.specifier);
  if (url === undefined) {
    const lines = [
      'import ' + shim.local + 'Default from ' + JSON.stringify(shim.specifier) + ';',
      'export * from ' + JSON.stringify(shim.specifier) + ';',
      'export default ' + shim.local + 'Default;',
    ].concat(shim.body);
    url = 'data:text/javascript;base64,' + nodeBuffer.Buffer.from(lines.join('\n'), 'utf8').toString('base64');
    shimUrls.set(shim.specifier, url);
  }
  return url;
}

const nativeExportCache = new Map();
/** 当前运行时的模块上这个具名导出到底有没有（node 20+ 有就不改写，别抢原生）。 */
function hasNativeExport(specifier, name) {
  const key = specifier + '|' + name;
  if (!nativeExportCache.has(key)) {
    let present = false;
    try {
      const mod = createRequire(import.meta.url)(specifier);
      present = mod != null && mod[name] !== undefined;
    } catch {
      present = false;
    }
    nativeExportCache.set(key, present);
  }
  return nativeExportCache.get(key);
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * `import { a, styleText, b } from 'node:util'` 里只要有本运行时缺的名字，就把整个 specifier
 * 换成垫片模块（垫片自带缺失实现 + export * 透传其余导出）。改不动的一律原样保留。
 */
export function rewriteMissingNamedImports(source) {
  if (!source.includes('node:')) return source;
  let out = source;
  for (const shim of MODULE_SHIMS) {
    if (!out.includes(shim.specifier)) continue;
    const pattern = new RegExp('\\bimport\\s*\\{([^}]*)\\}\\s*from\\s*([\'"])' + escapeRegExp(shim.specifier) + '\\2\\s*;?', 'g');
    out = out.replace(pattern, (whole, names) => {
      const list = names.split(',').map((entry) => entry.trim()).filter(Boolean);
      const needed = list.some((entry) => {
        const imported = entry.split(/\s+as\s+/)[0].trim();
        return shim.provides.includes(imported) && !hasNativeExport(shim.specifier, imported);
      });
      if (!needed) return whole;
      return 'import {' + names + '} from ' + JSON.stringify(shimUrl(shim)) + ';';
    });
  }
  return out;
}

// 兼容旧名字（本文件早先只处理 styleText）。
export const rewriteStyleTextImports = rewriteMissingNamedImports;

// ── 源码级正则字面量改写 ──────────────────────────────────────────────────────
const REGEX_AFTER_CHARS = new Set(['', '(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '^', '~', '<', '>']);
const REGEX_AFTER_WORDS = new Set(['return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw', 'case', 'do', 'else', 'yield', 'await']);

function skipLineComment(source, i) {
  const end = source.indexOf('\n', i);
  return end < 0 ? source.length : end;
}

function skipBlockComment(source, i) {
  const end = source.indexOf('*/', i + 2);
  return end < 0 ? source.length : end + 2;
}

function skipQuoted(source, i) {
  const quote = source[i];
  let j = i + 1;
  while (j < source.length) {
    const ch = source[j];
    if (ch === '\\') { j += 2; continue; }
    if (ch === quote) return j + 1;
    if (ch === '\n') return j;
    j += 1;
  }
  return source.length;
}

/** 模板字面量：跳过插值代码块（只数大括号，够用且不会提前结束）。 */
function skipTemplate(source, i) {
  let j = i + 1;
  while (j < source.length) {
    const ch = source[j];
    if (ch === '\\') { j += 2; continue; }
    if (ch === '`') return j + 1;
    if (ch === '$' && source[j + 1] === '{') {
      let depth = 1;
      j += 2;
      while (j < source.length && depth > 0) {
        const c = source[j];
        if (c === '\\') { j += 2; continue; }
        if (c === '"' || c === "'") { j = skipQuoted(source, j); continue; }
        if (c === '`') { j = skipTemplate(source, j); continue; }
        if (c === '{') depth += 1;
        else if (c === '}') depth -= 1;
        j += 1;
      }
      continue;
    }
    j += 1;
  }
  return source.length;
}

/** source[i] === '/'，按正则字面量扫描；不是合法字面量（换行先到）返回 null。 */
function scanRegexLiteral(source, i) {
  let j = i + 1;
  let inClass = false;
  while (j < source.length) {
    const ch = source[j];
    if (ch === '\\') { j += 2; continue; }
    if (ch === '\n') return null;
    if (ch === '[') inClass = true;
    else if (ch === ']') inClass = false;
    else if (ch === '/' && !inClass) {
      let k = j + 1;
      while (k < source.length && /[a-z]/.test(source[k])) k += 1;
      return { start: i, bodyStart: i + 1, bodyEnd: j, end: k, body: source.slice(i + 1, j), flags: source.slice(j + 1, k) };
    }
    j += 1;
  }
  return null;
}

function wordBefore(source, i) {
  let j = i - 1;
  while (j >= 0 && /\s/.test(source[j])) j -= 1;
  const end = j + 1;
  while (j >= 0 && /[\w$]/.test(source[j])) j -= 1;
  return { word: source.slice(j + 1, end), char: j >= 0 ? source[j] : '' };
}

/**
 * 只改正则字面量：必须带 u/v flag（否则 \u{...} 没意义，且原行为是"当普通字符 p{...}"，
 * 改了反而变语义），且 body 里确实有可解析的属性转义。
 */
export function rewriteRegexLiterals(source) {
  const unresolved = [];
  let out = '';
  let i = 0;
  let changed = false;
  while (i < source.length) {
    const ch = source[i];
    if (ch === '/' && source[i + 1] === '/') { const end = skipLineComment(source, i); out += source.slice(i, end); i = end; continue; }
    if (ch === '/' && source[i + 1] === '*') { const end = skipBlockComment(source, i); out += source.slice(i, end); i = end; continue; }
    if (ch === '"' || ch === "'") { const end = skipQuoted(source, i); out += source.slice(i, end); i = end; continue; }
    if (ch === '`') { const end = skipTemplate(source, i); out += source.slice(i, end); i = end; continue; }
    if (ch === '/') {
      const before = wordBefore(source, i);
      const regexAllowed = REGEX_AFTER_CHARS.has(before.char) || REGEX_AFTER_WORDS.has(before.word);
      if (regexAllowed) {
        const literal = scanRegexLiteral(source, i);
        if (literal) {
          if (/[uv]/.test(literal.flags) && hasUnicodePropertyEscape(literal.body)) {
            const rw = rewritePropertyEscapes(literal.body);
            if (rw.changed) {
              out += '/' + rw.pattern + '/' + literal.flags;
              changed = true;
            } else {
              unresolved.push(literal.body);
              out += source.slice(literal.start, literal.end);
            }
          } else {
            out += source.slice(literal.start, literal.end);
          }
          i = literal.end;
          continue;
        }
      }
    }
    out += ch;
    i += 1;
  }
  return { source: out, changed, unresolved };
}

/**
 * 模块源码 → Node 18 能编译的源码。strict 模式（DSH_NODE18_STRICT_PROPERTY_ESCAPES=1）把
 * 改写不了的正则字面量按 V8 原话抛错，用来证明"没有属性转义漏进 V8"（真机无 ICU 时同样会抛）。
 */
export function rewriteNode18Source(source, options = {}) {
  if (typeof source !== 'string' || source.length === 0) return source;
  let out = source;
  if (hasUnicodePropertyEscape(out)) {
    const result = rewriteRegexLiterals(out);
    out = result.source;
    if (options.strict && result.unresolved.length > 0) {
      const where = options.url ? ' (module ' + options.url + ')' : '';
      throw new SyntaxError('Invalid regular expression: /' + result.unresolved[0] + '/: Invalid property name in character class' + where);
    }
  }
  out = rewriteMissingNamedImports(out);
  return out;
}

function esmCompatHookUrl() {
  const source = [
    'import { rewriteNode18Source } from ' + JSON.stringify(new URL('./node18-compat.js', import.meta.url).href) + ';',
    'let strict = false;',
    'export async function initialize(data) { strict = Boolean(data && data.strict); }',
    'export async function load(url, context, nextLoad) {',
    '  const result = await nextLoad(url, context);',
    '  if (!result || result.source === null || result.source === undefined) return result;',
    "  if (result.format !== 'module' && result.format !== 'commonjs') return result;",
    "  if (typeof url === 'string' && url.startsWith('data:')) return result;",
    "  const source = typeof result.source === 'string' ? result.source : Buffer.from(result.source).toString('utf8');",
    '  const out = rewriteNode18Source(source, { url, strict });',
    '  if (out === source) return result;',
    '  return { ...result, source: out, shortCircuit: true };',
    '}',
  ].join('\n');
  return 'data:text/javascript;base64,' + nodeBuffer.Buffer.from(source, 'utf8').toString('base64');
}

let sourceHookInstalled = false;

/**
 * 装 load hook（Node 18 只有异步 register，没有 registerHooks）。幂等：重复调用直接返回。
 * 返回形如 {installed, skipped}，调用方据此决定要不要告警，不在这里抛。
 */
export function installNode18EsmCompat(options = {}) {
  if (sourceHookInstalled) return { installed: true, skipped: 'already' };
  if (typeof nodeModule.register !== 'function') return { installed: false, skipped: 'no-register' };
  const strict = options.strict === undefined
    ? process.env.DSH_NODE18_STRICT_PROPERTY_ESCAPES === '1'
    : Boolean(options.strict);
  nodeModule.register(esmCompatHookUrl(), { data: { strict } });
  sourceHookInstalled = true;
  return { installed: true, strict };
}

/** 一次装齐：File/Blob、运行期 RegExp、模块源码改写。bin.js 与 android-node-exec 的 Worker 都调它。 */
export function installNode18Compat(options = {}) {
  installGlobals();
  installRegExpCompat();
  const hook = installNode18EsmCompat(options);
  return { ...hook, styleText: hasNativeExport('node:util', 'styleText') };
}
