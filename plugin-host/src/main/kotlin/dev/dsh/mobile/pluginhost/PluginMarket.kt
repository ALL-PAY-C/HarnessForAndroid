package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A plugin the user can add.
 *
 * [kind] separates the two very different install paths: `App` plugins are
 * Kotlin modules compiled into the APK, `Dsh` plugins are cordis bundles that
 * live on the DSH host and are installed there. The market shows both and never
 * pretends it can download code into a running app.
 */
data class MarketEntry(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val kind: Kind = Kind.Dsh,
    val installHint: String = "",
    val homepage: String = "",
    /** Direct zip URL for installable packages; empty means "not installable here". */
    val packageUrl: String = "",
    /** 上游仓库，供「直达项目」用。 */
    val repo: String = "",
    /**
     * npm 包名。非空时安装走 registry（覆盖率最高的一条路：
     * market-v2 索引里 322/521 个可安装条目是 npm 形态）。
     */
    val npm: String = "",
    /** 市场里的下载量与 star 数（没有就是 0）。 */
    val downloads: Int = 0,
    val stars: Int = 0,
    val author: String = "",
    /** 归并后的标签，多源取并集。 */
    val tags: List<String> = emptyList(),
    /** 这个条目出现在哪些源里（[MarketSource.id]）。 */
    val sources: List<String> = emptyList(),
) {
    enum class Kind { App, Dsh, Package }
}

/**
 * 一个市场源。kind 只影响展示与排序权重。
 *
 * [search] 非空时这个源走「动态搜索」而不是静态 JSON：目前只有 GitHub 代码搜索这一种，
 * 好处是社区新插件一发布就能搜到，不用等谁去维护索引（静态索引基本都挂了）。
 */
data class MarketSource(
    val id: String,
    val name: String,
    val url: String,
    val kind: SourceKind = SourceKind.Community,
    val search: String? = null,
    val format: Format = Format.Json,
) {
    enum class SourceKind { Official, Community, Mirror }

    /**
     * 源的数据形态。以前只有「静态 JSON」和「GitHub 搜索」两种，靠 search 是否为空
     * 猜；加了 npm 搜索和 market-v2 之后猜不准了，所以显式声明。
     */
    enum class Format { Json, GithubSearch, NpmSearch, MarketV2 }
}

/** 单个源的拉取结果。失败不抛，只记在 [error] 里。 */
data class SourceStatus(
    val id: String,
    val name: String,
    val ok: Boolean,
    val count: Int = 0,
    val error: String? = null,
)

/**
 * 一次多源拉取的合并结果。
 *
 * [rawCount] 是去重前的条目总数，界面用它算「去重 K 个」。
 */
data class MarketSnapshot(
    val plugins: List<MarketEntry> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val rawCount: Int = 0,
) {
    val okSources: Int get() = sources.count { it.ok }

    /** 「N 个源 · M 个插件（去重 K 个）」。 */
    fun summary(): String =
        "${sources.size} 个源 · ${plugins.size} 个插件（去重 ${(rawCount - plugins.size).coerceAtLeast(0)} 个）"
}

object PluginMarket {

    /** Registry the market tries first; anything JSON-shaped works. */
    const val DEFAULT_REGISTRY = "https://dshmarket.com/registry.json"

    /**
     * 单源总预算。多源并发，最坏 8 秒整体返回，不阻塞界面。
     *
     * 拆成「连接 3s / 读 5s」两段：网络受限时卡在 TCP 握手的那一源 3 秒就放弃，
     * 能连上但很慢的源允许读 5 秒 —— 以前一个 10 秒的 readTimeout 会让整个
     * 列表等到超时才出结果。
     */
    const val SOURCE_TIMEOUT_MS = 8_000L

    /** 连接超时。被墙/无路由时 TCP 握手会一直挂着，3 秒够用。 */
    const val CONNECT_TIMEOUT_MS = 3_000

    /** 读超时。索引体积几百 KB，慢速网络下 5 秒足够读完。 */
    const val READ_TIMEOUT_MS = 5_000

