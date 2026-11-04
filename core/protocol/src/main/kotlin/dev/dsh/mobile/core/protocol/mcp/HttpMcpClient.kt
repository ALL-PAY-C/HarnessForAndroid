package dev.dsh.mobile.core.protocol.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP 传输的可注入接缝。生产走 [OkHttpMcpCaller]，测试注入假实现，
 * 因此 JSON-RPC 编解码、错误映射、超时全部可以不起端口就验。
 */
data class McpHttpRequest(
    val url: String,
    val headers: List<McpHeader>,
    val body: String,
    val timeoutMs: Long,
)

data class McpHttpResponse(
    val status: Int,
    val contentType: String,
    val body: String,
    /** streamable HTTP 的会话头；后续请求要原样带回去。 */
    val sessionId: String? = null,
)

fun interface McpHttpCaller {
    suspend fun post(request: McpHttpRequest): McpHttpResponse
}

fun defaultMcpHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

class OkHttpMcpCaller(
    private val http: OkHttpClient = defaultMcpHttpClient(),
) : McpHttpCaller {

    override suspend fun post(request: McpHttpRequest): McpHttpResponse = withContext(Dispatchers.IO) {
        val body = request.body.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(request.url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        request.headers.forEach { builder.header(it.name, it.value) }
        val client = http.newBuilder()
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(builder.build()).execute().use { response ->
            McpHttpResponse(
                status = response.code,
                contentType = response.header("Content-Type").orEmpty(),
                body = response.body?.string().orEmpty(),
                sessionId = response.header("Mcp-Session-Id"),
            )
        }
    }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** 握手 20s 对齐 DshMux.INITIALIZE_TIMEOUT_MS；工具表与 ping 给 30s。 */
internal const val MCP_HANDSHAKE_TIMEOUT_MS = 20_000L
internal const val MCP_LIST_TIMEOUT_MS = 30_000L
internal const val MCP_MAX_CALL_TIMEOUT_MS = 600_000L
internal const val MCP_MAX_PAGES = 5

/**
 * MCP 客户端（JSON-RPC 2.0 over Streamable HTTP，响应可为 JSON 或 text/event-stream）。
 *
 * 一把 Mutex 串行化同一 server 上的请求：JSON-RPC 允许并发 id，但 server 端会话
 * 初始化有顺序要求（initialize 之前不能发别的），串行最省心也够快。
 */
class HttpMcpClient(
    private val cfg: McpServerConfig,
    private val caller: McpHttpCaller = OkHttpMcpCaller(),
) : McpClient {

    private val _state = MutableStateFlow<McpClientState>(McpClientState.Idle)
    override val state: StateFlow<McpClientState> = _state.asStateFlow()

    private val gate = Mutex()
    private var handshake: McpHandshake? = null
    private var sessionId: String? = null
    private var seq = 0L

    override suspend fun connect(): McpHandshake = gate.withLock {
        stateful { ensureLocked() }
    }

    override suspend fun listTools(): List<McpToolDef> = gate.withLock {
        stateful {
            ensureLocked()
            val tools = mutableListOf<McpToolDef>()
            var cursor: String? = null
            var page = 0
            while (page < MCP_MAX_PAGES) {
                val (frame, _) = exchange("tools/list", McpRpc.toolsListParams(cursor), MCP_LIST_TIMEOUT_MS)
                val result = McpRpc.resultOf(frame)
                tools += McpRpc.parseTools(result)
                cursor = McpRpc.nextCursor(result)
                page++
                if (cursor == null) break
            }
            _state.value = McpClientState.Ready(tools.size)
            tools
        }
    }

    override suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long): McpCallResult = gate.withLock {
        stateful {
            ensureLocked()
            val budget = timeoutMs.coerceIn(1_000L, MCP_MAX_CALL_TIMEOUT_MS)
            val (frame, _) = exchange("tools/call", McpRpc.callParams(name, args), budget)
            McpRpc.parseCall(McpRpc.resultOf(frame))
        }
    }

    override suspend fun ping() = gate.withLock {
        stateful {
            ensureLocked()
            exchange("ping", null, MCP_LIST_TIMEOUT_MS)
            Unit
        }
    }

    override suspend fun close() {
        gate.withLock {
            handshake = null
            sessionId = null
            _state.value = McpClientState.Idle
        }
    }

    // ---- 内部 ----

    private suspend fun ensureLocked(): McpHandshake {
        handshake?.let { return it }
        _state.value = McpClientState.Connecting
        val hs = handshakeLocked()
        handshake = hs
        _state.value = McpClientState.Ready(0)
        return hs
    }

    private suspend fun handshakeLocked(): McpHandshake {
        val (frame, response) = exchange("initialize", McpRpc.initializeParams(), MCP_HANDSHAKE_TIMEOUT_MS)
        sessionId = response.sessionId ?: sessionId
        // JSON-RPC 的 error 帧要保持 Protocol 语义，只有真的解析不了才算握手失败。
        val hs = try {
            McpRpc.parseHandshake(McpRpc.resultOf(frame))
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException(McpError.Handshake(e.message ?: "initialize 响应无法解析"))
        }
        // initialized 是通知，server 不回响应；它失败不影响已建立的会话。
        try {
            post(McpRpc.notification("notifications/initialized"), MCP_LIST_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
        }
        return hs
    }

    private suspend fun exchange(method: String, params: JsonObject?, timeoutMs: Long): Pair<String, McpHttpResponse> {
        val id = ++seq
        val response = post(McpRpc.request(id, method, params), timeoutMs)
        if (response.status !in 200..299) {
            throw McpException(McpError.Http(response.status, response.body.take(400).ifBlank { null }))
        }
        val frame = if (response.contentType.contains("text/event-stream", ignoreCase = true)) {
            McpRpc.pickFrame(response.body, id)
                ?: throw McpException(McpError.Protocol("SSE 响应里没有 id=" + id + " 的帧"))
        } else {
            response.body
        }
        return frame to response
    }

    private suspend fun post(body: String, timeoutMs: Long): McpHttpResponse {
        val headers = buildList {
            addAll(cfg.headers)
            sessionId?.let { add(McpHeader("Mcp-Session-Id", it)) }
        }
        return try {
            withTimeout(timeoutMs) {
                caller.post(McpHttpRequest(cfg.url, headers, body, timeoutMs))
            }
        } catch (e: TimeoutCancellationException) {
            throw McpException(McpError.Timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw McpException(McpError.Network(e.message ?: e.toString()))
        }
    }

    /** 把任何失败落进 state 再抛出：UI 靠 state 画状态点，工具层靠异常拿文案。 */
    private suspend fun <T> stateful(block: suspend () -> T): T = try {
        block()
    } catch (e: McpException) {
        _state.value = McpClientState.Failed(e.error)
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val error = McpError.Network(e.message ?: e.toString())
        _state.value = McpClientState.Failed(error)
        throw McpException(error)
    }
}

/**
 * JSON-RPC 2.0 编解码（纯函数）。请求体构造、响应解析、错误映射都在这里，
 * 客户端只负责发与收。
 */
internal object McpRpc {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val CLIENT_NAME = "HarnessForAndroid"
    const val CLIENT_VERSION = "1.0"

    fun request(id: Long, method: String, params: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun notification(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { })
        put("clientInfo", buildJsonObject {
            put("name", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        })
    }

    fun toolsListParams(cursor: String?): JsonObject? =
        cursor?.let { buildJsonObject { put("cursor", it) } }

    fun callParams(name: String, args: JsonObject): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", args)
    }

    /** 取 result；JSON-RPC error 帧与非法 JSON 都映射成 [McpError.Protocol]。 */
    fun resultOf(frame: String): JsonObject {
        val element = runCatching { Json.parseToJsonElement(frame) }.getOrNull()
            ?: throw McpException(McpError.Protocol("响应不是合法 JSON"))
        val obj = element as? JsonObject
            ?: throw McpException(McpError.Protocol("响应不是 JSON 对象"))
        val error = obj["error"]
        if (error != null) {
            val e = error as? JsonObject
            val code = e?.get("code")?.jsonPrimitive?.contentOrNull ?: "?"
            val message = e?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw McpException(McpError.Protocol(code + ": " + message))
        }
        return obj["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun parseHandshake(result: JsonObject): McpHandshake {
        val info = result["serverInfo"] as? JsonObject
        return McpHandshake(
            protocolVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL_VERSION,
            serverName = info?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            serverVersion = info?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty(),
            capabilities = result["capabilities"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    fun parseTools(result: JsonObject): List<McpToolDef> {
        val array = result["tools"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val annotations = tool["annotations"] as? JsonObject
            McpToolDef(
                name = name,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                readOnly = annotations?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true,
                destructive = annotations?.get("destructiveHint")?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }

    fun nextCursor(result: JsonObject): String? =
        result["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun parseCall(result: JsonObject): McpCallResult {
        val texts = mutableListOf<String>()
        val images = mutableListOf<McpImage>()
        (result["content"] as? JsonArray)?.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                "image" -> {
                    val data = block["data"]?.jsonPrimitive?.contentOrNull
                    if (data != null) {
                        images += McpImage(
                            mimeType = block["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                            base64 = data,
                        )
                    }
                }
            }
        }
        // 只有结构化结果时退化成一段 JSON 文本，别让模型拿到空字符串。
        if (texts.isEmpty() && images.isEmpty()) {
            (result["structuredContent"] as? JsonObject)?.let { texts += it.toString() }
        }
        return McpCallResult(
            text = texts.joinToString("\n"),
            images = images,
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** SSE 响应体里挑出 id 匹配的 JSON-RPC 帧；不是 SSE 就整段当 JSON 用。 */
    fun pickFrame(body: String, id: Long): String? {
        for (event in SseFramer.frame(body).events) {
            val obj = runCatching { Json.parseToJsonElement(event.data) }.getOrNull() as? JsonObject ?: continue
            if (obj["id"]?.jsonPrimitive?.longOrNull == id) return event.data
        }
        return body.trim().takeIf { it.startsWith("{") }
    }
}
