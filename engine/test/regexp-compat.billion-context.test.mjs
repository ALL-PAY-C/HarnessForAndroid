// T5 回归锁：billion-context@0.1.206 dist/agent/dsh-native.js 里 4 处 new RegExp 的真实 pattern。
//
// 真机 nodejs-mobile v18.20.4（v8 10.2.154.26-node.37）没有 ICU：process.versions.icu === null、typeof Intl === 'undefined'，
// 因此 V8 拒绝 Unicode 属性转义，new RegExp('^\\p{So}(?:[ \\t])?\\[ACP\\]', 'u') 抛
//   SyntaxError: Invalid regular expression: /^\p{So}(?:[ \t])?\[ACP\]/: Invalid property name
// 设备原始证据：.mock/t5-three-plugins/logs/probe-report.json（raw.withU.ok=false、shimmed.ok=true、regExpPatched=true）。
// 本机 node18.20.4 自带 ICU 74.2，会正常编译同一 pattern，所以"无 ICU 构建"的判据只能用 noIcuRegExp() 复刻。
import test from 'node:test';
import assert from 'node:assert/strict';
import { installRegExpCompat } from '../host/loader.js';

const PROPERTY_ESCAPE = /\\[pP]\{/;

/** 复刻无 ICU 的 V8：pattern 里出现属性转义就是 Invalid property name。 */
function noIcuRegExp(pattern, flags) {
  if (typeof pattern === 'string' && PROPERTY_ESCAPE.test(pattern)) {
    throw new SyntaxError('Invalid regular expression: /' + pattern + '/: Invalid property name');
  }
  return new RegExp(pattern, flags);
}

// dsh-native.js 字节偏移：2305374 MARKER_HEAD / 2305437 MARKER_LINE / 2308500 STATUS_MARKER_HEAD / 2310035 STATUS_HEAD
const CASES = [
  { id: 'MARKER_HEAD', pattern: '^\\p{So}(?:[ \\t])?\\[ACP\\]', flags: 'u', hit: '\u{1F4E6} [ACP] tail', miss: 'x [ACP] tail' },
  { id: 'MARKER_LINE', pattern: '^\\p{So}(?:[ \\t])?\\[ACP\\][^\\n]*\\n?', flags: 'gmu', hit: '\u{2705} [ACP] line one\n', miss: 'plain [ACP] line\n' },
  { id: 'STATUS_MARKER_HEAD', pattern: '^\\p{So}(?:[ \\t])?\\[ACP\\] acp_status result:', flags: 'u', hit: '\u{1F4CA} [ACP] acp_status result:', miss: 'x [ACP] acp_status result:' },
  { id: 'STATUS_HEAD', pattern: '^\\p{So}(?:[ \\t])?\\[ACP\\][^\\n]*acp_status[^\\n]*\\n?', flags: 'gmu', hit: '\u{1F4CA} [ACP] hi acp_status x\n', miss: 'plain [ACP] hi acp_status x\n' },
];

const flagsOf = (flags) => new Set(flags.split(''));

for (const item of CASES) {
  test(item.id + ' 原始 pattern 在无 ICU 构建上编译失败（复现 T5 报错）', () => {
    assert.throws(() => noIcuRegExp(item.pattern, item.flags), /Invalid property name/);
  });

  test(item.id + ' 经 loader 垫片后可在无 ICU 构建上编译并正确匹配', () => {
    const re = new RegExp(item.pattern, item.flags);
    assert.ok(!PROPERTY_ESCAPE.test(re.source), 'source 里不能残留 \\p{：' + re.source.slice(0, 60));
    for (const flag of flagsOf(item.flags)) assert.ok(re.flags.includes(flag), 'flags 丢了 ' + flag + '：' + re.flags);
    assert.ok(re.flags.includes('u'), '改写后需要 u 让 \\u{...} 生效：' + re.flags);
    const compiled = noIcuRegExp(re.source, re.flags);
    assert.equal(compiled.test(item.hit), true, item.id + ' 应命中 ' + JSON.stringify(item.hit));
    assert.equal(compiled.test(item.miss), false, item.id + ' 不应命中非 So 字符');
  });
}

test('垫片装上后 dsh-native 的 4 个 pattern 全部走改写（无一处残留属性转义）', () => {
  assert.equal(installRegExpCompat(), undefined);
  for (const item of CASES) {
    const re = new RegExp(item.pattern, item.flags);
    assert.ok(!PROPERTY_ESCAPE.test(re.source), item.id);
  }
});
