// T5：Node 18（nodejs-mobile v18.20.4）没有 Unicode 属性转义支持，插件里 new RegExp('\\p{So}...')
// 会抛 "Invalid regular expression: ... Invalid property name"。这里锁住 loader 的垫片不变量：
// 含 \p{ / \P{ 的 pattern 字符串必须被改写成具体 \u{...} 区间（并补 u flag），否则运行期再炸。
// 说明：真机 nodejs-mobile v18.20.4 没有 ICU（icu === null、Intl undefined），V8 因此拒绝属性转义；
// 而本机 node18.20.4 自带 ICU 74.2 能正常编译同一 pattern，所以这里用 node18RegExp() 复刻"无 ICU 构建"的判据
// （pattern 里出现属性转义就是 Invalid property name），让这套断言在任何 Node 版本上都能复现 T5 的原始失败。
// 设备原始证据：.mock/t5-three-plugins/logs/probe-report.json。
import test from 'node:test';
import assert from 'node:assert/strict';
import { installRegExpCompat } from '../host/loader.js';

const PROPERTY_ESCAPE = /\\[pP]\{/;

function node18RegExp(pattern, flags) {
  if (typeof pattern === 'string' && PROPERTY_ESCAPE.test(pattern)) {
    throw new SyntaxError('Invalid regular expression: /' + pattern + '/: Invalid property name');
  }
  return new RegExp(pattern, flags);
}

const ACP_PATTERN = '^\\p{So}(?:[ \\t])?\\[ACP\\]';

test('node18 判据能复现 T5 原始报错（基线）', () => {
  assert.throws(() => node18RegExp(ACP_PATTERN), /Invalid property name/);
});

test('billion-context 的 ACP 正则被改写后 node18 可以编译并正确匹配', () => {
  const re = new RegExp(ACP_PATTERN);
  assert.ok(!PROPERTY_ESCAPE.test(re.source), 'source 里不能残留 \\p{：' + re.source);
  assert.ok(re.flags.includes('u'), '改写后需要 u flag 让 \\u{...} 生效');
  assert.equal(node18RegExp(re.source, re.flags).test('\u{1F4E6} [ACP] extra'), true);
  assert.equal(re.test('\u{1F4E6} [ACP] extra'), true);
  assert.equal(re.test('x [ACP] extra'), false, '非 So 字符不应命中');
});

test('显式 flags 保留并补 u（g/i 不丢）', () => {
  const re = new RegExp('\\p{L}+', 'gi');
  assert.ok(re.flags.includes('g') && re.flags.includes('i') && re.flags.includes('u'), re.flags);
  assert.doesNotThrow(() => node18RegExp(re.source, re.flags));
});

test('\\P{...} 改写成取反字符类', () => {
  const re = new RegExp('^\\P{L}$');
  assert.ok(!PROPERTY_ESCAPE.test(re.source), re.source);
  assert.ok(re.source.includes('[^'), re.source);
  assert.equal(re.test('1'), true);
  assert.equal(re.test('a'), false);
});

test('无属性转义的 pattern 不被改写、不硬塞 u flag', () => {
  const re = new RegExp('a+b');
  assert.equal(re.source, 'a+b');
  assert.equal(re.flags.includes('u'), false);
});

test('垫片是同一份 RegExp：instanceof 与原型链不变', () => {
  const re = new RegExp('x+');
  assert.ok(re instanceof RegExp);
  assert.equal(typeof RegExp.prototype.test, 'function');
  assert.equal(RegExp('y').test('y'), true, '不带 new 的调用形式也要работать');
});

test('重复安装幂等（已装过直接返回）', () => {
  assert.equal(installRegExpCompat(), undefined);
  assert.equal(new RegExp('\\p{So}').test('\u{1F4E6}'), true);
});
