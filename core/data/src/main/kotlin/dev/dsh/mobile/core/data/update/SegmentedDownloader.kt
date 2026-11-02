package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 下载结果：字节数 + 实际 sha256（hex 小写）+ 参与下载的地址顺序。 */
internal data class DownloadOutcome(val bytes: Long, val sha256: String, val usedUrls: List<String>)

/**
 * APK 多连接分段下载器。
 *
 * 策略：
 * 1. 候选地址并行探测（`Range: bytes=0-0`），先返回的当主镜像，其余按原顺序兜底；
 * 2. 主镜像支持 Range（HTTP 206）且长度已知：切成 [connections] 段并发下载。单段失败立刻换下一个
 *    镜像，并从该段已写入的偏移续传（Range 起点 = 段起点 + 已写字节），不是整段重下；
 *    失败一次的镜像从本轮候选里剔除，全部剔除后清空重来，最多 [maxRounds] 轮；
 * 3. 不支持 Range 的镜像退化为单流下载，仍带 .part 断点续传；
 * 4. 进度 = 已落盘字节 / 总长度，映射成 0..100 的 Int，只在百分比变化时回调且保证单调递增；
 * 5. 落盘完成校验 sha256（调用方给了才校验），.part 改名成目标文件。
 *
 * 纯 JVM 实现（不引用 android.*），JVM 单测可直接 new 出来注入参数。
 */
