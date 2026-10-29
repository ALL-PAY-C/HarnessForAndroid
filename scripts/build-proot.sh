#!/bin/sh
# 用 Android NDK 静态构建 proot（termux/proot fork，不依赖 make/autotools）。
#
# 用法:
#   sh scripts/build-proot.sh [abi ...]        # abi: x86_64 | arm64-v8a（缺省两个都建）
#
# 输入（可用环境变量覆盖）:
#   ANDROID_NDK_HOME  NDK 根目录（缺省 D:/AndroidSDK/ndk/26.3.11579264）
#   PROOT_SRC         termux/proot 解包目录（缺省 .mock/agent-env/build/proot-master）
#   TALLOC_SRC        talloc-2.4.2 解包目录
#   TALLOC_INC        手写最小 config.h 所在目录
#
# 输出:
#   .mock/agent-env/artifacts/<abi>/libproot.so
#   .mock/agent-env/artifacts/<abi>/SHA256SUMS
#
# 为什么是 termux/proot 而不是 proot-me/proot 5.4.0:
#   5.4.0 在 Android 15 x86_64 上把 execve 路径无条件改写成 PROOT_LOADER 再 exec，
#   该 execve 直接返回 EFAULT(-14)（'proot error: execve("/bin/sh"): Bad address'）。
#   termux fork 在 src/execve/enter.c:392 默认置 skip_proot_loader = true，
#   enter.c:670 因此跳过 loader 重定向，execve 走原始路径。故本脚本不再构建 loader，
#   PROOT_LOADER 也不再是运行前提；PROOT_TMP_DIR 仍然是。
#
# proot 源树强依赖 talloc（src/cli.c 第 27 行 #include <talloc.h>），NDK sysroot 没有，
# 所以这里先把 talloc.c 编成静态库一起链进 proot。
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
MOCK="$REPO_ROOT/.mock/agent-env"
# 固定源：termux/proot master 快照
#   https://github.com/termux/proot/archive/refs/heads/master.tar.gz
#   sha256 a699849b85369451fe920c73d1b29871a0cd5aa9022e564fe87e0bca6cfdc7ba (374792 bytes)
PROOT_SRC="${PROOT_SRC:-$MOCK/build/proot-master}"
TALLOC_SRC="${TALLOC_SRC:-$MOCK/build/talloc-2.4.2}"
TALLOC_INC="${TALLOC_INC:-$MOCK/build/talloc-include}"
NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-D:/AndroidSDK/ndk/26.3.11579264}}"
API="${ANDROID_API:-26}"

# unbundle loader 的安装前缀只影响 proot 内部记录的 loader 路径，实际不会被用到。
UNBUNDLE_PREFIX="${PROOT_UNBUNDLE_PREFIX:-/data/data/dev.dsh.mobile/files/linux}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  Darwin)               HOST_TAG="darwin-x86_64" ;;
  *)                    HOST_TAG="linux-x86_64" ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$BIN/clang.exe"
[ -x "$CC" ] || CC="$BIN/clang"
AR="$BIN/llvm-ar.exe"
[ -x "$AR" ] || AR="$BIN/llvm-ar"
STRIP="$BIN/llvm-strip.exe"
[ -x "$STRIP" ] || STRIP="$BIN/llvm-strip"
READELF="$BIN/llvm-readelf.exe"
[ -x "$READELF" ] || READELF="$BIN/llvm-readelf"

# clang 编译时要在 TMPDIR 建临时文件；MSYS 下继承来的 TEMP=/tmp 对原生 clang 不可解析，
# 会报 "unable to make temporary file: Permission denied"。统一改到构建目录。
mkdir -p "$MOCK/build/tmp"
if command -v cygpath >/dev/null 2>&1; then
  TMPWIN=$(cygpath -w "$MOCK/build/tmp")
else
  TMPWIN="$MOCK/build/tmp"
fi
TMPDIR="$TMPWIN"; TMP="$TMPWIN"; TEMP="$TMPWIN"
export TMPDIR TMP TEMP

# MSYS 会把参数里的 "/data/..." 当成绝对路径改写成 C:/Users/.../data/...，
# 导致 -DPROOT_UNBUNDLE_LOADER 宏变成非法表达式。只对这一个宏关掉路径转换。
MSYS2_ARG_CONV_EXCL="-DPROOT_UNBUNDLE_LOADER"
export MSYS2_ARG_CONV_EXCL

