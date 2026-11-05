package dev.dsh.mobile.pluginhost.builtin.gateway

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.pluginhost.LoopbackPorts
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// 示例上游，可由配置覆盖：域名与 UA 只是默认值，设置页的 baseUrl / extraHeaders 优先。
private const val DEFAULT_BASE_URL = "https://api.example.invalid/v1"
private const val USER_AGENT = "DshGateway/1.0"

/** 未填 baseUrlOverride 时用的默认上游（唯一定义处见上面的常量块）。 */
internal val defaultBaseUrl: String get() = DEFAULT_BASE_URL

/** 网关配置：一次 ensureStarted 读一次，改配置靠下次探测重起（见内置插件的 ensureStarted）。 */
data class GatewayConfig(
    val access: String,
    val refresh: String? = null,
    val proxy: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 可选模型名：填了就覆盖请求里的 model。 */
    val model: String? = null,
    /** 额外请求头（设置项 extraHeaders，一行一个 Name: Value）。 */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    /** 幂等判据：任一字段变了就必须重起，否则旧进程还在用旧凭据。 */
    fun signature(): String = listOf(
        access,
        refresh.orEmpty(),
        proxy.orEmpty(),
        baseUrl,
        extraHeaders.entries.sortedBy { it.key }.joinToString(",") { it.key + "=" + it.value },
    ).joinToString("|")
}

/**
 * 本机 OpenAI 兼容网关。
 *
 * 对宿主只暴露 /v1/models 与 /v1/chat/completions；对内把请求洗成上游形状后转发到
 * 「baseUrl + CHAT_PATH」，再把 SSE 原样回吐（非流式则自己聚合）。
 *
 * 用 ServerSocket 手写最小 HTTP/1.1 而不是引框架：plugin-host 的 classpath 里没有 OkHttp
 * （okhttp 只在 core:protocol 里 implementation），也不值得为一个回环端口加依赖。
 * 只服务 127.0.0.1，一个连接一个线程，Connection: close 收尾。
 */
class ChatGateway(private val config: GatewayConfig) {

    private val stopped = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var access: String = config.access

    @Volatile
    private var refreshToken: String? = config.refresh

    /** 只有刷新成功过才知道过期时间；直接贴 access token 时这里是 0（永不在本地判过期）。 */
    @Volatile
    private var expiresAt: Long = 0L

    @Volatile
    private var modelsCache: List<ModelInfo>? = null

    /** 上次跑通的 chat 路径：被渠道校验拦过一次就不再每次都先撞 /v2。 */
    @Volatile
    private var chatPath: String = ChatPayload.CHAT_PATH

    fun isRunning(): Boolean = server?.isClosed == false

