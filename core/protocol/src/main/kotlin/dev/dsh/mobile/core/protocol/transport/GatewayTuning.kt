package dev.dsh.mobile.core.protocol.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 网关重试/连接失败判定与思维链字段识别：只跟请求拼装和流式响应解析有关，与群组编排无关。
// 放 transport 层：agent 侧（ModelStream/GroupSupport）单向引用它，反过来没有边。

/** 思维链可能出现的字段名（各家上游不统一）。 */
private val REASONING_KEYS = listOf("reasoning_content", "reasoning", "thinking", "reasoning_details", "analysis")
private val REASONING_INNER_KEYS = listOf("summary", "text", "content")

/** 本机网关的自举重试：连不上才重试，非连接类错误一次就抛。 */
internal const val GATEWAY_ATTEMPTS = 3
internal const val GATEWAY_BACKOFF_MS = 700L

internal val CONNECT_HINTS = listOf(
    "failed to connect",
    "connection refused",
    "connect timed out",
    "econnrefused",
    "connection reset",
    "broken pipe",
    "no route to host",
)

private fun innerThoughtText(obj: JsonObject): String? {
    for (k in REASONING_INNER_KEYS) {
        val v = obj[k]
        if (v is JsonPrimitive && v.isString) return v.content
    }
    return null
}

/** 从 delta / message 里认出思维链：字段名五选一，值可能是字符串、数组或对象。 */
internal fun reasoningOf(obj: JsonObject?): String? {
    if (obj == null) return null
    for (key in REASONING_KEYS) {
        val v = obj[key] ?: continue
        val text = when (v) {
            is JsonPrimitive -> if (v.isString) v.content else null
            is JsonArray -> v.mapNotNull {
                when (it) {
                    is JsonPrimitive -> if (it.isString) it.content else null
                    is JsonObject -> innerThoughtText(it)
                    else -> null
                }
            }.filter { it.isNotBlank() }.joinToString("")
            is JsonObject -> innerThoughtText(v)
            else -> null
        } ?: continue
        if (text.isNotBlank()) return text
    }
    return null
}
