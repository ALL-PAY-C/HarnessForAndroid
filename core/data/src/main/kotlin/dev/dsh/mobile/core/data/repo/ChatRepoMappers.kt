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
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.Compactor
import dev.dsh.mobile.core.model.event.DshEvent
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

// ---------------------------------------------------------------- mapping

fun SessionEntity.toModel(): Session = Session(
    id = id,
    title = title,
    cwd = cwd,
    updatedAt = updatedAt,
    createdAt = createdAt,
    status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.Idle),
    preview = preview,
    messageCount = messageCount,
    usage = TokenUsage(input = inputTokens, output = outputTokens, cached = cachedTokens, lastPrompt = lastPrompt),
    pinned = pinned == 1,
    archived = archived == 1,
    group = grp,
    kind = runCatching { SessionKind.valueOf(kind) }.getOrDefault(SessionKind.Single),
    agentId = agentId,
    persona = persona,
    participants = runCatching {
        toolJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
            participantsJson.ifBlank { "[]" },
        ).map { Participant(it.id, it.modelId, it.name, it.persona, it.slot, it.role) }
    }.getOrDefault(emptyList()),
    remoteId = remoteId,
    endpointId = endpointId,
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    cwd = cwd,
    updatedAt = updatedAt,
    createdAt = createdAt,
    status = status.name,
    preview = preview,
    messageCount = messageCount,
    inputTokens = usage.input,
    outputTokens = usage.output,
    pinned = if (pinned) 1 else 0,
    archived = if (archived) 1 else 0,
    grp = group,
    kind = kind.name,
    agentId = agentId,
    participantsJson = toolJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ParticipantDto.serializer()),
        participants.map { ParticipantDto(it.id, it.modelId, it.name, it.persona, it.slot, it.role) },
    ),
    persona = persona,
    remoteId = remoteId,
    endpointId = endpointId,
)

/**
 * 远端会话只补它权威的两列：时间戳只前进、状态以远端为准。
 * 本地字段（标题、人设、绑定模型、分组）不被远端覆盖。
 */
fun mergeRemoteSession(local: SessionEntity?, remote: Session): SessionEntity {
    if (local == null) return remote.toEntity()
    return local.copy(
        updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        status = remote.status.name,
    )
}

fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = runCatching { ChatMessage.Role.valueOf(role) }.getOrDefault(ChatMessage.Role.SYSTEM),
    text = text,
    thought = thought,
    toolCalls = runCatching {
        toolJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ToolCallDto.serializer()),
            toolCallsJson,
        ).map {
            ToolCall(
                id = it.id,
                title = it.title,
                kind = it.kind,
                status = runCatching { ToolCall.ToolStatus.valueOf(it.status) }
                    .getOrDefault(ToolCall.ToolStatus.Completed),
                detail = it.detail,
                args = it.args,
                requestJson = it.requestJson,
                responseJson = it.responseJson,
                elapsedMs = it.elapsedMs,
                attempts = it.attempts,
            )
        }
    }.getOrDefault(emptyList()),
    at = at,
    streaming = streaming == 1,
    authorId = authorId,
    authorName = authorName,
    attachments = runCatching {
        org.json.JSONArray(attachmentsJson).let { a -> (0 until a.length()).map { a.optString(it) } }
    }.getOrDefault(emptyList()).filter { it.isNotBlank() },
    durationMs = durationMs,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
)

/**
 * LIKE 模式转义，配合 SQL 里的 ESCAPE '\'：先转反斜杠再转 % 和 _，顺序不能换
 * （先转 % 会把新加的反斜杠再转一遍）。不转的话用户搜「100%」会把 % 当通配符命中全库。
 */
internal fun escapeLike(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
