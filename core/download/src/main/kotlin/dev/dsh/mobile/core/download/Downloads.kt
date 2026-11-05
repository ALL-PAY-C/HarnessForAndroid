package dev.dsh.mobile.core.download

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 内容寻址下载缓存。
 *
 * - 强缓存：调用方给了 sha256 → 缓存文件名就是 sha256。命中判定不看文件名，重新算一遍 sha 才算命中
 *   （名字可以伪造，内容不行）。命中 = 完全跳过网络，多镜像探测都不发。
 * - 弱缓存：没有 sha256（npm tarball / GitHub zipball / 技能 zip 都没有）→ 键 = URL，值 = 上次落盘的
 *   大小 + 缓存副本文件名，登记在 weak.index。命中只保证「同 URL、同大小」，服务端在同一个 URL 换成
 *   大小相同的新内容时会命中旧文件——这是弱缓存的内生风险，只用于「一个 URL 对应一个固定版本」的场景。
 */
class DownloadCache(private val dir: File) {

    /** 强缓存目标路径：<sha256>.bin。也直接当下载目标用，省一次复制。 */
    fun strongPath(sha256: String): File = File(dir.also { it.mkdirs() }, sha256.trim().lowercase() + ".bin")

    /** 强缓存命中：文件存在、非空、重新算出的 sha 与期望一致。 */
    fun hitStrong(expectedSha256: String): File? {
        val want = expectedSha256.trim().lowercase()
        if (want.length != 64) return null
        val f = File(dir, want + ".bin")
        if (!f.isFile || f.length() <= 0L) return null
        return if (runCatching { SegmentedDownloader.sha256Of(f) }.getOrNull() == want) f else null
    }

    /** 弱缓存命中：URL 登记过，且缓存副本还在、大小与登记值一致。 */
    fun hitWeak(url: String): File? {
        if (url.isBlank()) return null
        val entry = synchronized(LOCK) { readIndex()[keyOf(url)] } ?: return null
        val f = File(dir, entry.name)
        if (!f.isFile || f.length() != entry.size) return null
        return f
    }

    /** 落盘后登记弱缓存：文件名带上 URL 与大小，索引最后写，避免并发读者拿到半截副本。 */
    fun storeWeak(url: String, file: File) {
        if (url.isBlank() || !file.isFile) return
        val size = file.length()
        if (size <= 0L) return
        val name = "w-" + keyOf(url).take(16) + "-" + size + ".bin"
        val cached = File(dir, name)
        synchronized(LOCK) {
            if (!cached.isFile || cached.length() != size) {
                dir.mkdirs()
                file.copyTo(cached, overwrite = true)
            }
            writeIndex(readIndex() + (keyOf(url) to Entry(name, size)))
        }
    }

    private class Entry(val name: String, val size: Long)

    private fun readIndex(): Map<String, Entry> {
        val f = File(dir, INDEX)
        if (!f.isFile) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 3) return@mapNotNull null
                val size = p[2].toLongOrNull() ?: return@mapNotNull null
                p[0] to Entry(p[1], size)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeIndex(index: Map<String, Entry>) {
        runCatching {
            dir.mkdirs()
            File(dir, INDEX).writeText(
                index.entries.joinToString("\n") { (k, v) -> k + "\t" + v.name + "\t" + v.size },
            )
        }
    }

    private fun keyOf(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val INDEX = "weak.index"
        val LOCK = Any()
    }
}

/**
 * 全应用唯一的下载入口。
 *
 * 所有要落盘的网络文件（自更新 APK / 插件包 / npm 依赖 / 技能 zip / GitHub zipball / Agent 预设）
 * 都从这里走：分段 + 续传 + sha256 校验 + 内容寻址缓存，调用点只负责给候选地址、目标路径和上限。
 *
 * [download] 会**先把缓存排在最前**：强缓存命中就直接复制，不再发任何请求；下载到强缓存路径时
 * 也直接下到缓存文件名上，再复制给调用方（调用方拿到的始终是自己独占的文件，删它不影响缓存）。
 */
object Downloads {

    suspend fun download(
        urls: List<String>,
        target: File,
        cache: DownloadCache? = null,
        expectedSha256: String? = null,
        maxBytes: Long = 0L,
        label: String = "下载",
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 60_000,
        userAgent: String = "dsh-mobile",
        onProgress: (Int) -> Unit = {},
    ): DownloadOutcome {
        val sha = expectedSha256?.trim()?.lowercase()?.takeIf { it.length == 64 }
        val primary = urls.firstOrNull { it.isNotBlank() }.orEmpty()
        target.parentFile?.mkdirs()

        if (cache != null) {
            val hit = if (sha != null) cache.hitStrong(sha) else cache.hitWeak(primary)
            if (hit != null) {
                copyInto(hit, target)
                onProgress(100)
                // usedUrls 为空 = 本次没走网络（缓存命中），调用方可据此跳过进度动画。
                return DownloadOutcome(target.length(), sha ?: SegmentedDownloader.sha256Of(target), emptyList())
            }
        }

        val into = if (cache != null && sha != null) cache.strongPath(sha) else target
        val outcome = SegmentedDownloader(
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            userAgent = userAgent,
        ).download(urls, into, sha, maxBytes, label, onProgress)

        if (cache != null) {
            if (sha != null) copyInto(into, target) else cache.storeWeak(primary, target)
        }
        return outcome.copy(bytes = target.length())
    }

    /** 阻塞版：[download] 用在既有同步代码里（安装器接口是阻塞的）。调用方负责丢到 IO 线程。 */
    fun downloadBlocking(
        urls: List<String>,
        target: File,
        cache: DownloadCache? = null,
        expectedSha256: String? = null,
        maxBytes: Long = 0L,
        label: String = "下载",
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 60_000,
        userAgent: String = "dsh-mobile",
        onProgress: (Int) -> Unit = {},
    ): DownloadOutcome = runBlocking {
        download(urls, target, cache, expectedSha256, maxBytes, label, connectTimeoutMs, readTimeoutMs, userAgent, onProgress)
    }

    /**
     * 拉一段文本（清单 / packument），候选地址按顺序试。
     *
     * 不落盘所以不进缓存，但连接参数与 [download] 同一套，UA 与超时不再各文件各写一份。
     */
    suspend fun fetchText(
        urls: List<String>,
        label: String = "内容",
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
        userAgent: String = "dsh-mobile",
    ): String {
        val candidates = urls.map { it.trim() }.filter { it.isNotBlank() }
        require(candidates.isNotEmpty()) { label + " 失败：没有可用地址" }
        var lastError = ""
        for (url in candidates) {
            val r = runCatching { readText(url, connectTimeoutMs, readTimeoutMs, userAgent) }
            r.getOrNull()?.let { return it }
            lastError = r.exceptionOrNull()?.message.orEmpty()
        }
        throw IOException(label + " 失败：" + lastError.ifBlank { "没有可用地址" })
    }

    fun fetchTextBlocking(
        urls: List<String>,
        label: String = "内容",
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
        userAgent: String = "dsh-mobile",
    ): String = runBlocking { fetchText(urls, label, connectTimeoutMs, readTimeoutMs, userAgent) }

    private fun readText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int, userAgent: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP " + code + " · " + url)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 本地复制；同一路径直接返回（缓存路径就是目标路径时不再自己复制自己）。 */
    private fun copyInto(from: File, to: File) {
        if (from.absolutePath == to.absolutePath) return
        to.parentFile?.mkdirs()
        to.delete()
        from.copyTo(to, overwrite = true)
    }
}
