#!/bin/sh
# 下载并校验 rootfs / proot 源码制品（全部走 idmget，不用 curl/wget）。
#
# 用法: sh scripts/fetch-rootfs.sh [x86_64|arm64-v8a|all]
# 落盘: .mock/agent-env/dl/，校验值以 linux-runtime/src/main/assets/linux-artifacts.json 为准。
#
# 工具预置（T5）为什么不在构建期固化：本脚本跑在 Windows 宿主，没有 chroot/proot，
# 无法对 minirootfs 执行 apk add。工具改为运行时预置：LinuxBootstrap.ensure() 在 guest 内
# 跑 apk add --no-cache python3 py3-pip git curl wget ca-certificates，逐包 apk info -e 校验，
# 失败 apk del 回滚并恢复 /etc/apk/world。清单见 assets/linux-artifacts.json 的 tools 段，
# 与 LinuxScripts.REQUIRED_PACKAGES 由 LinuxScriptsTest 防漂移。
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
DL="$REPO_ROOT/.mock/agent-env/dl"
mkdir -p "$DL"

ALPINE_BASE="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases"
PROOT_TARBALL="https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz"
TALLOC_TARBALL="https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz"

# 与 assets/linux-artifacts.json 保持一致
X86_URL="$ALPINE_BASE/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz"
X86_SHA="d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c"
X86_BYTES="3490290"
ARM_URL="$ALPINE_BASE/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
ARM_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"
ARM_BYTES="3947906"

fetch() {
  url="$1"; name="$2"; sha="$3"; bytes="$4"
  dest="$DL/$name"
  if [ -f "$dest" ]; then
    got=$(sha256sum "$dest" | cut -d' ' -f1)
    if [ "$got" = "$sha" ]; then
      echo "已存在且校验通过: $name"
      return 0
    fi
    echo "校验不符，重新下载: $name" >&2
    rm -f "$dest"
  fi
  echo "下载 $url"
  if command -v idmget >/dev/null 2>&1; then
    idmget "$url" -Out "$DL" -Name "$name" -Wait >/dev/null
  else
    idmget.cmd "$url" -Out "$DL" -Name "$name" -Wait >/dev/null
  fi
  got=$(sha256sum "$dest" | cut -d' ' -f1)
  size=$(wc -c < "$dest" | tr -d ' ')
  [ "$got" = "$sha" ] || { echo "sha256 不符: $name 期望 $sha 实际 $got" >&2; exit 1; }
  [ "$size" = "$bytes" ] || { echo "字节数不符: $name 期望 $bytes 实际 $size" >&2; exit 1; }
  echo "ok $name ($bytes bytes)"
}

target="${1:-all}"
case "$target" in
  x86_64|all) fetch "$X86_URL" "alpine-minirootfs-3.20.3-x86_64.tar.gz" "$X86_SHA" "$X86_BYTES" ;;
esac
case "$target" in
  arm64-v8a|all) fetch "$ARM_URL" "alpine-minirootfs-3.20.3-aarch64.tar.gz" "$ARM_SHA" "$ARM_BYTES" ;;
esac

if [ "$target" = "all" ]; then
  fetch "$PROOT_TARBALL" "proot-5.4.0.tar.gz" "29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68" "225529" || true
  fetch "$TALLOC_TARBALL" "talloc-2.4.2.tar.gz" "85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6" "679750" || true
fi
echo "完成"
