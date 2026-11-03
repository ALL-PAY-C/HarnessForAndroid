package dev.dsh.mobile.core.protocol.transport

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.protocol.ReconnectPolicy
import dev.dsh.mobile.core.protocol.long
import dev.dsh.mobile.core.protocol.obj
import dev.dsh.mobile.core.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次成功握手 = 一个世代。上层据此判断「手上的 id / 流 / 待批准请求」是否还属于当前连接。
 */
data class MuxGeneration(
    val number: Int,
    val hostName: String,
    val hostVersion: String,
    val protocolVersion: Int = 1,
)

/**
 * 退避表是唯一的重连来源（改造前在 [ReconnectPolicy]）。
 * [readyTimeoutMs] / [pingIntervalMs] 从现有常量搬：WS 建链 15s 超时、`Endpoint.heartbeatMs` 默认 15s。
 */
data class MuxOptions(
    val readyTimeoutMs: Long = 15_000L,
    val pingIntervalMs: Long = 15_000L,
    /** 第 n 次重连前等多久；长度即最大重连次数。 */
    val retryCapsMs: List<Long> = ReconnectPolicy().retryCaps(),
    val autoReconnect: Boolean = true,
) {
    fun delayFor(attempt: Int): Long = retryCapsMs.getOrElse(attempt - 1) { retryCapsMs.lastOrNull() ?: 0L }

    companion object {
        /** 旧构造入口（`ReconnectPolicy`）→ 新退避表，数值逐档相等。 */
        fun from(policy: ReconnectPolicy) = MuxOptions(
            retryCapsMs = policy.retryCaps(),
            autoReconnect = policy.enabled,
        )
    }
}

/** `ReconnectPolicy.delayFor(1..maxAttempts)` 展开成退避表：500,1000,…,20000。 */
fun ReconnectPolicy.retryCaps(): List<Long> = (1..maxAttempts).map { delayFor(it) }

/**
 * mux 的相位。只描述连接事实，不含任何 UI 文案 —— 文案映射留在业务层，
 * 这样「错误文案与改造前一致」是编译期可见的一件事。
 */
sealed interface MuxPhase {
    data object Idle : MuxPhase
    data class Connecting(val attempt: Int) : MuxPhase
    data class Reconnecting(val attempt: Int, val reason: String) : MuxPhase
    data class Ready(val generation: MuxGeneration) : MuxPhase
    /** [attemptsExhausted] = 退避表用尽（改造前 `attempt > maxAttempts` 那一支）。 */
    data class Failed(val reason: String, val attemptsExhausted: Boolean) : MuxPhase
}

/**
 * 第 2 层：一个 [DshCarrier] 上只写一次的东西 —— 世代、ready 握手、退避重连、
 * 流重开、待批准请求、请求-响应配对。
 *
 * 相位用回调 [onPhase] 而不是 StateFlow：每个 transport 少一个常驻 collector 协程，
 * 且状态赋值与改造前一样发生在 mux 自己的协程里。
 */
