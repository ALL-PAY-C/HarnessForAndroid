package dev.dsh.mobile.core.platform.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import dev.dsh.mobile.core.model.settings.contextWindowOf

// O8-B①: 从 core:data.settings.ModelStore 上移（端点上下文窗口探测；parseContextLengths 保持 internal，单测随文件迁到本模块）。
/**
 * 端点 /models 返回里各模型的上下文窗口表（context_length / context_window）。
 *
 * 各家字段名不统一也不保证存在，所以读不到就返回空表、调用方退回内置表。
 * 纯字符串解析，单测直接喂样例 JSON。
 */
internal fun parseContextLengths(body: String): Map<String, Int> {
    if (body.isBlank()) return emptyMap()
    val json = runCatching { JSONObject(body) }.getOrNull()
    val array = json?.optJSONArray("data")
        ?: json?.optJSONArray("models")
        ?: runCatching { JSONArray(body) }.getOrNull()
        ?: return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("id").ifBlank { item.optString("name") }.trim()
        if (id.isEmpty()) continue
        val window = listOf("context_length", "context_window", "max_context_length", "max_input_tokens")
            .firstNotNullOfOrNull { field -> item.optInt(field, 0).takeIf { it > 0 } }
            ?: continue
        out[id] = window
    }
    return out
}

/**
 * 从模型端点的 GET {baseUrl}/models 读一次窗口表；失败返回空表，从不抛。
 *
 * 与「测试连接」同一条请求路径，都走 core/common 的 HttpJson 门面。
 * 界面拿到模型列表后再调它，把 [contextWindowOf] 的 discovered 填上。
 */
suspend fun probeContextLengths(
    baseUrl: String,
    apiKey: String,
    timeoutMs: Int = 8_000,
): Map<String, Int> = withContext(Dispatchers.IO) {
    val root = baseUrl.trim().trimEnd('/')
    if (root.isEmpty()) return@withContext emptyMap()
    runCatching {
        val headers = buildMap {
            put("Accept", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer " + apiKey.trim())
        }
        // 非 2xx 由门面抛 HttpJsonException，和以前 return emptyMap 一样落到 runCatching 的兜底。
        parseContextLengths(HttpJson.getText(root + "/models", headers, timeoutMs))
    }.getOrDefault(emptyMap())
}
