package dev.dsh.mobile.core.model.mcp

import kotlinx.serialization.json.JsonObject

/**
 * MCP 配置模型（T9.10 P1）。
 *
 * 这一份模型被两条链路共用：InProcess 由应用自己连，ACP 则原样下发给引擎。
 * 因此字段必须与引擎 schema 对齐——headers / env 是 {name,value} 数组而不是 map，
 * 写错引擎直接拒（app.asar zod :17988-18041）。
 */

/** 一条请求头或环境变量。 */
data class McpHeader(val name: String, val value: String)

/** HTTP / SSE 由应用内客户端连；STDIO 只能由引擎 spawn（Android 10+ 禁 exec 私有目录）。 */
enum class McpTransportKind { Http, Sse, Stdio }

data class McpServerConfig(
    val id: String,
    /** 已 sanitize 的前缀：工具名 mcp__<name>__<tool>。 */
    val name: String,
    val transport: McpTransportKind,
    val url: String = "",
    val headers: List<McpHeader> = emptyList(),
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: List<McpHeader> = emptyList(),
    val enabled: Boolean = true,
    val sensitivity: ToolSensitivity = ToolSensitivity.ReversibleWrite,
    val toolCallTimeoutMs: Long = 120_000L,
)

/**
 * 错误模型。除 [ToolError] 外都必须被上层转成一句中文结果回给模型，
 * 绝不抛穿工具循环（否则整轮 turn 会被吞掉）。
 */
sealed interface McpError {
    data object Disabled : McpError
    data object UnsupportedTransport : McpError
    data class Handshake(val cause: String) : McpError
    data class Http(val status: Int, val body: String?) : McpError
    data object Timeout : McpError
    data class Protocol(val cause: String) : McpError
    data class ToolError(val message: String) : McpError

    /** 连接 / DNS / TLS / 读流中断：没有拿到 HTTP 响应。 */
    data class Network(val cause: String) : McpError
}

/** UI 与工具结果共用的中文文案，状态点语义与 McpClientState 一一对应。 */
fun McpError.describe(): String = when (this) {
    McpError.Disabled -> "已停用"
    McpError.UnsupportedTransport -> "当前链路不支持该传输（STDIO 由引擎启动）"
    is McpError.Handshake -> "握手失败：" + cause
    is McpError.Http -> "HTTP " + status + (body?.takeIf { it.isNotBlank() }?.let { "：" + it } ?: "")
    McpError.Timeout -> "超时"
    is McpError.Protocol -> "协议错误：" + cause
    is McpError.ToolError -> "工具返回错误：" + message
    is McpError.Network -> "离线：" + cause
}

/** 带错误模型的异常：调用方拿到 [error] 直接映射成状态或文案。 */
class McpException(val error: McpError, message: String = error.describe()) : Exception(message)

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
