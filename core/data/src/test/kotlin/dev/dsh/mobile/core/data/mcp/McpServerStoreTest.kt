package dev.dsh.mobile.core.data.mcp

import dev.dsh.mobile.core.model.mcp.McpCallResult
import dev.dsh.mobile.core.protocol.mcp.McpClient
import dev.dsh.mobile.core.model.mcp.McpClientState
import dev.dsh.mobile.core.model.mcp.McpError
import dev.dsh.mobile.core.model.mcp.McpException
import dev.dsh.mobile.core.model.mcp.McpHandshake
import dev.dsh.mobile.core.model.mcp.McpServerConfig
import dev.dsh.mobile.core.model.mcp.McpToolDef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.dsh.mobile.core.model.mcp.sanitizeServerName
import dev.dsh.mobile.core.model.mcp.serverNameError
import dev.dsh.mobile.core.model.mcp.McpKeyValue
import dev.dsh.mobile.core.model.mcp.McpServerRecord

class McpServerStoreTest {

    private fun record(id: String = "a", name: String = "demo") = McpServerRecord(
        id = id,
        name = name,
        transport = "http",
        url = "https://example.com/mcp",
        headers = listOf(McpKeyValue("Authorization", "Bearer t")),
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val servers = listOf(record(), record("b", "other").copy(enabled = false, toolCallTimeoutMs = 300_000L))
        val decoded = decodeServers(encodeServers(servers))
        assertEquals(servers, decoded)
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(decodeServers("not json").isEmpty())
        assertTrue(decodeServers("").isEmpty())
        assertTrue(decodeServers("[{\"name\":\"no-id\"}]").isEmpty())
    }

    @Test
    fun sanitizeLowercasesAndReplaces() {
        assertEquals("my_server", sanitizeServerName("My Server"))
        assertEquals("a-b", sanitizeServerName(" A-B "))
        assertEquals("", sanitizeServerName("中文"))
    }

    @Test
    fun nameErrorRejectsBlankAndDuplicate() {
        assertEquals("名称不能为空", serverNameError("  ", emptyList()))
        assertEquals("名称至少要有一个字母或数字", serverNameError("服务器", emptyList()))
        assertEquals("已存在同名服务器：demo", serverNameError("Demo", listOf(record())))
        assertNull(serverNameError("fresh", listOf(record())))
    }

    @Test
    fun probeReturnsServerAndTools() = runTest {
        val result = probeMcpServer(record()) { FakeClient(tools = listOf("read_file", "write_file")) }
        assertTrue(result.ok)
        assertEquals("fake-server", result.server)
        assertEquals(listOf("read_file", "write_file"), result.tools)
        assertNull(result.error)
    }

    @Test
    fun probeMapsProtocolError() = runTest {
        val result = probeMcpServer(record()) { FakeClient(fail = McpError.Http(401, "unauthorized")) }
        assertEquals(false, result.ok)
        assertEquals("HTTP 401：unauthorized", result.error)
    }

    @Test
    fun probeRejectsStdio() = runTest {
        val result = probeMcpServer(record().copy(transport = "stdio"))
        assertEquals(false, result.ok)
        assertTrue(result.error!!.contains("STDIO"))
    }

    @Test
    fun recordConvertsToProtocolConfig() {
        val config = record().copy(transport = "sse").toProtocolConfig()
        assertEquals("demo", config.name)
        assertEquals("https://example.com/mcp", config.url)
        assertEquals(1, config.headers.size)
    }

    private class FakeClient(
        private val tools: List<String> = emptyList(),
        private val fail: McpError? = null,
    ) : McpClient {
        private val stateFlow = MutableStateFlow<McpClientState>(McpClientState.Idle)
        override val state: StateFlow<McpClientState> = stateFlow

        override suspend fun connect(): McpHandshake {
            fail?.let { throw McpException(it) }
            return McpHandshake("2025-06-18", "fake-server", "0.1.0", JsonObject(emptyMap()))
        }

        override suspend fun listTools(): List<McpToolDef> =
            tools.map { McpToolDef(it, "", JsonObject(emptyMap()), readOnly = true, destructive = false) }

        override suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long): McpCallResult =
            McpCallResult("ok")

        override suspend fun ping() = Unit

        override suspend fun close() {
            stateFlow.value = McpClientState.Idle
        }
    }
}
