package dev.dsh.mobile.core.protocol.web

import dev.dsh.mobile.core.protocol.DshJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/** 引擎档位：免费引擎不需要密钥；需密钥引擎缺密钥时进 skipped，绝不伪装成失败或成功。 */
enum class SearchTier(val key: String, val label: String) {
    Free("free", "免费引擎（无需密钥）"),
    Keyed("key", "需密钥引擎"),
}

data class SearchSource(val title: String, val url: String, val snippet: String = "")

data class SearchAttempt(
    val engine: String,
    val tier: SearchTier,
    val ok: Boolean,
    val resultCount: Int,
    val latencyMs: Long,
    val error: String? = null,
)

data class SearchSkip(val engine: String, val tier: SearchTier, val reason: String)

data class SearchOutcome(
    val query: String,
    /** 真正给出结果的引擎。 */
    val engine: String,
    val tier: SearchTier,
    val sources: List<SearchSource>,
    val attempts: List<SearchAttempt>,
    val skipped: List<SearchSkip>,
) {
    val tierLabel: String get() = tier.label
}

interface SearchProvider {
    val id: String
    val tier: SearchTier

    /** 配置缺什么就返回一句原因（进 skipped，不算失败）；齐全返回 null。 */
    fun missingConfig(settings: WebSettings): String?

    suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
        timeoutMs: Long,
    ): List<SearchSource>
}

/** 免费：Bing 网页抓取（参考 plugin-websearch 的 bing 引擎，只保留结构与重试思路）。 */
class BingSearchProvider : SearchProvider {
    override val id: String = "bing"
    override val tier: SearchTier = SearchTier.Free

    override fun missingConfig(settings: WebSettings): String? = null

