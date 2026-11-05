package dev.dsh.mobile.core.platform.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * android.jar 里没有 com.sun.net.httpserver（Kotlin 用 android.jar 当 bootclasspath，JDK 的
 * jdk.httpserver 模块不可见），所以这里用 ServerSocket 手写一个最小 HTTP 应答器：
 * 只验证门面自己的行为（状态码、超时、header、字符集），够用且不依赖任何额外库。
 */
class HttpJsonTest {

    private class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class Res(
        val code: Int,
        val body: ByteArray,
        val contentType: String = "application/json",
        val delayMs: Long = 0,
    )

    private class StubServer(private val respond: (Req) -> Res) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        val baseUrl: String = "http://127.0.0.1:" + server.localPort

        init {
            pool.execute {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { serve(client) } }
                }
            }
        }

        private fun serve(client: Socket) {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val head = readHead(input)
                val lines = head.split("\r\n")
                val start = lines.first().split(" ")
                val headers = lines.drop(1)
                    .filter { it.contains(":") }
                    .associate {
                        it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim()
                    }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val raw = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(raw, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val res = respond(Req(start[0], start[1], headers, String(raw, Charsets.UTF_8)))
                if (res.delayMs > 0) Thread.sleep(res.delayMs)
                val out = socket.getOutputStream()
                val head2 = "HTTP/1.1 " + res.code + " X\r\n" +
                    "Content-Type: " + res.contentType + "\r\n" +
                    "Content-Length: " + res.body.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(head2.toByteArray(Charsets.ISO_8859_1))
                out.write(res.body)
                out.flush()
            }
        }

        private fun readHead(input: InputStream): String {
            val buf = StringBuilder()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b < 0) break
                buf.append(b.toChar())
                if (prev == '\r'.code && b == '\n'.code) {
                    val text = buf.toString()
                    if (text.endsWith("\r\n\r\n")) return text
                }
                prev = b
            }
            return buf.toString()
        }

        override fun close() {
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }

    private fun withServer(respond: (Req) -> Res, block: (String) -> Unit) {
        StubServer(respond).use { block(it.baseUrl) }
    }

    @Test
    fun getTextReturnsBodyOn200() = withServer({ Res(200, """{"data":[]}""".toByteArray()) }) { url ->
        assertEquals("""{"data":[]}""", runBlocking { HttpJson.getText(url) })
    }

    @Test
    fun nonSuccessThrowsWithStatusAndBody() = withServer({ Res(404, """{"error":"no such route"}""".toByteArray()) }) { url ->
        try {
            runBlocking { HttpJson.getText(url) }
            fail("404 应当抛 HttpJsonException")
        } catch (e: HttpJsonException) {
            assertEquals(404, e.status)
            assertEquals("""{"error":"no such route"}""", e.body)
            assertTrue("消息里要带状态码: " + e.message, e.message!!.startsWith("HTTP 404"))
        }
    }

    @Test
    fun readTimeoutBecomesReadableException() = withServer({ Res(200, "late".toByteArray(), delayMs = 1_000) }) { url ->
        try {
            runBlocking { HttpJson.getText(url, timeoutMs = 2_000, readTimeoutMs = 150) }
            fail("读超时应当抛异常")
        } catch (e: HttpJsonTimeoutException) {
            assertTrue("超时消息要能读懂: " + e.message, e.message!!.contains("超时"))
        }
    }

    @Test
    fun headersAreForwardedVerbatim() = withServer({ req ->
        Res(200, req.headers["x-probe"].orEmpty().toByteArray())
    }) { url ->
        assertEquals("v1", runBlocking { HttpJson.getText(url, mapOf("X-Probe" to "v1")) })
    }

    @Test
    fun nonUtf8BodyIsDecodedByDeclaredCharset() = withServer({
        Res(200, "模型列表".toByteArray(charset("GBK")), contentType = "text/plain; charset=GBK")
    }) { url ->
        assertEquals("模型列表", runBlocking { HttpJson.getText(url) })
    }

    @Test
    fun postJsonSendsBodyAndJsonContentType() = withServer({ req ->
        Res(201, (req.headers["content-type"].orEmpty() + "|" + req.body).toByteArray())
    }) { url ->
        assertEquals(
            """application/json|{"model":"m"}""",
            runBlocking { HttpJson.postJson(url, """{"model":"m"}""") },
        )
    }
}
