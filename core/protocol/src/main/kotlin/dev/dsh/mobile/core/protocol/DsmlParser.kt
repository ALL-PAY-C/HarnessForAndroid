package dev.dsh.mobile.core.protocol

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 上游把 DeepSeek 原生工具调用标记当正文流回来时的兜底解析。
 *
 * 现象：模型本该走 tool_calls 字段，但网关把 DSML 标记当文本塞进 delta.content：
 *
 *     <||DSML||tool_calls>
 *     <||DSML||invoke name="shell">
 *     <||DSML||parameter name="command" string="true">ls /sdcard</||DSML||parameter>
 *     </||DSML||invoke>
 *     </||DSML||tool_calls>
 *
 * 于是本轮 message.tool_calls 为空 → 上层判定「模型自己收尾」→ 任务停住，用户只看到一坨标记。
 * 这里做三件事：认出这些标记块、把标记块从正文里删掉、把块内容翻成 [{name, arguments}]。
 *
 * 竖线写了三种样子（| / 全角｜ / 全角双｜｜），所以匹配一律用「竖线段算一个竖线」的比较，
 * 不做字符替换——下标始终落在原文上，切文本不会错位。
 *
 * 失败必须无声：任何解析异常都退回「原文 + 空结果」，绝不把异常带给调用方。
 */
internal data class DsmlToolCall(val name: String, val arguments: String) {
    /** 翻成 message.tool_calls 里的那一项，id 用 UUID（上游没给）。 */
    fun toToolCallJson(id: String = UUID.randomUUID().toString()): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("arguments", arguments)
        }
    }
}

private const val DSML_MARK = "DSML"

/** 一段连续竖线（| 或 全角｜，一个或多个）之后的第一个下标。 */
private fun pipeRunEnd(s: String, from: Int): Int {
    var i = from
    while (i < s.length && (s[i] == '|' || s[i] == '\uFF5C')) i++
    return i
}

/** 同一个标记的常见写法：`<|DSML| calls>`、`<|DSML| invoke>` 空格与别名都认。 */
private fun tagAliases(name: String): List<String> = when (name.lowercase()) {
    "tool_calls" -> listOf("tool_calls", "calls", "call", "tool_call", "tool_use", "function_calls", "function_call")
    "invoke" -> listOf("invoke", "function", "tool_use")
    "parameter" -> listOf("parameter", "param", "arguments", "argument", "arg")
    else -> listOf(name)
}

private fun skipSpace(s: String, from: Int): Int {
    var i = from
    while (i < s.length && s[i].isWhitespace()) i++
    return i
}

/** 从 i 起匹配某个别名（忽略大小写、后接标识符继续则不算），返回匹配长度，未中返回 -1。 */
private fun matchAlias(s: String, i: Int, name: String): Int {
    var best = -1
    for (a in tagAliases(name)) {
        if (regionMatchesIgnore(s, i, a)) {
            val end = i + a.length
            if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) continue
            if (a.length > best) best = a.length
        }
    }
    return best
}

private fun regionMatchesIgnore(s: String, from: Int, sub: String): Boolean =
    from >= 0 && from + sub.length <= s.length && s.regionMatches(from, sub, 0, sub.length, ignoreCase = true)

private fun indexOfIgnore(s: String, sub: String, from: Int): Int {
    if (sub.isEmpty()) return -1
    val first = sub[0].lowercaseChar()
    var i = from.coerceAtLeast(0)
    while (i + sub.length <= s.length) {
        if (s[i].lowercaseChar() == first && regionMatchesIgnore(s, i, sub)) return i
        i++
    }
    return -1
}

private val DSML_ATTR = Regex("""([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s">]+))""")

private fun attrOf(tag: String, name: String): String? {
    for (m in DSML_ATTR.findAll(tag)) {
        if (m.groupValues[1].equals(name, ignoreCase = true)) {
            return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
        }
    }
    return null
}

/** 匹配 <|DSML|name ... >（竖线可多可少），返回开标签结束（> 之后）的下标；不像这个标签就返回 null。 */
private fun dsmlOpenEnd(s: String, from: Int, name: String): Int? {
    if (from < 0 || from >= s.length || s[from] != '<') return null
    var i = pipeRunEnd(s, from + 1)
    if (i == from + 1) return null
    if (!regionMatchesIgnore(s, i, DSML_MARK)) return null
    i += DSML_MARK.length
    val afterPipes = pipeRunEnd(s, i)
    if (afterPipes == i) return null
    i = skipSpace(s, afterPipes)
    val matched = matchAlias(s, i, name)
    if (matched < 0) return null
    i += matched
    val gt = s.indexOf('>', i)
    if (gt < 0) return null
    val lt = s.indexOf('<', i)
    if (lt in 0 until gt) return null
    return gt + 1
}

