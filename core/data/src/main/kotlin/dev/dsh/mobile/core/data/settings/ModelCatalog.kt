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
    /** OpenAI-compatible `GET /v1/models`, tolerant of the usual shape variants. */
    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = baseUrl.trim().trimEnd('/')
                require(root.isNotEmpty()) { "Base URL 为空" }
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
