package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.PermissionOption
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.protocol.transport.DshCarrier
import dev.dsh.mobile.core.protocol.transport.DshMux
import dev.dsh.mobile.core.protocol.transport.LineCarrier
import dev.dsh.mobile.core.protocol.transport.MuxOptions
import dev.dsh.mobile.core.protocol.transport.MuxPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * ACP v1 的**协议语义**层：会话、事件归一化、权限答复、prompt 终态 barrier。
 *
 * 连接正确性已经搬走：帧搬运在 [DshCarrier]，握手/世代/退避重连/请求-响应配对在 [DshMux]。
 * 本类只组合「一个 carrier + 一个 mux」，不再持有 channel、不再写重连分支。
 * 对外行为（谁先发什么、超时、错误文案）与改造前逐字一致，见 research/T64-carrier.md。
 */
class AcpTransport(
    carrier: DshCarrier,
    private val kind: TransportKind,
    scope: CoroutineScope,
    muxOptions: MuxOptions = MuxOptions(),
    private val defaultCwd: () -> String = { "/" },
    /**
     * 会话人设 / 额外系统提示。远程会话没有 LocalEngineTransport 的 extraSystem 字段，
     * 只能在首条用户文本上补一次（见 [personaWrapped]）。默认空实现 = 老行为：原文照发。
     */
    private val personaProvider: suspend (String) -> String = { "" },
) : DshTransport {

    /**
     * 兼容构造：一行字符串进出的 [LineChannel] 自动包成 carrier。
     * 现有调用方与 AcpTransportTest 都走这条，行为不变。
     */
    constructor(
        channelProvider: suspend () -> LineChannel,
        kind: TransportKind,
        scope: CoroutineScope,
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        defaultCwd: () -> String = { "/" },
        personaProvider: suspend (String) -> String = { "" },
    ) : this(
        carrier = LineCarrier(scope, channelProvider),
        kind = kind,
        scope = scope,
        muxOptions = MuxOptions.from(reconnectPolicy),
        defaultCwd = defaultCwd,
        personaProvider = personaProvider,
    )

    private val _events = MutableSharedFlow<DshEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<DshEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** P5 barrier：prompt 响应后合成的终态 marker 由 collector 消费完再 ACK，保证旧 chunk 全部消费后才放 sendGate。 */
    private val turnAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** 已注入过人设的会话 → 那次注入的原文。人设没变就不重复注入，别每轮都往历史里塞一遍。 */
    private val personaInjected = ConcurrentHashMap<String, String>()

    private val mux = DshMux(
        carrier = carrier,
        scope = scope,
        options = muxOptions,
        onFrame = { frame -> handleFrame(frame) },
        onPhase = { phase -> _state.value = phase.toRuntimeState() },
    )

    // ---------------------------------------------------------------- lifecycle

    override suspend fun connect() {
        mux.start()
    }

    override suspend fun disconnect() {
        mux.stop("已断开")
        // N-2/Q5：唤醒所有在 ack.await() 上等待的 prompt，避免断线期 30s 空等 + turnAcks 泄漏。
        // 注意：这只释放等待，不代表远端旧任务 settled —— 阻塞标记(quarantine)由 repo 层保留。
        turnAcks.values.forEach { it.completeExceptionally(IllegalStateException("已断开")) }
        turnAcks.clear()
        _state.value = RuntimeState.NotConfigured
    }

    /**
     * mux 相位 → 对外状态。用户可见文案全部留在这里（与改造前逐字相同）：
     * 首次建链「连接中」、重连中「重连中」、退避用尽「重连失败：<原因>」。
     */
    private fun MuxPhase.toRuntimeState(): RuntimeState = when (this) {
        MuxPhase.Idle -> RuntimeState.NotConfigured
        is MuxPhase.Connecting ->
            if (attempt == 1) RuntimeState.Starting("连接中") else RuntimeState.Reconnecting(attempt, "重连中")
        is MuxPhase.Reconnecting -> RuntimeState.Reconnecting(attempt, reason)
        is MuxPhase.Ready -> RuntimeState.Ready(kind, "${generation.hostName} ${generation.hostVersion}".trim())
        is MuxPhase.Failed ->
            RuntimeState.Failed(if (attemptsExhausted) "重连失败：$reason" else reason, retryable = true)
    }

    // ------------------------------------------------------------------ inbound

    /** mux 只上抛「带 method 的帧」；响应帧由 mux 按 id 对账。 */
    private suspend fun handleFrame(obj: JsonObject) {
        val method = obj.str("method") ?: return
        val params = obj.obj("params") ?: JsonObject(emptyMap())
        when (method) {
            "session/update" -> handleUpdate(params)
            "session/request_permission" -> handlePermissionRequest(params, obj.long("id"))
            else -> _events.tryEmit(DshEvent.Raw(method, params))
        }
    }

    private fun handleUpdate(params: JsonObject) {
        val sid = params.str("sessionId") ?: return
        val up = params.obj("update") ?: params
        when (val kind = up.str("sessionUpdate")) {
            "agent_message_chunk" -> textOf(up)?.let { _events.tryEmit(DshEvent.MessageChunk(sid, it)) }
            "agent_thought_chunk" -> textOf(up)?.let { _events.tryEmit(DshEvent.ThoughtChunk(sid, it)) }
            "user_message_chunk" -> Unit
            "tool_call", "tool_call_update" -> parseToolCall(sid, up)
            "plan" -> {
                val steps = up.arr("entries")?.mapNotNull { (it as? JsonObject)?.str("content") } ?: emptyList()
                _events.tryEmit(DshEvent.Plan(sid, steps))
            }
            "usage_update" -> parseUsage(sid, up)
            "available_commands_update" -> {
                val cmds = up.arr("availableCommands")
                    ?.mapNotNull { (it as? JsonObject)?.str("name") } ?: emptyList()
                _events.tryEmit(DshEvent.Commands(sid, cmds))
            }
            "config_option_update" -> parseConfigOptions(sid, up)
            else -> _events.tryEmit(DshEvent.Raw("session/update:$kind", up))
        }
    }

    private fun textOf(up: JsonObject): String? {
        val content = up.obj("content") ?: return up.str("text")
        return content.str("text") ?: content.str("delta")
    }

    private fun parseToolCall(sid: String, up: JsonObject) {
        val call = ToolCall(
            id = up.str("toolCallId") ?: up.str("id") ?: return,
            title = up.str("title") ?: up.str("kind") ?: "工具调用",
            kind = up.str("kind") ?: "",
            status = when (up.str("status")) {
                "completed" -> ToolCall.ToolStatus.Completed
                "failed" -> ToolCall.ToolStatus.Failed
                "in_progress" -> ToolCall.ToolStatus.InProgress
                else -> ToolCall.ToolStatus.Pending
            },
            detail = up.str("content") ?: "",
        )
        _events.tryEmit(DshEvent.ToolCallEvent(sid, call))
    }

    private fun parseUsage(sid: String, up: JsonObject) {
        val u = up.obj("usage") ?: up
        val usage = TokenUsage(
            input = u.long("inputTokens") ?: u.long("input") ?: 0L,
            output = u.long("outputTokens") ?: u.long("output") ?: 0L,
            cached = u.long("cachedReadTokens") ?: u.long("cached") ?: 0L,
        )
        _events.tryEmit(DshEvent.Usage(sid, usage, cumulative = true))
    }

    private fun parseConfigOptions(sid: String, up: JsonObject) {
        val arr = up.arr("configOptions") ?: return
        val options = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ConfigOption(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: o.str("id") ?: "",
                currentValue = o.str("currentValue") ?: "",
                values = o.arr("options")?.mapNotNull { v ->
                    val vo = v as? JsonObject ?: return@mapNotNull null
                    OptionValue(vo.str("value") ?: "", vo.str("name") ?: vo.str("label") ?: "")
                } ?: emptyList(),
            )
        }
        _events.tryEmit(DshEvent.ConfigOptions(sid, options))
    }

    private fun handlePermissionRequest(params: JsonObject, rpcId: Long?) {
        val sid = params.str("sessionId") ?: return
        val options = params.arr("options")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PermissionOption(
                optionId = o.str("optionId") ?: return@mapNotNull null,
                name = o.str("name") ?: "",
                kind = o.str("kind") ?: "",
            )
        } ?: emptyList()
        val reqId = rpcId?.toString() ?: mux.nextRequestId().toString()
        if (rpcId != null) mux.registerApproval(reqId, rpcId)
        val title = params.obj("toolCall")?.str("title") ?: "请求权限"
        _events.tryEmit(DshEvent.Permission(sid, PermissionRequest(sid, reqId, title, options)))
    }

    // ------------------------------------------------------------------- rpc

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        mux.call(method, params, timeoutMs)

    // ------------------------------------------------------------- convenience

    override suspend fun listSessions(cwd: String?): List<Session> {
        val res = mux.call(
            "session/list",
            buildJsonObject { if (cwd != null) put("cwd", cwd) },
            30_000L,
        )
        val arr = res.arr("sessions") ?: JsonArray(emptyList())
        return arr.mapNotNull { el -> (el as? JsonObject)?.toSession() }
    }

    override suspend fun newSession(cwd: String): Session {
        val res = mux.call(
            "session/new",
            buildJsonObject {
                put("cwd", cwd.ifBlank { defaultCwd() })
                put("mcpServers", buildJsonArray { })
            },
            120_000L,
        )
        val session = res.toSession()
        res.arr("configOptions")?.let {
            parseConfigOptions(session.id, JsonObject(mapOf("configOptions" to JsonArray(it))))
        }
        return session
    }

    override suspend fun resumeSession(sessionId: String): Session {
        val res = mux.call("session/resume", buildJsonObject { put("sessionId", sessionId) }, 120_000L)
        return res.toSession().copy(id = sessionId)
    }

    override suspend fun closeSession(sessionId: String) {
        // 会话关了，人设注入记录跟着作废：同名重建时要能重新注入一次。
        personaInjected.remove(sessionId)
        mux.call("session/close", buildJsonObject { put("sessionId", sessionId) }, 20_000L)
    }

    override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
        val body = personaWrapped(sessionId, text)
        val res = mux.call(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", body) }) })
            },
            timeoutMs = 30 * 60_000L,
        )
        val stopReason = res.str("stopReason") ?: "end_turn"
        if (turnId != null) {
            // P5 barrier：响应到达后把带 turnId 的终态 marker 可靠送入事件流（emit 非 tryEmit），
            // 并等 collector 消费完 marker（即消费完所有先到的旧 chunk）后才返回。
            // 这样 sendGate 释放时旧流一定已被消费，不会污染新轮。
            val ack = CompletableDeferred<Unit>()
            turnAcks[turnId] = ack
            try {
                // N-3：emit 与 ack 同纳入有界等待 —— collector 卡死时 buffer 满 emit 会无限挂起，
                // 必须整体受超时约束，超时抛明确 Err（不假成功、不永挂、不泄漏 turnAcks）。
                withTimeout(30_000L) {
                    _events.emit(DshEvent.TurnEnd(sessionId, stopReason, turnId = turnId))
                    ack.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("终态消费超时（collector 未 ACK）", e)
            } finally {
                turnAcks.remove(turnId)
            }
        }
        return stopReason
    }

    /**
     * 把会话人设前置到本轮文本上，同一人设只注入一次（改过之后下一轮重新注入）。
     *
     * 远程 transport 的 session/prompt 没有「系统提示」字段，ACPP 端也不会替我们拼 persona，
     * 所以只能借第一条用户消息带过去：让它出现在远端历史里一次，而不是每轮重复一遍。
     */
    private suspend fun personaWrapped(sessionId: String, text: String): String {
        val persona = runCatching { personaProvider(sessionId) }.getOrNull().orEmpty().trim()
        if (persona.isEmpty()) {
            // 人设被清空：顺手丢掉「已注入」的记账，否则同一份人设再打开时会被当成已注入而跳过。
            personaInjected.remove(sessionId)
            return text
        }
        if (personaInjected[sessionId] == persona) return text
        personaInjected[sessionId] = persona
        return "用户为这个会话设定的人设与规则，请始终遵守：\n" + persona + "\n\n" + text
    }

    override suspend fun cancel(sessionId: String) {
        // ACP cancel 是通知（服务端 .onNotification，无 id 无响应），发完即回；
        // 真正终态由 prompt 响应（settle 后 resolve "cancelled"）作为 barrier。
        mux.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    override fun ackTurn(turnId: String) {
        turnAcks.remove(turnId)?.complete(Unit)
    }

    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) {
        mux.call(
            "session/set_config_option",
            buildJsonObject {
                put("sessionId", sessionId)
                put("configId", optionId)
                put("value", value)
            },
            20_000L,
        )
    }

    override suspend fun replyPermission(sessionId: String, requestId: String, optionId: String?) {
        val rpcId = mux.takeApproval(requestId)
            ?: run {
                AppLog.w("未知 permission requestId=$requestId：已答复或连接重建后 requestId 失效")
                throw IllegalStateException("未知 permission requestId=$requestId")
            }
        if (!mux.isAttached) {
            AppLog.w("未连接，无法答复 permission requestId=$requestId")
            throw IllegalStateException("未连接，无法答复 permission requestId=$requestId")
        }
        mux.sendResult(
            rpcId,
            buildJsonObject {
                if (optionId != null) {
                    put("outcome", buildJsonObject {
                        put("outcome", "selected")
                        put("optionId", optionId)
                    })
                } else {
                    put("outcome", buildJsonObject { put("outcome", "cancelled") })
                }
            },
        )
    }

    // ------------------------------------------------------------------ mapping

    private fun JsonObject.toSession(): Session {
        val id = str("sessionId") ?: str("id") ?: ""
        return Session(
            id = id,
            title = str("title") ?: "",
            cwd = str("cwd") ?: "",
            updatedAt = long("updatedAt") ?: 0L,
            createdAt = long("createdAt") ?: 0L,
            status = SessionStatus.Idle,
        )
    }
}
