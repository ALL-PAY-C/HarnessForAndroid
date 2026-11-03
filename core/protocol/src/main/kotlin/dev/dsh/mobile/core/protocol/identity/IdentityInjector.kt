package dev.dsh.mobile.core.protocol.identity

import java.util.concurrent.ConcurrentHashMap

/**
 * 身份注入记账：决定「这一轮要不要把身份卡塞进 user 文本」。
 *
 * 远端 ACP 的 session/prompt 没有 system 字段，身份只能寄存于某一条 user 消息；
 * 而那条消息会被压缩掉、会话会被关掉重开，所以「只注入一次」等于「一定会忘」。
 * 这里的策略是：首轮必注入，之后每 [everyTurns] 轮补一次，且在这些时刻强制补：
 * 卡内容变了（hash 不同）、closeSession、resumeSession、压缩发生（[invalidate]）。
 *
 * 纯内存、无 IO、无协程调度 —— 可单测。本地引擎不用它（本地每轮重建 system）。
 */
class IdentityInjector(
    /** 取这张卡的渲染文本（= IdentityCard.render()）；空串 = 没有卡，直通不改写。 */
    private val identityOf: suspend (String) -> String = { "" },
    /** 补注入间隔（轮）。1 = 每轮都注入；默认 6，把成本从 O(N) 降到 O(N/6)。 */
    private val everyTurns: Int = DEFAULT_EVERY_TURNS,
) {
    private data class Mark(val turn: Int, val hash: Int)

    /** 每会话已走过的轮数（只在真的有一张卡时才累加）。 */
    private val turns = ConcurrentHashMap<String, Int>()

    /** 每会话上次注入的轮次与当时代码：null = 下一次调用必须注入。 */
    private val marks = ConcurrentHashMap<String, Mark>()

    /**
     * 下一次调用必须注入。卡变了 / 会话关掉重开 / 续接 / 压缩之后都调它。
     * 不传会话 → 清空全部（连接代际变化时用）。
     */
    fun invalidate(sessionId: String) {
        marks.remove(sessionId)
    }

    fun invalidateAll() {
        marks.clear()
        turns.clear()
    }

    /** 会话彻底结束：轮次记账一起丢掉，避免同名重建时从第 37 轮接着数。 */
    fun forget(sessionId: String) {
        marks.remove(sessionId)
        turns.remove(sessionId)
    }

    /**
     * 把身份前置到本轮文本上；不需要注入时原样返回 [text]。
     * 措辞沿用改造前的 persona 包装（无卡时会话行为逐字不变）。
     */
    suspend fun wrap(sessionId: String, text: String): String {
        val identity = runCatching { identityOf(sessionId) }
            .getOrNull().orEmpty().trim()
        if (identity.isEmpty()) {
            // 卡被清空：丢掉「已注入」的记账，否则同一张卡再打开时会被当成已注入而跳过。
            marks.remove(sessionId)
            return text
        }
        val turn = turns.merge(sessionId, 1, Int::plus) ?: 1
        val hash = identity.hashCode()
        val mark = marks[sessionId]
        if (mark != null && mark.hash == hash && turn - mark.turn < everyTurns.coerceAtLeast(1)) return text
        marks[sessionId] = Mark(turn, hash)
        return HEADER + identity + "\n\n" + text
    }

    companion object {
        /** 默认补注入间隔：40 轮只多发 6 次，不会退化成每轮重发。 */
        const val DEFAULT_EVERY_TURNS = 6

        /** 与 AcpTransport 改造前的 persona 包装逐字相同。 */
        const val HEADER = "用户为这个会话设定的人设与规则，请始终遵守：\n"
    }
}
