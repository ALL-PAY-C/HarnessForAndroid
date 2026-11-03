package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.common.runCatchingDsh
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.MESSAGE_TEXT_READ_CAP
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionKind
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.protocol.AcpTransport
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.Compactor
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import dev.dsh.mobile.core.protocol.LocalEngineTransport
import dev.dsh.mobile.core.protocol.SessionProjection
import dev.dsh.mobile.core.protocol.SessionProjections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------- 值类型（ChatRepository 拆分 T7.3）
// 原 ChatRepository.kt 内的 DTO / 轮次状态类型，包名与可见性不变，调用点零改动。

@Serializable
internal data class ToolCallDto(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val detail: String,
    /** CoT 留存：命令原文 / 请求摘要 / 输出原文 / 耗时 / 重试次数。 */
    val args: String = "",
    val requestJson: String = "",
    val responseJson: String = "",
    val elapsedMs: Long = 0L,
    val attempts: Int = 0,
)

/** 每会话当前挂起的 prompt 子任务 + 它所属的轮次 id：cancel 只按 turnId 取消，绝不误杀新轮。 */
internal data class PromptJob(val turnId: String, val job: kotlinx.coroutines.Deferred<String>)

@Serializable
internal data class ParticipantDto(
    val id: String,
    val modelId: String,
    val name: String,
    val persona: String = "",
    val slot: Int = 0,
    /** 群聊角色席位 id；空串 = 未指定。老数据没有这个字段，靠默认值兼容。 */
    val role: String = "",
)

internal val toolJson = Json { ignoreUnknownKeys = true }

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
    internal fun finishPart(part: AuthorPart): TurnState {
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

/**
 * 抽屉搜索「聊天记录」分区的一行：会话标题、角色、命中片段、时间都由一次查询带出，
 * UI 只负责把命中的词加粗，不再二次查库。
 */
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val snippet: String,
    val at: Long,
)

// ---------------------------------------------------------------- 落库常量

/** 被用户中断的那一轮留下的占位正文。 */
internal const val PARTIAL_MARK = "（已停止 · 这条回复被中途打断）"

/** 一整轮什么都没产出时落的占位正文（以前是静默空白）。 */
internal const val EMPTY_TURN_MARK = "（本轮没有产生任何回复，请重试）"

/**
 * 正文不截断：原文必须整条落库（T4）。CursorWindow 默认 2MB 的保护挪到读侧——
 * 列表 / 历史查询只取 MESSAGE_COLUMNS 里的 substr 前缀，全文走 MessageDao.textById。
 * 思维链仍按 64K 字符裁，避免一条 thought 把整行顶爆。
 */
internal const val MAX_PERSIST_THOUGHT_CHARS = 64 * 1024

internal fun cappedThoughtForPersist(text: String): String {
    if (text.length <= MAX_PERSIST_THOUGHT_CHARS) return text
    return text.take(MAX_PERSIST_THOUGHT_CHARS) +
        "\n（思维链过长，已截断保存）"
}
