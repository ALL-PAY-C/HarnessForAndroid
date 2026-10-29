package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellResult
import java.io.File

/**
 * R2.1 + R2.3：工具链预装 + apk 串行化。
 *
 * 锁用 mkdir 而不是 flock：coreutils 本身就在待装清单里，不能先依赖它；
 * mkdir 是原子操作，再加 pid 存活检查，就能回收上一个进程被杀留下的锁。
 */
object LinuxToolchain {

    /** 预装清单：ffmpeg 给音视频、ripgrep 给搜索、rsync 给同步、sqlite 给本地库。 */
    const val PACKAGES = "bash jq ffmpeg sqlite unzip tar coreutils findutils openssh-client rsync ripgrep"

    const val LOCK_DIR = "/var/lock/dsh-apk.lock"

    /** 60 × 0.5s = 30s：一个 ffmpeg 安装的时长上限，等不到就报错而不是无限排队。 */
    const val LOCK_RETRIES = 60
    const val LOCK_SLEEP = "0.5"

    const val STATE = "#T5-TOOLCHAIN-STATE"
    const val BEGIN = "#T5-TOOLCHAIN-BEGIN"
    const val END = "#T5-TOOLCHAIN-END"
    const val STATUS = "#T5-TOOLCHAIN-STATUS"
    const val ERROR = "#T5-TOOLCHAIN-ERROR"
    const val LOCK_LINE = "#T5-APK-LOCK"

    private const val D = "\$"

    fun packages(): List<String> = PACKAGES.split(' ')

    /**
     * 把任意 guest 命令包进 apk 锁。并行调 apk 会撞 "Unable to lock database"，
     * 这里串起来；锁持有者的 pid 已经不在了就直接抢，避免一次崩溃锁死整台设备。
     */
    fun lockedScript(
        inner: String,
        lockDir: String = LOCK_DIR,
        retries: Int = LOCK_RETRIES,
        sleep: String = LOCK_SLEEP,
    ): String {
        val parent = lockDir.substringBeforeLast('/', "/var/lock")
        return """
            set -u
            LOCK='$lockDir'
            mkdir -p '$parent'
            apk_lock() {
              i=0
              while :; do
                if mkdir "${D}LOCK" 2>/dev/null; then
                  echo "${D}${D}" > "${D}LOCK/pid"
                  echo "$LOCK_LINE acquired pid=${D}${D}"
                  return 0
                fi
                holder=''
                if [ -f "${D}LOCK/pid" ]; then holder=${D}(cat "${D}LOCK/pid" 2>/dev/null || true); fi
                if [ -n "${D}holder" ] && ! kill -0 "${D}holder" 2>/dev/null; then
                  echo "$LOCK_LINE stale pid=${D}holder"
                  rm -rf "${D}LOCK"
                  continue
                fi
                i=${D}((i + 1))
                if [ "${D}i" -ge $retries ]; then
                  echo "$LOCK_LINE giveup retries=$retries"
                  return 1
                fi
                sleep $sleep
              done
            }
            trap 'rm -rf "${D}LOCK"' EXIT INT TERM
            apk_lock || { echo "$LOCK_LINE failed"; exit 1; }
            $inner
        """.trimIndent()
    }

    /**
     * 幂等预装：逐个 `apk info -e` 判断，全齐就跳过联网。
     * 成功判据同样是「包都进了数据库」而不是 apk 退出码——proot 下 rename 失败会返回非零。
     */
    fun provisionScript(packages: List<String> = packages()): String {
        val req = packages.joinToString(" ")
        val body = """
            REQ='$req'
            installed=''
            missing=''
            for p in ${D}REQ; do
              if apk info -e "${D}p" >/dev/null 2>&1; then installed="${D}installed ${D}p"; else missing="${D}missing ${D}p"; fi
            done
            echo "$STATE installed=${D}installed"
            echo "$STATE missing=${D}missing"
            if [ -z "${D}missing" ]; then
              echo "$STATUS ok=1 skipped=1 apk_rc=-"
              exit 0
            fi
            # proot 下 apk 的 unlink+rename 会失败：装之前先清掉会撞车的目标与残留临时文件。
            # 只清「包还没注册」的那些，已装好的文件不碰。
            if ! apk info -e unzip >/dev/null 2>&1; then rm -f /usr/bin/zipinfo 2>/dev/null; fi
            if ! apk info -e ncurses-terminfo-base >/dev/null 2>&1; then rm -f /etc/terminfo/v/vt220 2>/dev/null; fi
            find /etc/terminfo/v /usr/bin -maxdepth 1 -name '.apk.*' -delete 2>/dev/null || true
            echo "$BEGIN"
            apk add --no-cache ${D}missing 2>&1
            apk_rc=${D}?
            echo ""
            echo "$END rc=${D}apk_rc"
            still=''
            for p in ${D}REQ; do
              apk info -e "${D}p" >/dev/null 2>&1 || still="${D}still ${D}p"
            done
            if [ -n "${D}still" ]; then
              echo "$STATUS ok=0 skipped=0 apk_rc=${D}apk_rc"
              echo "$ERROR missing=${D}still"
              exit 1
            fi
            echo "$STATUS ok=1 skipped=0 apk_rc=${D}apk_rc"
            exit 0
        """.trimIndent()
        return lockedScript(body)
    }

