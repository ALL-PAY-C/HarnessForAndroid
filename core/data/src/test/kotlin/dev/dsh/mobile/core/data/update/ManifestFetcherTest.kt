package dev.dsh.mobile.core.data.update

import dev.dsh.mobile.core.model.web.WebErrorCode
import dev.dsh.mobile.core.model.web.WebToolException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 清单出网口三条路径：200 取到正文、非 2xx 带状态码与正文摘要、服务器不回包时超时收场。
 * 本地 ServerSocket 起一个假服务器，不碰公网。
 */
class ManifestFetcherTest {

    @Test
    fun returnsBodyOn200() = runBlocking {
        LocalServer { socket ->
            readRequestLine(socket.inputStream)
            respond(socket, 200, "{\"versionCode\":7}")
        }.use { server ->
            val body = DefaultManifestFetcher(timeoutMs = 3_000L).fetch(server.baseUrl + "/update.json")
            assertEquals("{\"versionCode\":7}", body)
        }
    }

    @Test
    fun nonTwoHundredCarriesStatusAndBodyPreview() = runBlocking {
        LocalServer { socket ->
            readRequestLine(socket.inputStream)
            respond(socket, 404, "manifest missing")
        }.use { server ->
            val error = runCatching {
                DefaultManifestFetcher(timeoutMs = 3_000L).fetch(server.baseUrl + "/update.json")
            }.exceptionOrNull()
            assertTrue("非 2xx 必须失败，实际拿到 " + error, error is IllegalArgumentException)
            val message = error!!.message.orEmpty()
            assertTrue("错误里要带状态码，实际：" + message, message.startsWith("HTTP 404"))
            assertTrue("错误里要带正文摘要，实际：" + message, message.contains("manifest missing"))
        }
    }

    @Test
    fun readTimeoutSurfacesInsteadOfHanging() {
        // 只 accept、不回包：读超时必须把它打断，否则一次更新检查要挂满 20s。
        LocalServer { }.use { server ->
            val startedAt = System.nanoTime()
            val error = runCatching {
                runBlocking { DefaultManifestFetcher(timeoutMs = 300L).fetch(server.baseUrl + "/slow") }
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("应当超时失败，实际 " + error, error != null)
            assertTrue(
                "超时语义应走 WebToolException(Timeout)，实际 " + error,
                error is WebToolException && error.code == WebErrorCode.Timeout,
            )
            assertTrue("300ms 超时不该等超过 3s，实测 " + elapsedMs + "ms", elapsedMs < 3_000)
        }
    }
}

private fun readRequestLine(input: InputStream) {
    while (true) {
        val b = input.read()
        if (b == -1 || b == 0x0A) return
    }
}

private fun respond(socket: Socket, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val reason = if (code == 200) "OK" else "Error"
    val head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: " + bytes.size + "\r\n" +
        "Connection: close\r\n\r\n"
    socket.getOutputStream().apply {
        write(head.toByteArray(Charsets.ISO_8859_1))
        write(bytes)
        flush()
    }
    socket.close()
}

/** 一次一个连接的假服务器；handler 决定回什么，空实现就是「接了不回」用来测超时。 */
private class LocalServer(private val onConnected: (Socket) -> Unit) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker = thread(isDaemon = true, name = "manifest-fetcher-test") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { onConnected(socket) }
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:" + server.localPort

    override fun close() {
        runCatching { server.close() }
        worker.interrupt()
    }
}
