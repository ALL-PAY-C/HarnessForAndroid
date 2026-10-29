package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.web.FakeTransport
import dev.dsh.mobile.core.protocol.web.WebSettings
import dev.dsh.mobile.core.protocol.web.WebToolkit
import dev.dsh.mobile.core.protocol.web.respond
import dev.dsh.mobile.core.protocol.web.respondBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** web_search / web_fetch / download 必须真的注册进 LocalEngineTransport 的工具表与执行器。 */
class WebToolsWiringTest {

    private fun transport(web: WebToolkit) = LocalEngineTransport(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = { null },
        historyProvider = { emptyList() },
        shellExec = { "" },
        web = web,
    )

    private fun webToolkit() = WebToolkit(
        http = FakeTransport { request ->
            if (request.url.contains("bing.com")) request.respond(body = BING_HTML_FOR_WIRING)
            else if (request.url.contains("fail")) request.respond(status = 500, body = "boom")
            else request.respond(body = "<html><head><title>T</title></head><body>ok</body></html>")
        },
        settings = { WebSettings() },
    )

    @Test
    fun toolTableExposesWebTools() {
        val names = transport(webToolkit()).toolsJson().mapNotNull { element ->
            element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        }
        assertEquals(listOf("shell", "phone", "fs_read", "fs_write", "fs_list", "fs_op", "channel_list", "run_bg", "bg_status", "bg_kill", "subagent", "web_search", "web_fetch", "download"), names)
    }

    @Test
    fun registeredToolRunsAndReturnsJson() = runTest {
        val executor = ToolExecutor(webToolkit().toolHandlers())
        val result = executor.run("web_fetch", buildJsonObject { put("url", "https://example.com/ok") })
        assertTrue(result.error, result.ok)
        assertTrue(result.output.contains("\"kind\":\"web_fetch\""))
    }

    @Test
    fun httpFailureIsNotWrappedAsSuccess() = runTest {
        val executor = ToolExecutor(webToolkit().toolHandlers())
        val result = executor.run("web_fetch", buildJsonObject { put("url", "https://example.com/fail") })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("HTTP 500"))
        assertEquals("", result.output)
    }

    @Test
    fun downloadFailureIsNotWrappedAsSuccess() = runTest {
        val workspace = java.nio.file.Files.createTempDirectory("dsh-ws-wiring").toFile()
        try {
            val toolkit = WebToolkit(
                http = FakeTransport { request ->
                    if (request.url.contains("fail")) request.respond(status = 500, body = "boom")
                    else request.respondBytes(200, ByteArray(4), "application/octet-stream")
                },
                workspace = workspace,
                settings = { WebSettings() },
            )
            val executor = ToolExecutor(toolkit.toolHandlers())
            val result = executor.run("download", buildJsonObject { put("url", "https://example.com/fail") })
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("HTTP 500"))
            assertEquals("", result.output)
            assertEquals(0, workspace.walk().filter { it.isFile }.count())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun missingArgumentIsNotWrappedAsSuccess() = runTest {
        val executor = ToolExecutor(webToolkit().toolHandlers())
        val result = executor.run("web_search", buildJsonObject { })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("query"))
    }

    @Test
    fun unknownToolStillReportsRegistrationList() = runTest {
        val executor = ToolExecutor(webToolkit().toolHandlers())
        val result = executor.run("nope", buildJsonObject { })
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("web_search"))
    }

    private companion object {
        const val BING_HTML_FOR_WIRING = "<html><body><h2><a href=\"https://example.com/a\">A</a></h2><p>s</p></body></html>"
    }
}
