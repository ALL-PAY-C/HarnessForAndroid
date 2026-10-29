#!/usr/bin/env bash
# 从本机官方 DSH checkout 里抽出「最小可运行内核 bundle」，打包成可校验的 tar.gz。
#
# 来源：asar 内的 node_modules/@deepseek-ai/dsh —— 官方 bin 入口 lib/bin.js。
# 取官方 headless 最小闭包，并固定 linux-x64-musl 原生加载器；
# 不取 Electron UI、libreoffice-kit、Win32 sharp/ripgrep。
#
# 用法：scripts/fetch-dsh-kernel.sh [asar路径] [--abi x86_64|arm64-v8a]
# arm64 从已验证的 x64 内核替换 Linux 原生插件；Windows 变体与其余文件不变。
# 产物：linux-runtime/src/main/assets/dsh-kernel/dsh-kernel-<version>.tgz
# 后缀用 .tgz 而不是 .tar.gz：aapt2 打包时会自动 gunzip 掉 .gz 资产并去掉后缀，APK 里就变成裸 tar。
#       linux-runtime/src/main/assets/dsh-kernel/kernel-manifest.json
# 幂等：重复运行产出字节一致（tar 内 mtime/mode/uid 固定，gzip 不带时间戳）。
set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASAR=""
ABI="x86_64"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --abi) ABI="${2:?--abi requires a value}"; shift 2 ;;
    --abi=*) ABI="${1#*=}"; shift ;;
    x64|x86_64|arm64|arm64-v8a) ABI="$1"; shift ;;
    *) if [ -n "$ASAR" ]; then echo "unexpected argument: $1" >&2; exit 2; fi; ASAR="$1"; shift ;;
  esac
done
case "$ABI" in
  x64|x86_64) ABI="x86_64" ;;
  arm64|arm64-v8a) ABI="arm64-v8a" ;;
  *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac
if [ -z "$ASAR" ]; then echo "set ASAR to the dsh app.asar (official @deepseek-ai/dsh 0.2.1-rc.2) first" >&2; exit 2; fi
OUT="$ROOT/linux-runtime/src/main/assets/dsh-kernel"
STAGE="$ROOT/build/dsh-kernel-stage"

# arm64 reuses the validated headless closure rather than executing foreign ELF on the host.
if [ "$ABI" = "arm64-v8a" ]; then
  ARM_WORK="$ROOT/.mock/agent-env/t6/node22"
  mkdir -p "$ARM_WORK" "$OUT/node"
  NODE_SOURCE="https://unofficial-builds.nodejs.org/download/release/v22.23.4/node-v22.23.4-linux-arm64-musl.tar.xz"
  ADDON_SOURCE="https://registry.npmjs.org/node-addon-require-builtin-linux-arm64-musl/-/node-addon-require-builtin-linux-arm64-musl-0.1.9.tgz"
  if [ ! -f "$ARM_WORK/node-v22.23.4-linux-arm64-musl.tar.xz" ]; then
    idmget "$NODE_SOURCE" -Out "$ARM_WORK" -Wait
  fi
  if [ ! -f "$ARM_WORK/node-addon-require-builtin-linux-arm64-musl-0.1.9.tgz" ]; then
    idmget "$ADDON_SOURCE" -Out "$ARM_WORK" -Wait
  fi
  python - "$OUT" "$ARM_WORK" <<'PY'
import hashlib, json, pathlib, struct, sys, tarfile
out, work = map(pathlib.Path, sys.argv[1:])
manifest = json.loads((out / 'kernel-manifest.json').read_text(encoding='utf-8'))
base = out / manifest['archive']
assert hashlib.sha256(base.read_bytes()).hexdigest() == manifest['sha256']
assert base.stat().st_size == manifest['bytes']
addon = work / 'node-addon-require-builtin-linux-arm64-musl-0.1.9.tgz'
assert hashlib.sha256(addon.read_bytes()).hexdigest() == '9b5491ba17aaa0397b02da610ba92c9f6b81a080344aad00f53db3ad71eedc40'

def elf(data):
    assert data[:4] == b'\x7fELF'
    assert struct.unpack('<H' if data[5] == 1 else '>H', data[18:20])[0] == 183