private class TagClose(val start: Int, val end: Int)

/** 找 </|DSML|name>（竖线可多可少），返回闭合标签的起止下标。 */
private fun dsmlCloseAt(s: String, from: Int, name: String): TagClose? {
    var i = from.coerceAtLeast(0)
    while (i < s.length) {
        val lt = s.indexOf('<', i)
        if (lt < 0) return null
        if (lt + 1 < s.length && s[lt + 1] == '/') {
            var j = pipeRunEnd(s, lt + 2)
            if (j > lt + 2 && regionMatchesIgnore(s, j, DSML_MARK)) {
                j += DSML_MARK.length
                val afterPipes = pipeRunEnd(s, j)
                if (afterPipes > j) {
                    j = skipSpace(s, afterPipes)
                    val matched = matchAlias(s, j, name)
                    if (matched >= 0) {
                        val k = skipSpace(s, j + matched)
                        if (k < s.length && s[k] == '>') return TagClose(lt, k + 1)
                    }
                }
            }
        }
        i = lt + 1
    }
    return null
}

private fun parseJsonOrNull(raw: String): JsonElement? =
    runCatching { DshJson.parseToJsonElement(raw.trim()) }.getOrNull()

/**
 * 参数值落成 JSON：string="true" 永远当字符串；string="false" 或 xsi:type="object|array|…"
 * 先试 JSON；都没有时，只有长得像结构（{ 或 [ 开头）才试 JSON，其余一律当字符串。
 * 这样 ls -la 不会因为解析失败变 null，{"a":1} 也不会被当字符串塞给 shell。
 */
private fun coerceValue(raw: String, stringFlag: String?, typeAttr: String?): JsonElement {
    val text = raw.trim()
    if (stringFlag.equals("true", ignoreCase = true)) return JsonPrimitive(text)
    val structural = stringFlag.equals("false", ignoreCase = true) ||
        typeAttr?.lowercase() in setOf("object", "array", "number", "boolean", "integer")
    if (structural) return parseJsonOrNull(text) ?: JsonPrimitive(text)
    if (text.startsWith("{") || text.startsWith("[")) parseJsonOrNull(text)?.let { return it }
    return JsonPrimitive(text)
}

/** 一个 invoke 内的全部 parameter 翻成参数对象 JSON 字符串；没有参数就是 {}。 */
private fun paramsJson(inner: String): String {
    val params = LinkedHashMap<String, JsonElement>()
    var i = 0
    while (i < inner.length) {
        val lt = inner.indexOf('<', i)
        if (lt < 0) break
        val openEnd = dsmlOpenEnd(inner, lt, "parameter")
        if (openEnd == null) {
            i = lt + 1
            continue
        }
        val tag = inner.substring(lt, openEnd)
        val close = dsmlCloseAt(inner, openEnd, "parameter")
        if (close == null) {
            i = lt + 1
            continue
        }
        val name = attrOf(tag, "name")?.trim()
        if (!name.isNullOrBlank()) {
            val value = coerceValue(
                inner.substring(openEnd, close.start),
                attrOf(tag, "string"),
                attrOf(tag, "xsi:type") ?: attrOf(tag, "type"),
            )
            params[name] = value
        }
        i = close.end
    }
    return JsonObject(params).toString()
}

/** 一个或多个 <|DSML|invoke name="x">…</|DSML|invoke>。 */
private fun parseInvokes(body: String): List<DsmlToolCall> {
    val calls = ArrayList<DsmlToolCall>()
    var i = 0
    while (i < body.length) {
        val lt = body.indexOf('<', i)
        if (lt < 0) break
        val openEnd = dsmlOpenEnd(body, lt, "invoke")
        if (openEnd == null) {
            i = lt + 1
            continue
        }
        val close = dsmlCloseAt(body, openEnd, "invoke")
        if (close == null) {
            i = lt + 1
            continue
        }
        val name = attrOf(body.substring(lt, openEnd), "name")?.trim()
        if (!name.isNullOrBlank()) calls += DsmlToolCall(name, paramsJson(body.substring(openEnd, close.start)))
        i = close.end
    }
    return calls
}

private fun argsJson(v: JsonElement?): String = when (v) {
    null, is JsonNull -> "{}"
    is JsonPrimitive -> if (v.isString) v.content.ifBlank { "{}" } else v.content
    else -> v.toString()
}

