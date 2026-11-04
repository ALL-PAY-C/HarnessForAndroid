package dev.dsh.mobile.core.protocol.mcp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** 握手结果。capabilities 留着给 P4 的能力门控用，P2 只记录不解释。 */
data class McpHandshake(
    val protocolVersion: String,
    val serverName: String,
    val serverVersion: String,
    val capabilities: JsonObject,
)

data class McpToolDef(
    /** 原始名，不加 mcp__ 前缀。 */
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** annotations.readOnlyHint */
    val readOnly: Boolean,
    /** annotations.destructiveHint */
    val destructive: Boolean,
)

data class McpImage(val mimeType: String, val base64: String)

data class McpCallResult(
    val text: String,
    val images: List<McpImage> = emptyList(),
    val isError: Boolean = false,
)

/** 状态点语义：灰=未连接，黄=连接中，绿=Ready(n 工具)，红=Failed。 */
sealed interface McpClientState {
    data object Idle : McpClientState
    data object Connecting : McpClientState
    data class Ready(val tools: Int) : McpClientState
    data class Failed(val error: McpError) : McpClientState
}

interface McpClient {
    val state: StateFlow<McpClientState>

    suspend fun connect(): McpHandshake
    suspend fun listTools(): List<McpToolDef>
    suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long): McpCallResult

    /** 轻量探活，不拉工具表。 */
    suspend fun ping()

    suspend fun close()
}
