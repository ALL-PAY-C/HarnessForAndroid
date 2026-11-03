package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * 上次成功索引的本地缓存。
 *
 * 存在的理由：网络受限时单源超时 8s 之后只剩内置目录，用户看到一个空/半空的列表，
 * 既不知道为什么，也没法重试。缓存让「昨天看到的东西」今天还在。
 *
 * [write] 返回 false 表示没落盘（体积超限或 IO 失败），调用方不需要处理 —— 缓存是尽力而为。
 */
interface MarketCacheStore {
    /** 新鲜缓存（年龄在 TTL 内）；文件缺失、损坏、过期、解析不出条目时一律返回空列表，不抛。 */
    fun read(): List<MarketEntry>

    /** 不看 TTL 的读取：全部源都挂掉时用它，稍旧的列表也好过空列表。 */
    fun readAny(): List<MarketEntry> = read()

    /** 缓存年龄（毫秒）；没有缓存或实现不支持计时时返回 null。 */
    fun ageMs(): Long? = null

    fun write(entries: List<MarketEntry>): Boolean
}

/** 内存实现，测试与「不需要持久化」的场景用。 */
class MemoryMarketCache(private var entries: List<MarketEntry> = emptyList()) : MarketCacheStore {
    override fun read(): List<MarketEntry> = entries
    override fun write(entries: List<MarketEntry>): Boolean {
        this.entries = entries
        return true
    }
}

/**
 * 一个文件一份快照（JSON）。
 *
 * 体积上限 [MAX_BYTES] = 1 MB：当前全量合并结果约 500 条、几百 KB，留一倍余量。
 * 超限就直接丢掉不写 —— 宁可没有缓存，也不要在别人的 files 目录里塞几 MB
 * （ponytail: 真要更大就换 DataStore + 分片，这里不上）。
 */
class FileMarketCache(
    private val file: File,
    private val maxBytes: Int = MAX_BYTES,
    /** 超过这个年龄的缓存不再参与「正常刷新」的合并，只在全源失败时兜底。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MarketCacheStore {

    override fun read(): List<MarketEntry> = runCatching {
        if (!file.isFile) return emptyList()
        val snap = MarketCacheCodec.decodeSnapshot(file.readText()) ?: return emptyList()
        if (ageOf(snap.savedAt) > ttlMs) return emptyList()
        snap.entries
    }.getOrDefault(emptyList())

    override fun readAny(): List<MarketEntry> = runCatching {
        if (!file.isFile) return emptyList()
        MarketCacheCodec.decodeSnapshot(file.readText())?.entries.orEmpty()
    }.getOrDefault(emptyList())

    override fun ageMs(): Long? = runCatching {
        if (!file.isFile) return null
        val snap = MarketCacheCodec.decodeSnapshot(file.readText()) ?: return null
        ageOf(snap.savedAt)
    }.getOrNull()

    /** 时钟回拨（负数）当 0：文件比当前时间还新说明时钟乱了，不该因此判过期。 */
    private fun ageOf(savedAt: Long): Long = (clock() - savedAt).coerceAtLeast(0L)

    override fun write(entries: List<MarketEntry>): Boolean = runCatching {
        if (entries.isEmpty()) return false
        // 时间戳用注入的时钟：否则测试里的假时钟和文件里的真时间对不上，TTL 永远算不出过期。
        val text = MarketCacheCodec.encode(entries, clock())
        if (text.toByteArray().size > maxBytes) {
            file.delete()
            return false
        }
        // 先写临时文件再换名：中途被杀掉也不会留下半截 JSON。
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        /** 1 MB；当前合并结果几百 KB，超了就不写。 */
        const val MAX_BYTES = 1_048_576

        /**
         * 15 分钟。市场条目每天变不了几次，但「一小时前的索引」当刷新结果给用户
         * 又会让人以为新插件没发布 —— 15 分钟够覆盖一次正常刷新周期。
         */
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L
    }
}

