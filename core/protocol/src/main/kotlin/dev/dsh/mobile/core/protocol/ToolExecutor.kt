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
    /**
     * 大输出落盘（P0-4）：给了它就不再硬截 6000 字符 —— 超阈值写 app 私有目录，
     * 正文留头尾 + locator，模型用 read_output 按 offset/limit 读回全文。
     */
    private val spill: ToolOutputSpill? = null,
    /**
     * 不可重试的工具名。执行器默认失败重试 1 次，这对读类工具没坏处，
     * 但对有副作用的工具就是「把点击/输入再打一遍」——那些工具自己用错误包回业务失败，
     * 唯一需要拦住的就是这一层重试。
     */
    private val noRetryTools: Set<String> = emptySet(),
) {
    /** [callId] 用于落盘文件名：同一轮里同名工具调两次不能互相覆盖输出。 */
    suspend fun run(name: String, args: JsonObject, turnId: String? = null, callId: String? = null): ToolExecResult {
        val started = System.currentTimeMillis()
        var attempts = 0
        var last: String? = null
        val maxRetries = if (name in noRetryTools) 0 else retries
        for (i in 0..maxRetries) {
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
                    output = spill?.present(out, turnId, callId ?: name) ?: out.truncateForTool(maxOutputChars),
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
            if (i < maxRetries) delay(retryDelayMs)
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