    /**
     * 内置源。官方源在前；社区/GitHub pages/镜像都按「JSON 数组或 {items:[...]}」
     * 解析，任何一个挂了都静默降级成空列表，不影响其他源和内置目录。
     */
    val sources: List<MarketSource> = listOf(
        // market-v2 是目前唯一还在更新的社区索引（600 条、带 packages.targets
        // 里可直接安装的 npm/url 目标）。放最前面，因为它是真的能装上东西的那一个。
        MarketSource(
            id = "awesome",
            name = "awesome-dsh-plugin 索引",
            url = "https://raw.githubusercontent.com/bruc3van/awesome-dsh-plugin/main/data/market-v2.json",
            kind = MarketSource.SourceKind.Community,
            format = MarketSource.Format.MarketV2,
        ),
        MarketSource(
            id = "awesome-jsdelivr",
            name = "awesome-dsh-plugin · jsDelivr 镜像",
            url = "https://cdn.jsdelivr.net/npm/awesome-dsh-plugin-feed@latest/data/market-v2.json",
            kind = MarketSource.SourceKind.Mirror,
            format = MarketSource.Format.MarketV2,
        ),
        // npm 是全社区最大的插件仓库：搜索一次就能拿到包名，安装直接走 registry。
        MarketSource(
            id = "npm-search",
            name = "npm 搜索 · dsh-plugin",
            url = "https://registry.npmjs.org/-/v1/search",
            kind = MarketSource.SourceKind.Community,
            search = "dsh-plugin",
            format = MarketSource.Format.NpmSearch,
        ),
        MarketSource(
            id = "npm-search-harness",
            name = "npm 搜索 · deepseek harness",
            url = "https://registry.npmjs.org/-/v1/search",
            kind = MarketSource.SourceKind.Community,
            search = "deepseek harness plugin",
            format = MarketSource.Format.NpmSearch,
        ),
        MarketSource(
            id = "npm-search-cordis",
            name = "npm 搜索 · cordis plugin",
            url = "https://registry.npmjs.org/-/v1/search",
            kind = MarketSource.SourceKind.Community,
            search = "cordis plugin",
            format = MarketSource.Format.NpmSearch,
        ),
        MarketSource(
            id = "dshmarket",
            name = "DSH Market 官方",
            url = DEFAULT_REGISTRY,
            kind = MarketSource.SourceKind.Official,
        ),
        MarketSource(
            id = "github",
            name = "GitHub · dsh-plugins 社区索引",
            url = "https://raw.githubusercontent.com/dsh-plugins/index/main/plugins.json",
        ),
        MarketSource(
            id = "pages",
            name = "dsh-plugin-hub · Pages 索引",
            url = "https://dsh-plugin-hub.github.io/registry/index.json",
        ),
        MarketSource(
            id = "mirror",
            name = "jsDelivr 镜像",
            url = "https://cdn.jsdelivr.net/gh/dsh-plugins/index@main/plugins.json",
            kind = MarketSource.SourceKind.Mirror,
        ),
        // 静态索引常年失修，动态搜索补上「新发布的插件」。三条查询各管一类作者写法。
        MarketSource(
            id = "gh-search-dsh",
            name = "GitHub 搜索 · dsh plugin",
            url = "https://api.github.com/search/repositories",
            kind = MarketSource.SourceKind.Community,
            search = "dsh+plugin+in:name,description,readme",
        ),
        MarketSource(
            id = "gh-search-topic",
            name = "GitHub 搜索 · topic:dsh-plugin",
            url = "https://api.github.com/search/repositories",
            kind = MarketSource.SourceKind.Community,
            search = "topic:dsh-plugin",
        ),
        MarketSource(
            id = "gh-search-harness",
            name = "GitHub 搜索 · deepseek harness",
            url = "https://api.github.com/search/repositories",
            kind = MarketSource.SourceKind.Community,
            search = "deepseek+harness+plugin",
        ),
    )