class DshMux(
    private val carrier: DshCarrier,
    private val scope: CoroutineScope,
    private val options: MuxOptions = MuxOptions(),
    /** 非响应帧（带 method 的）交给上层；响应帧在这里按 id 对账吃掉。 */
    private val onFrame: suspend (JsonObject) -> Unit = {},
    private val onPhase: (MuxPhase) -> Unit = {},
) {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    /** 待批准请求：requestId → 原始 rpc id。世代切换（断链）时整批作废。 */
    private val approvals = ConcurrentHashMap<String, Long>()

    /**
     * 逻辑流重开表：世代 +1 后逐条重开。
     * ACP v1 没有流 id 概念（session/update 靠 method 分发），所以今天没有调用方；
     * 它是给 HTTP/SSE 类载体留的接缝。ponytail: 无载体使用时整块可删，不影响现有行为。
     */
    private val streams = ConcurrentHashMap<String, suspend (MuxGeneration) -> Unit>()

    private val _generations = MutableSharedFlow<MuxGeneration>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val generations: SharedFlow<MuxGeneration> = _generations.asSharedFlow()

    @Volatile private var generation: MuxGeneration? = null

    /** 已建过链且没被 [stop]。断链后仍为 true —— 与改造前 `channel != null` 同义。 */
    @Volatile private var attached = false

    @Volatile private var stopped = true

    private var frameJob: Job? = null
    private var lossJob: Job? = null
    private var reconnectJob: Job? = null

    val currentGeneration: MuxGeneration? get() = generation
    val isAttached: Boolean get() = attached

    /** 与改造前共用一个 AtomicLong：rpc id 与合成 permission id 同源。 */
    fun nextRequestId(): Long = nextId.getAndIncrement()

    fun registerApproval(requestId: String, rpcId: Long) {
        approvals[requestId] = rpcId
    }

    fun takeApproval(requestId: String): Long? = approvals.remove(requestId)

    fun openStream(streamId: String, reopen: suspend (MuxGeneration) -> Unit) {
        streams[streamId] = reopen
    }

    fun closeStream(streamId: String) {
        streams.remove(streamId)
    }

    // ---------------------------------------------------------------- lifecycle

    suspend fun start() {
        stopped = false
        reconnectJob?.cancel()
        frameJob?.cancel()
        lossJob?.cancel()
        // 先订阅再建链：断链事件与帧都不能丢在订阅之前。
        frameJob = scope.launch { carrier.frames.collect { dispatch(it) } }
        lossJob = scope.launch { carrier.losses.collect { onLoss(it) } }
        connect(attempt = 1)
    }

    suspend fun stop(reason: String = "已断开") {
        stopped = true
        reconnectJob?.cancel()
        frameJob?.cancel()
        lossJob?.cancel()
        runCatching { carrier.close() }
        attached = false
        failPending(reason)
        approvals.clear()
        onPhase(MuxPhase.Idle)
    }

    private suspend fun connect(attempt: Int) {
        onPhase(MuxPhase.Connecting(attempt))
        try {
            withTimeout(options.readyTimeoutMs) { carrier.connect() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("channel open failed: ${t.message}")
            scheduleReconnect(attempt, t.message ?: "连接失败")
            return
        }
        attached = true
        val init = try {
            call(INITIALIZE, initializeParams(), INITIALIZE_TIMEOUT_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("initialize failed: ${t.message}")
            scheduleReconnect(attempt, t.message ?: "初始化失败")
            return
        }
        val g = MuxGeneration(
            number = (generation?.number ?: 0) + 1,
            hostName = init.obj("agentInfo")?.str("name") ?: "dsh",
            hostVersion = init.obj("agentInfo")?.str("version") ?: "",
            protocolVersion = init.long("protocolVersion")?.toInt() ?: 1,
        )
        generation = g
        onPhase(MuxPhase.Ready(g))
        _generations.tryEmit(g)
        streams.values.forEach { reopen -> scope.launch { runCatching { reopen(g) } } }
    }

    private fun scheduleReconnect(attempt: Int, reason: String) {
        if (stopped || !options.autoReconnect) {
            onPhase(MuxPhase.Failed(reason, attemptsExhausted = false))
            return
        }
        if (attempt > options.retryCapsMs.size) {
            onPhase(MuxPhase.Failed(reason, attemptsExhausted = true))
            return
        }
        onPhase(MuxPhase.Reconnecting(attempt, reason))
        reconnectJob = scope.launch {
            delay(options.delayFor(attempt))
            if (!stopped) connect(attempt + 1)
        }
    }

    private suspend fun onLoss(loss: CarrierLoss) {
        if (stopped) return
        failPending(loss.reason)
        approvals.clear()
        scheduleReconnect(1, loss.reason)
    }

    private fun failPending(msg: String) {
        pending.forEach { (_, d) -> d.completeExceptionally(IllegalStateException(msg)) }
        pending.clear()
    }

    // --------------------------------------------------------------- dispatch

    private suspend fun dispatch(frame: JsonObject) {
        val method = frame.str("method")
        if (method == null) {
            val id = frame.long("id") ?: return
            val d = pending.remove(id) ?: return
            val err = frame.obj("error")
            if (err != null) {
                d.completeExceptionally(IllegalStateException(err.str("message") ?: "rpc error"))
            } else {
                d.complete(frame.obj("result") ?: JsonObject(emptyMap()))
            }
            return
        }
        onFrame(frame)
    }

    // -------------------------------------------------------------------- rpc

    suspend fun call(method: String, params: JsonObject, timeoutMs: Long = 60_000L): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            carrier.send(payload)
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 无 id、无响应、不挂 pending 的通知（cancel 等）。 */
    suspend fun notify(method: String, params: JsonObject) {
        carrier.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    /** 回一条带 id 的结果（permission 答复）。信封归 mux，payload 语义归上层。 */
    suspend fun sendResult(id: Long, result: JsonObject) {
        carrier.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    private fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        put(
            "clientCapabilities",
            buildJsonObject {
                put(
                    "fs",
                    buildJsonObject {
                        put("readTextFile", false)
                        put("writeTextFile", false)
                    },
                )
                put("terminal", false)
            },
        )
    }

    private companion object {
        const val INITIALIZE = "initialize"

        /** 握手超时与载体建链超时是两个值：前者 20s（改造前 `doConnect` 的字面量），后者 [MuxOptions.readyTimeoutMs]。 */
        const val INITIALIZE_TIMEOUT_MS = 20_000L
    }
}
