package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.context.WarmLayer

/**
 * 一条温层输入消息：rowid 是对账水位（messages.rowid），role / text 给模型看。
 *
 * 单独建类型而不是复用 ChatMessage：ChatMessage 没有 rowid，而温层的去重完全靠它
 * （S1 的 coveredUntilMessageId）；单测也不用为了造一条消息去拼整个 ChatMessage。
 */
data class WarmMessage(
    val rowId: Long,
    val role: String,
    val text: String,
)

/**
 * 温层刷新回执（T9.8 S2）。
 *
 * [layer] 为 null = 本次没写库：没有新消息、模型输出一个字段都解析不出、或调用失败。
 * 三种情况都不是异常，旧温层原样留着 —— 温层「有则更好」，绝不能挡住一轮对话。
 */
data class WarmRefreshReport(
    val layer: WarmLayer? = null,
    val message: String = "",
) {
    val refreshed: Boolean get() = layer != null
}
