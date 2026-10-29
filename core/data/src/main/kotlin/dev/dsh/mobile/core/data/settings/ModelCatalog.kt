package dev.dsh.mobile.core.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lists the models an endpoint actually serves.
 *
 * A single key usually unlocks several models, and the gateway only routes to
 * whatever name the client asks for — so the app has to ask, then let the user
 * pick, instead of silently using one hardcoded default.
 */
object ModelCatalog {
    /** engine:// 端点由宿主引擎通道供模型列表，这条 HTTP 路永远不走。 */
    private const val ENGINE_HINT = "engine:// 端点由引擎插件提供，模型列表走宿主引擎通道（对话框里的「拉取模型列表」会自动用这条通道）"

    /** engine:// 端点：调用方据此换通道（引擎通道），而不是来 fetch。 */
    fun isEngineEndpoint(baseUrl: String): Boolean =
        baseUrl.trim().lowercase().startsWith("engine://")

    /**
     * 端点能不能走 HTTP。返回 null = 可以；否则是给用户看的原因。
     *
     * java.net.URL 对 engine:// 抛 MalformedURLException: unknown protocol，
     * 用户看到的是一句协议层天书；这里换成能照着做的提示，且绝不进 URL。
     * 纯函数，单测直接打这里。
     */
    fun schemeRejection(baseUrl: String): String? {
        val root = baseUrl.trim()
        val scheme = root.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
        return when (scheme) {
            "http", "https" -> null
            "engine" -> ENGINE_HINT
            else -> "只支持 http/https 端点，当前是「${scheme.ifBlank { root }}」"
        }
    }

    /** OpenAI-compatible `GET /v1/models`, tolerant of the usual shape variants. */
    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = baseUrl.trim().trimEnd('/')
                require(root.isNotEmpty()) { "Base URL 为空" }
                schemeRejection(root)?.let { throw IllegalArgumentException(it) }
                val candidates = listOf("$root/models", "$root/v1/models").distinct()
                var lastError: String? = null
                var found: List<String>? = null
                for (url in candidates) {
                    val attempt = runCatching { get(url, apiKey) }
                    val body = attempt.getOrNull()
                    if (body == null) {
                        lastError = attempt.exceptionOrNull()?.message
                    } else {
                        val ids = parse(body)
                        if (ids.isNotEmpty()) {
                            found = ids
                            break
                        }
                        lastError = "响应里没有模型列表"
                    }
                }
                found ?: error(lastError ?: "无法获取模型列表")
            }
        }

    private fun get(url: String, apiKey: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${text.take(160)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Accepts `{data:[{id}]}`, `{models:[...]}` and `{models:[{id|name}]}`. */
    private fun parse(body: String): List<String> {
        val json = JSONObject(body)
        val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
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
}
