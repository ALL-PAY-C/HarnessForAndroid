package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 一个清单源的失败原因。多源同时挂掉时要能看到每一个，而不是只有第一个。 */
data class SourceFailure(val url: String, val reason: String)

/** 一次并行竞速的结果。 */
sealed class ManifestAttempt {

    /** 拿到正文。[failures] 是同轮里其他源的失败，用于日志与「部分降级」提示。 */
    class Ok(
        val url: String,
        val body: String,
        val failures: List<SourceFailure>,
    ) : ManifestAttempt() {
        override fun toString(): String = "Ok(" + url + ")"
    }

    class Failed(val failures: List<SourceFailure>) : ManifestAttempt() {
        override fun toString(): String = "Failed(" + failures + ")"
    }
}

/** 每源失败原因拼成一行；空列表给一句兜底文案。 */
fun describeFailures(failures: List<SourceFailure>): String =
    if (failures.isEmpty()) {
        "没有可用更新源"
    } else {
        failures.joinToString("；") { it.url + " → " + it.reason }
    }

/**
 * 清单获取的并行竞速：所有源同时发车，先拿到正文的胜出，其余立刻取消。
 *
 * 为什么不让 [Updater] 自己写这个循环：4 个源严格串行、每源 20s 的写法最坏 80s 才报错，
 * 而清单只有几 KB。并行 + 每源独立超时 + 总预算把最坏耗时压到 max(单源超时)。
 * 纯逻辑（不碰 android.*），单测直接注入假 fetcher 验证时序。
 */
object ManifestRace {

    /** 单源超时：清单几 KB，8s 够慢网读完。 */
    const val PER_SOURCE_TIMEOUT_MS = 8_000L

    /** 总预算：比单源超时略大，正常一两个源 1s 内就回来了。 */
    const val TOTAL_BUDGET_MS = 12_000L

    suspend fun race(
        sources: List<String>,
        perSourceTimeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        totalBudgetMs: Long = TOTAL_BUDGET_MS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        fetch: suspend (String) -> String,
    ): ManifestAttempt {
        if (sources.isEmpty()) return ManifestAttempt.Failed(emptyList())
        return coroutineScope {
            val outcomes = Channel<Outcome>(sources.size)
            val failures = java.util.Collections.synchronizedList(ArrayList<SourceFailure>())
            val jobs = sources.map { url ->
                launch(dispatcher) {
                    var reason: String? = null
                    val body = try {
                        withTimeout(perSourceTimeoutMs) { fetch(url) }
                    } catch (e: TimeoutCancellationException) {
                        reason = "超时 " + perSourceTimeoutMs + "ms"
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                        null
                    }
                    if (body != null) {
                        outcomes.send(Outcome.Ok(url, body))
                    } else {
                        failures.add(SourceFailure(url, reason ?: "未知错误"))
                        outcomes.send(Outcome.Done)
                    }
                }
            }
            var winner: Outcome.Ok? = null
            var done = 0
            val inBudget = withTimeoutOrNull(totalBudgetMs) {
                // 每个源都有结论就收工：再等下去只会多出一条「总预算耗尽」的假原因。
                while (winner == null && done < sources.size) {
                    val o = outcomes.receive()
                    if (o is Outcome.Ok) winner = o else done++
                }
                true
            }
            // 赢家已经把正文交出来了，剩下的源没有保留价值：不取消就要陪它们跑满超时。
            jobs.forEach { it.cancel() }
            val w = winner
            if (w != null) {
                ManifestAttempt.Ok(w.url, w.body, failures.toList())
            } else {
                val extra = if (inBudget == null) {
                    listOf(SourceFailure("(总预算)", "超过 " + totalBudgetMs + "ms"))
                } else {
                    emptyList()
                }
                ManifestAttempt.Failed(failures.toList() + extra)
            }
        }
    }

    private sealed class Outcome {
        class Ok(val url: String, val body: String) : Outcome()
        object Done : Outcome()
    }
}