def pack(path, files, dirs, owner):
    for name in files:
        parent = pathlib.PurePosixPath(name).parent
        while str(parent) != '.':
            dirs.add(str(parent)); parent = parent.parent
    with path.open('wb') as f:
        for name, data, isdir in ([(n + '/', b'', True) for n in sorted(dirs)] + [(n, files[n], False) for n in sorted(files)]):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE if isdir else tarfile.REGTYPE
            info.mode = 0o755 if isdir else 0o644
            info.mtime = 1700000000
            info.uid = info.gid = 0
            info.uname = info.gname = owner
            info.size = len(data)
            f.write(info.tobuf(format=tarfile.USTAR_FORMAT))
            f.write(data)
            f.write(b'\0' * (-len(data) % 512))
        f.write(b'\0' * 1024)

old = 'node_modules/node-addon-require-builtin-linux-x64-musl'
new = 'node_modules/node-addon-require-builtin-linux-arm64-musl'
files, dirs = {}, set()
with tarfile.open(base) as tar:
    for member in tar:
        name = member.name.rstrip('/')
        if name == old or name.startswith(old + '/'):
            continue
        if member.isdir(): dirs.add(name)
        elif member.isfile(): files[name] = tar.extractfile(member).read()
        else: raise AssertionError('unexpected kernel member: ' + name)
with tarfile.open(addon) as tar:
    for member in tar:
        relative = member.name.removeprefix('package/').rstrip('/')
        assert relative != member.name and '..' not in pathlib.PurePosixPath(relative).parts
        name = new + '/' + relative
        if member.isdir(): dirs.add(name)
        elif member.isfile():
            data = tar.extractfile(member).read()
            if name.endswith('.node'): elf(data)
            files[name] = data
        else: raise AssertionError('unexpected addon member: ' + name)
pack(work / 'kernel-arm64.tar', files, dirs, 'root')

strip = 'node-v22.23.4-linux-arm64-musl'
source = work / (strip + '.tar.xz')
assert source.stat().st_size == 32558868
stage = work / 'stage-arm64'
files, dirs = {}, set()
with tarfile.open(source) as tar:
    for member in tar:
        name = member.name.rstrip('/')
        assert name == strip or name.startswith(strip + '/')
        assert '..' not in pathlib.PurePosixPath(name).parts
        if name == strip + '/include' or name.startswith(strip + '/include/') or name == strip + '/CHANGELOG.md': continue
        if member.isdir():
            dirs.add(name); (stage / name).mkdir(parents=True, exist_ok=True)
        elif member.isfile() or member.issym() or member.islnk():
            data = tar.extractfile(member).read()
            if name == strip + '/bin/node': elf(data)
            files[name] = data
            dest = stage / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
        else: raise AssertionError('unexpected Node member: ' + name)
pack(work / 'node-arm64.tar', files, dirs, '')
PY
  VERSION="$(python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "$OUT/kernel-manifest.json")"
  gzip -9 -n -c "$ARM_WORK/kernel-arm64.tar" > "$OUT/dsh-kernel-$VERSION-arm64.tgz"
  gzip -9 -n -c "$ARM_WORK/node-arm64.tar" > "$OUT/node/node-v22.23.4-linux-arm64-musl.tgz"
  python - "$OUT" <<'PY'
