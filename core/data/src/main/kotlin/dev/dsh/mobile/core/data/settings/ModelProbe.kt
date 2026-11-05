package dev.dsh.mobile.core.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import dev.dsh.mobile.core.platform.net.HttpJson
import dev.dsh.mobile.core.platform.net.HttpJsonException
import java.net.SocketTimeoutException

/** 探测失败的分类。展示文案由 UI 层按资源拼接（core:data 不带中文）。 */
enum class ProbeFailure { EmptyBaseUrl, Timeout, Transport, Unknown }

/** 「测试连接」的结果。纯数据，展示文案在 UI 层按资源拼。 */
data class ProbeResult(
    val ok: Boolean,
    val elapsedMs: Long,
    /** GET /models 拿到的前几个模型名；走 chat/completions 退化成功时为空。 */
    val models: List<String> = emptyList(),
    /** 非 2xx 时的 HTTP 状态码；0 = 没拿到响应（连接失败 / 超时）。 */
    val httpCode: Int = 0,
    /** 失败原因（异常信息或响应体片段）。 */
    val error: String = "",
    /** 失败的分类；成功或只有原始错误串时为 null。 */
    val failure: ProbeFailure? = null,
    /** 设备内引擎插件（engine:// ...）不需要联网，成功时直接算通过。 */
    val engineOffline: Boolean = false,
)

/**
 * 一键验证模型配置：先问 GET {baseUrl}/models（不打 token），拿不到再退化成
 * 一条 max_tokens=1 的 chat 请求，验证 key 与模型名真的能用。
 *
 * 请求走 core/common 的 HttpJson 门面，不引第三方网络库。
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
        if (root.isEmpty()) return ProbeResult(ok = false, elapsedMs = 0, failure = ProbeFailure.EmptyBaseUrl)
        // engine:// 是设备内引擎插件（engine/host/llm.js），没有 HTTP 端点：
        // 以前这里直接 new URL(engine://...) 会抛 unknown protocol: engine，测试按钮永远红。
        if (!root.startsWith("http://") && !root.startsWith("https://")) {
            return ProbeResult(
                ok = true,
                elapsedMs = 0,
                models = listOfNotNull(model.trim().takeIf { it.isNotEmpty() }),
                engineOffline = true,
            )
        }
        val started = System.currentTimeMillis()

        // 1) GET {baseUrl}/models：带 Authorization: Bearer key
        val listAttempt = runCatching { request("$root/models", apiKey, null, timeoutMs) }
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
            request("$root/chat/completions", apiKey, payload, timeoutMs)
        }
        val chatOut = chatAttempt.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        return when {
            chatOut == null -> {
                val (failure, detail) = describe(chatAttempt.exceptionOrNull())
                ProbeResult(ok = false, elapsedMs = elapsed, error = detail, failure = failure)
            }

            chatOut.code in 200..299 -> ProbeResult(ok = true, elapsedMs = elapsed)

            else -> ProbeResult(
                ok = false,
                elapsedMs = elapsed,
                httpCode = chatOut.code,
                error = chatOut.body.take(160),
            )
        }
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
        apiKey: String,
        body: String?,
        timeoutMs: Int,
    ): HttpOutcome {
        val headers = buildMap {
            put("Accept", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer " + apiKey.trim())
        }
        return try {
            // 门面把非 2xx 抛成 HttpJsonException；这里翻回原来「带 code 交调用方判断」的形状。
            val text = if (body == null) {
                HttpJson.getTextBlocking(url, headers, timeoutMs)
            } else {
                HttpJson.postJsonBlocking(url, body, headers, timeoutMs)
            }
            HttpOutcome(200, text)
        } catch (e: HttpJsonException) {
            HttpOutcome(e.status, e.body.orEmpty())
        }
    }

    private fun describe(t: Throwable?): Pair<ProbeFailure, String> = when {
        t == null -> ProbeFailure.Unknown to ""
        t is SocketTimeoutException -> ProbeFailure.Timeout to ""
        else -> ProbeFailure.Transport to (t.message ?: t.javaClass.simpleName).take(120)
    }
}
