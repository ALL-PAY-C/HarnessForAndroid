package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * T3 单元侧：下载器只负责搬字节。
 * 关键约定 —— 下载器不做校验，校验归 LinuxEnvironment；这里钉死
 * 「拿不到 200 就报错」「重定向会跟」「字节原样落盘」。
 */
class LinuxDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 只回一次请求就够；够真、没有第三方依赖、不需要 com.sun.net.httpserver。 */
    private class TinyServer(handler: (String) -> String) : AutoCloseable {
        private val ss = ServerSocket(0)
        val port: Int get() = ss.localPort
        private val worker = thread(isDaemon = true) {
            runCatching {
                while (!ss.isClosed) {
                    ss.accept().use { s ->
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val raw = handler(line)
                        s.getOutputStream().apply { write(raw.toByteArray()); flush() }
                    }
                }
            }
        }

        override fun close() {
            runCatching { ss.close() }
            worker.interrupt()
        }
    }

    private fun ok(body: String): String =
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"

    private fun manifest(url: String, bytes: Long = 0) = LinuxManifest(
        version = "3.20.3",
        abi = "x86_64",
        url = url,
        sha256 = "",
        bytes = bytes,
    )

    @Test
    fun `下载的字节原样落盘`() {
        val payload = "rootfs-bytes-" + "x".repeat(1000)
        TinyServer { ok(payload) }.use { server ->
            val dest = File(tmp.newFolder("dl"), "rootfs.tar.gz.part")
            LinuxDownload.http(manifest("http://127.0.0.1:${server.port}/rootfs"), dest)
            assertEquals(payload, dest.readText())
        }
    }

    @Test
    fun `非 200 直接失败而不是留个空文件`() {
        TinyServer { "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n" }.use { server ->
            val dest = File(tmp.newFolder("dl2"), "rootfs.tar.gz.part")
            val err = runCatching { LinuxDownload.http(manifest("http://127.0.0.1:${server.port}/x"), dest) }
            assertTrue(err.toString(), err.exceptionOrNull() is IllegalStateException)
            assertTrue(err.exceptionOrNull()!!.message!!, err.exceptionOrNull()!!.message!!.contains("404"))
        }
    }

    @Test
    fun `重定向会跟随`() {
        val payload = "after-redirect"
        TinyServer { req ->
            if (req.contains("/real")) ok(payload)
            else "HTTP/1.1 302 Found\r\nLocation: /real\r\nContent-Length: 0\r\n\r\n"
        }.use { server ->
            val dest = File(tmp.newFolder("dl3"), "r.part")
            LinuxDownload.http(manifest("http://127.0.0.1:${server.port}/start"), dest)
            assertEquals(payload, dest.readText())
        }
    }
}
