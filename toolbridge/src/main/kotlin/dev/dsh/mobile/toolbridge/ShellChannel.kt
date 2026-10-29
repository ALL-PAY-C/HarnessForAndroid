package dev.dsh.mobile.toolbridge

/** 一次 shell 调用的结果。 */
data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = code == 0
    fun asToolOutput(limit: Int = 8_000): String {
        val out = stdout.trim()
        val err = stderr.trim()
        val body = buildString {
            if (out.isNotEmpty()) append(out)
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("[stderr] ").append(err)
            }
        }
        return if (body.length <= limit) body else body.take(limit) + "\n…(截断)"
    }

    companion object {
        fun unavailable(reason: String) = ShellResult(-1, "", reason)
    }
}

enum class ChannelKind { Shizuku, Root, Termux, App, Proot }

/**
 * 手机端工具执行通道。模型要"调用手机上的工具"时，最终都落到这里的一条命令上。
 * 新增通道（比如某个厂商的 shell 服务）只需实现本接口并 register 到 [ShellBridge]。
 */
interface ShellChannel {
    val id: String
    val label: String
    val kind: ChannelKind

    /** 当前是否可用（已安装/已授权/已 root）。 */
    fun available(): Boolean

    /** 给用户看的一句话说明。 */
    fun detail(): String

    fun terminalSpec(): ShellSpec? = null

    suspend fun exec(command: String, timeoutMs: Long = 20_000): ShellResult
}
