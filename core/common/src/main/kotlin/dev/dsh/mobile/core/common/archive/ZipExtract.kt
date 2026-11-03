package dev.dsh.mobile.core.common.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** 上限的人话写法（嵌套异常类型也要用，所以放文件级）。 */
private fun mb(bytes: Long): String = if (bytes >= 1024 * 1024) "${bytes / 1024 / 1024} MB" else "$bytes 字节"

/**
 * 唯一的 zip 解包入口：条目名校验（zip slip / 绝对路径 / 盘符）+ 条目数、单条目、总解压量上限。
 *
 * 之前 6 个模块各写一份 canonicalPath 前缀比较，改一处漏五处；现在只有这里一份实现。
 * 只认 zip：tar（KernelTar 的 pax 长名支持、plugin-host 的 TarGz）走各自的解析器，不收敛到这里。
 */
object ZipExtract {

    /** 解包上限。0 或负数表示不设限。 */
    data class Options(
        val maxEntries: Int = 2000,
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = 256L * 1024 * 1024,
        /** false 时目标已存在就跳过该条目（条目仍会被读完，不影响后续解析）。 */
        val overwrite: Boolean = true,
        /** 落盘后补组/其他可读位：Android 上 staging 常被别的进程读。 */
        val worldReadable: Boolean = true,
    ) {
        companion object {
            /** 只防路径穿越、不设体积上限（自己产出的包：备份快照、引擎资产）。 */
            fun pathOnly(maxEntries: Int = 100_000) = Options(maxEntries, Long.MAX_VALUE, Long.MAX_VALUE)
        }
    }

    /** 落盘结果：文件条目数与写入字节数。 */
    data class Stats(val entries: Int, val bytes: Long)

    /** 明确的失败类型；调用方按自己的语义翻成业务错误，不要只抛裸 IOException。 */
    sealed class ZipExtractException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        class IllegalEntry(val entry: String) : ZipExtractException("非法条目路径：$entry")
        class TooManyEntries(val limit: Int) : ZipExtractException("条目数超过上限 $limit")
        class EntryTooLarge(val entry: String, val limit: Long) : ZipExtractException("单条目超过上限 ${mb(limit)}：$entry")
        class TotalTooLarge(val limit: Long) : ZipExtractException("解压总量超过上限 ${mb(limit)}")
        class Corrupt(cause: Throwable) : ZipExtractException("压缩包读不出来：${cause.message ?: cause}", cause)
    }

    /**
     * 解压 [input] 到 [destDir]（目录会被创建），返回写入的文件数与字节数。
     *
     * 任何越界条目都整体失败而不是静默跳过：包内容不可信时，跳过等于让调用方拿着半包数据继续跑。
     */
    fun extract(input: InputStream, destDir: File, options: Options = Options()): Stats {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        var files = 0
        var written = 0L
        forEachEntry(input, options) { name, isDirectory, data ->
            val target = targetOf(root, name)
            if (isDirectory) {
                target.mkdirs()
                return@forEachEntry
            }
            if (options.overwrite || !target.exists()) {
                target.parentFile?.mkdirs()
                written += target.outputStream().use { data.copyTo(it) }
                files++
                if (options.worldReadable) target.setReadable(true, false)
            }
        }
        return Stats(files, written)
    }

    fun extract(zip: File, destDir: File, options: Options = Options()): Stats =
        zip.inputStream().use { extract(it, destDir, options) }

    fun extract(bytes: ByteArray, destDir: File, options: Options = Options()): Stats =
        extract(bytes.inputStream(), destDir, options)

    /**
     * 逐条遍历，回调拿到条目名（原样，不做路径判定）、是否目录、以及该条目的输入流。
     *
     * 输入流由这里负责关闭（关掉即结束当前条目），回调只读不关。需要落盘的话，路径必须先用
     * [cleanName] 归一化、再用 [isInside] 确认落在目标目录内 —— 两条都过不了就别写。
     */
    fun forEachEntry(
        input: InputStream,
        options: Options = Options(),
        onEntry: (name: String, isDirectory: Boolean, data: InputStream) -> Unit,
    ) {
        val totals = Totals()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = next(zip) ?: break
                totals.entries++
                if (options.maxEntries > 0 && totals.entries > options.maxEntries) {
                    throw ZipExtractException.TooManyEntries(options.maxEntries)
                }
                if (entry.isDirectory) {
                    onEntry(entry.name, true, EMPTY)
                } else {
                    Metered(zip, entry.name, options, totals).use { data -> onEntry(entry.name, false, data) }
                }
                zip.closeEntry()
            }
        }
    }

    /** 只读条目名，不解压、不落盘。 */
    fun names(input: InputStream, options: Options = Options()): List<String> {
        val names = ArrayList<String>()
        forEachEntry(input, options) { name, _, _ -> names += name }
        return names
    }

    fun names(bytes: ByteArray, options: Options = Options()): List<String> = names(bytes.inputStream(), options)

    fun names(zip: File, options: Options = Options()): List<String> = zip.inputStream().use { names(it, options) }

    /**
     * 条目名 → 干净的相对路径（'/' 分隔）；非法返回 null。
     *
     * 非法：空、以 '/' 开头、带盘符（`C:`）、含 '..' 段。反斜杠先归一成 '/' 再判，
     * 所以 `a\..\evil` 一样拦得住。
     */
    fun cleanName(name: String): String? {
        val clean = name.replace('\\', '/').trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("/")) return null
        if (DRIVE_LETTER.containsMatchIn(clean)) return null
        if (clean.split('/').any { it == ".." }) return null
        return clean
    }

    /** [child] 是否落在 [root] 之内（canonical 前缀比较）。 */
    fun isInside(root: File, child: File): Boolean {
        val base = root.canonicalPath
        val path = child.canonicalPath
        return path == base || path.startsWith(base + File.separator)
    }

    /** [name] 在 [root] 下的落点；越界就抛 [ZipExtractException.IllegalEntry]。 */
    fun targetOf(root: File, name: String): File {
        val clean = cleanName(name) ?: throw ZipExtractException.IllegalEntry(name)
        val target = File(root, clean)
        if (!isInside(root, target)) throw ZipExtractException.IllegalEntry(name)
        return target
    }

    private fun next(zip: ZipInputStream): ZipEntry? =
        try {
            zip.nextEntry
        } catch (e: IOException) {
            throw ZipExtractException.Corrupt(e)
        }

    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    private val EMPTY = ByteArray(0).inputStream()

    private class Totals {
        var entries = 0
        var bytes = 0L
    }

    /** 给当前条目套一层计数：超额即在读取过程中炸掉，不等到写满磁盘。 */
    private class Metered(
        private val zip: ZipInputStream,
        private val name: String,
        private val options: Options,
        private val totals: Totals,
    ) : InputStream() {
        private var own = 0L

        override fun read(): Int {
            val byte = zip.read()
            if (byte >= 0) count(1)
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = zip.read(buffer, offset, length)
            if (read > 0) count(read)
            return read
        }

        override fun available(): Int = 0

        override fun close() {
            zip.closeEntry()
        }

        private fun count(n: Int) {
            own += n
            if (options.maxEntryBytes > 0 && own > options.maxEntryBytes) {
                throw ZipExtractException.EntryTooLarge(name, options.maxEntryBytes)
            }
            totals.bytes += n
            if (options.maxTotalBytes > 0 && totals.bytes > options.maxTotalBytes) {
                throw ZipExtractException.TotalTooLarge(options.maxTotalBytes)
            }
        }
    }
}
