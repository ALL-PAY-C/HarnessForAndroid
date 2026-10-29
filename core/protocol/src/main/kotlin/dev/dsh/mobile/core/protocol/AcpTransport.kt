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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ACP v1 (JSON-RPC 2.0, newline delimited) over any [LineChannel].
 * Owns request/response correlation, notification normalization, permission replies
 * and reconnect/backoff. Independent of whether the channel is a socket or a process.
 */
class AcpTransport(
    private val channelProvider: suspend () -> LineChannel,
    private val kind: TransportKind,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val defaultCwd: () -> String = { "/" },
) : DshTransport {

    private val _events = MutableSharedFlow<DshEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<DshEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val pendingPermissions = ConcurrentHashMap<String, Long>()
    /** P5 barrier：prompt 响应后合成的终态 marker 由 collector 消费完再 ACK，保证旧 chunk 全部消费后才放 sendGate。 */
    private val turnAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile private var channel: LineChannel? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var manualClose = false

    // ---------------------------------------------------------------- lifecycle

    override suspend fun connect() {
        manualClose = false
        reconnectJob?.cancel()
        doConnect(attempt = 1)
    }

    private suspend fun doConnect(attempt: Int) {
        _state.value =
            if (attempt == 1) RuntimeState.Starting("连接中") else RuntimeState.Reconnecting(attempt, "重连中")
        val ch = try {
            channelProvider().also { it.open() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("channel open failed: ${t.message}")
            scheduleReconnect(attempt, t.message ?: "连接失败")
            return
        }
        channel = ch
        readJob?.cancel()
        readJob = scope.launch { readLoop(ch) }
        try {
            val init = callInternal(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", 1)
                    put(
                        "clientCapabilities",
                        buildJsonObject {
                            put("fs", buildJsonObject {
                                put("readTextFile", false)
                                put("writeTextFile", false)
                            })
                            put("terminal", false)
                        },
                    )
                },
                timeoutMs = 20_000L,
            )
            val agentName = init.obj("agentInfo")?.str("name") ?: "dsh"
            val version = init.obj("agentInfo")?.str("version") ?: ""
            _state.value = RuntimeState.Ready(kind, "$agentName $version".trim())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("initialize failed: ${t.message}")
            scheduleReconnect(attempt, t.message ?: "初始化失败")
        }
    }

    private fun scheduleReconnect(attempt: Int, reason: String) {
        if (manualClose || !reconnectPolicy.enabled) {
            _state.value = RuntimeState.Failed(reason, retryable = true)
            return
        }
        if (attempt > reconnectPolicy.maxAttempts) {
            _state.value = RuntimeState.Failed("重连失败：$reason", retryable = true)
            return
        }
        _state.value = RuntimeState.Reconnecting(attempt, reason)
        reconnectJob = scope.launch {
            delay(reconnectPolicy.delayFor(attempt))
            if (!manualClose) doConnect(attempt + 1)
        }
    }

    override suspend fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        readJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        failAllPending("已断开")
        // N-2/Q5：唤醒所有在 ack.await() 上等待的 prompt，避免断线期 30s 空等 + turnAcks 泄漏。
        // 注意：这只释放等待，不代表远端旧任务 settled —— 阻塞标记(quarantine)由 repo 层保留。
        turnAcks.values.forEach { it.completeExceptionally(IllegalStateException("已断开")) }
        turnAcks.clear()
        _state.value = RuntimeState.NotConfigured
    }

    private fun failAllPending(msg: String) {
        pending.forEach { (_, d) -> d.completeExceptionally(IllegalStateException(msg)) }
        pending.clear()
    }

    // ------------------------------------------------------------------ io loop

    private suspend fun readLoop(ch: LineChannel) {
        try {
            ch.incoming.collect { line ->
                if (line.isBlank()) return@collect
                val el = runCatching { DshJson.parseToJsonElement(line) }.getOrNull()
                if (el is JsonObject) handleMessage(el) else AppLog.d("non-json: ${line.take(120)}")
            }
            if (!manualClose) {
                failAllPending("连接已关闭")
                scheduleReconnect(1, "连接已关闭")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (!manualClose) {
                val reason = t.message ?: "读取中断"
                failAllPending(reason)
                scheduleReconnect(1, reason)
            }
        }
    }

    private fun handleMessage(obj: JsonObject) {
        val method = obj.str("method")
        val id = obj.long("id")
        if (method == null) {
            if (id != null) {
                val d = pending.remove(id) ?: return
                val err = obj.obj("error")
                if (err != null) {
                    d.completeExceptionally(IllegalStateException(err.str("message") ?: "rpc error"))
                } else {
                    d.complete(obj.obj("result") ?: JsonObject(emptyMap()))
                }
            }
            return
        }
        val params = obj.obj("params") ?: JsonObject(emptyMap())
        when (method) {
            "session/update" -> handleUpdate(params)
            "session/request_permission" -> handlePermissionRequest(params, id)
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
        val reqId = rpcId?.toString() ?: nextId.getAndIncrement().toString()
        if (rpcId != null) pendingPermissions[reqId] = rpcId
        val title = params.obj("toolCall")?.str("title") ?: "请求权限"
        _events.tryEmit(DshEvent.Permission(sid, PermissionRequest(sid, reqId, title, options)))
    }

    // ------------------------------------------------------------------- rpc

    private suspend fun callInternal(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val ch = channel ?: throw IllegalStateException("未连接")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        ch.send(DshJson.encodeToString(JsonObject.serializer(), payload))
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 发一条 JSON-RPC 通知（无 id、无响应、不挂 pending）。用于 cancel 等 fire-and-forget 语义。 */
    private suspend fun notify(method: String, params: JsonObject) {
        val ch = channel ?: throw IllegalStateException("未连接")
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        ch.send(DshJson.encodeToString(JsonObject.serializer(), payload))
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        callInternal(method, params, timeoutMs)

    // ------------------------------------------------------------- convenience

    override suspend fun listSessions(cwd: String?): List<Session> {
        val res = callInternal(
            "session/list",
            buildJsonObject { if (cwd != null) put("cwd", cwd) },
            30_000L,
        )
        val arr = res.arr("sessions") ?: JsonArray(emptyList())
        return arr.mapNotNull { el -> (el as? JsonObject)?.toSession() }
    }

    override suspend fun newSession(cwd: String): Session {
        val res = callInternal(
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
        val res = callInternal("session/resume", buildJsonObject { put("sessionId", sessionId) }, 120_000L)
        return res.toSession().copy(id = sessionId)
    }

    override suspend fun closeSession(sessionId: String) {
        callInternal("session/close", buildJsonObject { put("sessionId", sessionId) }, 20_000L)
    }

    override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
        val res = callInternal(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
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

    override suspend fun cancel(sessionId: String) {
        // ACP cancel 是通知（服务端 .onNotification，无 id 无响应），发完即回；
        // 真正终态由 prompt 响应（settle 后 resolve "cancelled"）作为 barrier。
        notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    override fun ackTurn(turnId: String) {
        turnAcks.remove(turnId)?.complete(Unit)
    }

    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) {
        callInternal(
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
        val rpcId = pendingPermissions.remove(requestId)
            ?: run {
                AppLog.w("未知 permission requestId=$requestId：已答复或连接重建后 requestId 失效")
                throw IllegalStateException("未知 permission requestId=$requestId")
            }
        val ch = channel
            ?: run {
                AppLog.w("未连接，无法答复 permission requestId=$requestId")
                throw IllegalStateException("未连接，无法答复 permission requestId=$requestId")
            }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put(
                "result",
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
        ch.send(DshJson.encodeToString(JsonObject.serializer(), payload))
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