[ -x "$CC" ] || { echo "找不到 NDK clang: $CC" >&2; exit 1; }
[ -d "$PROOT_SRC/src" ] || { echo "找不到 proot 源码: $PROOT_SRC" >&2; exit 1; }
[ -f "$TALLOC_SRC/talloc.c" ] || { echo "找不到 talloc 源码: $TALLOC_SRC" >&2; exit 1; }

# proot 源文件清单 = termux fork src/GNUmakefile 的 OBJECTS（68 个，已去掉 loader 相关）
PROOT_OBJECTS="cli/cli cli/proot cli/note \
execve/enter execve/exit execve/shebang execve/elf execve/ldso execve/auxv execve/aoxp \
path/binding path/glue path/canon path/f2fs-bug path/path path/proc path/temp \
syscall/seccomp syscall/syscall syscall/chain syscall/enter syscall/exit syscall/sysnum \
syscall/socket syscall/heap syscall/rlimit syscall/pipe_shadow \
tracee/tracee tracee/mem tracee/reg tracee/event tracee/seccomp tracee/statx \
ptrace/ptrace ptrace/user ptrace/wait \
extension/extension extension/ashmem_memfd/ashmem_memfd extension/kompat/kompat \
extension/fake_id0/chown extension/fake_id0/chroot extension/fake_id0/getsockopt \
extension/fake_id0/sendmsg extension/fake_id0/socket extension/fake_id0/open \
extension/fake_id0/unlink extension/fake_id0/rename extension/fake_id0/chmod \
extension/fake_id0/utimensat extension/fake_id0/access extension/fake_id0/exec \
extension/fake_id0/link extension/fake_id0/symlink extension/fake_id0/mk \
extension/fake_id0/stat extension/fake_id0/helper_functions extension/fake_id0/fake_id0 \
extension/hidden_files/hidden_files extension/mountinfo/mountinfo \
extension/port_switch/port_switch \
extension/sysvipc/sysvipc extension/sysvipc/sysvipc_msg extension/sysvipc/sysvipc_sem \
extension/sysvipc/sysvipc_shm extension/link2symlink/link2symlink \
extension/fix_symlink_size/fix_symlink_size"