    fun start(port: Int): Boolean {
        if (isRunning()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LoopbackPorts.LOOPBACK, port), 16)
            server = socket
            stopped.set(false)
            thread(name = "chat-gateway-" + port, isDaemon = true) { acceptLoop(socket) }
            AppLog.i("内置网关已监听 " + LoopbackPorts.LOOPBACK + ":" + port)
            true
        } catch (e: Exception) {
            AppLog.w("内置网关绑定端口 " + port + " 失败: " + e.message)
            false
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopped.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            thread(name = "chat-conn", isDaemon = true) {
                try {
                    client.use { handle(it) }
                } catch (e: Exception) {
                    AppLog.w("内置网关连接异常: " + e.message)
                }
            }
        }
    }

    private class Request(val method: String, val path: String, val body: String)

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        val request = readRequest(input) ?: return
        when {
            request.method == "GET" && request.path == "/healthz" -> respondText(out, 200, "ok")
            request.method == "GET" && (request.path == "/v1/models" || request.path == "/models") ->
                handleModels(out)
            request.method == "POST" && request.path.startsWith("/v1/chat/completions") -> handleChat(request, out)
            else -> respondError(out, 404, "未知路径 " + request.path, "invalid_request_error", "not_found")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        var length = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val name = header.substring(0, colon).trim().lowercase()
            if (name == "content-length") length = header.substring(colon + 1).trim().toIntOrNull() ?: 0
        }
        val body = if (length > 0) readExactly(input, length) else ""
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), body)
    }

    // ---- 模型目录 -------------------------------------------------------

    private fun handleModels(out: OutputStream) {
        if (access.isBlank()) {
            val body = JSONObject()
                .put("object", "list")
                .put("data", JSONArray())
                .put("hint", "未填上游凭据，请在插件设置里填写绑定凭据")
            respondJson(out, 200, body.toString())
            return
        }
        val models = loadModels()
        val array = JSONArray()
        for (model in models) array.put(ChatPayload.toOpenAiModel(model))
        val body = JSONObject().put("object", "list").put("data", array)
        if (models.isEmpty()) body.put("hint", "上游模型目录为空，请检查凭据是否有效")
        respondJson(out, 200, body.toString())
    }

    /**
     * 目录失败不让模型源变空：回落到上次成功的缓存，再回落到内置种子表，
     * 否则用户会看到「一个模型都没有」，连选都没得选。
     */
    private fun loadModels(): List<ModelInfo> {
        modelsCache?.let { return it }
        val config = callUpstream("GET", ChatPayload.CONFIG_PATH, null)
        val personal = callUpstream("GET", ChatPayload.MODELS_PATH, null)
        val configBody = if (config.status in 200..299) config.readAll() else null
        val personalBody = if (personal.status in 200..299) personal.readAll() else null
        val parsed = ChatPayload.modelsFromUpstream(configBody, personalBody)
        if (parsed.isNotEmpty()) {
            modelsCache = parsed
            return parsed
        }
        AppLog.w("上游模型目录拉取失败: config=" + config.status + " personal=" + personal.status)
        return SEED_MODELS
    }

    // ---- chat -----------------------------------------------------------

    private fun handleChat(request: Request, out: OutputStream) {
        if (access.isBlank()) {
            respondError(
                out,
                400,
                "未填上游凭据，请在插件设置里填写绑定凭据",
                "invalid_request_error",
                "missing_credential",
            )
            return
        }
        val clientStream = runCatching { JSONObject(request.body).optBoolean("stream", false) }.getOrDefault(false)
        val prepared = try {
            ChatPayload.prepare(applyModel(request.body))
        } catch (e: Exception) {
            respondError(out, 400, "请求体不是合法 JSON: " + e.message, "invalid_request_error", "invalid_json")
            return
        }

        ChatPayload.ensureSystemPrompt(prepared)

        if (needsRefresh()) refresh()

        var upstream = sendChat(prepared.toString())
        if (upstream.status == 401 && refreshToken != null) {
            // 401 = 令牌失效；有 refresh 就换一次再打一次，不重试风暴。
            if (refresh()) upstream = sendChat(prepared.toString())
        }

        if (upstream.status !in 200..299) {
            val text = upstream.readAll()
            val error = classifyError(upstream.status, text)
            AppLog.w("上游响应 " + upstream.status + " code=" + error.code)
            respondError(out, error.status, error.message, error.type, error.code)
            return
        }

        if (clientStream) {
            pipeSse(upstream.stream, out)
        } else {
            val aggregator = ChatSse.Aggregator()
            val reader = BufferedReader(InputStreamReader(upstream.stream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                when (val event = ChatSse.classify(line)) {
                    is SseEvent.Chunk -> aggregator.accept(event.json)
                    SseEvent.Done -> {
                        aggregator.markDone()
                        break
                    }
                    SseEvent.Ignore -> Unit
                }
            }
            respondJson(out, 200, aggregator.toCompletion().toString())
        }
    }

    /** 逐帧转发；保证恰好一个 [DONE]，上游截断时先补一条错误帧。 */
    private fun pipeSse(stream: InputStream, out: OutputStream) {
        writeHead(out, 200, "text/event-stream")
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var sawDone = false
        var forwarded = false
        while (true) {
            val line = reader.readLine() ?: break
            when (val event = ChatSse.classify(line)) {
                is SseEvent.Chunk -> {
                    val normalized = ChatSse.normalize(event.json) ?: continue
                    out.write(ChatSse.encode(normalized).toByteArray(Charsets.UTF_8))
                    out.flush()
                    forwarded = true
                }
                SseEvent.Done -> {
                    sawDone = true
                    break
                }
                SseEvent.Ignore -> Unit
            }
        }
        if (!sawDone) {
            val note = if (forwarded) "upstream stream ended before completion" else "upstream returned an empty stream"
            val frame = JSONObject().put(
                "error",
                JSONObject()
                    .put("message", note)
                    .put("type", "upstream_error")
                    .put("code", if (forwarded) "upstream_truncated" else "upstream_empty"),
            )
            out.write(ChatSse.encode(frame).toByteArray(Charsets.UTF_8))
            out.flush()
        }
        out.write(ChatSse.doneFrame().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---- 上游 -----------------------------------------------------------

    private class Upstream(val status: Int, val stream: InputStream) {
        fun readAll(): String = runCatching {
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrDefault("")
    }

    /**
     * 发 chat 请求：先走上次成功的路径，被渠道校验（11-128）拦下就换另一条并记住。
     * 两条路径同一套头、同一个 body，差别只在服务端校验松紧。
     */
    private fun sendChat(body: String): Upstream {
        val first = chatPath
        val second = if (first == ChatPayload.CHAT_PATH) ChatPayload.CONSOLE_PATH else ChatPayload.CHAT_PATH
        val attempt = callUpstream("POST", first, body)
        if (attempt.status in 200..299) return attempt
        val text = attempt.readAll()
        if (!isChannelCheck(text)) return Upstream(attempt.status, text.byteInputStream(Charsets.UTF_8))
        AppLog.w("上游渠道校验拦截 path=" + first + "，改用 " + second)
        val retry = callUpstream("POST", second, body)
        if (retry.status in 200..299) chatPath = second
        return retry
    }

    /** 渠道校验的判据：业务码 11-128 或官方文案（内容审核拦截是同一个码，文案不同，不算）。 */
    private fun isChannelCheck(body: String): Boolean =
        body.contains("11-128") ||
            body.contains("\"code\":11128") ||
            body.contains("channel verification") ||
            body.contains("渠道校验")

    private fun callUpstream(method: String, path: String, body: String?): Upstream {
        val base = config.baseUrl.trimEnd('/')
        val connection = (URL(base + path).openConnection(proxyOrDirect()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            for ((name, value) in buildHeaders()) setRequestProperty(name, value)
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
        return Upstream(status, stream)
    }

    /** 设置项里的可选模型名：填了就覆盖请求里的 model。 */
    private fun applyModel(body: String): String {
        val model = config.model?.trim().orEmpty()
        if (model.isEmpty()) return body
        return runCatching { JSONObject(body).put("model", model).toString() }.getOrDefault(body)
    }

    /**
     * 通用头组：Bearer + Content-Type + 通用 UA，再叠设置里的额外头。
     * 不再带任何具名指纹头，身份完全由设置项决定。
     */
    private fun buildHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json, text/event-stream"
        headers["User-Agent"] = USER_AGENT
        if (access.isNotBlank()) headers["Authorization"] = "Bearer " + access
        headers.putAll(config.extraHeaders)
        return headers
    }

    private fun proxyOrDirect(): Proxy {
        val raw = config.proxy?.trim().orEmpty()
        if (raw.isEmpty()) return Proxy.NO_PROXY
        val cleaned = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "8080").toIntOrNull() ?: 8080
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    // ---- token ----------------------------------------------------------

    private fun needsRefresh(): Boolean {
        val expires = expiresAt
        if (expires <= 0L) return false
        return System.currentTimeMillis() + REFRESH_SKEW_MS >= expires
    }

    @Synchronized
    private fun refresh(): Boolean {
        val token = refreshToken ?: return false
        val connection = try {
            (URL(config.baseUrl.trimEnd('/') + ChatPayload.REFRESH_PATH).openConnection(proxyOrDirect())
                as HttpURLConnection)
        } catch (e: Exception) {
            AppLog.w("上游刷新连接失败: " + e.message)
            return false
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = REFRESH_TIMEOUT_MS
            connection.doOutput = true
            for ((name, value) in buildHeaders()) {
                if (name.equals("Authorization", ignoreCase = true)) continue
                connection.setRequestProperty(name, value)
            }
            connection.setRequestProperty("X-Refresh-Token", token)
            connection.setRequestProperty("X-Auth-Refresh-Source", "plugin")
            connection.outputStream.use { it.write(ByteArray(0)) }
            if (connection.responseCode !in 200..299) {
                AppLog.w("上游刷新失败 HTTP " + connection.responseCode)
                return false
            }
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            val next = json.optString("accessToken")
            if (next.isEmpty()) {
                AppLog.w("上游刷新响应缺 accessToken，需要重新登录")
                return false
            }
            access = next
            json.optString("refreshToken").takeIf { it.isNotEmpty() }?.let { refreshToken = it }
            val expiresIn = json.optLong("expiresIn", 0L)
            expiresAt = if (expiresIn > 0L && expiresIn < MAX_PLAUSIBLE_EXPIRES_S) {
                System.currentTimeMillis() + expiresIn * 1000L
            } else {
                0L
            }
            true
        } catch (e: Exception) {
            AppLog.w("上游刷新异常: " + e.message)
            false
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ---- 错误面（P1-8） --------------------------------------------------

    internal class GatewayError(val status: Int, val type: String, val code: String, val message: String)

    internal fun classifyError(httpStatus: Int, body: String?): GatewayError {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val upstreamCode = json?.opt("code")?.toString().orEmpty()
        val upstreamMsg = json?.optString("msg").orEmpty().ifEmpty { json?.optString("displayMsg").orEmpty() }
        val message = upstreamMsg.ifEmpty { "上游返回 HTTP " + httpStatus }
        return when (httpStatus) {
            400 -> when (upstreamCode) {
                "11101" -> GatewayError(400, "invalid_request_error", "invalid_request", message)
                "11133" -> GatewayError(400, "invalid_request_error", "tool_schema", message)
                "11135" -> GatewayError(400, "invalid_request_error", "invalid_image", message)
                "11148" -> GatewayError(400, "invalid_request_error", "tool_pairing", message)
                "11151" -> GatewayError(400, "invalid_request_error", "empty_message", message)
                "11115" -> GatewayError(400, "invalid_request_error", "context_length_exceeded", message)
                "11-128" -> GatewayError(400, "invalid_request_error", "channel_check", message)
                else -> GatewayError(400, "invalid_request_error", "upstream_400", message)
            }
            401 -> GatewayError(401, "invalid_request_error", "credential_expired", "凭据已失效，请在插件设置里重新填写")
            402 -> GatewayError(402, "insufficient_quota", "quota_exhausted", message)
            403 -> if (upstreamCode == "11140") {
                GatewayError(403, "account_restricted", "account_restricted", message)
            } else {
                GatewayError(403, "upstream_error", "upstream_forbidden", message)
            }
            404 -> GatewayError(404, "invalid_request_error", "model_not_found", message)
            429 -> if (upstreamCode == "14018") {
                GatewayError(429, "insufficient_quota", "quota_exhausted", message)
            } else {
                GatewayError(429, "rate_limit_error", "rate_limited", message)
            }
            else -> GatewayError(502, "upstream_error", "upstream_" + httpStatus, message)
        }
    }

    // ---- HTTP 写出 ------------------------------------------------------

    private fun respondJson(out: OutputStream, status: Int, body: String) {
        writeHead(out, status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8).size)
        out.write(body.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun respondText(out: OutputStream, status: Int, body: String) {
        writeHead(out, status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8).size)
        out.write(body.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun respondError(out: OutputStream, status: Int, message: String, type: String, code: String) {
        val body = JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", type).put("code", code),
        )
        respondJson(out, status, body.toString())
    }

    private fun writeHead(out: OutputStream, status: Int, contentType: String, length: Int = -1) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        402 -> "Payment Required"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val REFRESH_TIMEOUT_MS = 20_000
        private const val REFRESH_SKEW_MS = 10 * 60 * 1000L
        private const val MAX_PLAUSIBLE_EXPIRES_S = 10L * 365 * 24 * 3600

        /** 目录拉不到时的兜底：够用户在模型源里选一条，不至于空列表。 */
        private val SEED_MODELS: List<ModelInfo> = listOf(
            "deepseek-v4.1-flash",
            "deepseek-v4-pro",
            "gpt-5.5",
            "kimi-k3",
            "glm-5.3",
            "gemini-3.5-flash",
            "auto",
        ).map { ModelInfo(it, it, 0, 0, false, emptyList(), null) }

    }
}

private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
        if (byte == '\n'.code) break
        if (byte == '\r'.code) continue
        buffer.write(byte)
    }
    return buffer.toString("UTF-8")
}

private fun readExactly(input: InputStream, length: Int): String {
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = input.read(bytes, read, length - read)
        if (count <= 0) break
        read += count
    }
    return String(bytes, 0, read, Charsets.UTF_8)
}
