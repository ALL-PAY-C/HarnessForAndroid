package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The only protocol surface the UI/data layers may touch.
 * Implementations: [AcpTransport] over WebSocket (loopback/LAN) or over an
 * embedded Node process (in-process). Swapping implementations changes nothing upstream.
 */
interface DshTransport {
    val events: SharedFlow<DshEvent>
    val state: StateFlow<RuntimeState>

    suspend fun connect()
    suspend fun disconnect()

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 60_000L): JsonObject

    suspend fun listSessions(cwd: String? = null): List<Session>
    suspend fun newSession(cwd: String): Session
    suspend fun resumeSession(sessionId: String): Session
    suspend fun closeSession(sessionId: String)
    /**
     * 发一条用户消息。
     * [images] 是本地图片的绝对路径，支持视觉的模型会收到 base64 图像内容块。
     */
    suspend fun prompt(sessionId: String, text: String, images: List<String> = emptyList(), turnId: String? = null): String
    suspend fun cancel(sessionId: String)

    /**
     * ACP 终态 barrier 的 ACK：collector 处理完带 [turnId] 的合成 TurnEnd marker 后回调，
     * 让 [prompt] 的等待得以释放（旧 chunk 全部消费后才放 sendGate）。本地引擎无此语义，默认空实现。
     */
    fun ackTurn(turnId: String) = Unit

    /**
     * 「插入」：不打断当前这一轮，把用户的话塞进下一轮请求里。
     * 默认实现什么都不做，只有本地引擎真的支持插话。
     */
    fun interject(sessionId: String, text: String) = Unit
    suspend fun setConfigOption(sessionId: String, optionId: String, value: String)
    suspend fun replyPermission(sessionId: String, requestId: String, optionId: String?)
}