/**
 * 缓存文件的编解码。
 *
 * 只认自己写出来的格式；外部 JSON 一律走 [PluginMarket.parse]。
 * [decode] 单条坏数据跳过，整体坏掉返回空 —— 静默丢弃是需求。
 */
object MarketCacheCodec {

    /** 缓存格式版本。改这个等于作废旧缓存：条目结构或内置目录变了就得让它重拉。
     *  v2：APK 不再内置任何插件条目，旧快照里那些条目会一直活着。 */
    private const val VERSION = 2

    fun encode(entries: List<MarketEntry>, savedAt: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("savedAt", savedAt)
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("version", e.version)
                    put("description", e.description)
                    put("kind", e.kind.name)
                    put("installHint", e.installHint)
                    put("homepage", e.homepage)
                    put("packageUrl", e.packageUrl)
                    put("repo", e.repo)
                    put("npm", e.npm)
                    put("downloads", e.downloads)
                    put("stars", e.stars)
                    put("author", e.author)
                    put("tags", JSONArray(e.tags))
                    put("sources", JSONArray(e.sources))
                },
            )
        }
        root.put("entries", array)
        return root.toString()
    }

    /** 带写入时间的一版快照：TTL 判定靠 [savedAt]。 */
    data class CachedSnapshot(val entries: List<MarketEntry>, val savedAt: Long)

    fun decode(text: String): List<MarketEntry> = decodeSnapshot(text)?.entries.orEmpty()

    /** 解析到「条目 + 写入时间」；版本不符或结构不对返回 null（静默丢弃是需求）。 */
    fun decodeSnapshot(text: String): CachedSnapshot? {
        if (text.isBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("version", 0) != VERSION) return null
        val array = root.optJSONArray("entries") ?: return null
        val entries = (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.optJSONObject(i) ?: return@runCatching null
                val id = o.optString("id").trim()
                if (id.isEmpty()) return@runCatching null
                MarketEntry(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    version = o.optString("version"),
                    description = o.optString("description"),
                    kind = MarketEntry.Kind.entries.firstOrNull { it.name == o.optString("kind") }
                        ?: MarketEntry.Kind.Dsh,
                    installHint = o.optString("installHint"),
                    homepage = o.optString("homepage"),
                    packageUrl = o.optString("packageUrl"),
                    repo = o.optString("repo"),
                    npm = o.optString("npm"),
                    downloads = o.optInt("downloads", 0),
                    stars = o.optInt("stars", 0),
                    author = o.optString("author"),
                    tags = o.optJSONArray("tags").toStringList(),
                    sources = o.optJSONArray("sources").toStringList(),
                )
            }.getOrNull()
        }
        // 老快照没有 savedAt：当 0 处理，于是它在 TTL 下必然过期，只在兜底路径可见。
        return CachedSnapshot(entries, root.optLong("savedAt", 0L))
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * 一次加载给界面看的完整状态：列表 + 每源状态 + 「这版是从哪来的」。
 *
 * 界面不需要自己推断该不该显示失败行，[notice] 直接给文案，没有就是 null。
 */
data class MarketLoad(
    val snapshot: MarketSnapshot = MarketSnapshot(),
    /** 这版里含上次成功索引的缓存（全部源都挂掉时就是它撑着）。 */
    val fromCache: Boolean = false,
    /** 全部源失败且没有缓存，只能展示内置索引。 */
    val usedBundled: Boolean = false,
) {
    /** 所有源都挂了（还没开始拉的时候 sources 为空，不算失败）。 */
    val allFailed: Boolean
        get() = snapshot.sources.isNotEmpty() && snapshot.sources.none { it.ok }

    val okSources: Int get() = snapshot.sources.count { it.ok }

    /** 顶部那一行状态；null = 不显示。 */
    fun notice(): String? = when {
        snapshot.sources.isEmpty() -> null
        !allFailed -> null
        fromCache -> "索引刷新失败（网络受限），正在用缓存"
        usedBundled -> "索引刷新失败（网络受限），已回退内置索引"
        else -> "索引刷新失败（网络受限）"
    }
}

