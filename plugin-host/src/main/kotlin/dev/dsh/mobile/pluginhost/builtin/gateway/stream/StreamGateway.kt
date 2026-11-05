package dev.dsh.mobile.pluginhost.builtin.gateway.stream

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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 网关配置：一次 ensureStarted 读一次，改配置靠下次探测重起（见插件入口类）。
 *
 * 上游身份全在设置项里：令牌、base、代理、模型、额外头；代码里不留域名与指纹。
 */
data class StreamConfig(
    val token: String = "",
    val proxy: String? = null,
    /** 上游 base：默认示例值，设置项 baseUrl 覆盖。 */
    val baseUrl: String = UpstreamSpec.EXAMPLE_UPSTREAM,
    /** 可选模型名：填了就替换请求里的 model。 */
    val model: String? = null,
    /** 额外请求头，一行一个 Name: Value。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 上游对话路径：空或不是 / 开头回落 [UpstreamSpec.CHAT_PATH]。 */
    val path: String = "",
    /** 上游项目标识：优先于授权 json 里的。 */
    val projectId: String = "",
    /** 出站 UA：空回落 [UpstreamSpec.DEFAULT_USER_AGENT]。 */
    val userAgent: String = "",
) {
    /** 幂等判据：任一字段变了就必须重起，否则旧进程还在用旧凭据。 */
    fun signature(): String = listOf(
        token,
        proxy.orEmpty(),
        baseUrl,
        model.orEmpty(),
        extraHeaders.toString(),
        path,
        projectId,
        userAgent,
    ).joinToString("|")
}

/** 设置项 → 凭据：授权 json 全文或裸令牌都认；配置里的项目 ID 优先于 json 里的。 */
fun resolveCredential(config: StreamConfig): StreamCredential {
    val raw = config.token.trim()
    val parsed = runCatching { EnvelopeCodec.parseCredential(raw) }.getOrNull()
    val access = parsed?.access?.takeIf { it.isNotBlank() } ?: raw
    val project = config.projectId.trim().ifEmpty { parsed?.project.orEmpty() }
    return StreamCredential(access = access, refresh = parsed?.refresh, project = project)
}

/** 对话路径：设置项优先，空或不是 / 开头回落示例路径。 */
fun chatPathOf(config: StreamConfig): String {
    val raw = config.path.trim()
    return if (raw.startsWith("/")) raw else UpstreamSpec.CHAT_PATH
}

/** 出站 UA：设置项优先，空回落示例 UA。 */
fun userAgentOf(config: StreamConfig): String {
    val raw = config.userAgent.trim()
    return raw.ifEmpty { UpstreamSpec.DEFAULT_USER_AGENT }
}

/**
 * 本机 OpenAI 兼容网关（B4-2）。
 *
 * 对宿主只暴露 /v1/models 与 /v1/chat/completions；对内把 OpenAI 请求洗成上游信封，
 * POST 到 [UpstreamSpec] 里的对话端点，再把上游帧翻译成
 * OpenAI 分片回吐。
 *
 * 为什么还是 ServerSocket 手写 HTTP/1.1（P0-8）：plugin-host 的 classpath 里没有 OkHttp，
 * 为一个回环端口加依赖不值得；而 HttpURLConnection 只走 HTTP/1.1、不发 ALPN —— 正好符合
 * 上游要求（HTTP/2 到 ESF 前置会 400，B4 报告 §风险 R3）。
 */
class StreamGateway(private val config: StreamConfig) {

    private val stopped = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    /** 凭据：设置项可能是授权 json 全文，也可能是裸令牌；项目 ID 由配置优先。 */
    private val credential: StreamCredential = resolveCredential(config)

    /** 上游令牌：只从设置项读，进程内不再改写（没有刷新链路）。 */
    private val token: String = credential.access

    @Volatile
    private var modelsCache: List<String>? = null

    fun isRunning(): Boolean = server?.isClosed == false

