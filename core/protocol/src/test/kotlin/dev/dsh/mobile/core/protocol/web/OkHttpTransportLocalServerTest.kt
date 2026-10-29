package dev.dsh.mobile.core.protocol.web

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 真实 OkHttpTransport 打本地 fake server：重定向、非 2xx、截断响应（断网）、流式下载。
 * 全程 127.0.0.1，不依赖公网。
 */
class OkHttpTransportLocalServerTest {

    private var server: LocalHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
        server = null
    }

    private fun serve(handler: (String) -> HttpReply): String =
        LocalHttpServer(handler).start().also { server = it }.baseUrl

    private fun htmlReply(body: String) =
        HttpReply(headers = mapOf("Content-Type" to "text/html; charset=utf-8"), body = body.toByteArray())

    @Test
    fun readsHtmlOverRealHttp() = runBlocking {
        val base = serve { htmlReply("<html><head><title>本地</title></head><body><p>正文</p></body></html>") }
        val outcome = WebFetcher().fetch(base + "/page", OkHttpTransport(), WebSettings())
        assertEquals(200, outcome.status)
        assertEquals("本地", outcome.title)
        assertTrue(outcome.text.contains("正文"))
    }

    @Test
    fun non2xxSurfacesAsHttpStatus() = runBlocking {
        val base = serve { HttpReply(status = 404, body = "nope".toByteArray()) }
        val error = runCatching { WebFetcher().fetch(base + "/missing", OkHttpTransport(), WebSettings()) }
            .exceptionOrNull()
        assertEquals(WebErrorCode.HttpStatus, (error as WebToolException).code)
    }

    @Test
    fun followsRedirectAndReportsFinalUrl() = runBlocking {
        var base = ""
        base = serve { path ->
            if (path == "/old") HttpReply(status = 302, headers = mapOf("Location" to base + "/new"))
            else htmlReply("<html><head><title>新地址</title></head><body><p>到了</p></body></html>")
        }
        val outcome = WebFetcher().fetch(base + "/old", OkHttpTransport(), WebSettings())
        assertEquals("新地址", outcome.title)
        assertTrue(outcome.finalUrl.endsWith("/new"))
        assertEquals(base + "/old", outcome.url)
    }

    @Test
    fun redirectLoopIsBounded() = runBlocking {
        var base = ""
        base = serve { path ->
            val next = if (path == "/loop1") "/loop2" else "/loop1"
            HttpReply(status = 302, headers = mapOf("Location" to base + next))
        }
        val error = runCatching { WebFetcher().fetch(base + "/loop1", OkHttpTransport(), WebSettings()) }
            .exceptionOrNull()
        assertEquals(WebErrorCode.TooManyRedirects, (error as WebToolException).code)
    }

    @Test
    fun truncatedBodyIsANetworkError() = runBlocking {
        val base = serve { HttpReply(headers = mapOf("Content-Type" to "text/html"), body = ByteArray(4096), sendBytes = 16) }
        val error = runCatching { WebFetcher().fetch(base + "/cut", OkHttpTransport(), WebSettings()) }
            .exceptionOrNull()
        assertTrue(error is WebToolException)
        assertEquals(WebErrorCode.Network, (error as WebToolException).code)
    }

    @Test
    fun streamsDownloadToWorkspace() = runBlocking {
        val payload = ByteArray(8_192) { (it % 251).toByte() }
        val base = serve { HttpReply(headers = mapOf("Content-Type" to "application/octet-stream"), body = payload) }
        val workspace: File = Files.createTempDirectory("dsh-ws-http").toFile()
        try {
            val progress = mutableListOf<Long>()
            val outcome = FileDownloader(WorkspaceSandbox(workspace)).download(
                DownloadRequest(url = base + "/file", fileName = "pkg.bin"),
                OkHttpTransport(),
            ) { written, _ -> progress += written }
            assertEquals(8_192L, outcome.bytes)
            assertEquals(payload.size.toLong(), File(workspace, "pkg.bin").length())
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
                outcome.sha256,
            )
            assertTrue(progress.isNotEmpty())
        } finally {
            workspace.deleteRecursively()
        }
    }
}
