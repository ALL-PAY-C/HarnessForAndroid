package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.event.OptionValue
import dev.dsh.mobile.core.model.event.ConfigOption
import dev.dsh.mobile.core.model.event.DshEvent
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.model.PermissionOption
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.protocol.identity.IdentityInjector
import dev.dsh.mobile.core.model.mcp.McpServerConfig
import dev.dsh.mobile.core.model.mcp.McpTransportKind
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
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
     * 会话身份卡文本（IdentityCard.render()，兼容旧 persona）。远程会话没有 LocalEngineTransport
     * 的 extraSystem 字段，只能借用户文本带过去，注入节奏由 [IdentityInjector] 决定。
     * 默认空实现 = 老行为：原文照发。
     */
    private val personaProvider: suspend (String) -> String = { "" },
    /** 补注入间隔（轮）。默认 6；测试可传 1 强制每轮，或传大值只看首轮。 */
    identityEveryTurns: Int = IdentityInjector.DEFAULT_EVERY_TURNS,
    /**
     * T9.10-P4：要随 session/new 下发给引擎的 MCP 服务器。引擎自己连、自己执行，
     * 工具调用走既有 toolCall 事件回流。默认空实现 = 老行为（不下发任何 server）。
     */
    private val mcpServers: () -> List<McpServerConfig> = { emptyList() },
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
        identityEveryTurns: Int = IdentityInjector.DEFAULT_EVERY_TURNS,
        mcpServers: () -> List<McpServerConfig> = { emptyList() },
    ) : this(
        carrier = LineCarrier(scope, channelProvider),
        kind = kind,
        scope = scope,
        muxOptions = MuxOptions.from(reconnectPolicy),
        defaultCwd = defaultCwd,
        personaProvider = personaProvider,
        identityEveryTurns = identityEveryTurns,
        mcpServers = mcpServers,
    )

    /** 事件出口（T9.14 修 1）：唯一实现是 [EventSink]，丢弃统一计数。 */
    private val sink = EventSink<DshEvent>()

    override val events: SharedFlow<DshEvent> = sink.events

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** P5 barrier：prompt 响应后合成的终态 marker 由 collector 消费完再 ACK，保证旧 chunk 全部消费后才放 sendGate。 */
    private val turnAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    /** 身份注入记账：首轮 + 每 N 轮 + 卡变 + 关/续会话 + 压缩后补注入（见 [IdentityInjector]）。 */
    private val identity = IdentityInjector(identityOf = { sessionId -> personaProvider(sessionId) }, everyTurns = identityEveryTurns)

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
        mux.stop("Disconnected")
        // N-2/Q5：唤醒所有在 ack.await() 上等待的 prompt，避免断线期 30s 空等 + turnAcks 泄漏。
        // 注意：这只释放等待，不代表远端旧任务 settled —— 阻塞标记(quarantine)由 repo 层保留。
        turnAcks.values.forEach { it.completeExceptionally(IllegalStateException("Disconnected")) }
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
            if (attempt == 1) RuntimeState.Starting("Connecting") else RuntimeState.Reconnecting(attempt, "Reconnecting")
        is MuxPhase.Reconnecting -> RuntimeState.Reconnecting(attempt, reason)
        is MuxPhase.Ready -> RuntimeState.Ready(kind, "${generation.hostName} ${generation.hostVersion}".trim())
        is MuxPhase.Failed ->
            RuntimeState.Failed(if (attemptsExhausted) "Reconnect failed: $reason" else reason, retryable = true)
    }

    // ------------------------------------------------------------------ inbound

    /** mux 只上抛「带 method 的帧」；响应帧由 mux 按 id 对账。 */
    private suspend fun handleFrame(obj: JsonObject) {
        val method = obj.str("method") ?: return
        val params = obj.obj("params") ?: JsonObject(emptyMap())
        when (method) {
            "session/update" -> handleUpdate(params)
            "session/request_permission" -> handlePermissionRequest(params, obj.long("id"))
            else -> sink.emit(DshEvent.Raw(method, params))
        }
    }

    private fun handleUpdate(params: JsonObject) {
        val sid = params.str("sessionId") ?: return
        val up = params.obj("update") ?: params
        when (val kind = up.str("sessionUpdate")) {
            "agent_message_chunk" -> textOf(up)?.let { sink.emit(DshEvent.MessageChunk(sid, it)) }
            "agent_thought_chunk" -> textOf(up)?.let { sink.emit(DshEvent.ThoughtChunk(sid, it)) }
            "user_message_chunk" -> Unit
            "tool_call", "tool_call_update" -> parseToolCall(sid, up)
            "plan" -> {
                val steps = up.arr("entries")?.mapNotNull { (it as? JsonObject)?.str("content") } ?: emptyList()
                sink.emit(DshEvent.Plan(sid, steps))
            }
            "usage_update" -> parseUsage(sid, up)
            "available_commands_update" -> {
                val cmds = up.arr("availableCommands")
                    ?.mapNotNull { (it as? JsonObject)?.str("name") } ?: emptyList()
                sink.emit(DshEvent.Commands(sid, cmds))
            }
            "config_option_update" -> parseConfigOptions(sid, up)
            else -> sink.emit(DshEvent.Raw("session/update:$kind", up))
        }
    }

    private fun textOf(up: JsonObject): String? {
        val content = up.obj("content") ?: return up.str("text")
        return content.str("text") ?: content.str("delta")
    }

    private fun parseToolCall(sid: String, up: JsonObject) {
        val call = ToolCall(
            id = up.str("toolCallId") ?: up.str("id") ?: return,
            title = up.str("title") ?: up.str("kind") ?: "Tool call",
            kind = up.str("kind") ?: "",
            status = when (up.str("status")) {
                "completed" -> ToolCall.ToolStatus.Completed
                "failed" -> ToolCall.ToolStatus.Failed
                "in_progress" -> ToolCall.ToolStatus.InProgress
                else -> ToolCall.ToolStatus.Pending
            },
            detail = up.str("content") ?: "",
        )
        sink.emit(DshEvent.ToolCallEvent(sid, call))
    }

    private fun parseUsage(sid: String, up: JsonObject) {
        val u = up.obj("usage") ?: up
        val usage = TokenUsage(
            input = u.long("inputTokens") ?: u.long("input") ?: 0L,
            output = u.long("outputTokens") ?: u.long("output") ?: 0L,
            cached = u.long("cachedReadTokens") ?: u.long("cached") ?: 0L,
        )
        sink.emit(DshEvent.Usage(sid, usage, cumulative = true))
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
        sink.emit(DshEvent.ConfigOptions(sid, options))
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
        val title = params.obj("toolCall")?.str("title") ?: "Permission request"
        sink.emit(DshEvent.Permission(sid, PermissionRequest(sid, reqId, title, options)))
    }

    // ------------------------------------------------------------------- rpc

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        mux.call(method, params, timeoutMs)

    // ------------------------------------------------------------- convenience

    /**
     * 下发给 session/new 的 mcpServers 数组（T9.10-P4）。
     *
     * 形状严格照引擎 schema：http/sse → {name,url,headers:[{name,value}],type}。
     * headers 是数组不是 map，写成对象引擎直接拒。
     * stdio 不下发：Android 上本应用拿不到可执行路径，由引擎侧自行配置。
     * provider 抛异常时退化成空数组 —— MCP 配置坏了不能阻断建会话。
     */
    private fun mcpServersParam(): JsonArray {
        val configs = try {
            mcpServers()
        } catch (t: Throwable) {
            AppLog.w("mcp servers unavailable: " + (t.message ?: t::class.simpleName))
            return JsonArray(emptyList())
        }
        return buildJsonArray {
            configs.forEach { cfg ->
                val type = when (cfg.transport) {
                    McpTransportKind.Http -> "http"
                    McpTransportKind.Sse -> "sse"
                    McpTransportKind.Stdio -> return@forEach
                }
                if (!cfg.enabled || !engineSupports(cfg.transport)) return@forEach
                add(
                    buildJsonObject {
                        put("name", cfg.name)
                        put("url", cfg.url)
                        put("type", type)
                        put("headers", buildJsonArray {
                            cfg.headers.forEach { h ->
                                add(buildJsonObject { put("name", h.name); put("value", h.value) })
                            }
                        })
                    },
                )
            }
        }
    }

    /**
     * 能力门控（init.capabilities.session.mcp.<http|sse>）：显式 false 才剔除该档。
     * 字段缺失 = 旧引擎没报这个能力，按「支持」处理，免得老引擎一条都发不出去。
     */
    private fun engineSupports(kind: McpTransportKind): Boolean {
        val caps = mux.currentGeneration?.capabilities ?: return true
        val key = when (kind) {
            McpTransportKind.Http -> "http"
            McpTransportKind.Sse -> "sse"
            McpTransportKind.Stdio -> return false
        }
        // 逐层安全转型：引擎给的 capabilities 形状不可信，畸形时按「支持」处理而不是抛穿建会话。
        val session = caps["session"] as? JsonObject ?: return true
        val mcp = session["mcp"] as? JsonObject ?: return true
        return (mcp[key] as? JsonPrimitive)?.booleanOrNull ?: true
    }

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
                put("mcpServers", mcpServersParam())
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
        // P4：session/resume 不接受 mcpServers（引擎协议里只有 session/new 带），恢复的会话
        // 沿用创建时的服务器集合。所以配置改动不热更新：新配置从下一个新会话开始生效。
        // 续接 = 远端历史可能已被裁剪过，身份必须重新落一次。
        identity.invalidate(sessionId)
        val res = mux.call("session/resume", buildJsonObject { put("sessionId", sessionId) }, 120_000L)
        return res.toSession().copy(id = sessionId)
    }

    override suspend fun closeSession(sessionId: String) {
        // 会话关了，注入记账跟着作废：同名重建时要从第 1 轮重新注入。
        identity.forget(sessionId)
        mux.call("session/close", buildJsonObject { put("sessionId", sessionId) }, 20_000L)
    }

    override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
        val body = identity.wrap(sessionId, text)
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
            // P5 barrier：响应到达后把带 turnId 的终态 marker 可靠送入事件流（走挂起发送，不用非挂起门面），
            // 并等 collector 消费完 marker（即消费完所有先到的旧 chunk）后才返回。
            // 这样 sendGate 释放时旧流一定已被消费，不会污染新轮。
            val ack = CompletableDeferred<Unit>()
            turnAcks[turnId] = ack
            try {
                // N-3：emit 与 ack 同纳入有界等待 —— collector 卡死时 buffer 满 emit 会无限挂起，
                // 必须整体受超时约束，超时抛明确 Err（不假成功、不永挂、不泄漏 turnAcks）。
                withTimeout(30_000L) {
                    sink.emitOrAwait(DshEvent.TurnEnd(sessionId, stopReason, turnId = turnId))
                    ack.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Timed out consuming the terminal state (collector did not ACK)", e)
            } finally {
                turnAcks.remove(turnId)
            }
        }
        return stopReason
    }

    /**
     * 远端侧发生压缩（历史被摘要替换）后调用：身份文本已经不在历史里了，下一轮必须重发。
     * 挂点是本类对外的唯一入口——压缩在远端服务端完成，本地看不到过程，只能由知道结果的
     * 一方（事件流里的 [DshEvent.Compacted] 消费者）回调进来。
     */
    fun onCompacted(sessionId: String) {
        identity.invalidate(sessionId)
    }

    /**
     * 事件流版本（T9.8 S3）：温层字段只是记账，重注入动作与 [onCompacted] 完全相同。
     * 本地引擎走 [dev.dsh.mobile.core.protocol.LocalEngineTransport] 的温层存储，不经过这里。
     */
    fun onCompacted(event: DshEvent.Compacted) {
        onCompacted(event.sessionId)
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
                AppLog.w("Unknown permission requestId=$requestId: already answered, or invalidated by a connection rebuild")
                throw IllegalStateException("Unknown permission requestId=$requestId")
            }
        if (!mux.isAttached) {
            AppLog.w("Not connected; cannot answer permission requestId=$requestId")
            throw IllegalStateException("Not connected; cannot answer permission requestId=$requestId")
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
