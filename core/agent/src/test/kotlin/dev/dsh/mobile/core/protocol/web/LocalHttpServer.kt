package dev.dsh.mobile.core.protocol.web

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** 一条原始 HTTP 应答；sendBytes >= 0 时只写出这么多字节就断开（模拟传输中断）。 */
internal data class HttpReply(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val sendBytes: Int = -1,
)

/**
 * 只给 JVM 单测用的最小 HTTP/1.1 服务器（127.0.0.1，随机端口）。
 * 用 ServerSocket 手写，避免引入 mockwebserver 之类的新依赖。
 */
internal class LocalHttpServer(private val handler: (path: String) -> HttpReply) : Closeable {

    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val worker = Thread({ acceptLoop() }, "local-http-server").apply { isDaemon = true }

    val baseUrl: String get() = "http://127.0.0.1:" + server.localPort

    fun start(): LocalHttpServer {
        worker.start()
        return this
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            runCatching { serve(socket) }
            runCatching { socket.close() }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        val reply = handler(path)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(reply.status).append(' ').append(statusText(reply.status)).append("\r\n")
        reply.headers.forEach { (key, value) -> head.append(key).append(": ").append(value).append("\r\n") }
        head.append("Content-Length: ").append(reply.body.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        val limit = if (reply.sendBytes >= 0) minOf(reply.sendBytes, reply.body.size) else reply.body.size
        if (limit > 0) output.write(reply.body, 0, limit)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\r'.code) continue
            if (byte == '\n'.code) return buffer.toString()
            buffer.append(byte.toChar())
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        302 -> "Found"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    override fun close() {
        runCatching { server.close() }
        worker.interrupt()
    }
}
