package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.DshResult
import dev.dsh.mobile.core.platform.runCatchingDsh
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
import dev.dsh.mobile.core.model.compaction.CompactResult
import dev.dsh.mobile.core.protocol.Compactor
import dev.dsh.mobile.core.model.event.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import dev.dsh.mobile.core.protocol.LocalEngineTransport
import dev.dsh.mobile.core.model.projection.SessionProjection
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
