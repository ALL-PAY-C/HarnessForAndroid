package dev.dsh.mobile.core.model.mcp

/** UI 与存储共用的扁平 record：feature:settings 看不到 core:protocol，转换只在这里做。 */
data class McpKeyValue(val name: String, val value: String)

data class McpServerRecord(
    val id: String,
    /** 已 sanitize 的短名，工具名前缀 mcp__<name>__<tool>。 */
    val name: String,
    val transport: String,
    val url: String,
    val headers: List<McpKeyValue> = emptyList(),
    val enabled: Boolean = true,
    val toolCallTimeoutMs: Long = 120_000L,
)