    fun start(port: Int): Boolean {
        if (isRunning()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LoopbackPorts.LOOPBACK, port), 16)
            server = socket
            stopped.set(false)
            thread(name = "stream-gateway-" + port, isDaemon = true) { acceptLoop(socket) }
            AppLog.i("流式网关已监听 " + LoopbackPorts.LOOPBACK + ":" + port)
            true
        } catch (e: Exception) {
            AppLog.w("流式网关绑定端口 " + port + " 失败: " + e.message)
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
            thread(name = "stream-conn", isDaemon = true) {
                try {
                    client.use { handle(it) }
                } catch (e: Exception) {
                    AppLog.w("本机反代网关连接异常: " + e.message)
                }
            }
        }
    }

    private class Request(val method: String, val path: String, val body: String)

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        val request = readRequest(input) ?: return
        AppLog.i(
            "网关请求 " + request.method + " " + request.path +
                " model=" + runCatching { JSONObject(request.body).optString("model") }.getOrDefault("") +
                " bytes=" + request.body.length,
        )
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
            val colon = header.indexOf(":")
            if (colon <= 0) continue
            val name = header.substring(0, colon).trim().lowercase()
            if (name == "content-length") length = header.substring(colon + 1).trim().toIntOrNull() ?: 0
        }
        val body = if (length > 0) readExactly(input, length) else ""
        return Request(parts[0].uppercase(), parts[1].substringBefore("?"), body)
    }

    // ---- 模型目录 -------------------------------------------------------

    private fun handleModels(out: OutputStream) {
        if (token.isBlank()) {
            val body = JSONObject()
                .put("object", "list")
                .put("data", JSONArray())
                .put("hint", "未填上游令牌，请在插件设置里填写鉴权令牌")
            respondJson(out, 200, body.toString())
            return
        }
        val models = loadModels()
        val array = JSONArray()
        for (id in models) array.put(modelJson(id))
        val body = JSONObject().put("object", "list").put("data", array)
        if (models.isEmpty()) body.put("hint", "上游模型目录为空，请检查凭据是否有效")
        respondJson(out, 200, body.toString())
    }

    /**
     * P1-5：动态拉目录；失败不让模型源变空 —— 先回落缓存，再回落内置种子表。
     * 目录走 POST 空体，见 B4 报告 §2 Q5。
     */
    private fun loadModels(): List<String> {
        modelsCache?.let { return it }
        val body = JSONObject()
        val upstream = callUpstream(UpstreamSpec.MODELS_PATH, body.toString(), false)
        val text = if (upstream.status in 200..299) upstream.readAll() else null
        val ids = EnvelopeCodec.modelIds(text)
        if (ids.isNotEmpty()) {
            modelsCache = ids
            return ids
        }
        AppLog.w("流式网关模型目录拉取失败 HTTP " + upstream.status + "，回落内置种子表")
        return EnvelopeCodec.SEED_MODELS
    }

    /** 宿主只认 OpenAI 的 model 对象；上游不给窗口大小，这些字段留空。 */
    private fun modelJson(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("object", "model")
        .put("created", 0)
        .put("owned_by", "local-relay")

    // ---- chat -----------------------------------------------------------

    private fun handleChat(request: Request, out: OutputStream) {
        if (token.isBlank()) {
            respondError(
                out,
                400,
                "未填上游令牌，请在插件设置里填写鉴权令牌",
                "invalid_request_error",
                "missing_credential",
            )
            return
        }
        val openAi = runCatching { JSONObject(request.body) }.getOrNull()
        if (openAi == null) {
            respondError(out, 400, "请求体不是合法 JSON", "invalid_request_error", "invalid_json")
            return
        }
        val clientStream = openAi.optBoolean("stream", false)
        config.model?.takeIf { it.isNotBlank() }?.let { openAi.put("model", it) }
        val model = openAi.optString("model")
        val envelope = EnvelopeCodec.build(
            openAi = openAi,
            credential = credential,
        )

        var upstream = callUpstream(chatPathOf(config), envelope.toString(), true)

        if (upstream.status !in 200..299) {
            val text = upstream.readAll()
            val error = classifyError(upstream.status, text, upstream.failure)
            AppLog.w("上游 " + upstream.status + " code=" + error.code)
            respondError(out, error.status, error.message, error.type, error.code)
            return
        }

        AppLog.i("网关转发上游 " + upstream.status + " model=" + model + " stream=" + clientStream)
        if (clientStream) {
            pipeSse(upstream.stream, out, model)
            AppLog.i("网关完成 model=" + model + " 流式=已结束")
        } else {
            val aggregator = SseFrames.Aggregator(model)
            val reader = BufferedReader(InputStreamReader(upstream.stream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                // 上游没有 [DONE]：EOF 就是结束，parseLine 返回 null 的行直接跳过。
                val event = SseFrames.parseLine(line) ?: continue
                if (aggregator.accept(event)) break
            }
            respondJson(out, 200, aggregator.toCompletion().toString())
        }
    }

    /**
     * 上游帧 → OpenAI 分片。思考段走 reasoning_content，正文走 content；
     * 收尾帧带 finish_reason 与 usage，最后补一个 [DONE]（宿主只认 OpenAI 协议）。
     */
    private fun pipeSse(stream: InputStream, out: OutputStream, model: String) {
        writeHead(out, 200, "text/event-stream")
        val id = "chatcmpl-" + UUID.randomUUID()
        val created = System.currentTimeMillis() / 1000L
        emit(out, SseFrames.chunk(id, model, created, JSONObject().put("role", "assistant").put("content", "")));
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var finished = false
        while (true) {
            val line = reader.readLine() ?: break
            val event = SseFrames.parseLine(line)
            if (event !is StreamEvent.Chunk) continue
            event.thought?.let {
                emit(out, SseFrames.chunk(id, model, created, JSONObject().put("reasoning_content", it)))
            }
            event.text?.let {
                emit(out, SseFrames.chunk(id, model, created, JSONObject().put("content", it)))
            }
            if (event.finishReason != null) {
                emit(
                    out,
                    SseFrames.chunk(
                        id, model, created, JSONObject(), event.finishReason,
                        SseFrames.openAiUsage(event.usage),
                    ),
                )
                finished = true
                break
            }
        }
        if (!finished) {
            // 上游断流没给 finishReason：补一个 stop，别让宿主把回答判成失败。
            emit(out, SseFrames.chunk(id, model, created, JSONObject(), "stop", null))
        }
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun emit(out: OutputStream, chunk: JSONObject) {
        out.write(("data: " + chunk.toString() + "\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---- 上游 -----------------------------------------------------------

    private class Upstream(val status: Int, val stream: InputStream, val failure: String?) {
        fun readAll(): String = runCatching {
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrDefault("")
    }

    private fun callUpstream(path: String, body: String, acceptSse: Boolean): Upstream {
        val connection = try {
            (URL(config.baseUrl.trimEnd { it == '/' } + path).openConnection(proxyOrDirect()) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                for ((name, value) in buildHeaders(acceptSse)) setRequestProperty(name, value)
                doOutput = true
            }
        } catch (e: Exception) {
            return Upstream(0, ByteArrayInputStreamOfEmpty, e.message)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            Upstream(status, stream, null)
        } catch (e: Exception) {
            runCatching { connection.disconnect() }
            Upstream(0, ByteArrayInputStreamOfEmpty, e.message)
        }
    }

    /** 通用头组：Bearer + 通用 UA + SSE 的 Accept，再叠设置里的额外头。 */
    private fun buildHeaders(acceptSse: Boolean): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = if (acceptSse) "text/event-stream" else "application/json"
        headers["User-Agent"] = userAgentOf(config)
        if (token.isNotBlank()) headers["Authorization"] = "Bearer " + token
        headers["Accept-Encoding"] = "identity"
        headers.putAll(config.extraHeaders)
        return headers
    }

    private fun proxyOrDirect(): Proxy {
        val raw = config.proxy?.trim().orEmpty()
        if (raw.isEmpty()) return Proxy.NO_PROXY
        val cleaned = raw.removePrefix("http://").removePrefix("https://").trimEnd { ch -> ch == '/' }
        val host = cleaned.substringBefore(":")
        val port = cleaned.substringAfter(":", "8080").toIntOrNull() ?: 8080
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    // ---- 错误面（B4 报告 §2 Q6） ------------------------------------------

    internal class GatewayError(val status: Int, val type: String, val code: String, val message: String)

    internal fun classifyError(httpStatus: Int, body: String?, failure: String?): GatewayError {
        if (httpStatus == 0) {
            val detail = failure?.takeIf { it.isNotBlank() } ?: "连接失败"
            return GatewayError(
                502,
                "upstream_error",
                "upstream_unreachable",
                "上游不可达，检查地址与代理（" + detail + "）",
            )
        }
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val error = json?.optJSONObject("error")
        val status = error?.optString("status").orEmpty()
        val message = error?.optString("message").orEmpty().ifEmpty { "上游返回 HTTP " + httpStatus }
        return when (httpStatus) {
            401 -> GatewayError(
                401, "invalid_request_error", "invalid_credential",
                "上游令牌无效（" + status.ifEmpty { "UNAUTHORIZED" } + "），请在插件设置里重新填写",
            )

            403 -> GatewayError(
                403, "invalid_request_error", "forbidden",
                "上游拒绝该请求：" + message,
            )

            404 -> GatewayError(
                404, "invalid_request_error", "model_not_found",
                "上游没有这个模型（" + message + "），请在模型源里换一个",
            )

            429 -> GatewayError(
                429, "rate_limit_error", "quota_exhausted",
                "配额已用尽，请等额度恢复或换账号：" + message,
            )

            503 -> GatewayError(
                503, "upstream_error", "auth_unavailable",
                "上游暂不可用（503）：" + message,
            )

            else -> GatewayError(httpStatus, "upstream_error", "upstream_" + httpStatus, message)
        }
    }

    // ---- HTTP 写回 -------------------------------------------------------

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
        head.append("HTTP/1.1 ").append(status).append(" ").append(reason(status)).append("\r\n")
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
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}

/** 失败路径用的空流：让 readAll() 返回空串而不是 NPE。 */
private val ByteArrayInputStreamOfEmpty: InputStream =
    java.io.ByteArrayInputStream(ByteArray(0))

private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
        if (byte == 10) break
        if (byte == 13) continue
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
