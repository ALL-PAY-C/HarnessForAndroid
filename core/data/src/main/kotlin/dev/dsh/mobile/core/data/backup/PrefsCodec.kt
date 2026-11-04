package dev.dsh.mobile.core.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences → JSON 的编解码。
 *
 * 类型必须原样带回去：Android 的 preferences 是强类型的，把 Int 读成 Long 会让
 * `getInt` 抛 ClassCastException。所以数值 / 集合都带一个短标记，只有 String 用裸值。
 */
object PrefsCodec {

    fun encode(values: Map<String, Any?>): String {
        val root = JSONObject()
        for ((key, value) in values) root.put(key, encodeValue(value))
        return root.toString()
    }

    fun decode(text: String): Map<String, Any?> {
        if (text.isBlank()) return emptyMap()
        val root = JSONObject(text)
        val out = LinkedHashMap<String, Any?>()
        for (key in root.keys()) out[key] = decodeValue(root.opt(key))
        return out
    }

    private fun encodeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean -> value
        is Int -> JSONObject().put("\$i", value)
        is Long -> JSONObject().put("\$l", value)
        is Float -> JSONObject().put("\$f", value.toDouble())
        is String -> value
        is Set<*> -> JSONObject().put("\$s", JSONArray(value.mapNotNull { it as? String }))
        else -> value.toString()
    }

    private fun decodeValue(raw: Any?): Any? = when (raw) {
        null, JSONObject.NULL -> null
        is Boolean -> raw
        is String -> raw
        is JSONObject -> when {
            raw.has("\$i") -> raw.optInt("\$i")
            raw.has("\$l") -> raw.optLong("\$l")
            raw.has("\$f") -> raw.optDouble("\$f").toFloat()
            raw.has("\$s") -> raw.optJSONArray("\$s").toStringSet()
            else -> raw.toString()
        }
        is JSONArray -> raw.toStringSet()
        else -> raw.toString()
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}