import hashlib, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
p = out / 'kernel-manifest.json'
m = json.loads(p.read_text(encoding='utf-8'))
def entry(abi, archive, strip=None):
    data = (out / archive).read_bytes()
    value = dict(abi=abi, archive=archive, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
    if strip: value['strip'] = strip
    return value
m['archives'] = [entry('x86_64', m['archive']), entry('arm64-v8a', 'dsh-kernel-' + m['version'] + '-arm64.tgz')]
m['nodeArchives'] = [entry('x86_64', m['nodeArchive'], m['nodeStrip']), entry('arm64-v8a', 'node/node-v22.23.4-linux-arm64-musl.tgz', 'node-v22.23.4-linux-arm64-musl')]
p.write_text(json.dumps(m, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
for value in m['archives'] + m['nodeArchives']: print(json.dumps(value))
PY
  echo "ok: $OUT ($ABI)"
  exit 0
fi

if [ ! -f "$ASAR" ]; then echo "asar not found: $ASAR" >&2; exit 1; fi
mkdir -p "$OUT" "$ROOT/build/kernel-native"
# Official npm musl optional dependency absent from the Windows ASAR.
NATIVE="$ROOT/scripts/artifacts/node-addon-require-builtin-linux-x64-musl-0.1.9.tgz"
echo "834a8371d143bd31c8169737271b013480f5c875afa483d4e5ba32a5553c2921  $NATIVE" | sha256sum -c -
tar -xzf "$NATIVE" -C "$ROOT/build/kernel-native"

ASAR="$ASAR" STAGE="$STAGE" OUT="$OUT" node --input-type=commonjs <<'NODE'
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const { execFileSync } = require('child_process');

const ASAR = process.env.ASAR;
const UNPACKED = ASAR + '.unpacked';
const STAGE = process.env.STAGE;
const OUT = process.env.OUT;

// ---- 1. 打开 asar -----------------------------------------------------------
const fd = fs.openSync(ASAR, 'r');
const head = Buffer.alloc(16);
fs.readSync(fd, head, 0, 16, 0);
const headerBlock = head.readUInt32LE(4);
const jsonLen = head.readUInt32LE(12);
const jsonBuf = Buffer.alloc(jsonLen);
fs.readSync(fd, jsonBuf, 0, jsonLen, 16);
const header = JSON.parse(jsonBuf.toString('utf8'));
const dataOffset = 8 + headerBlock;
const readNode = (n) => {
  if (n.local) return fs.readFileSync(n.local);
  const d = Buffer.alloc(n.size);
  fs.readSync(fd, d, 0, n.size, dataOffset + Number(n.offset));
  return d;
};

const nm = header.files['node_modules'];
const pkgs = new Map();
const collect = (node, prefix, acc) => {
  for (const [k, v] of Object.entries(node.files || {})) {
    if (v.files) collect(v, prefix + k + '/', acc);
    else acc.push([prefix + k, v.size, v]);
  }
  return acc;
};
for (const [name, node] of Object.entries(nm.files)) {
  if (!node.files) continue;
  if (name.startsWith('@')) {
    for (const [sub, sn] of Object.entries(node.files)) {
      if (sn.files) pkgs.set(name + '/' + sub, { node: sn, files: collect(sn, '', []) });
    }
  } else pkgs.set(name, { node, files: collect(node, '', []) });
}
const nativeDir = path.join(process.cwd(), 'build/kernel-native/package');
const nativeFiles = [];
function readNative(dir, prefix = '') {
  for (const name of fs.readdirSync(dir).sort()) {
    const file = path.join(dir, name), st = fs.statSync(file);
    if (st.isDirectory()) readNative(file, prefix + name + '/');
    else nativeFiles.push([prefix + name, st.size, {local:file}]);
  }
}
readNative(nativeDir);
const nativeNode = {files:{'package.json':{local:path.join(nativeDir, 'package.json')}}};
pkgs.set('node-addon-require-builtin-linux-x64-musl', {node:nativeNode,files:nativeFiles});
const pkgJson = (name) => {
  const e = pkgs.get(name);
  if (!e) return null;
  const pj = e.node.files['package.json'];
  if (!pj || pj.files) return null;
  try { return JSON.parse(readNode(pj).toString('utf8')); } catch { return null; }
};

// ---- 2. launcher 闭包 -------------------------------------------------------
// @deepseek-ai/dsh 自己的 dependencies 是 profile 插件清单（整包 151MB），
// 那是 T7 运行时安装的内容，不在这里展开。
const seeds = ['@deepseek-ai/dsh', '@deepseek-ai/dsh-app-boot', '@deepseek-ai/dsh-home-paths', '@deepseek-ai/dsh-http-proxy', '@deepseek-ai/dsh-headless', '@deepseek-ai/dsh-agent-loop', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-system-prompt', '@deepseek-ai/dsh-agent-default-model', '@deepseek-ai/dsh-llm-deepseek-api-key', 'node-addon-require-builtin-linux-x64-musl', 'commander'];
// Lazy profile/headless imports are runtime requirements, not optional profile plugins.
for (const [rel, , node] of pkgs.get('@deepseek-ai/dsh').files) {
  if (!rel.endsWith('.js')) continue;
  const source = readNode(node).toString('utf8');
  for (const match of source.matchAll(/(?:from\s*|import\s*\()\s*["'](@deepseek-ai\/[^"']+)["']/g)) {
    const name = match[1].split('/').slice(0, 2).join('/');
    if (!seeds.includes(name)) seeds.push(name);
  }
}
const NO_EXPAND = new Set(['@deepseek-ai/dsh']);

function closure(seedList) {
  const seen = new Set();
  const stack = [...seedList];
  while (stack.length) {
    const n = stack.pop();
    if (seen.has(n)) continue;
    const e = pkgs.get(n);
    if (!e) continue;
    seen.add(n);
    if (NO_EXPAND.has(n)) continue;
    const j = pkgJson(n);
    if (!j) continue;
    const deps = [].concat(Object.keys(j.dependencies || {}), Object.keys(j.peerDependencies || {}), Object.keys(j.optionalDependencies || {}));
    for (const d of deps) if (pkgs.has(d)) stack.push(d);
  }
  return seen;
}

function materialize(seen) {
  fs.rmSync(STAGE, { recursive: true, force: true });
  fs.mkdirSync(STAGE, { recursive: true });
  let bytes = 0, files = 0;
  for (const name of seen) {
    for (const [rel, size, node] of pkgs.get(name).files) {
      const dest = path.join(STAGE, 'node_modules', name, rel);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      if (node.unpacked) {
        const src = path.join(UNPACKED, 'node_modules', name, rel);
        if (fs.existsSync(src)) fs.copyFileSync(src, dest);
      } else fs.writeFileSync(dest, readNode(node));
      bytes += size; files++;
    }
  }
  return { bytes, files };
}

// 静态闭包会漏掉运行时才 import 的包（cordis 的 cosmokit 就是），
// 所以真跑一次 --version，按报错补种子，直到跑通。
const dshPkg = pkgJson('@deepseek-ai/dsh');
// engines.node 的下限写在桌面根 package.json 上（@deepseek-ai/dsh 自己没有 engines）。
const rootPkg = (() => {
  const n = header.files['package.json'];
  try { return n && !n.files ? JSON.parse(readNode(n).toString('utf8')) : {}; } catch { return {}; }
})();
const rootEngines = (rootPkg.engines && rootPkg.engines.node) || '';
const VERSION = dshPkg.version;
const ENTRY = 'node_modules/@deepseek-ai/dsh/lib/bin.js';
let seen, stats, stdout = '';
for (let i = 0; i < 30; i++) {
  seen = closure(seeds);
  stats = materialize(seen);
  try {
    stdout = execFileSync(process.execPath, [path.join(STAGE, ENTRY), '--version'], {
      cwd: STAGE, encoding: 'utf8', timeout: 120000, stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
    break;
  } catch (e) {
    const text = (e.stdout || '') + (e.stderr || '');
    const m = text.match(/Cannot find (?:package|module) '([^']+)'/);
    if (!m || seeds.indexOf(m[1]) >= 0) {
      console.error(text);
      throw new Error('kernel --version failed, unresolvable missing package: ' + (m ? m[1] : 'n/a'));
    }
    console.error('missing at runtime, adding seed: ' + m[1]);
    seeds.push(m[1]);
  }
}
if (stdout !== VERSION) throw new Error('version mismatch: bin.js printed "' + stdout + '" expected "' + VERSION + '"');
const profileDir = path.join(STAGE, 'profiles/headless');
fs.mkdirSync(profileDir, {recursive: true});
fs.copyFileSync(path.join(process.cwd(), 'scripts/headless-profile.yml'), path.join(profileDir, 'cordis.patch.yml'));
fs.writeFileSync(path.join(profileDir, 'package.json'), JSON.stringify({private:true,dsh:{profile:{bundles:[]}}}) + '\n');
execFileSync(process.execPath, [path.join(process.cwd(), 'scripts/verify-kernel-session.cjs'), STAGE], {cwd:process.cwd(),stdio:'inherit',timeout:70000});
fs.rmSync(path.join(STAGE, '.anonymous-user-id'), {force:true});
fs.rmSync(path.join(profileDir, 'cordis.yml'), {force:true});
console.log('kernel version: ' + VERSION);
console.log('packages: ' + seen.size + ' files: ' + stats.files + ' bytes: ' + stats.bytes);

// ---- 3. 确定性 ustar（LinuxTar 只读 name 字段，路径必须 <=100 字节）----------
const files = [];
const dirs = new Set();
const walk = (dir, prefix) => {
  for (const name of fs.readdirSync(dir).sort()) {
    const full = path.join(dir, name);
    const rel = prefix + name;
    const st = fs.statSync(full);
    if (st.isDirectory()) { dirs.add(rel); walk(full, rel + '/'); }
    else if (st.isFile()) files.push([rel, fs.readFileSync(full)]);
  }
};
walk(STAGE, '');
function splitName(name) {
  if (Buffer.byteLength(name) <= 100) return ['', name];
  for (let at = name.lastIndexOf('/'); at > 0; at = name.lastIndexOf('/', at - 1)) {
    const prefix = name.slice(0, at), leaf = name.slice(at + 1);
    if (Buffer.byteLength(prefix) <= 155 && Buffer.byteLength(leaf) <= 100 && leaf) return [prefix, leaf];
  }
  throw new Error('path exceeds ustar prefix/name capacity: ' + name);
}
for (const [rel] of files) splitName(rel);
for (const rel of dirs) if (Buffer.byteLength(rel) > 100) throw new Error('dir too long for ustar name field: ' + rel);

const MTIME = 1700000000;
const oct = (v, len) => v.toString(8).padStart(len - 1, '0') + '\0';
const hdr = (name, size, type) => {
  const b = Buffer.alloc(512, 0);
  const [prefix, leaf] = splitName(name);
  b.write(leaf, 0, 100, 'utf8');
  b.write(prefix, 345, 155, 'utf8');
  b.write(oct(type === '5' ? 0o755 : 0o644, 8), 100, 'ascii');
  b.write(oct(0, 8), 108, 'ascii');
  b.write(oct(0, 8), 116, 'ascii');
  b.write(oct(size, 12), 124, 'ascii');
  b.write(oct(MTIME, 12), 136, 'ascii');
  b.write('        ', 148, 'ascii');
  b.write(type, 156, 'ascii');
  b.write('ustar', 257, 'ascii');
  b.write('00', 263, 'ascii');
  b.write('root', 265, 'ascii');
  b.write('root', 297, 'ascii');
  let sum = 0;
  for (let i = 0; i < 512; i++) sum += b[i];
  b.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 'ascii');
  return b;
};
const parts = [];
for (const rel of [...dirs].sort()) parts.push(hdr(rel + '/', 0, '5'));
for (const [rel, data] of files.sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
  parts.push(hdr(rel, data.length, '0'), data);
  const pad = (512 - (data.length % 512)) % 512;
  if (pad) parts.push(Buffer.alloc(pad, 0));
}
parts.push(Buffer.alloc(1024, 0));
const tarGz = zlib.gzipSync(Buffer.concat(parts), { level: 9 });

const archive = 'dsh-kernel-' + VERSION + '.tgz';
fs.writeFileSync(path.join(OUT, archive), tarGz);
const sha256 = require('crypto').createHash('sha256').update(tarGz).digest('hex');

// ---- 4. manifest ------------------------------------------------------------
const manifestPath = path.join(OUT, 'kernel-manifest.json');
const previousManifest = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, 'utf8')) : {};
const manifest = {
  ...previousManifest,
  version: VERSION,
  entry: ENTRY,
  archive: archive,
  sha256: sha256,
  bytes: tarGz.length,
  files: stats.files,
  packages: seen.size,
  packageNames: [...seen].sort(),
  sessionGate: "official headless assistant T11_KERNEL_SESSION_42 RC=0",
  nativePackage: {name:"node-addon-require-builtin-linux-x64-musl",version:"0.1.9",archiveBytes:78191,sha256:"834a8371d143bd31c8169737271b013480f5c875afa483d4e5ba32a5553c2921",unpackedBytes:301756,source:"https://registry.npmjs.org/node-addon-require-builtin-linux-x64-musl/-/node-addon-require-builtin-linux-x64-musl-0.1.9.tgz"},
  unpackedBytes: stats.bytes,
  nodeEngine: rootEngines,
  license: dshPkg.license || '',
  source: ASAR,
  sourcePackage: dshPkg.name + '@' + VERSION,
  note: 'minimal official headless runtime; bundled profiles/headless, DSH_HOME=/root/dsh; no Electron/native desktop bundles'
};
manifest.archives = [{abi:'x86_64', archive, sha256, bytes:tarGz.length}, ...(previousManifest.archives || []).filter(item => item.abi !== 'x86_64')];
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log('archive: ' + archive + ' bytes: ' + tarGz.length + ' sha256: ' + sha256);
NODE

# ---- 5. 静态 Node 运行时（官方 musl 构建）----------------------------------
# 不用 apk nodejs：Alpine 3.20 只有 nodejs 20，混 3.22 源会把 icu-libs 升到 76，
# 而 node 20 链接的是 ICU74，直接起不来（T6 踩过，rootfs 已回滚重建）。
#
# 归档由官方 .tar.xz 剥掉 include/（58.9MB 头文件）与 CHANGELOG.md 后确定性重打包：
#   tar -xf node-v22.23.4-linux-x64-musl.tar.xz
#   rm -rf node-v22.23.4-linux-x64-musl/include node-v22.23.4-linux-x64-musl/CHANGELOG.md
#   tar -C <stage> -c --format=posix --mtime=@1700000000 --owner=0 --group=0 --numeric-owner \
#       node-v22.23.4-linux-x64-musl | gzip -9 -n > node-v22.23.4-linux-x64-musl.tgz
# 解包由 KernelTar 完成（支持 pax/GNU 扩展头，LinuxTar 只认 ustar name 会截断）。
NODE_TGZ="${NODE_TGZ:-$ROOT/.mock/agent-env/t6/node22/node-v22.23.4-linux-x64-musl.tgz}"
if [ ! -f "$NODE_TGZ" ]; then
  echo "node runtime archive missing: $NODE_TGZ" >&2
  echo "先下载官方构建（idmget 走本机 IDM）：" >&2
  echo "  idmget https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-x64-musl.tar.xz -Out <staging-dir> -Wait" >&2
  echo "再按脚本注释里的 tar 命令剥 include/ 重打包，然后重跑本脚本。" >&2
  exit 1
fi
mkdir -p "$OUT/node"
NODE_NAME="$(basename "$NODE_TGZ")"
cp -f "$NODE_TGZ" "$OUT/node/$NODE_NAME"
NODE_TGZ="$OUT/node/$NODE_NAME" MANIFEST="$OUT/kernel-manifest.json" node --input-type=commonjs <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const tgz = fs.readFileSync(process.env.NODE_TGZ);
const manifestPath = process.env.MANIFEST;
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const name = path.basename(process.env.NODE_TGZ);
const m = name.match(/^node-v(\d+\.\d+\.\d+)-linux-(\w+)-musl\.tgz$/);
if (!m) throw new Error('unexpected node archive name: ' + name);
manifest.nodeVersion = m[1];
manifest.nodeArchive = 'node/' + name;
manifest.nodeSha256 = crypto.createHash('sha256').update(tgz).digest('hex');
manifest.nodeBytes = tgz.length;
manifest.nodeStrip = 'node-v' + m[1] + '-linux-' + m[2] + '-musl';
manifest.nodeSource = 'https://nodejs.org/dist/v' + m[1] + '/' + manifest.nodeStrip + '.tar.xz';
manifest.nodeArchives = [{abi:'x86_64', archive:manifest.nodeArchive, sha256:manifest.nodeSha256, bytes:manifest.nodeBytes, strip:manifest.nodeStrip}, ...(manifest.nodeArchives || []).filter(item => item.abi !== 'x86_64')];
manifest.nodeLicense = 'MIT (Node.js)';
manifest.nodeNote = '官方 musl 静态构建，剥掉 include/ 与 CHANGELOG.md 后确定性重打包；解到 /root/node22，PATH 前置，不装系统 nodejs、不动 icu-libs';
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log('node: ' + manifest.nodeVersion + ' bytes: ' + manifest.nodeBytes + ' sha256: ' + manifest.nodeSha256);
NODE

echo "ok: $OUT"
