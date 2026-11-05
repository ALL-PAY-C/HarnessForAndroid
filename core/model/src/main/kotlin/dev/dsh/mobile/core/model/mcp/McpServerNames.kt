package dev.dsh.mobile.core.model.mcp

import java.util.UUID

// O8-B③: 从 core:data.mcp.McpServerStore 上移（纯函数/常量，无 Context、无 HTTP）。

/** P2 只做应用内直连的两种传输；stdio 只能由引擎 spawn（见 McpTransportKind 注释）。 */
val MCP_TRANSPORTS: List<String> = listOf("http", "sse")

fun newMcpServerId(): String = UUID.randomUUID().toString()

fun sanitizeServerName(raw: String): String {
    val lowered = raw.trim().lowercase()
    val builder = StringBuilder()
    for (ch in lowered) {
        builder.append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-') ch else '_')
    }
    return builder.toString().trim('_')
}

/** 返回一句中文错误，null 表示通过。others 是除自己以外的已有服务器。 */
fun serverNameError(raw: String, others: List<McpServerRecord>): String? {
    val name = sanitizeServerName(raw)
    if (raw.isBlank()) return "名称不能为空"
    if (name.isEmpty()) return "名称至少要有一个字母或数字"
    if (others.any { it.name == name }) return "已存在同名服务器：" + name
    return null
}

/** 「测试连接」结果：失败也要给出可读中文，不能让 UI 只剩空白。 */
data class McpProbeResult(
    val ok: Boolean,
    val server: String = "",
    val version: String = "",
    val tools: List<String> = emptyList(),
    val error: String? = null,
)
