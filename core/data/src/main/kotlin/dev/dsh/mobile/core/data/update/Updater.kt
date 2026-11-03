package dev.dsh.mobile.core.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.dsh.mobile.core.common.RuntimeMirrors
import dev.dsh.mobile.core.common.download.DownloadCache
import dev.dsh.mobile.core.common.download.Downloads
import dev.dsh.mobile.core.common.download.SegmentedDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 自更新：从一个静态 JSON 清单里读版本号，比当前包高就下载 APK 并交给系统安装器。
 *
 * 清单格式（放 GitHub raw / Release 附件 / 任意静态托管都行）：
 * ```json
 * { "versionCode": 45, "versionName": "1.13.2",
 *   "apkUrl": "https://.../dsh-android-1.13.2-arm64-release.apk",
 *   "notes": "修了 xxx" }
 * ```
 *
 * 之所以不内嵌 git 客户端：手机上装个 APK 不需要 clone 仓库，只需要一个稳定的
 * 清单 URL。仓库那边更新清单 + 传 APK，App 这边就能收到，等价于「用 git 发版」。
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    /** 清单可选提供的备用下载地址，按顺序在直连失败后尝试。 */
    val apkMirrors: List<String> = emptyList(),
    /** 清单可选的资产 sha256（hex），给了就在下载完成后校验；没给就跳过。 */
    val sha256: String = "",
)

/** sha256 校验结论。清单没给 sha256 时是 [NotProvided]：不算通过，但也不拦安装。 */
sealed class ShaStatus {
    data class Verified(val sha256: String) : ShaStatus()
    object NotProvided : ShaStatus()
}

/** 下载结果：可安装的文件 + 校验结论。失败（含校验不过）一律走 Result.failure，不在这里。 */
data class DownloadReport(val file: File, val sha: ShaStatus)

/** 下载完成但 sha256 对不上：文件已删除，调用方拿不到 File 就装不成。 */
class ShaMismatchException(message: String) : IllegalStateException(message)

/**
 * 一次检查的完整结果：清单判断 + 实际用了哪个源 + 每源耗时/失败原因。
 *
 * [info] 为 null 表示已是最新；[origin] 区分「刚拉的」与「内存/磁盘回退」——
 * 回退时 [sourceUrl] 为 null，界面那一行小字不能编一个源出来。
 */
data class UpdateCheck(
    val info: UpdateInfo?,
    val origin: ManifestOrigin,
    val sourceUrl: String?,
    val elapsedMs: Long,
    val failures: List<SourceFailure> = emptyList(),
    val stats: List<SourceStat> = emptyList(),
) {
    /** 界面那行小字。Live 给「源：gh-proxy.com · 486ms」，回退明确说不是网络来的。 */
    fun sourceLine(): String = when (origin) {
        ManifestOrigin.Live -> "源：" + sourceLabel(sourceUrl.orEmpty()) + " · " + elapsedMs + "ms"
        ManifestOrigin.Memory -> "源：本地内存副本（本轮全部源不可用）"
        ManifestOrigin.Disk -> "源：本地磁盘副本（本轮全部源不可用）"
    }
}

