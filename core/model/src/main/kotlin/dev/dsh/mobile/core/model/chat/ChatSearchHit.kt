package dev.dsh.mobile.core.model.chat

/**
 * 抽屉搜索「聊天记录」分区的一行：会话标题、角色、命中片段、时间都由一次查询带出，
 * UI 只负责把命中的词加粗，不再二次查库。
 *
 * O8-S4: 从 core/data.repo.ChatRepoTypes 上移——AppDrawer 要用它画结果行，
 * 而界面不该 import core:data。
 */
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val snippet: String,
    val at: Long,
)
