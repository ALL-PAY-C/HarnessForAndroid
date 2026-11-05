package dev.dsh.mobile.core.protocol.mcp

import dev.dsh.mobile.core.model.mcp.McpHandshake
import dev.dsh.mobile.core.model.mcp.McpToolDef
import dev.dsh.mobile.core.model.mcp.McpCallResult
import dev.dsh.mobile.core.model.mcp.McpClientState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface McpClient {
    val state: StateFlow<McpClientState>

    suspend fun connect(): McpHandshake
    suspend fun listTools(): List<McpToolDef>
    suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long): McpCallResult

    /** 轻量探活，不拉工具表。 */
    suspend fun ping()

    suspend fun close()
}