class Updater(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("dsh.update", Context.MODE_PRIVATE) }

    /** 内容寻址缓存：清单给了 sha256 时命中即跳过整次下载。 */
    private val downloadCache by lazy { DownloadCache(File(context.cacheDir, "dl-cache")) }

    /**
     * 清单的三级回退链：并行拉取 → 内存 → 磁盘（TTL）。
     *
     * 落点是 filesDir 而不是 cacheDir：缓存目录会被系统清掉，而这份副本正是
     * 「断网时还能判断有没有新版」的唯一依据。TTL 由 [KEY_MANIFEST_TTL] 覆盖，默认 6h。
     */
    private val manifestStore: ManifestStore by lazy {
        ManifestStore(
            file = File(context.filesDir, "update-manifest.json"),
            ttlMs = prefs.getLong(KEY_MANIFEST_TTL, ManifestStore.DEFAULT_TTL_MS),
        )
    }

    /**
     * 源测速记忆：键就落在这份 prefs 里（带 v1.speed. 前缀），换语义换前缀即可，
     * 不做迁移也不怕旧数据。
     */
    private val speedStore: SourceSpeedStore by lazy {
        SourceSpeedStore(
            read = { key -> prefs.getString(key, null) },
            write = { key, value -> prefs.edit().putString(key, value).apply() },
        )
    }

    /**
     * 发车顺序由 [speedStore] 的「罚分 + EMA」决定：有历史时只先发最快的两个，
     * 其余推迟 1.5s；没有历史时 [SourceSpeedStore.plan] 返回全发，行为与加测速之前一致。
     * 每个源的实测耗时经 [SourceSpeedStore.recordSuccess] / recordFailure 回写，不多发一次请求。
     */
    private val manifestResolver: ManifestResolver by lazy {
        ManifestResolver(manifestStore) { sources ->
            ManifestRace.race(
                sources = sources,
                perSourceTimeoutMs = manifestTimeoutMs(),
                totalBudgetMs = manifestBudgetMs(),
                plan = { speedStore.plan(it) },
                onStat = { stat ->
                    if (stat.failure == null) {
                        speedStore.recordSuccess(stat.url, stat.elapsedMs)
                    } else {
                        speedStore.recordFailure(stat.url)
                    }
                },
            ) { httpGet(it) }
        }
    }

    /** 更新源（清单 URL）。没手动配就走内置默认源（GitHub 仓库里的 update.json）。 */
    fun sourceUrl(): String = prefs.getString(KEY_SOURCE, "").orEmpty().ifBlank { DEFAULT_SOURCES.first() }

    /** 实际要尝试的清单地址：手动配了只用它；否则按内置候选顺序逐个试（国内网络 raw 常被拦，走镜像）。 */
    fun sourceCandidates(): List<String> {
        val custom = customSourceUrl()
        return if (custom.isNotBlank()) listOf(custom) else DEFAULT_SOURCES
    }

    /** 用户手动填的源；空串表示跟随默认源。 */
    fun customSourceUrl(): String = prefs.getString(KEY_SOURCE, "").orEmpty()

    fun setSourceUrl(url: String) {
        prefs.edit().putString(KEY_SOURCE, url.trim()).apply()
    }

    /** 当前安装的版本号 / 版本名，直接读系统，不依赖 BuildConfig（debug/release 都对）。 */
    fun installedVersion(): Pair<Long, String> = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        code to (info.versionName ?: "")
    }.getOrDefault(0L to "")

    /**
     * 拉清单并判断有没有新版。返回 null 表示已是最新。
     *
     * [checkDetailed] 的窄化视图：启动时的静默检查只要信息，不需要「用的哪个源」。
     */
    suspend fun check(): Result<UpdateInfo?> = checkDetailed().map { it.info }

    /**
     * 拉清单并判断有没有新版，同时把「实际用了哪个源 / 耗时 / 各源失败原因」交回去。
     *
     * 这些都是本轮竞速已经拿到的数据，不为可观测性多发任何请求。
     */
    suspend fun checkDetailed(): Result<UpdateCheck> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = sourceCandidates()
            require(sources.isNotEmpty()) { "还没配置更新源" }
            val resolved = manifestResolver.resolve(sources)
            val body = when (resolved) {
                is ManifestResult.Ok -> resolved.body
                // 全挂且本地没有可用副本才走到这里；消息里带每一个源的原因，不只第一个。
                is ManifestResult.Failed -> error("清单拉取失败：" + describeFailures(resolved.failures))
            }
            val json = JSONObject(body)
            val mirrors = json.optJSONArray("apkMirrors")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { u -> u.isNotBlank() } }
            }.orEmpty()
            val info = UpdateInfo(
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName", ""),
                apkUrl = json.optString("apkUrl", ""),
                notes = json.optString("notes", ""),
                apkMirrors = mirrors,
                sha256 = json.optString("sha256", ""),
            )
            require(info.versionCode > 0) { "清单里没有有效的 versionCode" }
            require(info.apkUrl.isNotBlank()) { "清单里没有 apkUrl" }
            val (current, _) = installedVersion()
            UpdateCheck(
                info = if (info.versionCode > current) info else null,
                origin = (resolved as ManifestResult.Ok).origin,
                sourceUrl = resolved.sourceUrl,
                elapsedMs = resolved.elapsedMs,
                failures = resolved.failures,
                stats = resolved.stats,
            )
        }
    }

    /**
     * 下载到 cacheDir。返回的 File 已经是可安装的 APK。
     *
     * 实际的并发是 [Downloads] 里的分段下载器干的：多镜像并行探测 → 多段 Range 并发 → 段内断点续传。
     * onProgress 仍然按「已下载字节 / 总长度」给 0..100，语义与旧的单连接实现一致，回调在 IO 线程。
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit = {}): Result<File> =
        downloadChecked(info, onProgress).map { it.file }

    /**
     * 带校验结论的下载。[download] 只是它的 File 视图，老的调用点不用改。
     *
     * sha256 对不上时抛 [ShaMismatchException] 并删掉文件：调用方拿不到 File，装不成。
     * 「校验失败禁止安装」靠类型保证，不靠每个界面都记得判断。
     */
    suspend fun downloadChecked(
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result<DownloadReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(context.cacheDir, "update-${info.versionName.ifBlank { info.versionCode.toString() }}.apk")
                val candidates = downloadCandidates(info)
                require(candidates.isNotEmpty()) { "下载失败：没有可用地址" }
                val expected = info.sha256.trim().lowercase().takeIf { it.length == 64 }
                // 有 sha256 时缓存文件名就是 sha256，同一个包第二次点「更新」不再走网络。
                try {
                    Downloads.download(
                        urls = candidates,
                        target = target,
                        cache = downloadCache,
                        expectedSha256 = expected,
                        label = "更新包",
                        onProgress = onProgress,
                    )
                } catch (e: Throwable) {
                    // 分段下载器自己就会校验；把它的失败翻译成可识别类型，界面才能显式红字。
                    val msg = e.message.orEmpty()
                    if (e !is ShaMismatchException && msg.contains("sha256 校验失败")) {
                        target.delete()
                        throw ShaMismatchException(msg)
                    }
                    throw e
                }
                require(target.length() > 1_000_000) { "文件不完整（${target.length()} 字节）" }
                // 再算一遍：缓存命中路径（分段下载器直接返回）不会经过上面的 verifySha。
                val actual = SegmentedDownloader.sha256Of(target)
                if (expected != null && actual != expected) {
                    target.delete()
                    throw ShaMismatchException(
                        "更新包 sha256 校验失败：期望 " + expected.take(16) + "…，实际 " + actual.take(16) + "…",
                    )
                }
                onProgress(100)
                DownloadReport(
                    file = target,
                    sha = if (expected == null) ShaStatus.NotProvided else ShaStatus.Verified(actual),
                )
            }
        }

    /** 交给系统安装器。首次会弹「允许安装未知应用」。 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 下载候选：清单给的镜像 → 内置 GitHub 反代 → 原始地址。国内直连 github 常被拦，反代多半能通。 */
    private fun downloadCandidates(info: UpdateInfo): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(info.apkMirrors.filter { it.isNotBlank() })
        if (info.apkUrl.isNotBlank()) {
            for (base in MIRROR_PREFIXES) out.add(base + info.apkUrl)
            out.add(info.apkUrl)
        }
        return out.toList()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dsh-mobile-updater")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "HTTP $code：${text.take(120)}" }
            text
        } finally {
            conn.disconnect()
        }
    }

    /** 单源超时（毫秒）。可配是因为弱网用户愿意等更久，而正常用户不想等 8s。 */
    fun manifestTimeoutMs(): Long =
        prefs.getLong(KEY_MANIFEST_TIMEOUT, ManifestRace.PER_SOURCE_TIMEOUT_MS)
            .coerceIn(1_000L, 60_000L)

    /** 一次 check 的总预算。并行之后它才是真正的「最坏耗时」上限。 */
    fun manifestBudgetMs(): Long =
        prefs.getLong(KEY_MANIFEST_BUDGET, ManifestRace.TOTAL_BUDGET_MS)
            .coerceIn(1_000L, 120_000L)

    fun setManifestBudgets(timeoutMs: Long, budgetMs: Long) {
        prefs.edit()
            .putLong(KEY_MANIFEST_TIMEOUT, timeoutMs)
            .putLong(KEY_MANIFEST_BUDGET, budgetMs)
            .apply()
    }

    companion object {
        /**
         * 内置更新源候选：国内网络 raw.githubusercontent 常被拦，先用反代与 jsDelivr，最后才直连。
         *
         * 前缀统一来自 [RuntimeMirrors]，不再在本文件里写死 —— 换反代只改那一处。
         */
        private const val REPO = "ALL-PAY-C/HarnessForAndroid"
        // 全部用 getter：用户在设置里换了反代之后，下一次 check 就该用新的那一套。
        private val RAW: String get() = RuntimeMirrors.GITHUB_RAW + REPO + "/main/update.json"
        val DEFAULT_SOURCE: String get() = RuntimeMirrors.proxied(RAW)
        val DEFAULT_SOURCES: List<String> get() = buildList {
            addAll(RuntimeMirrors.githubProxies().map { it + RAW })
            add(RuntimeMirrors.JSDELIVR_GH + REPO + "@main/update.json")
            add(RAW)
        }

        /** APK 下载用的 GitHub 反代前缀（按可达性排序），跟清单反代同源。 */
        private val MIRROR_PREFIXES: List<String> get() = RuntimeMirrors.githubProxies()

        private const val KEY_SOURCE = "source"
        private const val KEY_MANIFEST_TIMEOUT = "manifest_timeout_ms"
        private const val KEY_MANIFEST_BUDGET = "manifest_budget_ms"
        private const val KEY_MANIFEST_TTL = "manifest_ttl_ms"
    }
}
