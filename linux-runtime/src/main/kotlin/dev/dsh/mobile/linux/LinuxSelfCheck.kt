package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellChannel
import java.io.File

/** 一项自检证据：name 稳定、detail 是人读的一行（版本号 / IP / HTTP 码 / 失败原因）。 */
data class LinuxCheck(val name: String, val ok: Boolean, val detail: String)

data class LinuxSelfCheckReport(val checks: List<LinuxCheck>, val raw: String) {
    val ok: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }

    fun failed(): List<LinuxCheck> = checks.filter { !it.ok }

    fun summary(): String = checks.joinToString("; ") {
        it.name + "=" + (if (it.ok) "ok" else "FAIL") + "(" + it.detail + ")"
    }
}

/**
 * T5 的环境自检入口：跑 guest 侧脚本，把 #T5-CHECK 行解析成结构化结果。
 * 无 UI 依赖，T10 直接 new 出来 run() 即可。
 */
class LinuxSelfCheck(
    private val channel: ShellChannel,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val logFile: File? = null,
) {

    suspend fun run(): LinuxSelfCheckReport {
        val result = channel.exec(LinuxScripts.selfCheck(), timeoutMs)
        logFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(result.stdout + "\n--- stderr ---\n" + result.stderr)
        }
        return parse(result.stdout, result.stderr)
    }

    companion object {
        const val TIMEOUT_MS = 120_000L

        private val lineRe = Regex("^#T5-CHECK name=(\\S+) ok=([01]) detail=(.*)$", RegexOption.MULTILINE)

        fun parse(stdout: String, stderr: String = ""): LinuxSelfCheckReport {
            val checks = lineRe.findAll(stdout).map {
                LinuxCheck(it.groupValues[1], it.groupValues[2] == "1", it.groupValues[3].trim())
            }.toList()
            val raw = if (checks.isEmpty() && stderr.isNotBlank()) stderr else stdout
            return LinuxSelfCheckReport(checks, raw)
        }
    }
}
