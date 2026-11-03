package dev.dsh.mobile.core.protocol.transport

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.protocol.DshJson
import dev.dsh.mobile.core.protocol.LineChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 第 1 层：帧的搬运。不知道 RPC，不知道世代，**不负责重连** —— 重连是 [DshMux] 的事。
 *
 * 判据：换掉载体（socket / 进程 / binder / HTTP）不需要改 mux，换掉 mux 不需要改业务。
 * 现有 [LineChannel] 的两种实现靠 [LineCarrier] 适配，一行都不用改。
 */
interface DshCarrier {
    /** 收到的帧。不会因断链结束（重连复用同一条流）；停止消费靠取消 collector。 */
    val frames: Flow<JsonObject>

    /**
     * 断链事件（原因 + 是否值得重试）。只在「不是我们主动 close」时发。
     * 独立成流而不是 frames 里的特殊值：上层要能区分「流结束了」与「为什么结束」。
     */
    val losses: Flow<CarrierLoss>

    /** 建底层：socket / 进程 / binder。失败直接抛，由 mux 决定退避。 */
    suspend fun connect()

    suspend fun send(frame: JsonObject)

    /** 主动关；不发 loss。 */
    suspend fun close()
}

/**
 * 一次断链。[reason] 是给用户看的原文（沿用改造前的文案：「连接已关闭」「读取中断」等）。
 * [retryable] 由载体判定：stdio 进程被杀不可原地重试，socket 断开可重试。
 */
data class CarrierLoss(
    val reason: String,
    val retryable: Boolean = true,
    val cause: Throwable? = null,
)

/**
 * [LineChannel] → [DshCarrier] 适配层：换行分隔的 JSON 文本就是帧。
 *
 * 行编解码收敛在这里（改造前散在 `callInternal` 与 `readLoop` 两处）。
 * [provider] 每次 [connect] 都会重新调用，所以重连拿到的是一条全新通道。
 */
class LineCarrier(
    private val scope: CoroutineScope,
    private val provider: suspend () -> LineChannel,
) : DshCarrier {

    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)
    private val lossFlow = MutableSharedFlow<CarrierLoss>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var channel: LineChannel? = null
    private var pump: Job? = null

    @Volatile private var closing = false

    override val frames: Flow<JsonObject> = inbox.receiveAsFlow()
    override val losses: Flow<CarrierLoss> = lossFlow.asSharedFlow()

    override suspend fun connect() {
        closing = false
        val ch = provider()
        ch.open()
        channel = ch
        pump?.cancel()
        pump = scope.launch {
            try {
                ch.incoming.collect { line ->
                    if (line.isBlank()) return@collect
                    val el = runCatching { DshJson.parseToJsonElement(line) }.getOrNull()
                    if (el is JsonObject) inbox.trySend(el) else AppLog.d("non-json: ${line.take(120)}")
                }
                if (!closing) lossFlow.emit(CarrierLoss("连接已关闭", retryable = true))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (!closing) {
                    lossFlow.emit(CarrierLoss(t.message ?: "读取中断", retryable = true, cause = t))
                }
            }
        }
    }

    override suspend fun send(frame: JsonObject) {
        val ch = channel ?: throw IllegalStateException("未连接")
        ch.send(DshJson.encodeToString(JsonObject.serializer(), frame))
    }

    override suspend fun close() {
        closing = true
        pump?.cancel()
        runCatching { channel?.close() }
        channel = null
        // 刻意不关 inbox：同一个 carrier 要允许 stop → start 复用（改造前 disconnect 后还能再 connect）。
        // 帧流的终止由 mux 取消 collector 完成，不靠关闭 inbox。
    }
}
