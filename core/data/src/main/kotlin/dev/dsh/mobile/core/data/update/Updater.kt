package dev.dsh.mobile.core.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
)

class Updater(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences("dsh.update", Context.MODE_PRIVATE) }

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
     */
    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = sourceCandidates()
            require(sources.isNotEmpty()) { "还没配置更新源" }
            var body = ""
            var lastError = ""
            for (s in sources) {
                val r = runCatching { httpGet(s) }
                if (r.isSuccess) { body = r.getOrThrow(); lastError = ""; break }
                lastError = r.exceptionOrNull()?.message.orEmpty()
            }
            require(body.isNotBlank()) { "清单拉取失败：$lastError" }
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
            )
            require(info.versionCode > 0) { "清单里没有有效的 versionCode" }
            require(info.apkUrl.isNotBlank()) { "清单里没有 apkUrl" }
            val (current, _) = installedVersion()
            if (info.versionCode > current) info else null
        }
    }

    /**
     * 下载到 cacheDir。返回的 File 已经是可安装的 APK。
     */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(context.cacheDir, "update-${info.versionName.ifBlank { info.versionCode.toString() }}.apk")
                var lastError = ""
                for (url in downloadCandidates(info)) {
                    if (target.exists()) target.delete()
                    val r = runCatching { downloadOne(url, target, onProgress) }
                    if (r.isSuccess && target.length() > 1_000_000) {
                        onProgress(100)
                        return@runCatching target
                    }
                    lastError = r.exceptionOrNull()?.message ?: "文件不完整（${target.length()} 字节）"
                }
                throw IllegalStateException(lastError.ifBlank { "下载失败：没有可用地址" })
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

    private fun downloadOne(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dsh-mobile-updater")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "下载失败：HTTP $code" }
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
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

    companion object {
        /** 内置更新源候选：国内网络 raw.githubusercontent 常被拦，先用反代与 jsDelivr，最后才直连。 */
        private const val REPO = "ALL-PAY-C/HarnessForAndroid"
        private const val RAW = "https://raw.githubusercontent.com/$REPO/main/update.json"
        const val DEFAULT_SOURCE = "https://gh-proxy.com/$RAW"
        val DEFAULT_SOURCES = listOf(
            DEFAULT_SOURCE,
            "https://ghfast.top/$RAW",
            "https://cdn.jsdelivr.net/gh/$REPO@main/update.json",
            RAW,
        )

        /** APK 下载用的 GitHub 反代前缀（按可达性排序）。 */
        private val MIRROR_PREFIXES = listOf(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://hk.gh-proxy.com/",
            "https://ghproxy.net/",
        )
        private const val KEY_SOURCE = "source"
    }
}
