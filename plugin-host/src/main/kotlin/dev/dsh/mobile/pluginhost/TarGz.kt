package dev.dsh.mobile.pluginhost

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar 读取器。
 *
 * npm 包是 `.tgz`（gzip + ustar tar），Android 没有内置 tar，也不能保证设备上有
 * busybox/toybox 的 tar（更不该为了解包去 spawn 一个 shell）。这里只实现解包需要的
 * 那部分 ustar：name/size/typeflag/prefix 四个字段，外加跳过 pax 扩展头。
 *
 * 限制（够用即止）：不还原权限位、不还原 mtime、不支持稀疏文件。遇到不认识的
 * typeflag 就当普通文件跳过。
 */
internal object TarGz {

    /** 单包解压上限：200 MB，防止误装巨型包把手机拖死。 */
    private const val MAX_TOTAL = 200L * 1024 * 1024
    private const val MAX_ENTRIES = 6000

    /** 把 [input] 解到 [target]，每个写出的文件回调一次（相对路径, File）。 */
    fun extract(input: InputStream, target: File, onFile: (String, File) -> Unit = { _, _ -> }) {
        val root = target.canonicalFile
        // FileInputStream 不支持 mark/reset，而 [looksGzip] 要靠它探测文件头；
        // 之前直接把裸流丢进来，探测就抛 "mark/reset not supported"，整个安装失败。
        val source = if (input.markSupported()) input else java.io.BufferedInputStream(input, 64 * 1024)
        val tar = if (looksGzip(source)) GZIPInputStream(source) else source
        var total = 0L
        var entries = 0
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(tar, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = longName ?: nameOf(header)
            longName = null
            val size = sizeOf(header)
            val flag = header[156].toInt().toChar()
            val payloadSize = ((size + 511) / 512) * 512
            when (flag) {
                'x', 'g', 'L', 'K' -> {
                    // pax/GNU 扩展头：内容里藏着真实路径或更长字段，读掉但只用 'L' 的名字。
                    val body = ByteArray(size.toInt().coerceAtLeast(0))
                    readFully(tar, body)
                    skipFully(tar, payloadSize - body.size)
                    if (flag == 'L') longName = body.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                }
                '0', '\u0000', '7' -> {
                    entries++
                    require(entries <= MAX_ENTRIES) { "包内文件太多（上限 $MAX_ENTRIES）" }
                    total += size
                    require(total <= MAX_TOTAL) { "包太大（上限 200 MB）" }
                    val out = File(target, name).canonicalFile
                    // Zip-slip 同款保护：条目不许跳出 staging 目录。
                    require(out.path.startsWith(root.path)) { "包内路径越界：$name" }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { sink -> copyExactly(tar, sink, size) }
                    skipFully(tar, payloadSize - size)
                    onFile(name, out)
                }
                '5' -> skipFully(tar, payloadSize)
                else -> skipFully(tar, payloadSize)
            }
        }
    }

    private fun looksGzip(input: InputStream): Boolean {
        input.mark(4)
        val a = input.read()
        val b = input.read()
        input.reset()
        return a == 0x1f && b == 0x8b
    }

    private fun nameOf(h: ByteArray): String {
        val name = h.copyOfRange(0, 100).toString(Charsets.UTF_8).trimEnd('\u0000')
        val prefix = h.copyOfRange(345, 500).toString(Charsets.UTF_8).trimEnd('\u0000')
        val full = if (prefix.isBlank()) name else "$prefix/$name"
        return full.trimStart('/', '.')
    }

    private fun sizeOf(h: ByteArray): Long {
        // 八进制 ASCII，也可能被写成 256 进制（老 tar），两种都兼容。
        if (h[124].toInt() and 0x80 != 0) {
            var v = (h[124].toInt() and 0x7f).toLong()
            for (i in 125..135) v = (v shl 8) or (h[i].toInt() and 0xff).toLong()
            return v
        }
        val raw = h.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        return raw.toLongOrNull(8) ?: 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return off != 0
            off += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, sink: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            sink.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        if (left <= 0) return
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }
}
