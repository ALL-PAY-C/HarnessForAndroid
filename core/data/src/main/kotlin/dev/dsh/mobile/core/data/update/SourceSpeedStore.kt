package dev.dsh.mobile.core.data.update

/**
 * 源测速记忆：成功记 EMA 耗时，失败记罚分；两者一起决定下一轮谁先发。
 *
 * 为什么是 EMA 而不是「上次耗时」：一次弱网抖动不该把一个常年 400ms 的源排到最后。
 * 为什么失败只罚分不拉黑：反代被限流通常是暂时的，被罚的源仍然会发，只是晚 1.5s。
 *
 * 纯逻辑：存储后端由调用方注入（SharedPreferences / 单测内存 Map），本类不碰 android.*。
 * 键前缀带版本号，改语义时换 v2 就自然失效，不用写迁移。
 */
class SourceSpeedStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {

    /** 一个源的历史：EMA 耗时（毫秒）+ 连续失败罚分（失败 +1，成功 -1）。 */
    data class Stat(val emaMs: Long, val penalty: Int) {
        /** 排序分：罚分折成时间，一次失败等价于多花 [FAIL_PENALTY_MS]。 */
        val scoreMs: Long get() = emaMs + penalty.toLong() * FAIL_PENALTY_MS
    }

    /** null = 这个源没有历史，排序时按 [NEUTRAL_EMA_MS] 中性值参与。 */
    fun stat(url: String): Stat? {
        val ema = read(KEY_PREFIX + KEY_EMA + url)?.toLongOrNull() ?: return null
        val penalty = read(KEY_PREFIX + KEY_PENALTY + url)?.toIntOrNull() ?: 0
        return Stat(ema, penalty)
    }

    /** 成功：EMA 向本次耗时靠拢（α = 1/4），罚分回落一格。 */
    fun recordSuccess(url: String, elapsedMs: Long) {
        val prev = stat(url)
        val ema = if (prev == null) elapsedMs else (prev.emaMs * 3 + elapsedMs) / 4
        write(KEY_PREFIX + KEY_EMA + url, ema.coerceAtLeast(1L).toString())
        if (prev != null && prev.penalty > 0) {
            write(KEY_PREFIX + KEY_PENALTY + url, (prev.penalty - 1).toString())
        }
    }

    /** 失败：耗时不敢采信（超时的话会写进 8s 把源永久拉黑），只加重罚分。 */
    fun recordFailure(url: String) {
        val prev = stat(url)
        val penalty = ((prev?.penalty ?: 0) + 1).coerceAtMost(MAX_PENALTY)
        // 第一次失败也要留一条 EMA：没有它这个源在 plan() 里仍然是「无历史」。
        if (prev == null) write(KEY_PREFIX + KEY_EMA + url, NEUTRAL_EMA_MS.toString())
        write(KEY_PREFIX + KEY_PENALTY + url, penalty.toString())
    }

    /**
     * 发车计划：没有历史就全发（与加测速之前完全一致）；有历史则先发分数最高的两个。
     *
     * 排序稳定：分数相同（比如两个都没测过）时保持调用方给的顺序。
     */
    fun plan(sources: List<String>): RacePlan {
        if (sources.size <= ManifestRace.HOT_SOURCES || sources.none { stat(it) != null }) {
            return RacePlan(sources)
        }
        val ordered = sources.sortedBy { stat(it)?.scoreMs ?: NEUTRAL_EMA_MS }
        return RacePlan(
            hot = ordered.take(ManifestRace.HOT_SOURCES),
            delayed = ordered.drop(ManifestRace.HOT_SOURCES),
            delayMs = ManifestRace.STAGGER_DELAY_MS,
        )
    }

    companion object {
        /** 键前缀带版本：改语义换 v2，旧数据自然失效。 */
        private const val KEY_PREFIX = "v1.speed."
        private const val KEY_EMA = "ema."
        private const val KEY_PENALTY = "pen."

        /** 一次失败折算多少毫秒的排序代价：足够把挂掉的源压到任何健康源后面。 */
        const val FAIL_PENALTY_MS = 10_000L

        /** 罚分上限：别让一个早就恢复的源永远排在最后。 */
        const val MAX_PENALTY = 5

        /** 没测过的源按这个耗时参与排序：给它机会，但不让它压过已验证的快速源。 */
        const val NEUTRAL_EMA_MS = 1_000L
    }
}
