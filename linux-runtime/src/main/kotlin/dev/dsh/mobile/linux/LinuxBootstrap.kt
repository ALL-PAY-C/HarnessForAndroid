package dev.dsh.mobile.linux

import java.io.File

/** 预置结果。ok 只看「每个必需包都进了 apk 数据库」，不看 apk 退出码。 */
data class LinuxBootstrapResult(
    val ok: Boolean,
    val skipped: Boolean,
    val installed: List<String>,
    val missing: List<String>,
    val apkRc: Int?,
    val apkOutput: String,
    val error: String?,
    /** 完整 stdout，已过 [LinuxApkLog]：两条已知无害的 rename 行不会出现在这里。 */
    val raw: String,
    /** R2.1 工具链安装结果；未接 toolchain 时为 null。 */
    val toolchain: LinuxToolchain.Result? = null,
)

/** pip：guest 是 externally-managed（PEP 668），必须显式放行；索引走官方 HTTPS。 */
object LinuxPip {
    const val INDEX_URL = "https://pypi.org/simple"
    const val PATH = "etc/pip.conf"

    fun content(): String = "[global]\nbreak-system-packages = true\nindex-url = $INDEX_URL\n"

    fun applyTo(rootfs: File): Boolean = writeIfChanged(File(rootfs, PATH), content())
}

/**
 * T5：把 guest 变成可用的工具环境。
 *
 * 顺序固定：先写 resolv.conf/hosts（DNS 不通时 apk 一定挂），再写 pip.conf，
 * 最后跑幂等预置脚本。脚本原始输出与 apk 原始输出都留在结果里，便于诊断。
 */
class LinuxBootstrap(
    private val channel: ProotChannel,
    private val required: List<String> = LinuxScripts.required(),
    private val network: LinuxNetwork = channel.network,
    private val logFile: File? = null,
    /**
     * R2.1/R2.2：非空时先选镜像再装工具链。默认不接，T5 行为保持不变——
     * 工具链几十 MB，是否在启动路径上装由调用方决定。
     */
    private val toolchain: LinuxToolchainInstaller? = null,
) {

    suspend fun ensure(): LinuxBootstrapResult {
        val rootfs = channel.installation.rootfs
        network.applyTo(rootfs)
        LinuxPip.applyTo(rootfs)
        // 先选镜像：必需包也走快线，失败自动回退官方源，不影响下面的安装。
        runCatching { toolchain?.applyMirror() }
        val run = channel.exec(LinuxScripts.provision(required), timeoutMs = INSTALL_TIMEOUT_MS)
        // parse 内部已把 apk 输出过 LinuxApkLog；stderr 落盘前同样过一遍，不留直通路径。
        val parsed = parse(run.stdout, run.stderr)
        logFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(parsed.raw + "\n--- stderr ---\n" + LinuxApkLog.filterBenignApkErrors(run.stderr, parsed.ok))
        }
        val installed = if (parsed.ok) runCatching { toolchain?.install() }.getOrNull() else null
        return parsed.copy(toolchain = installed)
    }

    companion object {
        const val INSTALL_TIMEOUT_MS = 300_000L

        private val statusRe = Regex("#T5-STATUS\\s+ok=([01])\\s+skipped=([01])\\s+apk_rc=(\\S+)")
        private val errorRe = Regex("#T5-ERROR\\s+missing=([^\\n]*)")

        fun parse(stdout: String, stderr: String = ""): LinuxBootstrapResult {
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
            val begin = log.indexOf(LinuxScripts.APK_BEGIN)
            val end = log.indexOf(LinuxScripts.APK_END)
            val apkOutput = if (begin >= 0 && end > begin) {
                log.substring(begin + LinuxScripts.APK_BEGIN.length, end).trim()
            } else {
                ""
            }
            val error = when {
                status == null -> err.ifBlank { "provision script produced no #T5-STATUS" }.trim()
                !ok -> "packages still missing: " + stillMissing.joinToString(" ")
                else -> null
            }
            return LinuxBootstrapResult(
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
            Regex("#T5-STATE\\s+" + key + "=([^\\n]*)").find(stdout)?.groupValues?.get(1)
                ?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
    }
}
