package dev.dsh.mobile.core.model

/** A DSH conversation. Transport-agnostic: no JSON, no socket types leak here. */
data class Session(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L,
    val status: SessionStatus = SessionStatus.Idle,
    val preview: String = "",
    val messageCount: Int = 0,
    val usage: TokenUsage = TokenUsage.EMPTY,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val group: String = DEFAULT_GROUP,
    val kind: SessionKind = SessionKind.Single,
    /** Single chats: the model profile this conversation is bound to (null = app default). */
    val agentId: String? = null,
    /** 会话级人设 / 预设（普通会话生效；群聊成员各自带 persona）。 */
    val persona: String = "",
    /** Group members. The same [Participant.modelId] may appear several times on purpose (parallelism). */
    val participants: List<Participant> = emptyList(),
    /** ACP 远端会话 id；本地 id 独立保留（UI/DB 用本地 id）。null = 本地引擎 / 未建远端。 */
    val remoteId: String? = null,
    /** 远端会话归属的端点 id；仅当 [remoteId] 非空时有意义，隔离「旧端点 id 绝不发往新端点」。 */
    val endpointId: String? = null,
) {
    val isGroup: Boolean get() = kind == SessionKind.Group
    val memberCount: Int get() = participants.size

    companion object { const val DEFAULT_GROUP = "最近" }
}

enum class SessionKind { Single, Group }

/** One agent seat inside a group chat. */
data class Participant(
    val id: String,
    val modelId: String,
    val name: String,
    val persona: String = "",
    val slot: Int = 0,
)

enum class SessionStatus { Idle, Running, WaitingPermission, Failed }

data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    /** 最近一次请求的输入总量（未命中 + 缓存命中），不是累计占用。 */
    val lastPrompt: Long = 0,
) {
    val total: Long get() = input + output
    val isEmpty: Boolean get() = total == 0L

    /**
     * Share of the prompt served from the provider's cache.
     *
     * Cached tokens are reported separately from [input]; the denominator adds
     * them back so the ratio means "of everything the prompt needed, how much
     * was already warm".
     */
    val cachePercent: Int
        get() {
            val denom = input + cached
            return if (denom <= 0L) 0 else ((cached * 100) / denom).toInt()
        }
    val sessionTotal: Long get() = input + cached + output
    operator fun plus(o: TokenUsage) = TokenUsage(
        input + o.input, output + o.output, cached + o.cached, o.lastPrompt,
    )
    companion object { val EMPTY = TokenUsage() }
}
