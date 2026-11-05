package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.mcp.McpProbeResult
import dev.dsh.mobile.core.model.mcp.McpServerRecord

/** MCP 服务器表端口：由 core:data 的 McpServerStore 实现。save 不进接口（只被 upsert/delete 内部用）。 */
interface McpServerStoreApi {
    fun load(): List<McpServerRecord>
    fun upsert(record: McpServerRecord): List<McpServerRecord>
    fun setEnabled(id: String, enabled: Boolean): List<McpServerRecord>
    fun delete(id: String): List<McpServerRecord>

    /** 连接 → 拉工具表 → 断开；实现见 core:data 的 probeMcpServer。 */
    suspend fun probe(record: McpServerRecord): McpProbeResult
}
