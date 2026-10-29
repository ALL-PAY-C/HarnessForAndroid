package dev.dsh.mobile.core.protocol.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 网络工具的全部可调项。由宿主（core/data 的设置 + 密钥）组装后注入，
 * core/protocol 不反向依赖 core/data。
 */
data class WebSettings(
    /** auto = 免费引擎优先并自动回退；也可指定 bing / duckduckgo / searxng / exa。 */
    val engine: String = "auto",
    val exaApiKey: String = "",
    val searxngInstance: String = "",
    /** 整次搜索的总预算（不是单个引擎的）。 */
    val searchBudgetMs: Long = 12_000L,
    val fetchBudgetMs: Long = 15_000L,
    val downloadBudgetMs: Long = 20_000L,
    val maxFetchBytes: Long = 2L * 1024 * 1024,
    val maxFetchChars: Int = 20_000,
    val maxDownloadBytes: Long = 64L * 1024 * 1024,
)

/**
 * web_search / web_fetch / download 的实现与注册面。
 *
 * 网络层可注入（[http]），JVM 测试用 fake 或本地 fake server，不碰公网。
 * 失败一律抛 [WebToolException]，由 ToolExecutor 转成 ok=false——不包装成成功。
 */
class WebToolkit(
    private val http: HttpTransport = OkHttpTransport(),
    /** 用户工作区根目录；为空时 download 直接返回 NotConfigured，不落到别的目录。 */
    private val workspace: File? = null,
    private val settings: suspend () -> WebSettings = { WebSettings() },
    private val chain: SearchChain = SearchChain(),
    private val fetcher: WebFetcher = WebFetcher(),
) {

    fun toolHandlers(progress: ((Long, Long) -> Unit)? = null): Map<String, suspend (JsonObject) -> String> {
        val searchHandler: suspend (JsonObject) -> String = { args -> search(args) }
        val fetchHandler: suspend (JsonObject) -> String = { args -> fetch(args) }
        val downloadHandler: suspend (JsonObject) -> String = { args -> download(args, progress) }
        return mapOf(TOOL_SEARCH to searchHandler, TOOL_FETCH to fetchHandler, TOOL_DOWNLOAD to downloadHandler)
    }

    suspend fun search(args: JsonObject): String {
        val query = args.stringArg("query")?.takeIf { it.isNotBlank() }
            ?: throw WebToolException(WebErrorCode.BadRequest, "参数缺失：query")
        val limit = args.longArg("limit")?.coerceIn(1L, 20L)?.toInt() ?: 8
        val outcome = chain.search(query, limit, http, settings())
        return outcome.toJson().toString()
    }

    suspend fun fetch(args: JsonObject): String {
        val url = args.stringArg("url")?.takeIf { it.isNotBlank() }
            ?: throw WebToolException(WebErrorCode.BadRequest, "参数缺失：url")
        val outcome = fetcher.fetch(url, http, settings())
        return outcome.toJson().toString()
    }

    suspend fun download(args: JsonObject, progress: ((Long, Long) -> Unit)? = null): String {
        val url = args.stringArg("url")?.takeIf { it.isNotBlank() }
            ?: throw WebToolException(WebErrorCode.BadRequest, "参数缺失：url")
        val root = workspace ?: throw WebToolException(
            WebErrorCode.NotConfigured,
            "download 未接入用户工作区：构造 LocalEngineTransport 时需要传入 workspace",
        )
        val current = settings()
        val request = DownloadRequest(
            url = url,
            dir = args.stringArg("dir").orEmpty(),
            fileName = args.stringArg("name") ?: args.stringArg("fileName"),
            maxBytes = args.longArg("maxBytes")?.takeIf { it > 0L } ?: current.maxDownloadBytes,
            timeoutMs = current.downloadBudgetMs,
            overwrite = args.boolArg("overwrite") ?: false,
        )
        val outcome = FileDownloader(WorkspaceSandbox(root)).download(request, http, progress)
        return outcome.toJson().toString()
    }

    /** 供 LocalEngineTransport 的 tools 数组使用。 */
    fun functionSpecs(): List<JsonObject> = listOf(searchSpec(), fetchSpec(), downloadSpec())

    /** 供系统提示词使用：明确 search 与 fetch 的分工、以及免费/需密钥的差别。 */
    fun helpText(): String = listOf(
        "网络工具（与 shell 并行，失败会明确报错，不会返回假成功）：",
        "- web_search(query, limit?)：搜索网页。免费引擎 bing、duckduckgo 无需密钥；" +
            "需密钥引擎 searxng、exa 缺密钥时出现在 skipped 里并给出原因，不算失败。",
        "- web_fetch(url)：读取单个 http/https 页面的正文，返回 title/text/bytes/finalUrl。" +
            "非 2xx、超时、超过大小上限都会失败。",
        "- download(url, name?, dir?)：把文件下载进用户工作区（路径不能逃出工作区），" +
            "返回 path/bytes/sha256；断网或超限会清理半成品并失败。",
    ).joinToString("\n")

    private fun searchSpec(): JsonObject = functionSpec(
        name = TOOL_SEARCH,
        description = "搜索网页，返回结果列表。免费引擎（bing/duckduckgo）与需密钥引擎（searxng/exa）在结果的 tier 里分开标注。",
        properties = mapOf(
            "query" to stringProp("搜索关键词"),
            "limit" to numberProp("返回条数，1-20，默认 8"),
        ),
        required = listOf("query"),
    )

    private fun fetchSpec(): JsonObject = functionSpec(
        name = TOOL_FETCH,
        description = "读取一个 http/https 页面的正文（不是搜索）。返回 title/text/bytes；非 2xx、超时、超限都会失败。",
        properties = mapOf("url" to stringProp("要读取的完整 URL")),
        required = listOf("url"),
    )

    private fun downloadSpec(): JsonObject = functionSpec(
        name = TOOL_DOWNLOAD,
        description = "下载文件到用户工作区，返回相对路径、字节数与 SHA-256。文件名冲突自动加 -1；超限或断网会清理半成品并失败。",
        properties = mapOf(
            "url" to stringProp("要下载的完整 URL"),
            "name" to stringProp("保存的文件名（可省略，默认取 URL 末段或 Content-Disposition）"),
            "dir" to stringProp("工作区内的相对目录（可省略，默认工作区根）"),
            "maxBytes" to numberProp("本次下载的字节上限（可省略）"),
            "overwrite" to booleanProp("同名时是否覆盖，默认 false（自动改名）"),
        ),
        required = listOf("url"),
    )

    private fun functionSpec(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String>,
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject { properties.forEach { (key, value) -> put(key, value) } },
                        )
                        put(
                            "required",
                            buildJsonArray { required.forEach { add(JsonPrimitive(it)) } },
                        )
                    },
                )
            },
        )
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun numberProp(description: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun booleanProp(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    companion object {
        const val TOOL_SEARCH = "web_search"
        const val TOOL_FETCH = "web_fetch"
        const val TOOL_DOWNLOAD = "download"

    }
}

internal fun SearchOutcome.toJson(): JsonObject = buildJsonObject {
    put("ok", true)
    put("kind", "web_search")
    put("query", query)
    put("engine", engine)
    put("tier", tier.key)
    put("tierLabel", tierLabel)
    put("count", sources.size)
    put(
        "sources",
        buildJsonArray {
            sources.forEach { source ->
                add(
                    buildJsonObject {
                        put("title", source.title)
                        put("url", source.url)
                        put("snippet", source.snippet)
                    },
                )
            }
        },
    )
    put(
        "attempts",
        buildJsonArray {
            attempts.forEach { attempt ->
                add(
                    buildJsonObject {
                        put("engine", attempt.engine)
                        put("tier", attempt.tier.key)
                        put("ok", attempt.ok)
                        put("resultCount", attempt.resultCount)
                        put("latencyMs", attempt.latencyMs)
                        if (attempt.error != null) put("error", attempt.error)
                    },
                )
            }
        },
    )
    put(
        "skipped",
        buildJsonArray {
            skipped.forEach { skip ->
                add(
                    buildJsonObject {
                        put("engine", skip.engine)
                        put("tier", skip.tier.key)
                        put("reason", skip.reason)
                    },
                )
            }
        },
    )
}

internal fun FetchOutcome.toJson(): JsonObject = buildJsonObject {
    put("ok", true)
    put("kind", "web_fetch")
    put("url", url)
    put("finalUrl", finalUrl)
    put("status", status)
    put("contentType", contentType)
    put("title", title)
    put("bytes", bytes)
    put("truncated", truncated)
    put("text", text)
}

internal fun DownloadOutcome.toJson(): JsonObject = buildJsonObject {
    put("ok", true)
    put("kind", "download")
    put("path", path)
    put("fileName", fileName)
    put("bytes", bytes)
    put("sha256", sha256)
    put("url", url)
    put("finalUrl", finalUrl)
    put("contentType", contentType)
    put("elapsedMs", elapsedMs)
    put("renamed", renamed)
}

internal fun JsonObject.stringArg(key: String): String? =
    this[key]?.let { runCatching { it as JsonPrimitive }.getOrNull() }?.let { runCatching { it.content }.getOrNull() }

internal fun JsonObject.longArg(key: String): Long? = stringArg(key)?.toLongOrNull()

internal fun JsonObject.boolArg(key: String): Boolean? = when (val raw = stringArg(key)?.lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}
