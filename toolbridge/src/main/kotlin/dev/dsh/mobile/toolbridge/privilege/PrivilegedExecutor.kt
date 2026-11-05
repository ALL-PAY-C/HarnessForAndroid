package dev.dsh.mobile.toolbridge.privilege

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.toolbridge.ShellResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * su 的已知形态。
 *
 * AOSP / userdebug 机型的 su 是 `su [WHO [COMMAND...]]`：`su -c id` 会被当成把 "-c" 当 uid 解析
 * （实测报 `su: invalid uid/gid '-c'`），要写 `su 0 sh -c 'id'` 才拿到 uid=0。
 * 主流 root 管理器的 su 反过来只认 `su -c COMMAND`。两种都试，谁回 uid=0 用谁。
 */
enum class RootForm(val label: String) {
    DashC("su -c"),
    WhoArg("su 0 sh -c"),
    None("无");

    val available: Boolean get() = this != None

    /** 把一条 shell 命令包成本形态的 argv；[None] 没有可执行形态，返回 null。 */
    fun wrap(command: String): List<String>? = when (this) {
        DashC -> listOf("su", "-c", command)
        WhoArg -> listOf("su", "0", "sh", "-c", command)
        None -> null
    }
}

/**
 * 探测结果。[code] 是不可用时的退出码：调用方据此区分「根本没有 su」与「su 在但拿不到 root」，
 * 而不是把两种情况压成同一句「无 root」。
 */
data class RootProbe(
    val form: RootForm,
    val suPath: String?,
    val detail: String,
    /** 不可用原因码；默认按形态与 suPath 推断，特殊情形（su 在但对本进程不可见）由探测显式给出。 */
    val code: Int = codeOf(form, suPath),
) {
    companion object {
        fun codeOf(form: RootForm, suPath: String?): Int = when {
            form.available -> 0
            suPath == null -> CODE_NO_SU
            else -> CODE_SU_DENIED
        }

        /** 设备上没有 su（未 root）。 */
        const val CODE_NO_SU = -2

        /** su 存在，但两种形态都拿不到 uid=0（拒绝授权 / su 不可执行）。 */
        const val CODE_SU_DENIED = -3
    }
}

/**
 * root 探测与 argv 拼装。
 *
 * 探测是真进程调用（阻塞，和旧 RootChannel 一样有上限），结果缓存一次；[invalidate] 后下次 [root] 重探。
 * 纯逻辑（[pick] / [RootForm.wrap] / [RootProbe.code]）不碰进程，JVM 单测直接构造。
 */
object PrivilegedExecutor {

    private const val PROBE_TIMEOUT_MS = 4_000L

    /** toybox 与常见 root 管理器的 su 落点；`which` 不可用时靠它兜底。 */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
    )

    @Volatile
    private var cached: RootProbe? = null

    /** 缓存的探测结果（没有就探一次）。 */
    fun root(): RootProbe = cached ?: probe()

    /** 用户装了 root / 撤了授权之后调用，下次 [root] 重新探测。 */
    fun invalidate() {
        cached = null
    }

    fun probe(timeoutMs: Long = PROBE_TIMEOUT_MS): RootProbe {
        val suPath = locateSu()
        val result = if (suPath == null) {
            diagnoseMissingSu(timeoutMs)
        } else {
            val form = pick { argv -> runOnce(argv, timeoutMs) }
            RootProbe(
                form = form,
                suPath = suPath,
                detail = if (form.available) {
                    "已获取 root（" + form.label + " · " + suPath + "）"
                } else {
                    "su 存在但不可用：su -c 与 su 0 都拿不到 uid=0"
                },
            )
        }
        AppLog.i(
            "root probe: code=" + result.code + " form=" + result.form + " su=" + suPath +
                " detail=" + result.detail,
        )
        cached = result
        return result
    }

    /**
     * 形态判定（纯逻辑）：按 DashC → WhoArg 依次试，取第一个 exit==0 且 stdout 含 uid=0 的。
     *
     * 顺序不能反：AOSP 的 su 不认 -c，DashC 会直接失败；反过来在 root 管理器上先试 WhoArg 只是多绕一层
     * sh，虽然也能出 uid=0，却会把形态记错（exec 时再走 WhoArg 就对不上了）。
     */
    internal fun pick(run: (List<String>) -> ShellResult): RootForm {
        for (form in listOf(RootForm.DashC, RootForm.WhoArg)) {
            val argv = form.wrap("id") ?: continue
            val result = runCatching { run(argv) }.getOrNull() ?: continue
            if (result.code == 0 && result.stdout.contains("uid=0")) return form
        }
        return RootForm.None
    }

    /**
     * 候选路径都 stat 不到时，按名字再试一次 exec：异常里 error=13 说明 su 在、但 app uid 无权执行
     * （常见于 su 落点为 root:shell 0750 的 userdebug 镜像），error=2 才是真的没有 su。
     */
    private fun diagnoseMissingSu(timeoutMs: Long): RootProbe {
        val probe = runOnce(listOf("su", "-c", "id"), timeoutMs)
        val denied = probe.stderr.contains("error=13") || probe.stderr.contains("ermission denied", ignoreCase = true)
        return if (denied) {
            RootProbe(RootForm.None, null, "su 存在，但 app 无权执行（su 落点对本应用不可见）", RootProbe.CODE_SU_DENIED)
        } else {
            RootProbe(RootForm.None, null, "未检测到 su（设备未 root 或无 su 落点）")
        }
    }

    /** which su → 候选路径；都没有返回 null。 */
    private fun locateSu(): String? {
        SU_PATHS.firstOrNull { File(it).isFile }?.let { return it }
        val result = runOnce(listOf("which", "su"), 2_000)
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") && File(it).isFile }
    }

    /** 单次探测进程：先等退出再读，超时就杀，不会卡在读半截输出上。stderr 合并进 stdout。 */
    internal fun runOnce(argv: List<String>, timeoutMs: Long): ShellResult = runCatching {
        val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
        val done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            proc.destroyForcibly()
            ShellResult(-1, "", "探测超时（" + timeoutMs + "ms）")
        } else {
            ShellResult(proc.exitValue(), proc.inputStream.bufferedReader().use { it.readText() }, "")
        }
    }.getOrElse { ShellResult(-1, "", it.message.orEmpty()) }
}