    /** Case-insensitive match over name / id / description / tags. */
    fun search(entries: List<MarketEntry>, query: String): List<MarketEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.contains(q, true) ||
                it.id.contains(q, true) ||
                it.description.contains(q, true) ||
                it.tags.any { tag -> tag.contains(q, true) }
        }
    }

    /** Shipped catalogue; a remote index can extend it. */
    val bundled: List<MarketEntry> = listOf(
        MarketEntry(
            id = "dsh.skillmanager",
            name = "SkillManager",
            version = "1.0.0",
            description = "查看、启停 DSH 上的 skill 包，入口挂在左侧边栏",
            kind = MarketEntry.Kind.Dsh,
            installHint = "dsh plugin add @deepseek-ai/dsh-skill-manager",
            tags = listOf("skill", "管理"),
        ),
        MarketEntry(
            id = "dsh.qq-ui",
            name = "DSH QQ 皮肤",
            version = "1.0.0",
            description = "给 DSH Web UI 换上 QQ 风格外观",
            kind = MarketEntry.Kind.Dsh,
            installHint = "dsh plugin add dsh-qq-ui",
            tags = listOf("主题", "皮肤"),
        ),
        MarketEntry(
            id = "dsh.sample",
            name = "示例插件",
            version = "1.0.0",
            description = "演示消息渲染器、快捷指令、输入框按钮、设置项",
            kind = MarketEntry.Kind.App,
            tags = listOf("内置", "示例"),
        ),
    )

    /**
     * Registry shipped inside the APK.
     *
     * `dshmarket.com` is a static site with no JSON API, so the catalogue is
     * generated from it at build time and bundled. It stays useful offline and
     * never breaks when the site changes; [fetch] can still extend it live.
     */
    fun loadBundled(context: android.content.Context): List<MarketEntry> = runCatching {
        val text = context.assets.open("registry.json").bufferedReader().use { it.readText() }
        parse(text)
    }.getOrDefault(emptyList())

    /**
     * 多源并发拉取 + 归并去重（需求 5）。
     *
     * 每源独立 try-catch + 超时：一个源挂掉只体现在 [MarketSnapshot.sources]
     * 里，其他源照常合并；全部挂掉返回空列表，由调用方回落到内置目录。
     */
    /**
     * 增量版 [fetchAll]：哪个源先回来就先通过 [onProgress] 吐一版合并结果。
     *
     * 12 个源里 4 个静态索引要等到 8s 超时，全部 awaitAll 会让界面空等 30s 以上。
     * 这里每完成一个源就回调一次，界面拿到的 snapshot 逐次变大，用户先看到结果
     * 再看到「还在补源」，而不是对着「拉取中…」发呆。
     */
    suspend fun fetchAllStreaming(
        list: List<MarketSource> = sources,
        timeoutMs: Long = SOURCE_TIMEOUT_MS,
        onProgress: (MarketSnapshot) -> Unit,
    ): MarketSnapshot = coroutineScope {
        val collected = java.util.Collections.synchronizedList(ArrayList<Pair<SourceStatus, List<MarketEntry>>>())
        val jobs = list.map { source ->
            async(Dispatchers.IO) {
                val outcome = runCatching { withTimeout(timeoutMs) { fetchSource(source) } }
                val entries = outcome.getOrDefault(emptyList())
                val status = SourceStatus(
                    id = source.id,
                    name = source.name,
                    ok = outcome.isSuccess,
                    count = entries.size,
                    error = outcome.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                )
                collected.add(status to entries)
                val snapshotNow = collected.toList()
                val all = snapshotNow.flatMap { it.second }
                runCatching {
                    onProgress(
                        MarketSnapshot(
                            plugins = merge(all),
                            sources = snapshotNow.map { it.first },
                            rawCount = all.size,
                        ),
                    )
                }
                status to entries
            }
        }
        val results = jobs.awaitAll()
        val all = results.flatMap { it.second }
        MarketSnapshot(
            plugins = merge(all),
            sources = results.map { it.first },
            rawCount = all.size,
        )
    }

    suspend fun fetchAll(
        list: List<MarketSource> = sources,
        timeoutMs: Long = SOURCE_TIMEOUT_MS,
    ): MarketSnapshot = coroutineScope {
        val results = list
            .map { source ->
                async {
                    val outcome = runCatching { withTimeout(timeoutMs) { fetchSource(source) } }
                    val entries = outcome.getOrDefault(emptyList())
                    val status = SourceStatus(
                        id = source.id,
                        name = source.name,
                        ok = outcome.isSuccess,
                        count = entries.size,
                        error = outcome.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                    )
                    status to entries
                }
            }
            .awaitAll()

        val all = results.flatMap { it.second }
        MarketSnapshot(
            plugins = merge(all),
            sources = results.map { it.first },
            rawCount = all.size,
        )
    }

    /**
     * 归并去重：按 id 合并，version 取最高（semver 比较，解析失败退回字符串比较），
     * tags / sources 取并集，downloads / stars 取最大。
     */
    fun merge(entries: List<MarketEntry>): List<MarketEntry> {
        val byId = LinkedHashMap<String, MarketEntry>()
        entries.forEach { e ->
            if (e.id.isBlank()) return@forEach
            val old = byId[e.id]
            byId[e.id] = if (old == null) e else old.mergedWith(e)
        }
        return byId.values.toList()
    }

    private fun MarketEntry.mergedWith(other: MarketEntry): MarketEntry {
        val newer = if (compareVersion(other.version, version) > 0) other else this
        val older = if (newer === other) this else other
        return newer.copy(
            name = newer.name.ifBlank { older.name },
            description = newer.description.ifBlank { older.description },
            author = newer.author.ifBlank { older.author },
            installHint = newer.installHint.ifBlank { older.installHint },
            homepage = newer.homepage.ifBlank { older.homepage },
            packageUrl = newer.packageUrl.ifBlank { older.packageUrl },
            repo = newer.repo.ifBlank { older.repo },
            npm = newer.npm.ifBlank { older.npm },
            downloads = maxOf(downloads, other.downloads),
            stars = maxOf(stars, other.stars),
            tags = (tags + other.tags).distinct(),
            sources = (sources + other.sources).distinct(),
        )
    }

    /** semver 数值比较；任一侧解析不出数字就退回字符串比较（保证全序、不抛）。 */
    private fun compareVersion(a: String, b: String): Int {
        fun parts(v: String) = v.trim()
            .removePrefix("v")
            .split('.', '-', '+')
            .mapNotNull { it.toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        if (pa.isEmpty() || pb.isEmpty()) return a.compareTo(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return a.compareTo(b)
    }

    /** 拉一个源，字段名按 id/pluginId、name/title 之类做兼容。 */
    suspend fun fetchSource(source: MarketSource): List<MarketEntry> = withContext(Dispatchers.IO) {
        require(source.url.isNotBlank()) { "市场地址为空" }
        val query = source.search
        val full = when {
            query.isNullOrBlank() -> source.url
            source.format == MarketSource.Format.NpmSearch ->
                source.url + "?text=" + query.replace(" ", "+") + "&size=100"
            else -> source.url + "?q=" + query + "&sort=stars&order=desc&per_page=60"
        }
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json, application/json")
            // GitHub 未带 UA 会直接 403。
            setRequestProperty("User-Agent", "dsh-mobile")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${text.take(120)}")
            val parsed = when (source.format) {
                MarketSource.Format.NpmSearch -> parseNpmSearch(text)
                MarketSource.Format.MarketV2 -> parseMarketV2(text)
                MarketSource.Format.GithubSearch -> parseGithubSearch(text)
                MarketSource.Format.Json -> parse(text)
            }
            parsed.map { it.copy(sources = listOf(source.id)) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GitHub 搜索响应 -> 市场条目。
     *
     * `full_name` 当 id（稳定且唯一）；`html_url` 当 repo（安装时会走 GitHub 仓库那条路）；
     * 版本号 GitHub 不提供，留空，由 merge 时另一源的版本覆盖。
     */
    /**
     * npm 搜索响应 -> 市场条目。
     *
     * `registry.npmjs.org/-/v1/search` 返回 `{objects:[{package:{name,version,description,
     * links:{repository,homepage},publisher:{username},keywords:[]}}],total}`。
     * 包名直接当 npm 字段，安装走 [PluginInstaller.installNpm]，不需要再猜仓库。
     */
    private fun parseNpmSearch(text: String): List<MarketEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val objects = root.optJSONArray("objects") ?: return emptyList()
        return (0 until objects.length()).mapNotNull { i ->
            runCatching {
                val pkg = objects.optJSONObject(i)?.optJSONObject("package") ?: return@runCatching null
                val name = pkg.optString("name").ifBlank { return@runCatching null }
                val keywords = pkg.optJSONArray("keywords")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.orEmpty()
                MarketEntry(
                    id = "npm:" + name,
                    name = name.substringAfterLast('/'),
                    version = pkg.optString("version"),
                    description = pkg.optString("description"),
                    kind = MarketEntry.Kind.Package,
                    homepage = pkg.optJSONObject("links")?.optString("homepage").orEmpty(),
                    repo = pkg.optJSONObject("links")?.optString("repository").orEmpty(),
                    npm = name,
                    downloads = root.optInt("total", 0).takeIf { i0 -> i0 > 100000 }?.div(1000) ?: 0,
                    stars = 0,
                    author = pkg.optJSONObject("publisher")?.optString("username").orEmpty(),
                    tags = keywords.take(6),
                )
            }.getOrNull()
        }
    }

    /**
     * market-v2 索引 -> 市场条目。
     *
     * 结构：`{entries:[{full_name,description,stargazers_count,packages:{mode,
     * targets:[{profile,install,source,command,note}]}}]}`。
     * `source` 形如 `npm:@scope/name` / `github:owner/repo` / `url:https://…`，
     * 前缀决定安装走哪条路；没有 targets 的条目（mode=none/manual）照样列出来，
     * 但只给「项目主页」，不再让用户点一个必然失败的「下载安装」。
     */
    private fun parseMarketV2(text: String): List<MarketEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val entries = root.optJSONArray("entries")
            ?: root.optJSONArray("plugins")
            ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            runCatching {
                val o = entries.optJSONObject(i) ?: return@runCatching null
                val full = o.optString("full_name").ifBlank { o.optString("repo").removePrefix("https://github.com/") }
                if (full.isBlank()) return@runCatching null
                val packages = o.optJSONObject("packages")
                val targets = packages?.optJSONArray("targets")
                var npm = ""
                var pkgUrl = ""
                var repo = "https://github.com/" + full
                var installHint = ""
                if (targets != null) {
                    for (t in 0 until targets.length()) {
                        val target = targets.optJSONObject(t) ?: continue
                        val source = target.optString("source")
                        val install = target.optString("install")
                        when {
                            source.startsWith("npm:", true) -> if (npm.isBlank()) {
                                npm = install.ifBlank { source.removePrefix("npm:") }
                            }
                            source.startsWith("url:", true) -> if (pkgUrl.isBlank()) {
                                pkgUrl = source.removePrefix("url:")
                            }
                            source.startsWith("github:", true) -> if (repo.isBlank()) {
                                repo = "https://github.com/" + source.removePrefix("github:")
                            }
                        }
                        if (installHint.isBlank()) installHint = target.optString("command")
                    }
                }
                val category = o.optString("category_zh").ifBlank { o.optString("category") }
                MarketEntry(
                    id = "gh:" + full,
                    name = full.substringAfterLast('/'),
                    version = "",
                    description = o.optString("description").ifBlank { packages?.optString("note").orEmpty() },
                    kind = if (npm.isNotBlank() || pkgUrl.isNotBlank()) MarketEntry.Kind.Package else MarketEntry.Kind.Dsh,
                    installHint = installHint,
                    homepage = repo,
                    packageUrl = pkgUrl,
                    repo = repo,
                    npm = npm,
                    stars = o.optInt("stargazers_count", 0),
                    author = full.substringBefore('/'),
                    tags = listOfNotNull(category.takeIf { it.isNotBlank() }),
                )
            }.getOrNull()
        }
    }

    private fun parseGithubSearch(text: String): List<MarketEntry> {
        val array = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.optJSONObject(i) ?: return@runCatching null
                val full = o.optString("full_name", "")
                if (full.isBlank()) return@runCatching null
                val desc = o.optString("description", "")
                // 明显不是插件的仓库（比如只是某人的 app 源码）就别塞进市场。
                val text = (full + " " + desc + " " + o.stringList("topics").joinToString(" ")).lowercase()
                if (!text.contains("dsh") && !text.contains("deepseek")) return@runCatching null
                MarketEntry(
                    id = full,
                    name = full.substringAfter('/'),
                    version = "",
                    description = desc,
                    kind = MarketEntry.Kind.Dsh,
                    homepage = o.optString("html_url", ""),
                    repo = o.optString("html_url", ""),
                    downloads = 0,
                    stars = o.optInt("stargazers_count", 0),
                    author = o.optJSONObject("owner")?.optString("login", "").orEmpty(),
                    tags = o.stringList("topics"),
                )
            }.getOrNull()
        }
    }

    /**
     * 解析容错：接受裸数组或 `{items:[...]}`；单条坏数据跳过，不影响整批。
     */
    fun parse(text: String): List<MarketEntry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = runCatching {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("items")
                    ?: JSONObject(trimmed).optJSONArray("plugins")
                    ?: JSONObject(trimmed).optJSONArray("entries")
            }
        }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val raw = array.optJSONObject(i) ?: return@runCatching null
                // 有些索引把 plugin.json 原样嵌在 manifest 里：manifest 覆盖同名字段，
                // 但下载地址之类通常只在外层，所以先铺外层再盖内层。
                val o = raw.optJSONObject("manifest")?.let { inner ->
                    val merged = JSONObject()
                    raw.keys().asSequence().forEach { k -> merged.put(k, raw.get(k)) }
                    inner.keys().asSequence().forEach { k -> merged.put(k, inner.get(k)) }
                    merged
                } ?: raw
                val id = o.firstString("id", "pluginId", "slug", "name")
                if (id.isBlank()) return@runCatching null
                MarketEntry(
                    id = id,
                    name = o.firstString("name", "title", "displayName").ifBlank { id },
                    version = o.firstString("version", "latest"),
                    description = o.firstString("description", "desc", "summary"),
                    kind = when (o.firstString("kind", "type").lowercase()) {
                        "app" -> MarketEntry.Kind.App
                        "package", "zip" -> MarketEntry.Kind.Package
                        else -> MarketEntry.Kind.Dsh
                    },
                    installHint = o.firstString("installHint", "install", "command"),
                    homepage = o.firstString("homepage", "site", "html_url", "home"),
                    packageUrl = o.firstString(
                        "packageUrl", "url", "download", "downloadUrl", "download_url",
                        "asset", "zip", "tarball", "dist",
                    ),
                    repo = o.firstString("repo", "repository", "github", "source", "html_url"),
                    downloads = o.optInt("downloads", 0),
                    stars = o.optInt("stars", o.optInt("star", 0)),
                    author = o.firstString("author", "publisher"),
                    npm = o.firstString("npm", "npmName", "package", "npmPackage")
                        .ifBlank { npmFromHint(o.firstString("installHint", "install", "command")) },
                    tags = o.stringList("tags", "keywords"),
                )
            }.getOrNull()
        }
    }

    /**
     * 单源直取（保留旧签名）。失败非致命：内置目录仍可用。
     */
    suspend fun fetch(url: String): Result<List<MarketEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            fetchSource(MarketSource(id = "custom", name = "自定义源", url = url))
        }
    }

    /**
     * 从作者的安装命令里抠出 npm 包名。
     *
     * dshmarket 抓下来的索引只给 `dsh plugin add billion-context` 这种命令行，
     * 而这类包在 npm 上确实存在（registry.npmjs.org/billion-context/latest 就是 200）。
     * 以前只认显式 npm 字段，于是全部 74 条只能去猜 GitHub 仓库 —— 而它们的 repo
     * 字段统一指向 dsh-market/dsh-market 这个并不存在的仓库，点安装必然报
     * 「拉不到仓库」。抠出包名就能直接走 npm，覆盖率从 0 变成几乎全部。
     */
    internal fun npmFromHint(hint: String): String {
        val text = hint.substringBefore('#').trim()
        if (text.isEmpty()) return ""
        val re = Regex("""(?:plugin\s+add|npm\s+(?:i|install)|add)\s+([@A-Za-z0-9][@A-Za-z0-9/._-]*)""")
        val raw = re.find(text)?.groupValues?.get(1)?.trimEnd('.', ',', ';') ?: return ""
        if (raw.isEmpty()) return ""
        // 本地文件（xxx.tgz / ./foo / /abs/path）不是 npm 包名。
        if (raw.endsWith(".tgz", true) || raw.endsWith(".zip", true)) return ""
        if (raw.startsWith(".") || raw.startsWith("/")) return ""
        if (raw.any { it == ':' }) return ""
        return raw
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        keys.forEach { k ->
            if (has(k) && !isNull(k)) {
                val v = optString(k, "")
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    private fun JSONObject.stringList(vararg keys: String): List<String> {
        keys.forEach { k ->
            val arr = optJSONArray(k)
            if (arr != null) {
                return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            val csv = optString(k, "")
            if (csv.isNotBlank()) return csv.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
        }
        return emptyList()
    }
}