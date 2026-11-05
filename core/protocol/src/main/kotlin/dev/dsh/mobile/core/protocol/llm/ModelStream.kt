package dev.dsh.mobile.core.protocol.llm

import dev.dsh.mobile.core.platform.LoopbackGateway
import dev.dsh.mobile.core.protocol.CONNECT_HINTS
import dev.dsh.mobile.core.protocol.DshJson
import dev.dsh.mobile.core.protocol.GATEWAY_ATTEMPTS
import dev.dsh.mobile.core.protocol.GATEWAY_BACKOFF_MS
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.UpstreamHttpException
import dev.dsh.mobile.core.protocol.httpFailureReason
import dev.dsh.mobile.core.protocol.reasoningOf
import dev.dsh.mobile.core.protocol.stripDsmlToolCalls
import dev.dsh.mobile.core.protocol.tool.contentOrNullSafe
import dev.dsh.mobile.core.protocol.truncateOpenBlock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 模型 I/O（T7.1b 从 LocalEngineTransport 拆出，纯搬移，语义逐字未改）。
 *
 * 一件事：把「一次请求」变成「一次应答」——SSE 增量解析、usage 解析、失败话术、
 * 本机网关自举与连接类错误退避、引擎路由（baseUrl 以 engine:// 开头走 :node 而不是 HTTP）。
 *
 * 刻意不持有任何会话状态：上下文、事件流、工具都在传输层。
 * **HTTP 客户端与端点/凭据由构造方注入**（换 client 的地方永远只有传输层一处），
 * 本类不自建 OkHttpClient、不读设置。
 */
