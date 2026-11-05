package dev.dsh.mobile.core.protocol.mcp

import dev.dsh.mobile.core.model.mcp.McpHeader
import dev.dsh.mobile.core.model.mcp.McpTransportKind
import dev.dsh.mobile.core.model.mcp.McpServerConfig
import dev.dsh.mobile.core.model.mcp.McpError
import dev.dsh.mobile.core.model.mcp.McpException
import dev.dsh.mobile.core.model.mcp.McpClientState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全部走假 transport：不建端口、不发真实网络请求。 */
class HttpMcpClientTest {

    private val config = McpServerConfig(
        id = "s1",
        name = "demo",
        transport = McpTransportKind.Http,
        url = "https://example.com/mcp",
        headers = listOf(McpHeader("Authorization", "Bearer t")),
    )

    private fun client(caller: McpHttpCaller) = HttpMcpClient(config, caller)

    private fun json(body: String) = McpHttpResponse(200, "application/json", body)
    private fun ok() = McpHttpResponse(200, "application/json", "")

    private val initializeResult = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"demo-server","version":"1.2.3"},"capabilities":{}}}"""

    @Test
    fun connectSendsInitializeThenInitializedNotification() = runTest {
        val caller = FakeCaller(listOf(json(initializeResult), ok()))
        val handshake = client(caller).connect()

        assertEquals(2, caller.requests.size)
        val initBody = caller.requests[0].body
        assertTrue(initBody.contains("\"method\":\"initialize\""))
        assertTrue(initBody.contains("\"protocolVersion\":\"2025-06-18\""))
        assertTrue(initBody.contains("\"clientInfo\""))
        assertTrue(initBody.contains("\"id\":1"))

        val notified = caller.requests[1].body
        assertTrue(notified.contains("notifications/initialized"))
        assertTrue(!notified.contains("\"id\""))

        assertEquals("demo-server", handshake.serverName)
        assertEquals("1.2.3", handshake.serverVersion)
        assertEquals("2025-06-18", handshake.protocolVersion)
    }

    @Test
    fun connectForwardsConfiguredHeaders() = runTest {
        val caller = FakeCaller(listOf(json(initializeResult), ok()))
        client(caller).connect()
        assertTrue(caller.requests[0].headers.any { it.name == "Authorization" && it.value == "Bearer t" })
        assertEquals("https://example.com/mcp", caller.requests[0].url)
    }

    @Test
    fun listToolsParsesAnnotations() = runTest {
        val caller = FakeCaller(
            listOf(
                json(initializeResult),
                ok(),
                json(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[""" +
                        """{"name":"read_file","description":"读文件","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},""" +
                        """{"name":"delete_file","annotations":{"destructiveHint":true}}]}}""",
                ),
            ),
        )
        val tools = client(caller).listTools()

        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)
        assertEquals("读文件", tools[0].description)
        assertTrue(tools[0].readOnly)
        assertEquals(false, tools[0].destructive)
        assertEquals("delete_file", tools[1].name)
        assertTrue(tools[1].destructive)
        assertEquals(false, tools[1].readOnly)
        assertEquals("\"object\"", tools[0].inputSchema["type"].toString())
    }

    @Test
    fun listToolsFollowsPaginationCursor() = runTest {
        val caller = FakeCaller(
            listOf(
                json(initializeResult),
                ok(),
                json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a"}],"nextCursor":"p2"}}"""),
                json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"b"}]}}"""),
            ),
        )
        val tools = client(caller).listTools()
        assertEquals(listOf("a", "b"), tools.map { it.name })
        assertTrue(caller.requests[3].body.contains("\"cursor\":\"p2\""))
    }

    @Test
    fun callToolParsesTextAndImage() = runTest {
        val caller = FakeCaller(
            listOf(
                json(initializeResult),
                ok(),
                json(
                    """{"jsonrpc":"2.0","id":3,"result":{"isError":false,"content":[""" +
                        """{"type":"text","text":"done"},""" +
                        """{"type":"image","mimeType":"image/png","data":"QUJD"}]}}""",
                ),
            ),
        )
        val result = client(caller).callTool("read_file", JsonObject(emptyMap()), 5_000L)

        assertEquals("done", result.text)
        assertEquals(1, result.images.size)
        assertEquals("image/png", result.images[0].mimeType)
        assertEquals("QUJD", result.images[0].base64)
        assertEquals(false, result.isError)
        assertTrue(caller.requests[2].body.contains("\"method\":\"tools/call\""))
        assertTrue(caller.requests[2].body.contains("\"name\":\"read_file\""))
    }

    @Test
    fun callToolSurfacesIsErrorFlag() = runTest {
        val caller = FakeCaller(
            listOf(
                json(initializeResult),
                ok(),
                json("""{"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text","text":"boom"}]}}"""),
            ),
        )
        val result = client(caller).callTool("boom", JsonObject(emptyMap()), 5_000L)
        assertTrue(result.isError)
        assertEquals("boom", result.text)
    }

    @Test
    fun httpStatusMapsToHttpError() = runTest {
        val caller = FakeCaller(listOf(McpHttpResponse(401, "application/json", "unauthorized")))
        val client = client(caller)
        val error = runCatching { client.connect() }.exceptionOrNull()
        assertTrue(error is McpException)
        val mcp = (error as McpException).error
        assertTrue(mcp is McpError.Http)
        assertEquals(401, (mcp as McpError.Http).status)
        assertEquals("unauthorized", mcp.body)
        assertTrue(client.state.value is McpClientState.Failed)
    }

    @Test
    fun jsonRpcErrorMapsToProtocolError() = runTest {
        val caller = FakeCaller(listOf(json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""")))
        val error = runCatching { client(caller).connect() }.exceptionOrNull() as McpException
        assertTrue(error.error is McpError.Protocol)
        assertTrue(error.message!!.contains("-32601"))
        assertTrue(error.message!!.contains("Method not found"))
    }

    @Test
    fun handshakeTimeoutMapsToTimeout() = runTest {
        val caller = FakeCaller(listOf(json(initializeResult))).also { it.delayMs = 60_000L }
        val client = client(caller)
        val error = runCatching { client.connect() }.exceptionOrNull() as McpException
        assertEquals(McpError.Timeout, error.error)
        assertEquals(McpClientState.Failed(McpError.Timeout), client.state.value)
    }

    @Test
    fun sseResponsePicksFrameById() = runTest {
        val sseBody = """event: message
data: {"jsonrpc":"2.0","id":99,"result":{}}

data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"sse-server","version":"9"}}}

"""
        val caller = FakeCaller(listOf(McpHttpResponse(200, "text/event-stream", sseBody), ok()))
        val handshake = client(caller).connect()
        assertEquals("sse-server", handshake.serverName)
    }

    private class FakeCaller(private val responses: List<McpHttpResponse>) : McpHttpCaller {
        val requests = mutableListOf<McpHttpRequest>()
        var delayMs = 0L
        private var index = 0

        override suspend fun post(request: McpHttpRequest): McpHttpResponse {
            requests += request
            if (delayMs > 0L) delay(delayMs)
            val response = responses[minOf(index, responses.size - 1)]
            index++
            return response
        }
    }
}