build_abi() {
  abi="$1"
  case "$abi" in
    # loader_addr = termux src/arch.h 的 LOADER_ADDRESS；pokedata = HAS_POKEDATA_WORKAROUND
    x86_64)     triple="x86_64-linux-android$API"; loader_addr="0x600000000000"; pokedata=0 ;;
    arm64-v8a)  triple="aarch64-linux-android$API"; loader_addr="0x5400000000"; pokedata=1 ;;
    *) echo "不支持的 abi: $abi" >&2; return 1 ;;
  esac

  work="$MOCK/build/out-$abi"
  out="$MOCK/artifacts/$abi"
  rm -rf "$work" && mkdir -p "$work/obj" "$work/objloader" "$work/include" "$work/lib" "$out"

  # build.h：上游由 makefile 跑探测程序生成，这里固定成不启用可选加速器的保守配置
  # （process_vm_readv 走 ptrace 回退路径；seccomp 加速关闭）。
  {
    echo '/* generated by scripts/build-proot.sh */'
    echo '#ifndef BUILD_H'
    echo '#define BUILD_H'
    echo '#define VERSION "termux-proot-master"'
    echo '#endif /* BUILD_H */'
  } > "$work/include/build.h"

  # 宏值必须是字符串字面量：这里是 \"$UNBUNDLE_PREFIX\"（shell 去引号后 clang 收到 "..."）
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I$work/include -I$PROOT_SRC/src -I$TALLOC_SRC -DPROOT_UNBUNDLE_LOADER=\"$UNBUNDLE_PREFIX\""
  # -include string.h 只给 proot 本体：extension/ashmem_memfd/ashmem_memfd.c 在 __ANDROID__
  # 下用了 strcmp/memset 却没 include <string.h>，clang 18 把隐式声明当错误。loader 是
  # -ffreestanding 自带同名 basename()，不能预包含 string.h。
  PROOT_CPPFLAGS="$CPPFLAGS -include string.h"
  CFLAGS="-g -O2 -fPIC -Wall -Wno-error"
  LDFLAGS="-static -L$work/lib -ltalloc -Wl,-z,noexecstack"

  echo "[$abi] talloc"
  "$CC" --target="$triple" $CPPFLAGS -D__STDC_WANT_LIB_EXT1__=1 -I"$TALLOC_INC" -I"$TALLOC_SRC/lib/replace" \
    $CFLAGS -c "$TALLOC_SRC/talloc.c" -o "$work/talloc.o"
  "$AR" rcs "$work/lib/libtalloc.a" "$work/talloc.o"

  # loader：独立 ELF，入口固定在 LOADER_ADDRESS；proot 通过 PROOT_LOADER 指到它，
  # 由它按 proot 写好的 load script 把 guest 映射进内存（这就是 A4 的加载器）。
  # 放 objloader/：它自带 _start，混进主链接会和 crtbegin_static.o 撞符号。
  echo "[$abi] loader"
  "$CC" --target="$triple" $CPPFLAGS -fPIC -ffreestanding -c "$PROOT_SRC/src/loader/loader.c" -o "$work/objloader/loader.o"
  "$CC" --target="$triple" $CPPFLAGS -fPIC -ffreestanding -c "$PROOT_SRC/src/loader/assembly.S" -o "$work/objloader/assembly.o"
  "$CC" --target="$triple" -o "$work/loader" "$work/objloader/loader.o" "$work/objloader/assembly.o" \
    -static -nostdlib -Wl,--build-id=none,-Ttext=$loader_addr,-z,noexecstack
  "$STRIP" "$work/loader"

  # arm64 的 arch.h 有 HAS_POKEDATA_WORKAROUND，proot 侧要链 loader-info.o，
  # 它由 readelf -s 解析 loader 符号表生成（对应 GNUmakefile 的 loader/loader-info.c 规则）。
  if [ "$pokedata" = 1 ]; then
    echo "[$abi] loader-info"
    "$READELF" -s "$work/loader" | awk -f "$PROOT_SRC/src/loader/loader-info.awk" > "$work/include/loader-info.c"
    "$CC" --target="$triple" $CPPFLAGS $CFLAGS -c "$work/include/loader-info.c" -o "$work/obj/loader-info.o"
  fi

  echo "[$abi] proot objects"
  for obj in $PROOT_OBJECTS; do
    src="$PROOT_SRC/src/$obj.c"
    dst="$work/obj/$(echo "$obj" | tr '/' '_').o"
    [ -f "$src" ] || { echo "缺源文件: $src" >&2; return 1; }
    "$CC" --target="$triple" $PROOT_CPPFLAGS $CFLAGS -c "$src" -o "$dst"
  done

  # Android 16 arm64 Bionic rejects a PT_TLS segment with p_align < 64.  Static
  # bionic only contributes its 8-byte errno TLS slot, so lld emits p_align=8;
  # a real 64-byte-aligned TLS object raises the segment alignment to 64.
  if [ "$abi" = "arm64-v8a" ]; then
    echo "[$abi] tls-align"
    printf '%s\n' '__thread char __dsh_proot_tls_align[64] __attribute__((aligned(64)));' > "$work/include/tls-align.c"
    "$CC" --target="$triple" $CPPFLAGS $CFLAGS -fno-emulated-tls \
      -c "$work/include/tls-align.c" -o "$work/obj/tls-align.o"
  fi

  echo "[$abi] link"
  "$CC" --target="$triple" -o "$work/proot" "$work"/obj/*.o $LDFLAGS

  "$STRIP" "$work/proot"
  cp "$work/proot" "$out/libproot.so"
  cp "$work/loader" "$out/libproot-loader.so"
  chmod +x "$out/libproot.so" "$out/libproot-loader.so"

  ( cd "$out" && sha256sum libproot.so libproot-loader.so > SHA256SUMS )
  echo "[$abi] ok -> $out"
  cat "$out/SHA256SUMS"
}

if [ "$#" -eq 0 ]; then
  set -- x86_64 arm64-v8a
fi
for abi in "$@"; do
  build_abi "$abi"
done
