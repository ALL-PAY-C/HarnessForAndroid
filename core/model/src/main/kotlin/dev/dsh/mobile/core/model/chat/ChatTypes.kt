package dev.dsh.mobile.core.model.chat

import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.ToolCall
import java.util.UUID

/** Streaming output of one group member. */
data class AuthorPart(
    val id: String,
    val name: String,
    val text: String = "",
    val thought: String = "",
    val tools: List<ToolCall> = emptyList(),
    val done: Boolean = false,
    /** 同一轮里这个成员第几次发言：自动机/接力会让他反复开口，每次都要算独立气泡。 */
    val seq: Int = 0,
    /** 这个成员本段发言消耗的输入 / 输出 token（工具多轮会累加）。 */
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    /** 幂等键：一次发言一个，不能用 id（同一成员一轮会说好几次）。 */
    val key: String get() = id + "#" + seq
}

/** Live (not yet persisted) state of one running turn. */
data class TurnState(
    val text: String = "",
    val thought: String = "",
    val tools: List<ToolCall> = emptyList(),
    val running: Boolean = false,
    val permissions: List<PermissionRequest> = emptyList(),
    val plan: List<String> = emptyList(),
    /** 已经落库的发言键（[AuthorPart.key]）：避免 finishPart 与 finalizeTurn 各存一遍，气泡变三条。 */
    val persisted: Set<String> = emptySet(),
    /** Group chats: one entry per member currently speaking. */
    val parts: List<AuthorPart> = emptyList(),
    /** 这一轮开始的时间，用来算「用了 3 分 12 秒」。 */
    val startedAt: Long = System.currentTimeMillis(),
    /** 本轮唯一 id：幂等落库按 (sessionId, turnId, authorId) 判定，不再靠文本 + 时间猜。 */
    val turnId: String = UUID.randomUUID().toString(),
    /** 每个成员在本轮已经开过几次口，用来给新气泡分配 seq。 */
    val partSeq: Map<String, Int> = emptyMap(),
    /** 单聊本轮的 token 用量（工具多轮会累加）。 */
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    fun finishPart(part: AuthorPart): TurnState {
        val left = parts.filterNot { it.key == part.key }
        return copy(parts = left, persisted = persisted + part.key)
    }

    val permission: PermissionRequest? get() = permissions.lastOrNull()

    fun requestPermission(request: PermissionRequest): TurnState =
        if (!running) this else copy(permissions = permissions.filterNot { it.requestId == request.requestId } + request)

    fun resolvePermission(requestId: String, ownerAgentId: String? = null): TurnState =
        copy(permissions = permissions.filterNot {
            it.requestId == requestId && (ownerAgentId == null || it.ownerAgentId == null || it.ownerAgentId == ownerAgentId)
        })
}