/**
 * 市场加载器：缓存优先 → 并发刷新 → 全挂回落内置。
 *
 * [fetch] 可注入，单测里塞 fake，不碰网络。并发沿用 [PluginMarket.fetchAllStreaming]
 * 的语义：哪源先回来先吐一版，用户不用对着「拉取中…」干等。
 */
class MarketLoader(
    /** 内置目录（APK assets 里的 registry.json + 代码内置条目），已归并好。 */
    private val bundled: () -> List<MarketEntry> = { emptyList() },
    private val cache: MarketCacheStore? = null,
    private val sources: List<MarketSource> = PluginMarket.sources,
    private val timeoutMs: Long = PluginMarket.SOURCE_TIMEOUT_MS,
    private val maxConcurrent: Int = PluginMarket.SOURCE_CONCURRENCY,
    private val fetch: suspend (MarketSource) -> List<MarketEntry> = { PluginMarket.fetchSource(it) },
) {

    /** 首帧：缓存 + 内置，不发一个请求。断网启动时列表不是空的。 */
    fun initial(): MarketLoad {
        val cached = cache?.read().orEmpty()
        val base = bundled()
        return MarketLoad(
            snapshot = MarketSnapshot(
                plugins = PluginMarket.merge(cached + base),
                sources = emptyList(),
                rawCount = cached.size + base.size,
            ),
            fromCache = cached.isNotEmpty(),
            usedBundled = cached.isEmpty(),
        )
    }

    /**
     * 并发拉全部源。任一成功即用；全部失败回落「缓存 + 内置」。
     * 只要有源成功就把最终列表回写缓存。
     */
    suspend fun refresh(onProgress: (MarketLoad) -> Unit = {}): MarketLoad = coroutineScope {
        val cached = cache?.read().orEmpty()
        val base = bundled()
        val collected = Collections.synchronizedList(ArrayList<Pair<SourceStatus, List<MarketEntry>>>())
        // 闸门跨源共享：12 个源不会一次全发出去抢带宽。
        val gate = Semaphore(maxConcurrent.coerceAtLeast(1))
        val jobs = sources.map { source ->
            async(Dispatchers.IO) {
                val outcome = runCatching { PluginMarket.fetchOne(source, timeoutMs, gate, fetch) }
                val entries = outcome.getOrDefault(emptyList())
                val status = SourceStatus(
                    id = source.id,
                    name = source.name,
                    ok = outcome.isSuccess,
                    count = entries.size,
                    error = outcome.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
                )
                collected.add(status to entries)
                val now = collected.toList()
                val live = now.flatMap { it.second }
                runCatching {
                    onProgress(
                        MarketLoad(
                            snapshot = MarketSnapshot(
                                plugins = PluginMarket.merge(live + cached + base),
                                sources = now.map { it.first },
                                rawCount = live.size + cached.size + base.size,
                            ),
                        ),
                    )
                }
                status to entries
            }
        }
        val results = jobs.awaitAll()
        val live = results.flatMap { it.second }
        val statuses = results.map { it.first }
        val anyOk = statuses.any { it.ok }
        // 全挂时连过期缓存也接上：TTL 只决定它参不参与正常合并，不决定断网时要不要给空列表。
        val stale = if (anyOk) emptyList() else cache?.readAny().orEmpty()
        val plugins = PluginMarket.merge(live + cached + stale + base)
        // 全挂时不能拿「只有内置」的列表覆盖掉上次的成功索引。
        if (anyOk) runCatching { cache?.write(plugins) }
        MarketLoad(
            snapshot = MarketSnapshot(
                plugins = plugins,
                sources = statuses,
                rawCount = live.size + cached.size + base.size,
            ),
            fromCache = anyOk.not() && (cached.isNotEmpty() || stale.isNotEmpty()),
            usedBundled = anyOk.not() && cached.isEmpty() && stale.isEmpty(),
        )
    }
}
