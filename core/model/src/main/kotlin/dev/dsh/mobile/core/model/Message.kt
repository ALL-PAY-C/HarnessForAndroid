package dev.dsh.mobile.core.model

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val text: String = "",
    val thought: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val at: Long = System.currentTimeMillis(),
    val streaming: Boolean = false,
    /** Set by plugin renderers; null means "use the built-in renderer". */
    val rendererId: String? = null,
    /** Group chats: which participant produced this message (null in single chats). */
    val authorId: String? = null,
    val authorName: String? = null,
    /** 本地图片文件的绝对路径；发送时转成 base64 塞进 image content。 */
    val attachments: List<String> = emptyList(),
    /** 生成耗时（毫秒）；0 表示未知。 */
    val durationMs: Long = 0L,
    /** 这条回答消耗的输入 / 输出 token；0 表示模型没回 usage。气泡头显示「↑1.2k ↓356」。 */
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
}

data class ToolCall(
    val id: String,
    val title: String,
    val kind: String = "",
    val status: ToolStatus = ToolStatus.Pending,
    val detail: String = "",
    /** 工具入参原文（shell 命令或 JSON 参数），CoT 展开面板展示。 */
    val args: String = "",
    /** 发起这次工具调用时发给模型的请求摘要（原始 JSON）。 */
    val requestJson: String = "",
    /** 工具执行输出原文（已截断）。 */
    val responseJson: String = "",
    /** 执行耗时（毫秒）。 */
    val elapsedMs: Long = 0L,
    /** 失败重试次数；0 表示一次成功。 */
    val attempts: Int = 0,
) {
    enum class ToolStatus { Pending, InProgress, Completed, Failed }
}

/** A permission request surfaced as an inline card in the chat. */
data class PermissionRequest(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: List<PermissionOption>,
    /** 发起提问的 Agent；用户选择只回传给它（群聊其他 Agent 不得消费）。 */
    val ownerAgentId: String? = null,
    val ownerAgentName: String? = null,
)

data class PermissionOption(val optionId: String, val name: String, val kind: String)

data class AgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val model: String = "",
    val status: AgentStatus = AgentStatus.Idle,
    val usage: TokenUsage = TokenUsage.EMPTY,
    val lastActiveAt: Long = 0L,
)

enum class AgentStatus { Idle, Running, Waiting, Offline }
