package dev.dsh.mobile.core.protocol

/**
 * 上下文摘要持久化（P0-1）。
 *
 * 为什么不新建 Room 表：摘要是「一个会话一段自由文本」，进 Entity 要加列 + 迁移脚本，
 * 删会话还要级联清理；走 SharedPreferences 一个 key 就够，清理时 remove 一次即可。
 * 具体实现见 core/data 的 SharedPrefsSummaryStore（选的就是这个既有存储）。
 */
interface ContextSummaryStore {
    fun read(sessionId: String): String
    fun write(sessionId: String, text: String)
    fun clear(sessionId: String)

    /** 无持久化：单测与拿不到 Context 的场景用这个，行为等同「每次都重新生成摘要」。 */
    object None : ContextSummaryStore {
        override fun read(sessionId: String): String = ""
        override fun write(sessionId: String, text: String) = Unit
        override fun clear(sessionId: String) = Unit
    }
}
