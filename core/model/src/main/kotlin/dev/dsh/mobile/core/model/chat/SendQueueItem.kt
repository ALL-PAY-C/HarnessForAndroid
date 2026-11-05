package dev.dsh.mobile.core.model.chat

import java.util.UUID

/** 一条排队待发的消息：文本 + 附件 + 稳定 id（界面 key 与「撤下 / 插队」用）。 */
data class SendQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val images: List<String> = emptyList(),
)
