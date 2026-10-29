// 桌面端依赖收口：市场插件 import 的 @deepseek-ai/* 与裸名（schemastery / semver）必须真的解析得到。
// 用法: node test/desktop-deps.test.mjs  或  node --test test/desktop-deps.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installModuleFallback } from '../host/loader.js';

const engineRoot = dirname(dirname(fileURLToPath(import.meta.url)));

/** 包名 -> 至少要有这些导出（loader 解析不到就会抛 ERR_MODULE_NOT_FOUND）。 */
const PACKAGES = [
  { name: '@deepseek-ai/dsh-tools', exports: ['RUN_CODE_NAME'] },
  { name: '@deepseek-ai/dsh-credentials', exports: ['CredentialProvider'] },
  { name: '@deepseek-ai/dsh-settings', exports: ['SettingsProvider'] },
  { name: '@deepseek-ai/dsh-plan-mode', exports: ['PlanModeController'] },
  { name: '@deepseek-ai/dsh-atomic-write', exports: ['writeFileAtomic'] },
  { name: '@deepseek-ai/dsh-launch-environment', exports: ['createLaunchEnvironmentSnapshot'] },
  { name: '@deepseek-ai/dsh-user-questions', exports: ['UserQuestionService'] },
  { name: 'semver', exports: ['valid', 'SemVer'] },
];

function versionOf(name) {
  return JSON.parse(readFileSync(join(engineRoot, 'node_modules', ...name.split('/'), 'package.json'), 'utf8')).version;
}

for (const pkg of PACKAGES) {
  test(pkg.name + ' 可 import 且导出齐全', async () => {
    const loaded = await import(pkg.name);
    for (const key of pkg.exports) {
      assert.equal(typeof loaded[key] !== 'undefined', true, pkg.name + ' 缺少导出 ' + key);
    }
    console.log(pkg.name + '@' + versionOf(pkg.name) + '  exports=' + Object.keys(loaded).length);
  });
}

test('裸名别名：schemastery / semver 指向真实包', async () => {
  installModuleFallback();
  const z = await import('schemastery');
  assert.equal(typeof (z.default ?? z), 'function', 'schemastery 默认导出应是函数');
  const semver = await import('semver');
  assert.equal(semver.valid('1.2.3'), '1.2.3');
  console.log('schemastery -> @deepseek-ai/schemastery@' + versionOf('@deepseek-ai/schemastery'));
  console.log('semver -> semver@' + versionOf('semver'));
});

test('别名不吞错：不存在的裸名仍报原始错误', async () => {
  installModuleFallback();
  await assert.rejects(import('dsh-not-a-real-package'), /Cannot find package|ERR_MODULE_NOT_FOUND/);
});
