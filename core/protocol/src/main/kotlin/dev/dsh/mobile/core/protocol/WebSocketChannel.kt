package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Loopback / LAN WebSocket channel. Works against dsh-mobile-bridge (or any
 * ACP-over-WS server). Token travels in the query string and an Authorization header.
 */
class WebSocketChannel(
    private val url: String,
    private val token: String = "",
    private val heartbeatMs: Long = 15_000L,
) : LineChannel {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(heartbeatMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val buffer = Channel<String>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private var ws: WebSocket? = null

    override val incoming: Flow<String> = buffer.receiveAsFlow()
    override val description: String get() = "ws:$url"

    private fun targetUrl(): String {
        if (token.isBlank()) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }

    override suspend fun open() {
        val req = Request.Builder()
            .url(targetUrl())
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.i("ws open ${response.code}")
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                buffer.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.w("ws failure: ${t.message} code=${response?.code}")
                if (!opened.isCompleted) opened.completeExceptionally(t)
                buffer.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.i("ws closed $code $reason")
                if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("closed $code"))
                buffer.close()
            }
        })
        withTimeout(15_000L) { opened.await() }
    }

    override suspend fun send(line: String) {
        val socket = ws ?: throw IllegalStateException("WebSocket 未连接")
        if (!socket.send(line)) throw IllegalStateException("发送失败")
    }

    override suspend fun close() {
        runCatching { ws?.close(1000, "client close") }
        ws = null
        buffer.close()
        client.dispatcher.executorService.shutdown()
    }
}
