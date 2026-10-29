// Node 18（nodejs-mobile v18.20.4，无 ICU）真实缺口的单元回归。
// 设备证据：.mock/t5-three-plugins/final-REPORT.md §5.3(5)、.mock/t10-skill-final-cli-probe.log ——
// 真机上 skills CLI 在模块解析期就死在 /(?:(?![\uFF61-\uFF9F...])[\p{Script=Han}...]){1,1000}/uy。
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { rewriteMissingNamedImports, rewriteNode18Source, rewriteRegexLiterals } from '../host/node18-compat.js';
import { ensureWritableTemp } from '../host/loader.js';

// 设备上原样出现的三处（engine/node_modules/skills/dist/_chunks/libs/@clack/core.mjs:24/26/28）。
const CJKT_WIDE_RE = /(?:(?![\uFF61-\uFF9F\uFF00-\uFFEF])[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Script=Tangut}]){1,1000}/uy;
const EMOJI_RE = /[\p{Emoji}\p{Emoji_Modifier_Base}\p{Emoji_Modifier}\p{Emoji_Presentation}]/u;
const MODIFIER_RE = /\p{M}+/gu;

// 沙箱不允许在 %TEMP% 建目录（EPERM mkdtemp），基准落到工作区内。
const TEMP_ROOT = join(process.cwd(), '.mock', 'test-tmp');

function makeHome(tag) {
  mkdirSync(TEMP_ROOT, { recursive: true });
  return mkdtempSync(join(TEMP_ROOT, tag));
}

function wrap(pair) {
  return 'const RE = ' + pair + ';';
}

test('正则字面量：设备原样 CJKT_WIDE_RE 展开成纯 \\u{...} 区间（无 \\p{），flags 保留', () => {
  const result = rewriteRegexLiterals(wrap(CJKT_WIDE_RE.toString()));
  assert.equal(result.changed, true);
  assert.deepEqual(result.unresolved, []);
  assert.equal(/\\[pP]\{/.test(result.source), false, '展开后不允许残留属性转义');
  assert.match(result.source, /\/uy;?$/, 'flags 必须原样保留（uy）');
  const body = result.source.slice(result.source.indexOf('/') + 1, result.source.lastIndexOf('/'));
  const rebuilt = new RegExp(body, 'uy');
  assert.equal(rebuilt.test('\u6c49\u5b57'), true, '必须仍然匹配汉字');
  rebuilt.lastIndex = 0;
  assert.equal(rebuilt.test('abc'), false, '必须仍然不匹配 ASCII');
});

test('正则字面量：Emoji 与 \\p{M}+ 同样展开；不带 u flag 的字面量原样不动', () => {
  const source = wrap(EMOJI_RE.toString()) + '\n' + wrap(MODIFIER_RE.toString()) + '\nconst PLAIN = /\\p{Script=Han}/;';
  const result = rewriteRegexLiterals(source);
  assert.equal(/\\[pP]\{/.test(result.source.slice(0, result.source.indexOf('const PLAIN'))), false);
  assert.equal(result.source.includes('const PLAIN = /\\p{Script=Han}/;'), true);
});

test('源码改写：没有可改字面量时逐字节原样返回（字符串/注释不误伤）', () => {
  const source = [
    "const a = '\\\\p{Script=Han}'; // \\p{Script=Han} comment",
    'const b = `x ${1 + 1} y`;',
  ].join('\n');
  assert.equal(rewriteNode18Source(source), source);
});

test('源码改写：strict 模式对未知属性名按 V8 原话抛错（不伪造成功）', () => {
  const source = 'const r = /\\p{Script=NoSuchScript}/u;';
  assert.equal(rewriteNode18Source(source), source, '放宽模式原样保留，交给真实运行时报');
  assert.throws(() => rewriteNode18Source(source, { strict: true }), (error) => {
    assert.match(String(error.message), /Invalid property name in character class/);
    return true;
  });
});

test('缺失具名导出：本运行时缺 styleText / crc32 时换 data: 垫片，原生有则不动', () => {
  const major = Number(process.versions.node.split('.')[0]);
  const minor = Number(process.versions.node.split('.')[1]);
  const cases = [
    { source: 'import { styleText } from "node:util";', native: major > 20 || (major === 20 && minor >= 12) },
    { source: 'import { crc32, gunzipSync } from "node:zlib";', native: major > 20 || (major === 20 && minor >= 15) },
  ];
  for (const item of cases) {
    const out = rewriteMissingNamedImports(item.source);
    if (item.native) assert.equal(out, item.source, '原生有就不改写');
    else assert.match(out, /data:text\/javascript/);
  }
});

test('缺失具名导出：与缺失名字无关的 import 语句不动', () => {
  const source = 'import { readFile } from "node:fs/promises";\nimport { gzipSync } from "node:zlib";';
  assert.equal(rewriteMissingNamedImports(source), source);
});

test('临时目录：TMPDIR 优先且三变量一致，绝不落到 /tmp', () => {
  const home = makeHome('dsh-temp-home-');
  const previous = { TMPDIR: process.env.TMPDIR, TMP: process.env.TMP, TEMP: process.env.TEMP };
  try {
    process.env.TMPDIR = home;
    process.env.TMP = '/nonexistent-tmp-dir';
    process.env.TEMP = '/tmp';
    assert.equal(ensureWritableTemp(home), home);
    assert.equal(process.env.TMPDIR, home);
    assert.equal(process.env.TMP, home);
    assert.equal(process.env.TEMP, home);
  } finally {
    for (const key of Object.keys(previous)) {
      if (previous[key] === undefined) delete process.env[key];
      else process.env[key] = previous[key];
    }
    rmSync(home, { recursive: true, force: true });
  }
});

test('临时目录：候选都不可写时落到 home/tmp 并建出来', () => {
  const home = makeHome('dsh-temp-home2-');
  const previous = { TMPDIR: process.env.TMPDIR, TMP: process.env.TMP, TEMP: process.env.TEMP };
  try {
    process.env.TMPDIR = '/nonexistent-a';
    process.env.TMP = '/nonexistent-b';
    process.env.TEMP = '/nonexistent-c';
    assert.equal(ensureWritableTemp(home), join(home, 'tmp'));
    assert.equal(existsSync(join(home, 'tmp')), true);
  } finally {
    for (const key of Object.keys(previous)) {
      if (previous[key] === undefined) delete process.env[key];
      else process.env[key] = previous[key];
    }
    rmSync(home, { recursive: true, force: true });
  }
});
