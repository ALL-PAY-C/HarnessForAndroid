package dev.dsh.mobile.core.protocol.web

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class FetchOutcome(
    val url: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String,
    val title: String,
    /** 已去标签的正文；非 HTML 内容是原文。 */
    val text: String,
    val bytes: Long,
    val truncated: Boolean,
)

/**
 * 网页读取。与 web_search 分开：search 只给结果列表，fetch 才读正文。
 * 失败一律抛 [WebToolException]（非 2xx / 超时 / 超限 / 断网）。
 */
class WebFetcher {

    suspend fun fetch(url: String, http: HttpTransport, settings: WebSettings): FetchOutcome {
        val target = validateUrl(url)
        val cap = settings.maxFetchBytes
        val buffer = ByteArrayOutputStream()
        val sink = HttpSink { chunk, length ->
            if (buffer.size().toLong() + length > cap) {
                throw WebToolException(WebErrorCode.TooLarge, "响应超过上限 " + cap + " 字节：" + target)
            }
            buffer.write(chunk, 0, length)
        }
        val response = withBudgetOrTimeout(
            settings.fetchBudgetMs,
            "读取超时（" + settings.fetchBudgetMs + "ms）：" + target,
        ) {
            http.execute(
                HttpRequest(
                    url = target,
                    headers = browserHeaders(),
                    timeoutMs = settings.fetchBudgetMs,
                    sink = sink,
                ),
            )
        }
        if (response.code !in 200..299) {
            throw WebToolException(WebErrorCode.HttpStatus, "HTTP " + response.code + "：" + target)
        }
        val bytes = buffer.toByteArray()
        val raw = String(bytes, charsetOf(response.contentType))
        val isHtml = response.contentType.contains("html", ignoreCase = true) || raw.trimStart().startsWith("<")
        val title = if (isHtml) extractTitle(raw) else ""
        val full = if (isHtml) htmlToText(raw) else raw
        val truncated = full.length > settings.maxFetchChars
        return FetchOutcome(
            url = target,
            finalUrl = response.finalUrl,
            status = response.code,
            contentType = response.contentType,
            title = title,
            text = if (truncated) full.substring(0, settings.maxFetchChars) else full,
            bytes = if (response.receivedBytes > 0) response.receivedBytes else bytes.size.toLong(),
            truncated = truncated,
        )
    }

    private fun charsetOf(contentType: String): Charset {
        val found = CHARSET.find(contentType) ?: return Charsets.UTF_8
        return try {
            Charset.forName(found.groupValues[1])
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private companion object {
        val CHARSET = Regex("""(?i)charset=([A-Za-z0-9_\-]+)""")
    }
}

private val SCRIPT_STYLE = Regex("""(?is)<(script|style|noscript|template)\b[^>]*>[\s\S]*?</\1>""")
private val HTML_COMMENT = Regex("""(?s)<!--[\s\S]*?-->""")
private val BLOCK_TAG = Regex("""(?i)<(?:br|/p|/div|/li|/tr|/h[1-6]|/section|/article)\b[^>]*>""")
private val ANY_TAG = Regex("""<[^>]*>""")
private val BLANK_RUN = Regex("""[ \t\u00A0]+""")
private val HEX_ENTITY = Regex("""&#[xX]([0-9A-Fa-f]+);""")
private val DEC_ENTITY = Regex("""&#(\d+);""")
private val NAMED_ENTITY = Regex("""&(amp|lt|gt|quot|apos|nbsp|hellip|mdash|ndash|ldquo|rdquo|middot);""")
private val TITLE_TAG = Regex("""(?is)<title[^>]*>([\s\S]*?)</title>""")

/** 极简 HTML → 文本：去脚本样式、块级标签换行、折叠空白。够读正文，不做 DOM 解析。 */
internal fun htmlToText(html: String): String {
    var s = SCRIPT_STYLE.replace(html, " ")
    s = HTML_COMMENT.replace(s, " ")
    s = BLOCK_TAG.replace(s, "\n")
    s = ANY_TAG.replace(s, " ")
    s = unescapeHtml(s)
    return s.split('\n')
        .map { it.replace(BLANK_RUN, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

internal fun extractTitle(html: String): String {
    val found = TITLE_TAG.find(html) ?: return ""
    return unescapeHtml(ANY_TAG.replace(found.groupValues[1], " ")).replace(BLANK_RUN, " ").trim()
}

internal fun unescapeHtml(input: String): String {
    if (!input.contains('&')) return input
    var s = HEX_ENTITY.replace(input) { m -> codePoint(m.groupValues[1].toIntOrNull(16)) }
    s = DEC_ENTITY.replace(s) { m -> codePoint(m.groupValues[1].toIntOrNull()) }
    s = NAMED_ENTITY.replace(s) { m ->
        when (m.groupValues[1].lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "hellip" -> "…"
            "mdash" -> "—"
            "ndash" -> "–"
            "ldquo" -> "“"
            "rdquo" -> "”"
            "middot" -> "·"
            else -> m.value
        }
    }
    return s
}

private fun codePoint(value: Int?): String {
    if (value == null || value <= 0 || value > 0x10FFFF) return ""
    return String(Character.toChars(value))
}
