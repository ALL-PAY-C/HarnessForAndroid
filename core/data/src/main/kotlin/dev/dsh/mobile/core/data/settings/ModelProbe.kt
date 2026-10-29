package dev.dsh.mobile.core.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 「测试连接」的结果。纯数据，展示文案交给 [ModelProbe.formatResult]，这样格式化逻辑
 * 可以在 JVM 单测里直接验证，不用起网络。
 */
data class ProbeResult(
    val ok: Boolean,
    val elapsedMs: Long,
    /** GET /models 拿到的前几个模型名；走 chat/completions 退化成功时为空。 */
    val models: List<String> = emptyList(),
    /** 非 2xx 时的 HTTP 状态码；0 = 没拿到响应（连接失败 / 超时）。 */
    val httpCode: Int = 0,
    /** 失败原因（异常信息或响应体片段）。 */
    val error: String = "",
    /** 成功时的补充说明（例如本地引擎插件不用联网）。 */
    val note: String = "",
)

/**
 * 一键验证模型配置：先问 GET {baseUrl}/models（不打 token），拿不到再退化成
 * 一条 max_tokens=1 的 chat 请求，验证 key 与模型名真的能用。
 *
 * 只用 java.net.HttpURLConnection，不引第三方网络库。
 */
object ModelProbe {

    const val DEFAULT_TIMEOUT_MS = 6_000

    /** Snackbar 放不下更多，只回前几个模型名。 */
    const val MODEL_PREVIEW = 3

    suspend fun probe(
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ProbeResult = withContext(Dispatchers.IO) {
        probeBlocking(baseUrl, apiKey, model, timeoutMs)
    }

    /** 阻塞版；调用方负责放到 IO 线程（[probe] 已处理）。 */
    fun probeBlocking(
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ProbeResult {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return ProbeResult(ok = false, elapsedMs = 0, error = "Base URL 为空")
        // engine:// 是设备内引擎插件（engine/host/llm.js），没有 HTTP 端点：
        // 以前这里直接 new URL(engine://...) 会抛 unknown protocol: engine，测试按钮永远红。
        if (!root.startsWith("http://") && !root.startsWith("https://")) {
            return ProbeResult(
                ok = true,
                elapsedMs = 0,
                models = listOfNotNull(model.trim().takeIf { it.isNotEmpty() }),
                note = "引擎插件，免网络",
            )
        }
        val started = System.currentTimeMillis()

        // 1) GET {baseUrl}/models：带 Authorization: Bearer key
        val listAttempt = runCatching { request("$root/models", "GET", apiKey, null, timeoutMs) }
        val listOut = listAttempt.getOrNull()
        if (listOut != null && listOut.code in 200..299) {
            val ids = runCatching { parseModelIds(listOut.body) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) {
                return ProbeResult(
                    ok = true,
                    elapsedMs = System.currentTimeMillis() - started,
                    models = ids.take(MODEL_PREVIEW),
                )
            }
        }

        // 2) 退化：真发一条 1 token 的对话，模型名与 key 一起验证
        val payload = JSONObject()
            .put("model", model.trim())
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
            )
            .put("max_tokens", 1)
            .put("stream", false)
            .toString()
        val chatAttempt = runCatching {
            request("$root/chat/completions", "POST", apiKey, payload, timeoutMs)
        }
        val chatOut = chatAttempt.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        return when {
            chatOut == null -> ProbeResult(
                ok = false,
                elapsedMs = elapsed,
                error = describe(chatAttempt.exceptionOrNull(), timeoutMs),
            )

            chatOut.code in 200..299 -> ProbeResult(ok = true, elapsedMs = elapsed)

            else -> ProbeResult(
                ok = false,
                elapsedMs = elapsed,
                httpCode = chatOut.code,
                error = chatOut.body.take(160),
            )
        }
    }

    /** 成功 · 320ms · 模型 a,b / 失败 · HTTP 401 —— 纯函数，单测覆盖。 */
    fun formatResult(result: ProbeResult): String {
        if (result.ok) {
            val head = if (result.note.isNotBlank()) "成功 · " + result.note
            else "成功 · " + result.elapsedMs + "ms"
            return if (result.models.isEmpty()) head
            else head + " · 模型 " + result.models.joinToString(",")
        }
        val why = when {
            result.httpCode > 0 -> "HTTP " + result.httpCode
            result.error.isNotBlank() -> result.error
            else -> "未知错误"
        }
        return "失败 · " + why
    }

    /** 兼容 {data:[{id}]} / {models:[...]} / {models:[{id|name}]} / 纯数组。 */
    fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull()
        val array = json?.optJSONArray("data")
            ?: json?.optJSONArray("models")
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("name") }
                    .takeIf { it.isNotBlank() }
                    ?.let { out += it }

                is String -> if (item.isNotBlank()) out += item
            }
        }
        return out.sorted()
    }

    private class HttpOutcome(val code: Int, val body: String)

    private fun request(
        url: String,
        method: String,
        apiKey: String,
        body: String?,
        timeoutMs: Int,
    ): HttpOutcome {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + apiKey.trim())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpOutcome(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(t: Throwable?, timeoutMs: Int): String = when {
        t == null -> "未知错误"
        t is SocketTimeoutException -> "连接超时（" + timeoutMs + "ms）"
        else -> (t.message ?: t.javaClass.simpleName).take(120)
    }
}
