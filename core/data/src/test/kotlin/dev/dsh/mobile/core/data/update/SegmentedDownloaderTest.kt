package dev.dsh.mobile.core.data.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地测试用的 Range HTTP 服务（裸 socket 实现，只依赖 java.base）。
 *
 * - 支持 `Range: bytes=a-b`，回 206 + Content-Range；无 Range 回 200 全量；
 * - [ranges] 记录收到的 Range 头，[maxConcurrent] 记录同时处理的连接数峰值；
 * - [truncateAfter] >= 0 时每个响应只发这么多字节就断连，模拟「坏镜像」。
 */
private class RangeServer(
    private val payload: ByteArray,
    private val handlerDelayMs: Long = 0,
    private val truncateAfter: Long = -1,
) : AutoCloseable {

    private val listener = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val inFlight = AtomicInteger()

    @Volatile
    private var running = true

    val ranges = ConcurrentLinkedQueue<String>()
    val maxConcurrent = AtomicInteger()

    init {
        pool.execute { acceptLoop() }
    }

    val url: String get() = "http://127.0.0.1:" + listener.localPort + "/apk"

    private fun acceptLoop() {
        while (running) {
            val sock = try {
                listener.accept()
            } catch (e: SocketException) {
                break
            }
            pool.execute { serve(sock) }
        }
    }

    private fun serve(sock: Socket) {
        val cur = inFlight.incrementAndGet()
        maxConcurrent.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
        try {
            sock.use { s ->
                s.soTimeout = 5_000
                val input = s.getInputStream().buffered()
                val range = readHeaders(input)["range"].orEmpty()
                ranges.add(range)
                if (handlerDelayMs > 0) Thread.sleep(handlerDelayMs)
                var start = 0L
                var end = payload.size - 1L
                val m = RANGE.find(range)
                if (m != null) {
                    start = m.groupValues[1].toLong()
                    if (m.groupValues[2].isNotBlank()) end = m.groupValues[2].toLong()
                }
                end = minOf(end, payload.size - 1L)
                val len = (end - start + 1).toInt()
                val head = buildString {
                    append("HTTP/1.1 ").append(if (m != null) "206 Partial Content" else "200 OK").append("\r\n")
                    append("Content-Length: ").append(len).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (m != null) append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(payload.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = s.getOutputStream()
                out.write(head.toByteArray())
                val send = if (truncateAfter >= 0) minOf(len.toLong(), truncateAfter).toInt() else len
                out.write(payload, start.toInt(), send)
                out.flush()
            }
        } catch (e: Exception) {
            // 坏镜像：写完短包就关连接，客户端必然读不满
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun readHeaders(input: java.io.InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
                continue
            }
            if (sb.length < 8192) sb.append(c.toChar())
        }
        return headers
    }

    override fun close() {
        running = false
        runCatching { listener.close() }
        pool.shutdownNow()
    }

    companion object {
        private val RANGE = Regex("bytes=(\\d+)-(\\d*)")
    }
}

private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "dsh-dl-" + UUID.randomUUID()).apply {
    deleteRecursively()
    mkdirs()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun rangeStart(header: String): Long? = Regex("bytes=(\\d+)-").find(header)?.groupValues?.get(1)?.toLongOrNull()

class SegmentedDownloaderTest {

    /** 6 条 Range 连接并发拉完，拼出的字节与 sha256 必须与原文件一致，进度单调收敛到 100。 */
    @Test
    fun segmentsReassembleBytesAndReachConfiguredConcurrency() {
        val payload = ByteArray(4 * 1024 * 1024).also { Random(7).nextBytes(it) }
        val expect = sha256(payload)
        RangeServer(payload, handlerDelayMs = 40).use { srv ->
            val dir = tempDir()
            val target = File(dir, "dsh.apk")
            val progress = ArrayList<Int>()
            val outcome = runBlocking {
                SegmentedDownloader(connections = 6)
                    .download(listOf(srv.url), target, expectedSha256 = expect) { p ->
                        synchronized(progress) { progress.add(p) }
                    }
            }
            assertArrayEquals(payload, target.readBytes())
            assertEquals(expect, outcome.sha256)
            assertEquals(expect, sha256(target.readBytes()))
            assertEquals(payload.size.toLong(), target.length())
            assertTrue("并发峰值应 >= 6，实际 " + srv.maxConcurrent.get(), srv.maxConcurrent.get() >= 6)
            assertTrue("应发出 >= 6 个带 Range 的请求，实际 " + srv.ranges.size, srv.ranges.count { it.isNotEmpty() } >= 6)
            assertEquals(100, progress.last())
            assertTrue("进度应单调递增：" + progress, progress.zipWithNext().all { (a, b) -> b >= a })
            assertFalse(File(dir, "dsh.apk.part").exists())
            assertFalse(File(dir, "dsh.apk.part.seg").exists())
        }
    }

    /** 主镜像（探测先返回的那个）中途断流 → 换镜像并从段内已写偏移续传，最终文件仍完整。 */
    @Test
    fun brokenMirrorIsDroppedAndDownloadResumesFromOffset() {
        val payload = ByteArray(3 * 1024 * 1024).also { Random(11).nextBytes(it) }
        val expect = sha256(payload)
        // 坏镜像不延迟 → 探测先返回成为主镜像；好镜像慢 150ms，只作兜底。
        RangeServer(payload, truncateAfter = 64 * 1024).use { broken ->
            RangeServer(payload, handlerDelayMs = 150).use { good ->
                val dir = tempDir()
                val target = File(dir, "dsh.apk")
                val outcome = runBlocking {
                    SegmentedDownloader(connections = 4, probeBudgetMs = 2_000)
                        .download(listOf(broken.url, good.url), target)
                }
                assertArrayEquals(payload, target.readBytes())
                assertEquals(expect, outcome.sha256)
                val resumed = good.ranges.mapNotNull { rangeStart(it) }.any { it > 0 }
                assertTrue("好镜像应收到非 0 起点的 Range（证明续传而非重下）：" + good.ranges, resumed)
                assertTrue("坏镜像应被尝试过：" + broken.ranges, broken.ranges.isNotEmpty())
            }
        }
    }

    /** 清单给了 sha256 但内容不符 → 直接失败，不把坏包交给安装器。 */
    @Test
    fun wrongSha256Fails() {
        val payload = ByteArray(600 * 1024).also { Random(3).nextBytes(it) }
        RangeServer(payload).use { srv ->
            val target = File(tempDir(), "dsh.apk")
            val e = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    SegmentedDownloader(connections = 2).download(listOf(srv.url), target, expectedSha256 = "deadbeef")
                }
            }
            assertTrue("错误信息应提到 sha256：" + e.message, e.message.orEmpty().contains("sha256"))
        }
    }
}