private fun callFromObject(obj: JsonObject): List<DsmlToolCall> {
    val fn = obj["function"] as? JsonObject
    val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: (fn?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return emptyList()
    val args = obj["arguments"] ?: obj["parameters"] ?: obj["args"]
        ?: fn?.get("arguments") ?: fn?.get("parameters")
    return listOf(DsmlToolCall(name.trim(), argsJson(args)))
}

/** <tool_call>{json}</tool_call>：接受单个对象、对象数组、{tool_calls:[…]}、以及 OpenAI 的 function 包装。 */
internal fun callsFromJson(raw: String): List<DsmlToolCall> {
    val el = parseJsonOrNull(raw) ?: return emptyList()
    return runCatching {
        when (el) {
            is JsonArray -> el.mapNotNull { it as? JsonObject }.flatMap { callFromObject(it) }
            is JsonObject -> (el["tool_calls"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.flatMap { callFromObject(it) }
                ?: callFromObject(el)
            else -> emptyList()
        }
    }.getOrElse { emptyList() }
}

/** 标记块删掉后收拾空行：三行以上压成两行，首尾空白去掉。 */
private fun tidy(s: String): String =
    s.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

/**
 * 正文里混着的工具调用标记 →（去掉标记的正文, 解析出的调用）。
 *
 * 只有真解析出调用时才删文本；解析不出来（缺闭合标签、JSON 坏了、认不出的写法）一律原样返回，
 * 让上层继续按「模型自己收尾」走，用户看得见的还是模型写的东西。
 */
internal fun stripDsmlToolCalls(text: String): Pair<String, List<DsmlToolCall>> {
    if (text.isEmpty() || text.indexOf('<') < 0) return text to emptyList()
    return runCatching { scanBlocks(text) }.getOrElse { text to emptyList() }
}

private fun scanBlocks(text: String): Pair<String, List<DsmlToolCall>> {
    val calls = ArrayList<DsmlToolCall>()
    val kept = StringBuilder()
    var cursor = 0
    var i = 0
    while (i < text.length) {
        val lt = text.indexOf('<', i)
        if (lt < 0) break

        // 1) 完整包裹：<|DSML|tool_calls> …… </|DSML|tool_calls>
        val wrapperEnd = dsmlOpenEnd(text, lt, "tool_calls")
        if (wrapperEnd != null) {
            val close = dsmlCloseAt(text, wrapperEnd, "tool_calls")
            if (close == null) {
                i = lt + 1
                continue
            }
            val parsed = parseInvokes(text.substring(wrapperEnd, close.start))
            if (parsed.isNotEmpty()) {
                kept.append(text, cursor, lt)
                cursor = close.end
                calls += parsed
            }
            i = close.end
            continue
        }

        // 2) 裸 invoke（上游有时省掉外层 tool_calls）：<|DSML|invoke …>……</|DSML|invoke>
        val invokeEnd = dsmlOpenEnd(text, lt, "invoke")
        if (invokeEnd != null) {
            val close = dsmlCloseAt(text, invokeEnd, "invoke")
            if (close == null) {
                i = lt + 1
                continue
            }
            val parsed = parseInvokes(text.substring(lt, close.end))
            if (parsed.isNotEmpty()) {
                kept.append(text, cursor, lt)
                cursor = close.end
                calls += parsed
            }
            i = close.end
            continue
        }

        // 3) XML 变体：<tool_call>{json}</tool_call>、<function_call>…</function_call>
        val xml = xmlOpenAt(text, lt)
        if (xml != null) {
            val tag = xml.first
            val bodyStart = xml.second
            val close = "</" + tag + ">"
            val closeAt = indexOfIgnore(text, close, bodyStart)
            if (closeAt < 0) {
                i = lt + 1
                continue
            }
            val end = closeAt + close.length
            val parsed = callsFromJson(text.substring(bodyStart, closeAt))
            if (parsed.isNotEmpty()) {
                kept.append(text, cursor, lt)
                cursor = end
                calls += parsed
            }
            i = end
            continue
        }

        i = lt + 1
    }
    if (calls.isEmpty()) return text to emptyList()
    kept.append(text, cursor, text.length)
    return tidy(kept.toString()) to calls
}

private val XML_TAGS = listOf("tool_calls", "tool_call", "function_call")

/** <tool_call> / <tool_calls> / <function_call>：返回（标签名, 内容起点）。 */
private fun xmlOpenAt(s: String, from: Int): Pair<String, Int>? {
    for (tag in XML_TAGS) {
        val open = "<" + tag + ">"
        if (regionMatchesIgnore(s, from, open)) return tag to (from + open.length)
    }
    return null
}

/**
 * 流式闸门：正文 delta 逐块进来，标记块在还没「完整」的时候就要吞掉，
 * 否则用户会先看到半截 DSML 再看着它消失。
 *
 * 做法和 ThinkSplitter 一样：末尾留一截「可能是某个标记前缀」的字符串不发，
 * 等下一个 chunk 拼上再决定；一旦确认进了标记块，就一直吞到对应的闭合标签。
 */
internal class DsmlStreamFilter {
    private val pending = StringBuilder()
    private var suppressDsmlTag: String? = null
    private var suppressXmlClose: String? = null

    /** 喂一个 delta，返回这一块里可以立刻展示的正文（可能为空）。 */
    fun feed(chunk: String): String {
        pending.append(chunk)
        return drain(false)
    }

    /** 流结束：把押在 pending 里的尾巴处理掉（标记块丢弃，普通文字放出）。 */
    fun finish(): String = drain(true)

    private class Mark(val start: Int, val length: Int, val dsmlTag: String?, val xmlClose: String?)

    private fun drain(flush: Boolean): String {
        val out = StringBuilder()
        while (true) {
            val s = pending.toString()
            if (s.isEmpty()) return out.toString()

            val xmlClose = suppressXmlClose
            if (xmlClose != null) {
                val idx = indexOfIgnore(s, xmlClose, 0)
                if (idx >= 0) {
                    pending.delete(0, idx + xmlClose.length)
                    suppressXmlClose = null
                    continue
                }
                if (flush) {
                    pending.setLength(0)
                    suppressXmlClose = null
                    return out.toString()
                }
                val keep = closePrefixLen(s)
                pending.setLength(0)
                if (keep > 0) pending.append(s, s.length - keep, s.length)
                return out.toString()
            }

            val tag = suppressDsmlTag
            if (tag != null) {
                val close = dsmlCloseAt(s, 0, tag)
                if (close != null) {
                    pending.delete(0, close.end)
                    suppressDsmlTag = null
                    continue
                }
                if (flush) {
                    pending.setLength(0)
                    suppressDsmlTag = null
                    return out.toString()
                }
                // 还在块里：只留可能是闭合标签前缀的尾巴，其余全吞。
                val keep = closePrefixLen(s)
                pending.setLength(0)
                if (keep > 0) pending.append(s, s.length - keep, s.length)
                return out.toString()
            }

            val mark = firstOpen(s)
            if (mark == null) {
                val keep = if (flush) 0 else heldBack(s)
                out.append(s, 0, s.length - keep)
                pending.setLength(0)
                if (keep > 0) pending.append(s, s.length - keep, s.length)
                return out.toString()
            }
            out.append(s, 0, mark.start)
            pending.delete(0, mark.start + mark.length)
            suppressDsmlTag = mark.dsmlTag
            suppressXmlClose = mark.xmlClose
        }
    }

    private fun firstOpen(s: String): Mark? {
        var i = s.indexOf('<')
        while (i >= 0) {
            for (tag in DSML_OPENS) {
                val end = dsmlOpenEnd(s, i, tag)
                if (end != null) return Mark(i, end - i, tag, null)
            }
            val xml = xmlOpenAt(s, i)
            if (xml != null) {
                val close = "</" + xml.first + ">"
                return Mark(i, xml.second - i, null, close)
            }
            i = s.indexOf('<', i + 1)
        }
        return null
    }

    /** s 末尾有多少字符可能是某个开标记的前缀：先不发，等拼上再判断。 */
    private fun heldBack(s: String): Int {
        val lt = s.lastIndexOf('<')
        if (lt < 0) return 0
        val tail = s.substring(lt)
        if (tail.length > 48) return 0
        // 还没闭合的 DSML 开标记（别名 / 空格写法都算）：先押住，别漏到正文
        if (tail.indexOf('>') < 0 && tail.contains(DSML_MARK, ignoreCase = true)) return tail.length
        val maybe = DSML_OPENS.any { flexPrefix(tail, "<|" + DSML_MARK + "|" + it) } ||
            XML_TAGS.any { flexPrefix(tail, "<" + it + ">") }
        return if (maybe) tail.length else 0
    }

    /** 闭合标签可能还没写完：从最后一个 < 起押住。押错了下一轮会当块内文本丢掉，无害。 */
    private fun closePrefixLen(s: String): Int {
        val lt = s.lastIndexOf('<')
        if (lt < 0) return 0
        val keep = s.length - lt
        return if (keep <= 48) keep else 0
    }

    /** tail 是否可能是 open 的前缀（竖线段一次吃掉，所以 | 和 ｜｜ 视为同一个字符）。 */
    private fun flexPrefix(tail: String, open: String): Boolean {
        var i = 0
        var j = 0
        while (i < tail.length && j < open.length) {
            if (open[j] == '|') {
                val k = pipeRunEnd(tail, i)
                if (k == i) return false
                i = k
                j++
                continue
            }
            if (tail[i].isWhitespace()) { i++; continue }
            if (tail[i].lowercaseChar() != open[j].lowercaseChar()) return false
            i++
            j++
        }
        return i == tail.length
    }

    private companion object {
        val DSML_OPENS = listOf("tool_calls", "invoke", "parameter")
    }
}