internal class SegmentedDownloader(
    /** 分片并发连接数。 */
    private val connections: Int = DEFAULT_CONNECTIONS,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 30_000,
    /** 并行探测镜像的预算，超时未响应的镜像直接放弃。 */
    private val probeBudgetMs: Long = 4_000,
    /** 整次下载硬超时，兜底防卡死。 */
    private val overallTimeoutMs: Long = 20 * 60_000L,
    /** 同一镜像累计失败几次后剔除。 */
    private val failuresBeforeDrop: Int = 1,
    /** 镜像全灭后允许的重来轮数。 */
    private val maxRounds: Int = 3,
) {

    private class Probe(val url: String, val length: Long, val ranges: Boolean)

    private class Seg(val start: Long, val end: Long) {
        var done: Long = 0L
        val size: Long get() = end - start + 1
    }

    suspend fun download(
        candidates: List<String>,
        target: File,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit = {},
    ): DownloadOutcome = withTimeout(overallTimeoutMs) {
        val urls = LinkedHashSet(candidates.map { it.trim() }.filter { it.isNotBlank() }).toList()
        require(urls.isNotEmpty()) { "没有可用下载地址" }
        val winner = probeWinner(urls) ?: throw IllegalStateException("下载失败：所有地址都不通")
        val ordered = listOf(winner.url) + urls.filter { it != winner.url }
        val part = File(target.parentFile, target.name + ".part")
        withContext(Dispatchers.IO) {
            target.delete()
            val sha = if (winner.ranges && winner.length > 0) {
                segmented(ordered, winner.length, part, target, onProgress)
            } else {
                singleStream(ordered, part, target, winner.length, onProgress)
            }
            verifySha(part.let { target }, sha, expectedSha256)
            DownloadOutcome(target.length(), sha, ordered)
        }
    }

    // ---- 分段并发 ----

    private suspend fun segmented(
        urls: List<String>,
        length: Long,
        part: File,
        target: File,
        onProgress: (Int) -> Unit,
    ): String {
        val segs = planSegments(length, connections)
        val stateFile = File(part.parentFile, part.name + ".seg")
        val resumed = loadState(stateFile, segs, length)
        val raf = RandomAccessFile(part, "rw")
        try {
            if (!resumed) {
                raf.setLength(length) // 预分配，多线程定位写不越界
                stateFile.delete()
            }
            val done = AtomicLong(segs.sumOf { it.done })
            val lastPct = AtomicInteger(-1)
            val reportLock = Any()
            fun report() {
                val pct = ((done.get() * 100) / length).toInt().coerceIn(0, 100)
                synchronized(reportLock) {
                    if (pct > lastPct.get()) {
                        lastPct.set(pct)
                        onProgress(pct)
                    }
                }
            }
            report()

            val bad: MutableSet<String> = Collections.synchronizedSet(HashSet())
            val failures = HashMap<String, Int>()
            coroutineScope {
                segs.filter { it.done < it.size }.forEach { seg ->
                    launch(Dispatchers.IO) {
                        var rounds = 0
                        var lastError = ""
                        while (rounds < maxRounds) {
                            val url = synchronized(bad) { urls.firstOrNull { it !in bad } }
                            if (url == null) {
                                rounds += 1
                                synchronized(bad) { bad.clear() }
                                continue
                            }
                            try {
                                fetchRange(url, seg, raf) { n ->
                                    synchronized(seg) { seg.done += n }
                                    done.addAndGet(n)
                                    report()
                                }
                                saveState(stateFile, segs, length)
                                return@launch
                            } catch (e: IOException) {
                                lastError = e.message.orEmpty()
                                val n = synchronized(failures) {
                                    val v = (failures[url] ?: 0) + 1
                                    failures[url] = v
                                    v
                                }
                                if (n >= failuresBeforeDrop) synchronized(bad) { bad.add(url) }
                            }
                        }
                        throw IOException(lastError.ifBlank { "分段下载失败：镜像均不可用" })
                    }
                }
            }
            raf.setLength(length)
        } finally {
            // Windows 上句柄没关就改名会失败，必须先关再校验/改名
            runCatching { raf.close() }
        }
        onProgress(100)
        val sha = sha256(part)
        stateFile.delete()
        renameInto(part, target, length)
        return sha
    }

    private fun fetchRange(url: String, seg: Seg, raf: RandomAccessFile, onBytes: (Long) -> Unit) {
        val from = seg.start + seg.done
        val conn = open(url).apply { setRequestProperty("Range", "bytes=" + from + "-" + seg.end) }
        try {
            val code = conn.responseCode
            val ok = code == 206 || (code in 200..299 && seg.start == 0L && seg.done == 0L)
            if (!ok) throw IOException("HTTP " + code)
            val buf = ByteArray(BUFFER)
            var pos = from
            var remain = seg.end - from + 1
            conn.inputStream.use { input ->
                while (remain > 0) {
                    val want = minOf(buf.size.toLong(), remain).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    synchronized(raf) {
                        raf.seek(pos)
                        raf.write(buf, 0, n)
                    }
                    pos += n
                    remain -= n
                    onBytes(n.toLong())
                }
            }
            if (remain > 0) throw IOException("连接中断：还差 " + remain + " 字节")
        } finally {
            conn.disconnect()
        }
    }

    // ---- 单流兜底（镜像不吃 Range）----

    private suspend fun singleStream(
        urls: List<String>,
        part: File,
        target: File,
        knownLength: Long,
        onProgress: (Int) -> Unit,
    ): String {
        var lastError = ""
        for (url in urls) {
            val r = runCatching { withContext(Dispatchers.IO) { streamOne(url, part, knownLength, onProgress) } }
            if (r.isSuccess) {
                onProgress(100)
                val sha = sha256(part)
                renameInto(part, target, r.getOrThrow())
                return sha
            }
            lastError = r.exceptionOrNull()?.message.orEmpty()
        }
        throw IllegalStateException(lastError.ifBlank { "下载失败：没有可用地址" })
    }

    private fun streamOne(url: String, part: File, knownLength: Long, onProgress: (Int) -> Unit): Long {
        var resumeAt = if (part.exists()) part.length() else 0L
        val conn = open(url).apply { if (resumeAt > 0) setRequestProperty("Range", "bytes=" + resumeAt + "-") }
        try {
            val code = conn.responseCode
            val append = when {
                code == 206 -> true
                code in 200..299 -> {
                    if (resumeAt > 0) resumeAt = 0 // 服务器不吃 Range，只能从头来
                    part.delete()
                    false
                }
                else -> throw IOException("HTTP " + code)
            }
            val total = if (knownLength > 0) knownLength else conn.contentLengthLong + resumeAt
            FileOutputStream(part, append).use { out ->
                var written = resumeAt
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
                return written
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- 探测 / 校验 / 状态 ----

    /** 并行探测，先响应者胜出（其余探测协程取消）。 */
    private suspend fun probeWinner(urls: List<String>): Probe? = coroutineScope {
        val ch = Channel<Probe>(Channel.UNLIMITED)
        val jobs = urls.map { u ->
            launch(Dispatchers.IO) { runCatching { probeOne(u) }.getOrNull()?.let { ch.send(it) } }
        }
        val first = withTimeoutOrNull(probeBudgetMs) { ch.receiveCatching().getOrNull() }
        jobs.forEach { it.cancel() }
        first
    }

    private fun probeOne(url: String): Probe? = runCatching {
        val conn = open(url).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = conn.responseCode
            when {
                code == 206 -> {
                    val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    if (total > 0) Probe(url, total, true) else null
                }
                code in 200..299 -> conn.contentLengthLong.takeIf { it > 0 }?.let { Probe(url, it, false) }
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun verifySha(file: File, actual: String, expectedSha256: String?) {
        val want = expectedSha256?.trim()?.lowercase().orEmpty()
        if (want.isBlank()) return
        if (actual != want) throw IllegalStateException("sha256 校验失败：期望 " + want + "，实际 " + actual)
    }

    private fun renameInto(part: File, target: File, expectedLength: Long) {
        if (expectedLength > 0 && part.length() != expectedLength) {
            throw IOException("文件不完整：期望 " + expectedLength + " 字节，实际 " + part.length())
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun planSegments(length: Long, parts: Int): List<Seg> {
        val n = if (length <= MIN_PART) {
            1
        } else {
            minOf(parts.coerceAtLeast(1).toLong(), (length + MIN_PART - 1) / MIN_PART).toInt()
        }
        val chunk = (length + n - 1) / n
        return (0 until n).map { i ->
            val start = i * chunk
            Seg(start, minOf(start + chunk - 1, length - 1))
        }.filter { it.end >= it.start }
    }

    private fun loadState(stateFile: File, segs: List<Seg>, length: Long): Boolean {
        if (!stateFile.exists()) return false
        val lines = runCatching { stateFile.readLines() }.getOrDefault(emptyList())
        if (lines.firstOrNull() != "len=" + length) return false
        var any = false
        for (line in lines.drop(1)) {
            val p = line.split(':')
            if (p.size != 3) return false
            val s = p[0].toLongOrNull() ?: return false
            val e = p[1].toLongOrNull() ?: return false
            val d = p[2].toLongOrNull() ?: return false
            segs.firstOrNull { it.start == s && it.end == e }?.let {
                it.done = d.coerceIn(0L, it.size)
                any = any || it.done > 0
            }
        }
        return any
    }

    private fun saveState(stateFile: File, segs: List<Seg>, length: Long) {
        runCatching {
            stateFile.writeText(
                buildString {
                    append("len=").append(length).append('\n')
                    segs.forEach {
                        append(it.start).append(':').append(it.end).append(':').append(it.done).append('\n')
                    }
                }
            )
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "dsh-mobile-updater")
        setRequestProperty("Accept-Encoding", "identity")
    }

    companion object {
        /** 默认分片并发数。 */
        const val DEFAULT_CONNECTIONS = 6
        private const val BUFFER = 64 * 1024

        /** 小于该体积不切段，切了也没有并行收益。 */
        private const val MIN_PART = 512L * 1024
    }
}