internal class ModelStream(
    private val http: OkHttpClient,
    /** 引擎路由的 chat 桥；null = 这台设备没有 :node 引擎。 */
    private val engineChat: (suspend (JSONObject) -> JSONObject)?,
    /** 引擎通用 RPC（path, endpoint, payload）；engineModels 探测用它。 */
    private val engineRpc: (suspend (String, String, JSONObject) -> JSONObject)?,
) {

    internal suspend fun postOnce(
        cfg: LlmConfig,
        body: JsonObject,
        onReasoning: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): JsonObject {
        // 引擎路由：engine://<provider-id> 由 :node 引擎里插件注册的 provider 出答案，不碰网络。
        if (isEngineRoute(cfg.baseUrl)) return postToEngine(cfg, body, onText)
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val streamed = JsonObject(body.toMutableMap().apply { put("stream", JsonPrimitive(true)) })
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + cfg.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(streamed.toString().toRequestBody(JSON))
            .build()
        return http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw UpstreamHttpException(r.code, r.body?.string().orEmpty())
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = mutableMapOf<Int, JsonObject>()
            var usage: JsonObject? = null
            val plain = StringBuilder()
            // SSE 正常收尾必带 [DONE]；EOF 直出（连接被中途掐断）则保持 false。
            var sawDone = false
            // 上游的收尾原因（length / content_filter）：空回复的原因判定全靠它。
            var finishReason: String? = null
            // 空闲看门狗：readTimeout 只挡得住「一次读不到字节」，上游只要隔一会儿吐个保活行
            // 就能把连接一直吊着不推进。真正的判据是「多久没有解析出实质帧」，超了就抛出去。
            var lastFrameAt = System.currentTimeMillis()
            r.body?.source()?.use { src ->
                while (true) {
                    if (System.currentTimeMillis() - lastFrameAt > IDLE_TIMEOUT_MS) {
                        throw UpstreamIdleException("上游 " + IDLE_TIMEOUT_MS / 1000 + " 秒没有任何实质输出")
                    }
                    // 阻塞读不认协程取消：用户点停止后必须自己出圈，否则会继续把上一轮的
                    // 内容推回 UI，看起来像「已经停了还在输出」。
                    if (!currentCoroutineContext().isActive) break
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) {
                        // 上游无视 stream:true、直接回了一整包 JSON：留下来整体解析。
                        plain.append(line)
                        continue
                    }
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") { sawDone = true; break }
                    val frame = runCatching { DshJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    lastFrameAt = System.currentTimeMillis()
                    frame["usage"]?.let { u -> if (u is JsonObject) usage = u }
                    val choice = (frame["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    // finish_reason 只在最后一帧出现：正文被长度截断时它是唯一的证据。
                    (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }
                        ?.let { finishReason = it.content }
                    val delta = choice?.get("delta") as? JsonObject
                    val message = choice?.get("message") as? JsonObject
                    delta?.get("content")?.let {
                        if (it is JsonPrimitive && it.isString) {
                            content.append(it.content)
                            onText(it.content)
                        }
                    }
                    // 思维链字段各家用得不一样，统一交给 reasoningOf 认。
                    (reasoningOf(delta) ?: reasoningOf(message) ?: reasoningOf(frame))
                        ?.takeIf { it.isNotEmpty() }?.let {
                        reasoning.append(it)
                        onReasoning(it)
                    }
                    (delta?.get("tool_calls") as? JsonArray)?.forEach { element ->
                        val call = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                        val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val merged = toolCalls[index] ?: JsonObject(emptyMap())
                        val id = call["id"]?.jsonPrimitive?.contentOrNullSafe()
                            ?: merged["id"]?.jsonPrimitive?.contentOrNullSafe()
                        val name = call["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                            ?: merged["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        val argDelta = call["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                        val argsSoFar = merged["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                        toolCalls[index] = buildJsonObject {
                            id?.let { put("id", it) }
                            put("type", "function")
                            putJsonObject("function") {
                                name?.let { put("name", it) }
                                put("arguments", argsSoFar + argDelta)
                            }
                        }
                    }
                }
            }
            if (content.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty() && plain.isNotBlank()) {
                runCatching { DshJson.parseToJsonElement(plain.toString()).jsonObject }.getOrNull()?.let { return@use it }
            }
            // 兜底：上游有时把 DeepSeek 原生工具调用标记当正文流回来（DSML / <tool_call>）。
            // 本轮 tool_calls 为空时从正文里解析出来补进 tool_calls，并把标记文本从正文剔掉。
            var truncatedToolCall = false
            val finalContent = if (toolCalls.isEmpty()) {
                // 先记「有没有被截断」再剥：stripDsmlToolCalls 内部已经会切掉未闭合块，
                // 剥完就再也看不出当时是未闭合的了（上层要靠这个标记把静默 stop 变成可读的截断）。
                val guard = truncateOpenBlock(content.toString())
                if (guard.second) truncatedToolCall = true
                val stripped = stripDsmlToolCalls(guard.first)
                stripped.second.forEachIndexed { index, call ->
                    toolCalls[index] = call.toToolCallJson()
                }
                stripped.first
            } else {
                content.toString()
            }
            buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", JsonPrimitive(0))
                        finishReason?.let { put("finish_reason", it) }
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", finalContent)
                            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
                            if (toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    toolCalls.toSortedMap().values.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
                usage?.let { put("usage", it) }
                // 断流标记：没收到 [DONE] 就 EOF，说明上游中途断了（非流式整包在上方已提前 return）。
                if (!sawDone) put("dsh_eof_without_done", true)
                // 未闭合的工具调用块被切掉了：调用没执行，上层要能说出来，不能按 "stop" 收尾。
                if (truncatedToolCall) put("dsh_truncated_tool_call", true)
            }
        }
    }

    /** baseUrl 是否是引擎路由（engine:// 前缀，忽略首尾空白）。 */
    private fun isEngineRoute(baseUrl: String): Boolean =
        baseUrl.trim().startsWith(ENGINE_SCHEME)

    /**
     * 一次引擎往返：把 [body] 原样当 JSONObject 递进 :node，拿 value.content 当整段回复。
     *
     * 契约（引擎侧 /llm chat）：返回 {ok:true,value:{content,toolCalls?,usage?}}；content 是完整正文，
     * 不流式（引擎里没有 token 级回调），所以只 onText 一次，再由调用方做增量去重；
     * toolCalls 是插件 provider 转出来的 OpenAI 形状调用，原样塞进 choices[].message.tool_calls。
     */
    internal suspend fun postToEngine(
        cfg: LlmConfig,
        body: JsonObject,
        onText: (String) -> Unit = {},
    ): JsonObject {
        val call = engineChat ?: throw IOException(ENGINE_NOT_READY)
        val payload = runCatching { JSONObject(body.toString()) }
            .getOrElse { throw IOException("引擎请求体无法构造：" + it.message) }
        payload.put("model", engineModelOf(cfg))
        val resp = call(payload)
        if (!resp.optBoolean("ok", true)) {
            throw IOException("引擎回复失败：" + resp.optString("error").ifBlank { "未知错误" })
        }
        val value = resp.optJSONObject("value") ?: JSONObject()
        val rawContent = value.optString("content")
        // 插件 provider 转出来的结构化工具调用（OpenAI 形状），直接还原成 choices[].message.tool_calls。
        val calls = mutableListOf<JsonObject>()
        value.optJSONArray("toolCalls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val one = arr.optJSONObject(i) ?: continue
                runCatching { DshJson.parseToJsonElement(one.toString()).jsonObject }.getOrNull()?.let { calls += it }
            }
        }
        // 插件没给结构化调用时，再从正文里捞 DSML / <tool_call> 标记（部分 provider 只会写正文），
        // 捞到的同样进 tool_calls，标记文本从正文剔掉，避免 markup 整屏涌进 UI。
        val content = if (calls.isEmpty()) {
            val stripped = stripDsmlToolCalls(rawContent)
            stripped.second.forEach { calls += it.toToolCallJson() }
            stripped.first
        } else {
            rawContent
        }
        if (content.isNotEmpty()) onText(content)
        // usage 各家字段不一致，原样透给调用方（那边本来就用 contentOrNullSafe 宽松取值）。
        val usage = value.optJSONObject("usage")
        return buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    put("index", JsonPrimitive(0))
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                    }
                }
            }
            put("usage", usageJsonOf(usage))
        }
    }


    /** engine:// 后面的那段就是插件声明的模型 id；留空则退回配置里的 model 名。 */
    private fun engineModelOf(cfg: LlmConfig): String =
        cfg.baseUrl.trim().removePrefix(ENGINE_SCHEME).trim().ifBlank { cfg.model }

    private fun usageJsonOf(usage: JSONObject?): JsonObject =
        if (usage == null || usage.length() == 0) {
            JsonObject(emptyMap())
        } else {
            runCatching { DshJson.parseToJsonElement(usage.toString()).jsonObject }
                .getOrNull() ?: JsonObject(emptyMap())
        }

    /**
     * 引擎里可用的 provider 模型 id 列表（/llm models）。
     *
     * 引擎没起来、或宿主没注入 engineRpc 时抛 IOException——探活是可选能力，
     * 不要让它拖住主对话。
     */
    suspend fun engineModels(): List<String> {
        val rpc = engineRpc ?: throw IOException(ENGINE_NOT_READY)
        val resp = rpc("/llm", "models", JSONObject())
        if (!resp.optBoolean("ok", true)) {
            throw IOException("引擎回复失败：" + resp.optString("error").ifBlank { "未知错误" })
        }
        val value = resp.opt("value")
        val out = mutableListOf<String>()
        fun collect(from: org.json.JSONArray?) {
            if (from == null) return
            for (i in 0 until from.length()) {
                val item = from.opt(i)
                when (item) {
                    is String -> if (item.isNotBlank()) out += item
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("model") }
                        if (id.isNotBlank() && id !in out) out += id
                    }
                }
            }
        }
        if (value is JSONObject) {
            collect(value.optJSONArray("models"))
            collect(value.optJSONArray("data"))
            value.optString("model").takeIf { it.isNotBlank() }?.let { if (it !in out) out += it }
        } else if (value is org.json.JSONArray) {
            collect(value)
        } else if (value is String && value.isNotBlank()) {
            out += value
        }
        return out
    }

    /**
     * 请求 + 网关自举 + 只在连接类错误上退避重试。
     *
     * 模型地址常填本机网关（插件提供的进程）：它可能没起来、被系统杀掉、或上一次还在绑端口。
     * 连不上时先把网关拉起来再试，别让用户看到「断断续续」。
     */
    internal suspend fun post(
        cfg: LlmConfig,
        body: JsonObject,
        onReasoning: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): JsonObject {
        val host = runCatching { java.net.URI(cfg.baseUrl.trim()).host }.getOrNull()
        val loopback = LoopbackGateway.isLoopback(host)
        if (loopback) LoopbackGateway.ensure()
        var last: Throwable? = null
        var attempt = 0
        while (attempt < GATEWAY_ATTEMPTS) {
            try {
                return postOnce(cfg, body, onReasoning, onText)
            } catch (e: Throwable) {
                last = e
                if (!isConnectFailure(e)) throw e
                if (loopback) LoopbackGateway.ensure()
                if (attempt < GATEWAY_ATTEMPTS - 1) delay(GATEWAY_BACKOFF_MS)
                attempt++
            }
        }
        throw last ?: IOException("请求失败")
    }

    private fun isConnectFailure(e: Throwable): Boolean {
        if (e is java.net.ConnectException) return true
        if (e is java.net.SocketTimeoutException) return true
        val msg = e.message?.lowercase() ?: return false
        return CONNECT_HINTS.any { msg.contains(it) }
    }

    internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
        cont.invokeOnCancellation { runCatching { cancel() } }
    }


    /**
     * 把重试请求里最后一条 user 消息的正文前置一段框架提示（见 [PERSONA_FRAME]）。
     *
     * 带图时 content 是 parts 数组：框架作为第一个 text 段插进去，图片段原样保留。
     */
    internal fun frameUserMessage(body: JsonObject, frame: String): JsonObject {
        val messages = (body["messages"] as? JsonArray)?.toMutableList() ?: return body
        val index = messages.indexOfLast {
            (it as? JsonObject)?.get("role")?.jsonPrimitive?.contentOrNullSafe() == "user"
        }
        if (index < 0) return body
        val message = messages[index] as? JsonObject ?: return body
        val framed: JsonElement = when (val content = message["content"]) {
            null -> JsonPrimitive(frame)
            is JsonPrimitive -> JsonPrimitive(frame + "\n\n" + content.contentOrNullSafe().orEmpty())
            is JsonArray -> JsonArray(
                listOf(buildJsonObject {
                    put("type", "text")
                    put("text", frame)
                }) + content,
            )
            else -> content
        }
        messages[index] = buildJsonObject {
            message.forEach { (key, value) -> if (key != "content") put(key, value) }
            put("content", framed)
        }
        return buildJsonObject {
            body.forEach { (key, value) -> if (key != "messages") put(key, value) }
            put("messages", JsonArray(messages))
        }
    }

    /** 把连接类错误翻成人话：本机网关没起来时，用户根本不知道要去哪点一下。 */
    internal fun friendlyRequestError(e: Throwable, baseUrl: String, hasImages: Boolean = false): String {
        // 上游给了明确状态码：文案已经是「谁的问题、去哪改」，别再叠一层。
        if (e is UpstreamHttpException) return e.message ?: httpFailureReason(e.status, e.body)
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host ?: ""
        val isLocal = host == "127.0.0.1" || host == "localhost" || host == "::1"
        val refused = raw.contains("Failed to connect", true) ||
            raw.contains("Connection refused", true) ||
            raw.contains("ConnectException", true)
        if (isLocal && refused) {
            val port = uri?.port?.takeIf { it > 0 } ?: 80
            return "本机网关 127.0.0.1:" + port + " 没在运行（" + raw + "）。\n" +
                "宿主启动时会自动拉起它；仍失败就到「插件」页点一次启动，或把这个模型的地址改成上游直连。"
        }
        // 带图请求被上游打回时，报错文本常常只有一句 400，看不出是图片的问题。
        if (hasImages && (raw.contains("400") || raw.contains("image", true) || raw.contains("content", true))) {
            return raw + "\n\n这一轮带了图片：上游多半不吃图文混排（纯文本模型会把 image_url 直接打回）。" +
                "换一个支持视觉的模型重试，或者把图里的内容打字发出来。"
        }
        return raw
    }

    companion object {
        /** 引擎路由前缀：baseUrl="engine://<provider-model-id>"。 */
        const val ENGINE_SCHEME = "engine://"
        const val ENGINE_NOT_READY = "Engine not ready: open the plugin page once so the :node process starts"

        /** 多久没有任何实质帧就判定上游卡死（每解析出一帧就重置）。 */
        const val IDLE_TIMEOUT_MS = 300_000L

        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 上游长时间没有任何实质帧（不是 TCP 断了，是一直不推进）。
 *
 * 刻意不是 [java.net.SocketTimeoutException]：那个会被 [ModelStream.isConnectFailure] 判成连接问题，
 * 叠上 GATEWAY_ATTEMPTS 以后再乘上层重试，用户要等十几分钟才看到一句报错。
 */
internal class UpstreamIdleException(message: String) : IOException(message)
