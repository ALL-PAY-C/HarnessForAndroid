#!/usr/bin/env node
// 取内置网关的 CLIProxyAPI 静态二进制，落到 app/src/main/jniLibs/<abi>/libgateway.so。
// 为什么不入库：解压后 arm64 66.8MB / x86_64 73.6MB，超过仓库 .gitignore 的 50MB 二进制红线。
// 用法：node tools/fetch-gateway.mjs [--abi arm64-v8a|x86_64|all]（默认 all）
// 需要代理时先设 HTTPS_PROXY，脚本用 undici ProxyAgent 转发。
import { createWriteStream } from 'node:fs';
import { mkdir, rm, chmod, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';

const run = promisify(execFile);
const VERSION = '8.0.51';
const BASE = `https://github.com/router-for-me/CLIProxyAPI/releases/download/v${VERSION}`;
const TARGETS = {
  'arm64-v8a': { asset: `CLIProxyAPI_${VERSION}_linux_aarch64_no-plugin.tar.gz`, sha256: 'faa73498dd049ebdbc8cbc57fd827bb626480f38f2370a8c1d50c24e22097943' },
  'x86_64': { asset: `CLIProxyAPI_${VERSION}_linux_amd64_no-plugin.tar.gz`, sha256: '95f9ba083441828c55850f8ba8ae954e8281f29e8081866afeda1f44e455ae40' },
};
const root = path.resolve(import.meta.dirname, '..');
const tmp = path.join(root, 'build', 'gateway-fetch');

async function download(url, dest) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`下载失败 ${res.status} ${url}`);
  await pipeline(res.body, createWriteStream(dest));
}

async function sha256(file) {
  const h = createHash('sha256');
  h.update(await readFile(file));
  return h.digest('hex');
}

const arg = process.argv.indexOf('--abi');
const want = arg > 0 ? process.argv[arg + 1] : 'all';
const abis = want === 'all' ? Object.keys(TARGETS) : [want];
await mkdir(tmp, { recursive: true });

for (const abi of abis) {
  const t = TARGETS[abi];
  const tgz = path.join(tmp, t.asset);
  const outDir = path.join(root, 'app', 'src', 'main', 'jniLibs', abi);
  const out = path.join(outDir, 'libgateway.so');
  console.log(`[${abi}] 下载 ${t.asset}`);
  await download(`${BASE}/${t.asset}`, tgz);
  await mkdir(outDir, { recursive: true });
  await run('tar', ['-xzf', tgz, '-C', tmp]);
  const bin = path.join(tmp, 'cli-proxy-api');
  await run('mv', [bin, out]).catch(async () => {
    await run('cp', [bin, out]);
  });
  await chmod(out, 0o755);
  const got = await sha256(out);
  if (got !== t.sha256) throw new Error(`[${abi}] sha256 不符：${got} != ${t.sha256}`);
  console.log(`[${abi}] ok ${out} ${got.slice(0, 16)}…`);
}
await rm(tmp, { recursive: true, force: true });
console.log('done');
