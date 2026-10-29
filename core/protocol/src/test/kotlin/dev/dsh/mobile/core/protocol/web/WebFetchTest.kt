package dev.dsh.mobile.core.protocol.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchTest {

    private val page = """
        <html><head><title>示例 &amp; 标题</title>
        <style>.x{color:red}</style><script>var a = 1;</script></head>
        <body><h1>正文标题</h1><p>第一段 &lt;内容&gt;</p><p>第二段 &#65;</p></body></html>
    """.trimIndent()

    @Test
    fun readsHtmlAndStripsTags() = runTest {
        val http = FakeTransport { request -> request.respond(body = page) }
        val outcome = WebFetcher().fetch("https://example.com/page", http, WebSettings())
        assertEquals(200, outcome.status)
        assertEquals("示例 & 标题", outcome.title)
        assertTrue(outcome.text.contains("正文标题"))
        assertTrue(outcome.text.contains("第一段 <内容>"))
        assertTrue(outcome.text.contains("第二段 A"))
        assertFalse(outcome.text.contains("color:red"))
        assertFalse(outcome.text.contains("var a"))
        assertFalse(outcome.truncated)
    }

    @Test
    fun non2xxIsAnError() = runTest {
        val http = FakeTransport { request -> request.respond(status = 404, body = "nope") }
        val error = runCatching {
            WebFetcher().fetch("https://example.com/missing", http, WebSettings())
        }.exceptionOrNull()
        assertTrue(error is WebToolException)
        assertEquals(WebErrorCode.HttpStatus, (error as WebToolException).code)
        assertTrue(error.message!!.contains("404"))
    }

    @Test
    fun oversizedBodyIsRejected() = runTest {
        val big = "x".repeat(5_000)
        val http = FakeTransport { request -> request.respond(body = big) }
        val error = runCatching {
            WebFetcher().fetch("https://example.com/big", http, WebSettings(maxFetchBytes = 1_000))
        }.exceptionOrNull()
        assertTrue(error is WebToolException)
        assertEquals(WebErrorCode.TooLarge, (error as WebToolException).code)
    }

    @Test
    fun networkDropIsAnError() = runTest {
        val http = FakeTransport { request ->
            request.sink!!.write(ByteArray(10), 10)
            throw WebToolException(WebErrorCode.Network, "连接被重置：https://example.com/x")
        }
        val error = runCatching {
            WebFetcher().fetch("https://example.com/x", http, WebSettings())
        }.exceptionOrNull()
        assertEquals(WebErrorCode.Network, (error as WebToolException).code)
    }

    @Test
    fun timeoutIsReportedAsTimeout() = runTest {
        val http = FakeTransport { request ->
            delay(10_000)
            request.respond(body = page)
        }
        val error = runCatching {
            WebFetcher().fetch("https://example.com/slow", http, WebSettings(fetchBudgetMs = 200))
        }.exceptionOrNull()
        assertEquals(WebErrorCode.Timeout, (error as WebToolException).code)
    }

    @Test
    fun plainTextBodyIsKeptAsIs() = runTest {
        val http = FakeTransport { request ->
            request.respond(body = "hello\nworld", contentType = "text/plain; charset=utf-8")
        }
        val outcome = WebFetcher().fetch("https://example.com/robots.txt", http, WebSettings())
        assertEquals("", outcome.title)
        assertEquals("hello\nworld", outcome.text)
    }

    @Test
    fun finalUrlFromTransportIsReported() = runTest {
        val http = FakeTransport { request ->
            val response = request.respond(body = page)
            response.copy(finalUrl = "https://cn.example.com/page")
        }
        val outcome = WebFetcher().fetch("https://example.com/page", http, WebSettings())
        assertEquals("https://cn.example.com/page", outcome.finalUrl)
    }

    @Test
    fun nonHttpSchemeIsRejected() = runTest {
        val http = FakeTransport { request -> request.respond(body = page) }
        val error = runCatching {
            WebFetcher().fetch("file:///etc/passwd", http, WebSettings())
        }.exceptionOrNull()
        assertEquals(WebErrorCode.BadRequest, (error as WebToolException).code)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun fetchToolReturnsJson() = runTest {
        val http = FakeTransport { request -> request.respond(body = page) }
        val toolkit = WebToolkit(http = http, settings = { WebSettings() })
        val payload = toolkit.fetch(
            kotlinx.serialization.json.buildJsonObject { put("url", "https://example.com/page") },
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        assertEquals("示例 & 标题", json["title"]!!.jsonPrimitive.content)
        assertEquals(true, json["ok"]!!.jsonPrimitive.content.toBoolean())
    }
}
