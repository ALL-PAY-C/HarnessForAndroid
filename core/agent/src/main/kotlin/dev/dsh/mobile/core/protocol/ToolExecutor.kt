package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.platform.AppLog
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
    /**
     * 执行面。是函数而不是 Map：T9.10 P3 起工具表每轮都可能变（MCP 服务器连上/断开），
     * 构造时快照一份就等于把首轮的形状焊死，之后新注入的工具永远「未知工具」。
     */
    private val executors: () -> Map<String, suspend (JsonObject) -> String>,
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
    private val noRetryTools: () -> Set<String> = { emptySet() },
    /**
     * 逐工具超时预算（T9.10 P3）：MCP 工具单次可以跑到 120s，默认的 30s 会把它砍在半路。
     * 返回 null 表示「用 [timeoutMs]」。
     */
    private val timeoutOf: (String) -> Long? = { null },
) {
    /** 直接给一张固定 Map 的旧接法（单测与旧调用点）：等价于「工具表永不变化」。 */
    constructor(
        executors: Map<String, suspend (JsonObject) -> String>,
        timeoutMs: Long = 30_000L,
        retries: Int = 1,
        retryDelayMs: Long = 500L,
        maxOutputChars: Int = 6_000,
        spill: ToolOutputSpill? = null,
        noRetryTools: Set<String> = emptySet(),
    ) : this(
        { executors },
        timeoutMs,
        retries,
        retryDelayMs,
        maxOutputChars,
        spill,
        { noRetryTools },
        { null },
    )

    /** [callId] 用于落盘文件名：同一轮里同名工具调两次不能互相覆盖输出。 */
    suspend fun run(name: String, args: JsonObject, turnId: String? = null, callId: String? = null): ToolExecResult {
        val started = System.currentTimeMillis()
        var attempts = 0
        var last: String? = null
        // 一次调用内只取一份快照：重试之间工具表变了也不该中途换执行体。
        val table = executors()
        val maxRetries = if (name in noRetryTools()) 0 else retries
        for (i in 0..maxRetries) {
            attempts = i + 1
            try {
                val exec = table[name]
                    ?: return ToolExecResult(
                        ok = false,
                        output = "",
                        error = "Unknown tool: " + name + " (registered: " + table.keys.joinToString() + ")",
                        elapsedMs = System.currentTimeMillis() - started,
                        attempts = attempts,
                    )
                val budget = timeoutOf(name)?.coerceAtLeast(1_000L) ?: timeoutMs
                val out = withTimeoutOrNull(budget) { exec(args) }
                    ?: throw java.util.concurrent.TimeoutException("Execution timed out (" + budget + "ms)")
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
                last = "execution cancelled"
            } catch (t: Throwable) {
                last = t.javaClass.simpleName + ": " + (t.message ?: "execution failed")
                AppLog.w("tool exec failed (attempt " + attempts + "): " + last)
            }
            if (i < maxRetries) delay(retryDelayMs)
        }
        return ToolExecResult(
            ok = false,
            output = "",
            error = last ?: "execution failed",
            elapsedMs = System.currentTimeMillis() - started,
            attempts = attempts,
        )
    }
}

internal fun String.truncateForTool(limit: Int = 6_000): String =
    if (length <= limit) this else take(limit) + "\n…(truncated, " + length + " chars total)"
