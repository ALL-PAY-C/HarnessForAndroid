package dev.dsh.mobile.toolbridge

import java.util.Locale

/**
 * 极小的 JSON 输出助手。
 *
 * 工具返回值必须是结构化 JSON，但本模块不引第三方 JSON 库（序列化就这么点活），
 * 而 Android 自带的 org.json 在 JVM 单测里是空实现、一调就炸 —— 所以自己拼。
 * 只负责「拼一段合法 JSON」，不负责解析。
 */
internal object MiniJson {

    /** 已拼好的 JSON 片段：嵌进对象时原样输出，不再加引号。 */
    class Raw(val text: String)

    fun str(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append(String.format(Locale.ROOT, "\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> str(key) + ":" + value(value) }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]") { it }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.text
        is String -> str(v)
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is List<*> -> arr(v.map { value(it) })
        else -> str(v.toString())
    }
}