    data class Result(
        val ok: Boolean,
        val skipped: Boolean,
        val installed: List<String>,
        val missing: List<String>,
        val apkRc: Int?,
        val apkOutput: String,
        val error: String?,
        /** 完整 stdout，已过 [LinuxApkLog]：两条已知无害的 rename 行不会出现在这里。 */
        val raw: String,
    )

    private val statusRe = Regex(STATUS + "\\s+ok=([01])\\s+skipped=([01])\\s+apk_rc=(\\S+)")
    private val errorRe = Regex(ERROR + "\\s+missing=([^\\n]*)")

    fun parse(stdout: String, stderr: String = ""): Result {
        val status = statusRe.find(stdout)
        val ok = status?.groupValues?.get(1) == "1"
        val skipped = status?.groupValues?.get(2) == "1"
        val apkRc = status?.groupValues?.get(3)?.toIntOrNull()
        // apk 文本的唯一出口：两条已知无害的 rename 行在这里就没了，下面的
        // apkOutput / raw / error 全都取自过滤后的文本，调用方没有直通路径。
        val log = LinuxApkLog.filterBenignApkErrors(stdout, ok)
        val err = LinuxApkLog.filterBenignApkErrors(stderr, ok)
        val stillMissing = errorRe.find(log)?.groupValues?.get(1)
            ?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        val begin = log.indexOf(BEGIN)
        val end = log.indexOf(END)
        val apkOutput = if (begin >= 0 && end > begin) {
            log.substring(begin + BEGIN.length, end).trim()
        } else {
            ""
        }
        val error = when {
            status == null -> err.ifBlank { "toolchain script produced no $STATUS" }.trim()
            !ok -> "toolchain packages still missing: " + stillMissing.joinToString(" ")
            else -> null
        }
        return Result(
            ok = ok,
            skipped = skipped,
            installed = tokens(log, "installed"),
            missing = if (ok) emptyList() else stillMissing,
            apkRc = apkRc,
            apkOutput = apkOutput,
            error = error,
            raw = log,
        )
    }

    private fun tokens(stdout: String, key: String): List<String> =
        Regex(STATE + "\\s+" + key + "=([^\\n]*)").find(stdout)?.groupValues?.get(1)
            ?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
}

/**
 * R2.2 的落地：先探测选镜像写 /etc/apk/repositories，再装工具链。
 * 两步分开调用，调用方可以先 applyMirror 再跑自己的 apk 调用，让必需包也走快线。
 */
class LinuxToolchainInstaller(
    private val channel: ProotChannel,
    private val packages: List<String> = LinuxToolchain.packages(),
    private val candidates: List<LinuxApk.Mirror> = LinuxApk.CANDIDATES,
    private val logFile: File? = null,
) {

    /** 最近一次探测的原始输出，用于诊断「为什么用了官方源」。 */
    var lastProbe: String = ""
        private set

    var mirror: LinuxApk.Mirror = LinuxApk.FALLBACK
        private set

    suspend fun applyMirror(): LinuxApk.Mirror {
        val arch = LinuxApk.alpineArch(channel.installation.abi)
        val probe = channel.exec(LinuxApk.probeScript(arch, candidates), timeoutMs = PROBE_TIMEOUT_MS)
        lastProbe = probe.stdout + probe.stderr
        mirror = LinuxApk.choose(LinuxApk.parseProbes(probe.stdout))
        LinuxApk.applyTo(channel.installation.rootfs, mirror.base)
        return mirror
    }

    suspend fun install(): LinuxToolchain.Result {
        val run = channel.exec(LinuxToolchain.provisionScript(packages), timeoutMs = INSTALL_TIMEOUT_MS)
        // parse 内部已把 apk 输出过 LinuxApkLog；stderr 落盘前同样过一遍，不留直通路径。
        val result = LinuxToolchain.parse(run.stdout, run.stderr)
        logFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(result.raw + "\n--- stderr ---\n" + LinuxApkLog.filterBenignApkErrors(run.stderr, result.ok))
        }
        return result
    }

    companion object {
        /** 两个镜像 × 3s 超时 + 启动余量。 */
        const val PROBE_TIMEOUT_MS = 30_000L

        /** ffmpeg 依赖在 arm64 上不小；走后台任务，不占 30s 前台窗口。 */
        const val INSTALL_TIMEOUT_MS = 600_000L
    }
}
