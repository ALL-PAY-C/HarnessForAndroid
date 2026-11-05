package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import dev.dsh.mobile.core.model.update.SourceFailure
import dev.dsh.mobile.core.model.update.SourceStat

/** 一个清单源的失败原因。多源同时挂掉时要能看到每一个，而不是只有第一个。 */

/**
 * 单个源的实测结果：耗时 + 失败原因（成功为 null）。
 *
 * 界面那行小字（「源：gh-proxy.com · 486ms」）与 [SourceSpeedStore] 的 EMA 都吃这份数据，
 * 不为「测速」多发任何一次请求。
 */

/**
 * 发车计划：[hot] 立刻发，[delayed] 等 [delayMs] 再发。
 *
 * 默认只填 hot = 全部立刻发，这条路径必须与加测速之前完全一致（没有历史数据时就走它）。
 */
data class RacePlan(
    val hot: List<String>,
    val delayed: List<String> = emptyList(),
    val delayMs: Long = 0L,
) {
    val all: List<String> get() = hot + delayed
}

/** 一次并行竞速的结果。 */
sealed class ManifestAttempt {

    /** 拿到正文。[failures] 是同轮里其他源的失败，用于日志与「部分降级」提示。 */
    class Ok(
        val url: String,
        val body: String,
        val failures: List<SourceFailure>,
        /** 胜出源自己的耗时；0 表示没测到（旧调用点、假 fetcher）。 */
        val elapsedMs: Long = 0L,
        /** 本轮所有拿到结论的源（含失败），供界面与测速记忆使用。 */
        val stats: List<SourceStat> = emptyList(),
    ) : ManifestAttempt() {
        override fun toString(): String = "Ok(" + url + ")"
    }

    class Failed(
        val failures: List<SourceFailure>,
        val stats: List<SourceStat> = emptyList(),
    ) : ManifestAttempt() {
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
 *
 * [plan] 把「谁先发、谁等多久」这件事交给调用方（默认全发），[onStat] 把每源耗时交回去喂
 * [SourceSpeedStore]；两者都不改变「谁先返回用谁」的判定。
 */
object ManifestRace {

    /** 单源超时：清单几 KB，8s 够慢网读完。 */
    const val PER_SOURCE_TIMEOUT_MS = 8_000L

    /** 总预算：比单源超时略大，正常一两个源 1s 内就回来了。 */
    const val TOTAL_BUDGET_MS = 12_000L

    /** 有测速历史时立刻发几个源。两个够了：多一个只是多烧流量。 */
    const val HOT_SOURCES = 2

    /** 剩余的源推迟多久发。1.5s 足够让前两个里最快的那个返回并取消全场。 */
    const val STAGGER_DELAY_MS = 1_500L

    suspend fun race(
        sources: List<String>,
        perSourceTimeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        totalBudgetMs: Long = TOTAL_BUDGET_MS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        plan: ((List<String>) -> RacePlan)? = null,
        onStat: ((SourceStat) -> Unit)? = null,
        fetch: suspend (String) -> String,
    ): ManifestAttempt {
        if (sources.isEmpty()) return ManifestAttempt.Failed(emptyList())
        val schedule = buildList {
            val p = plan?.invoke(sources) ?: RacePlan(sources)
            p.hot.forEach { add(it to 0L) }
            p.delayed.forEach { add(it to p.delayMs) }
        }
        return coroutineScope {
            val outcomes = Channel<Outcome>(schedule.size)
            val failures = java.util.Collections.synchronizedList(ArrayList<SourceFailure>())
            val stats = java.util.Collections.synchronizedList(ArrayList<SourceStat>())
            val jobs = schedule.map { (url, startDelayMs) ->
                launch(dispatcher) {
                    if (startDelayMs > 0) delay(startDelayMs)
                    val startedAt = System.nanoTime()
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
                    val stat = SourceStat(url, (System.nanoTime() - startedAt) / 1_000_000, reason)
                    stats.add(stat)
                    onStat?.invoke(stat)
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
                while (winner == null && done < schedule.size) {
                    val o = outcomes.receive()
                    if (o is Outcome.Ok) winner = o else done++
                }
                true
            }
            // 赢家已经把正文交出来了，剩下的源没有保留价值：不取消就要陪它们跑满超时。
            jobs.forEach { it.cancel() }
            val w = winner
            if (w != null) {
                ManifestAttempt.Ok(
                    url = w.url,
                    body = w.body,
                    failures = failures.toList(),
                    elapsedMs = stats.firstOrNull { it.url == w.url }?.elapsedMs ?: 0L,
                    stats = stats.toList(),
                )
            } else {
                val extra = if (inBudget == null) {
                    listOf(SourceFailure("(总预算)", "超过 " + totalBudgetMs + "ms"))
                } else {
                    emptyList()
                }
                ManifestAttempt.Failed(failures.toList() + extra, stats.toList())
            }
        }
    }

    private sealed class Outcome {
        class Ok(val url: String, val body: String) : Outcome()
        object Done : Outcome()
    }
}
