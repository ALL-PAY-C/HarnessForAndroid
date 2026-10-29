// 任务 E 的 E2E：用真引擎上下文装载 .tmp/market 里解包的两个市场插件，
// 验证 dshLoader 门面（.dsh/.llm/.settings）与 credentials.resolve 足以让它们 apply 成功。
// 手动跑：node test/e2e-dshloader-market.mjs（不进 node --test 回归集）
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createContext } from '../host/cordis.js';
import { loadPlugin } from '../host/loader.js';

// 解包位置可换：npm tarball 解到哪里就指哪里（默认 D:/DSH/.tmp/market2）。
const MARKET = process.env.DSH_E2E_MARKET ?? 'D:/DSH/.tmp/market2';
const PLUGINS = [
  ['dsh-auxiliary 0.6.4', join(MARKET, 'auxpkg/package')],
  ['ollama-cloud 0.2.1', join(MARKET, 'ollama/package')],
];

const home = await mkdtemp(join(tmpdir(), 'dsh-e2e-market-'));
const ctx = createContext({ home });
let failed = 0;
try {
  for (const [label, dir] of PLUGINS) {
    const started = Date.now();
    try {
      const loaded = await loadPlugin(ctx, dir, {});
      console.log('APPLY OK   ' + label + ' → ' + loaded.package.name + '@' + loaded.package.version + ' name=' + loaded.name + ' export=' + loaded.exports.join(',') + ' ' + (Date.now() - started) + 'ms');
    } catch (error) {
      failed += 1;
      console.log('APPLY FAIL ' + label + ' → stage=' + (error?.stage ?? '?') + ' message=' + String(error?.message ?? error).split('\n')[0]);
      if (error?.diagnostics) console.log('           diagnostics=' + JSON.stringify(error.diagnostics).slice(0, 400));
    }
  }
} finally {
  await ctx.dispose();
}
process.exitCode = failed === 0 ? 0 : 1;