    override suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
        timeoutMs: Long,
    ): List<SearchSource> {
        val suffix = "?q=" + urlEncode(query) + "&mkt=zh-CN&adlt=off"
        var response = http.execute(
            HttpRequest("https://www.bing.com/search" + suffix, headers = browserHeaders(), timeoutMs = timeoutMs),
        )
        // 国内常见 302 → cn.bing.com；重定向没被跟随时补一次。
        if (response.code == 301 || response.code == 302) {
            response = http.execute(
                HttpRequest("https://cn.bing.com/search" + suffix, headers = browserHeaders(), timeoutMs = timeoutMs),
            )
        }
        if (response.code !in 200..299) {
            throw WebToolException(WebErrorCode.HttpStatus, "bing 返回 HTTP " + response.code)
        }
        return parseBing(String(response.body, Charsets.UTF_8), limit)
    }

    private fun parseBing(html: String, limit: Int): List<SearchSource> {
        val anchors = BING_ANCHOR.findAll(html).toList()
        val snippets = BING_SNIPPET.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
        val out = ArrayList<SearchSource>()
        anchors.forEachIndexed { index, match ->
            val href = match.groupValues[1]
            if (href.startsWith("http")) {
                out += SearchSource(
                    title = htmlToText(match.groupValues[2]),
                    url = href,
                    snippet = snippets.getOrElse(index) { "" },
                )
            }
        }
        return dedupeSources(out, limit)
    }

    private companion object {
        val BING_ANCHOR = Regex("""(?is)<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
        val BING_SNIPPET = Regex("""(?is)<p[^>]*>([\s\S]*?)</p>""")
    }
}

/** 免费：DuckDuckGo html 端点。 */
class DuckDuckGoSearchProvider : SearchProvider {
    override val id: String = "duckduckgo"
    override val tier: SearchTier = SearchTier.Free

    override fun missingConfig(settings: WebSettings): String? = null

    override suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
        timeoutMs: Long,
    ): List<SearchSource> {
        val url = "https://html.duckduckgo.com/html/?q=" + urlEncode(query)
        val response = http.execute(HttpRequest(url, headers = browserHeaders(), timeoutMs = timeoutMs))
        if (response.code !in 200..299) {
            throw WebToolException(WebErrorCode.HttpStatus, "duckduckgo 返回 HTTP " + response.code)
        }
        return parseDuckDuckGo(String(response.body, Charsets.UTF_8), limit)
    }

    private fun parseDuckDuckGo(html: String, limit: Int): List<SearchSource> {
        val snippets = DDG_SNIPPET.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
        val out = ArrayList<SearchSource>()
        DDG_ANCHOR.findAll(html).forEachIndexed { index, match ->
            val href = HREF.find(match.groupValues[1])?.groupValues?.get(1) ?: return@forEachIndexed
            val unwrapped = unwrapDuckDuckGo(href)
            if (unwrapped.startsWith("http")) {
                out += SearchSource(
                    title = htmlToText(match.groupValues[2]),
                    url = unwrapped,
                    snippet = snippets.getOrElse(index) { "" },
                )
            }
        }
        return dedupeSources(out, limit)
    }

    private fun unwrapDuckDuckGo(href: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1) ?: return href
        return try {
            URLDecoder.decode(uddg, "UTF-8")
        } catch (e: Exception) {
            href
        }
    }

    private companion object {
        val DDG_ANCHOR = Regex("""(?is)<a\b([^>]*class="result__a"[^>]*)>([\s\S]*?)</a>""")
        val DDG_SNIPPET = Regex("""(?is)<a\b[^>]*class="result__snippet"[^>]*>([\s\S]*?)</a>""")
        val HREF = Regex("""href="([^"]+)"""")
    }
}

/** 需密钥：自建 SearXNG 实例（实例地址算配置，缺了就跳过）。 */
class SearxngSearchProvider : SearchProvider {
    override val id: String = "searxng"
    override val tier: SearchTier = SearchTier.Keyed

    override fun missingConfig(settings: WebSettings): String? =
        if (settings.searxngInstance.isBlank()) "未配置 SearXNG 实例地址" else null

    override suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
        timeoutMs: Long,
    ): List<SearchSource> {
        val instance = settings.searxngInstance.trim().trimEnd('/')
        val url = instance + "/search?q=" + urlEncode(query) + "&format=json&safesearch=0"
        val response = http.execute(
            HttpRequest(url, headers = browserHeaders("application/json"), timeoutMs = timeoutMs),
        )
        if (response.code !in 200..299) {
            throw WebToolException(WebErrorCode.HttpStatus, "SearXNG 返回 HTTP " + response.code)
        }
        val root = try {
            DshJson.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw WebToolException(WebErrorCode.HttpStatus, "SearXNG 返回内容不是 JSON：" + e.message.orEmpty())
        }
        val results = root["results"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        val out = ArrayList<SearchSource>()
        results.forEach { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val link = item.text("url") ?: return@forEach
            out += SearchSource(item.text("title").orEmpty(), link, item.text("content").orEmpty())
        }
        return dedupeSources(out, limit)
    }
}

/** 需密钥：Exa 搜索 API。 */
class ExaSearchProvider : SearchProvider {
    override val id: String = "exa"
    override val tier: SearchTier = SearchTier.Keyed

    override fun missingConfig(settings: WebSettings): String? =
        if (settings.exaApiKey.isBlank()) "未配置 Exa API Key" else null

    override suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
        timeoutMs: Long,
    ): List<SearchSource> {
        val payload = buildJsonObject {
            put("query", query)
            put("numResults", limit)
        }.toString()
        val response = http.execute(
            HttpRequest(
                url = "https://api.exa.ai/search",
                method = "POST",
                headers = mapOf(
                    "content-type" to "application/json",
                    "accept" to "application/json",
                    "x-api-key" to settings.exaApiKey,
                ),
                body = payload.toByteArray(Charsets.UTF_8),
                timeoutMs = timeoutMs,
            ),
        )
        if (response.code !in 200..299) {
            throw WebToolException(WebErrorCode.HttpStatus, "Exa 返回 HTTP " + response.code)
        }
        val root = try {
            DshJson.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw WebToolException(WebErrorCode.HttpStatus, "Exa 返回内容不是 JSON：" + e.message.orEmpty())
        }
        val results = root["results"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        val out = ArrayList<SearchSource>()
        results.forEach { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val link = item.text("url") ?: return@forEach
            out += SearchSource(item.text("title").orEmpty(), link, item.text("text").orEmpty())
        }
        return dedupeSources(out, limit)
    }
}

/**
 * 引擎链：一次调用一个总预算，按「首选引擎 → 其余引擎」顺序回退。
 * 全部失败/无结果时抛 Err（不返回空成功）；缺密钥的引擎只进 skipped 并带原因。
 */
class SearchChain(private val providers: List<SearchProvider> = defaultProviders()) {

    fun order(settings: WebSettings): List<SearchProvider> {
        val want = settings.engine.trim().lowercase()
        if (want.isEmpty() || want == "auto") return providers
        return providers.filter { it.id == want } + providers.filter { it.id != want }
    }

    suspend fun search(
        query: String,
        limit: Int,
        http: HttpTransport,
        settings: WebSettings,
    ): SearchOutcome {
        val attempts = ArrayList<SearchAttempt>()
        val skipped = ArrayList<SearchSkip>()
        var sawTimeout = false
        val outcome = try {
            kotlinx.coroutines.withTimeoutOrNull(settings.searchBudgetMs) {
                for (provider in order(settings)) {
                    val missing = provider.missingConfig(settings)
                    if (missing != null) {
                        skipped += SearchSkip(provider.id, provider.tier, missing)
                        continue
                    }
                    val started = System.currentTimeMillis()
                    try {
                        val sources = provider.search(query, limit, http, settings, settings.searchBudgetMs)
                        val latency = System.currentTimeMillis() - started
                        if (sources.isEmpty()) {
                            attempts += SearchAttempt(provider.id, provider.tier, false, 0, latency, "无结果")
                            continue
                        }
                        attempts += SearchAttempt(provider.id, provider.tier, true, sources.size, latency)
                        return@withTimeoutOrNull SearchOutcome(
                            query = query,
                            engine = provider.id,
                            tier = provider.tier,
                            sources = sources,
                            attempts = attempts.toList(),
                            skipped = skipped.toList(),
                        )
                    } catch (e: CancellationException) {
                        sawTimeout = true
                        throw e
                    } catch (e: Throwable) {
                        val latency = System.currentTimeMillis() - started
                        attempts += SearchAttempt(provider.id, provider.tier, false, 0, latency, describeError(e))
                    }
                }
                null
            }
        } catch (e: CancellationException) {
            null
        }
        if (outcome != null) return outcome
        val detail = buildString {
            append("所有搜索引擎都没有可用结果（query=")
            append(query)
            append("）")
            if (attempts.isNotEmpty()) {
                append("；已尝试：")
                append(attempts.joinToString("；") { it.engine + "=" + (it.error ?: "失败") })
            }
            if (skipped.isNotEmpty()) {
                append("；已跳过：")
                append(skipped.joinToString("；") { it.engine + "（" + it.reason + "）" })
            }
        }
        val code = when {
            attempts.isEmpty() && skipped.isNotEmpty() -> WebErrorCode.MissingKey
            sawTimeout -> WebErrorCode.Timeout
            else -> WebErrorCode.HttpStatus
        }
        throw WebToolException(code, detail)
    }

    companion object {
        fun defaultProviders(): List<SearchProvider> = listOf(
            BingSearchProvider(),
            DuckDuckGoSearchProvider(),
            SearxngSearchProvider(),
            ExaSearchProvider(),
        )
    }
}

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun describeError(e: Throwable): String {
    val text = when (e) {
        is WebToolException -> e.code.name + ": " + e.message.orEmpty()
        else -> e.javaClass.simpleName + ": " + e.message.orEmpty()
    }
    return if (text.length > 200) text.substring(0, 200) else text
}

private fun JsonObject.text(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }

private fun dedupeSources(sources: List<SearchSource>, limit: Int): List<SearchSource> {
    val seen = HashSet<String>()
    val out = ArrayList<SearchSource>()
    for (source in sources) {
        if (!seen.add(source.url)) continue
        out += source
        if (out.size >= limit) break
    }
    return out
}
