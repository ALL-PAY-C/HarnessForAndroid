package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** 一次工具执行的结果；失败也带完整信息，调用方负责回灌模型。 */
data class ToolExecResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
    val elapsedMs: Long = 0L,
    val attempts: Int = 1,
)

/**
 * 统一工具执行器：单次 30s 超时（超时按失败），失败重试 1 次、间隔 500ms。
 * 任何异常都收敛成失败结果——不许把整轮对话炸掉。
 */
class ToolExecutor(
    private val executors: Map<String, suspend (JsonObject) -> String>,
    private val timeoutMs: Long = 30_000L,
    private val retries: Int = 1,
    private val retryDelayMs: Long = 500L,
    private val maxOutputChars: Int = 6_000,
) {
    suspend fun run(name: String, args: JsonObject): ToolExecResult {
        val started = System.currentTimeMillis()
        var attempts = 0
        var last: String? = null
        for (i in 0..retries) {
            attempts = i + 1
            try {
                val exec = executors[name]
                    ?: return ToolExecResult(
                        ok = false,
                        output = "",
                        error = "未知工具：" + name + "（已注册：" + executors.keys.joinToString() + "）",
                        elapsedMs = System.currentTimeMillis() - started,
                        attempts = attempts,
                    )
                val out = withTimeoutOrNull(timeoutMs) { exec(args) }
                    ?: throw java.util.concurrent.TimeoutException("执行超时（" + timeoutMs + "ms）")
                return ToolExecResult(
                    ok = true,
                    output = out.truncateForTool(maxOutputChars),
                    error = null,
                    elapsedMs = System.currentTimeMillis() - started,
                    attempts = attempts,
                )
            } catch (e: CancellationException) {
                // 真正的取消要往上抛；重试循环里的取消不算失败。
                if (!currentCoroutineContext().isActive) throw e
                last = "执行被取消"
            } catch (t: Throwable) {
                last = t.javaClass.simpleName + ": " + (t.message ?: "执行失败")
                AppLog.w("tool exec failed (attempt " + attempts + "): " + last)
            }
            if (i < retries) delay(retryDelayMs)
        }
        return ToolExecResult(
            ok = false,
            output = "",
            error = last ?: "执行失败",
            elapsedMs = System.currentTimeMillis() - started,
            attempts = attempts,
        )
    }
}

internal fun String.truncateForTool(limit: Int = 6_000): String =
    if (length <= limit) this else take(limit) + "\n…（已截断，共 " + length + " 字符）"
