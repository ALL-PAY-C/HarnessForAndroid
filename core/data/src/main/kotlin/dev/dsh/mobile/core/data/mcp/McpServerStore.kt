package dev.dsh.mobile.core.data.mcp

import android.content.Context
import dev.dsh.mobile.core.protocol.ToolSensitivity
import dev.dsh.mobile.core.protocol.mcp.HttpMcpClient
import dev.dsh.mobile.core.protocol.mcp.McpClient
import dev.dsh.mobile.core.protocol.mcp.McpError
import dev.dsh.mobile.core.protocol.mcp.McpException
import dev.dsh.mobile.core.protocol.mcp.McpHeader
import dev.dsh.mobile.core.protocol.mcp.McpServerConfig
import dev.dsh.mobile.core.protocol.mcp.McpTransportKind
import dev.dsh.mobile.core.protocol.mcp.describe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

private const val PREFS_NAME = "dsh.mcp"
private const val KEY_SERVERS = "key_servers"

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

/** P2 只做应用内直连的两种传输；stdio 只能由引擎 spawn（见 McpTransportKind 注释）。 */
val MCP_TRANSPORTS: List<String> = listOf("http", "sse")

fun newMcpServerId(): String = UUID.randomUUID().toString()

fun McpServerRecord.toProtocolConfig(): McpServerConfig = McpServerConfig(
    id = id,
    name = name,
    transport = when (transport) {
        "sse" -> McpTransportKind.Sse
        "stdio" -> McpTransportKind.Stdio
        else -> McpTransportKind.Http
    },
    url = url,
    headers = headers.map { McpHeader(it.name, it.value) },
    enabled = enabled,
    sensitivity = ToolSensitivity.ReversibleWrite,
    toolCallTimeoutMs = toolCallTimeoutMs,
)

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

fun encodeServers(servers: List<McpServerRecord>): String = JsonArray(
    servers.map { record ->
        buildJsonObject {
            put("id", record.id)
            put("name", record.name)
            put("transport", record.transport)
            put("url", record.url)
            put("enabled", record.enabled)
            put("toolCallTimeoutMs", record.toolCallTimeoutMs)
            put("headers", JsonArray(record.headers.map { header ->
                buildJsonObject {
                    put("name", header.name)
                    put("value", header.value)
                }
            }))
        }
    },
).toString()

/** 容错解析：坏行跳过，整段坏掉返回空表——设置页宁可少一行也不要崩。 */
fun decodeServers(raw: String): List<McpServerRecord> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        McpServerRecord(
            id = id,
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            transport = obj["transport"]?.jsonPrimitive?.contentOrNull ?: "http",
            url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            headers = (obj["headers"] as? JsonArray)?.mapNotNull { item ->
                val header = item as? JsonObject ?: return@mapNotNull null
                val name = header["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                McpKeyValue(name, header["value"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.orEmpty(),
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            toolCallTimeoutMs = obj["toolCallTimeoutMs"]?.jsonPrimitive?.longOrNull ?: 120_000L,
        )
    }
}

class McpServerStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<McpServerRecord> = decodeServers(prefs.getString(KEY_SERVERS, null).orEmpty())

    fun save(servers: List<McpServerRecord>): List<McpServerRecord> {
        prefs.edit().putString(KEY_SERVERS, encodeServers(servers)).apply()
        return servers
    }

    /** 同 id 覆盖，否则追加。返回新列表，UI 直接拿去渲染。 */
    fun upsert(record: McpServerRecord): List<McpServerRecord> {
        val current = load()
        val index = current.indexOfFirst { it.id == record.id }
        val next = if (index >= 0) current.toMutableList().also { it[index] = record } else current + record
        return save(next)
    }

    fun delete(id: String): List<McpServerRecord> = save(load().filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean): List<McpServerRecord> =
        save(load().map { if (it.id == id) it.copy(enabled = enabled) else it })
}

/** 「测试连接」结果：失败也要给出可读中文，不能让 UI 只剩空白。 */
data class McpProbeResult(
    val ok: Boolean,
    val server: String = "",
    val version: String = "",
    val tools: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * 连接 → 拉工具表 → 断开。注入 clientFactory 是为了单测不起端口。
 */
suspend fun probeMcpServer(
    record: McpServerRecord,
    clientFactory: (McpServerConfig) -> McpClient = { HttpMcpClient(it) },
): McpProbeResult {
    if (record.transport == "stdio") {
        return McpProbeResult(ok = false, error = McpError.UnsupportedTransport.describe())
    }
    val client = clientFactory(record.toProtocolConfig())
    return try {
        val handshake = client.connect()
        val tools = client.listTools()
        McpProbeResult(
            ok = true,
            server = handshake.serverName.ifBlank { record.name },
            version = handshake.serverVersion,
            tools = tools.map { it.name },
        )
    } catch (e: McpException) {
        McpProbeResult(ok = false, error = e.error.describe())
    } catch (e: Exception) {
        McpProbeResult(ok = false, error = e.message ?: "连接失败")
    } finally {
        runCatching { client.close() }
    }
}
