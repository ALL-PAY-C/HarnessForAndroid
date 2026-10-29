package dev.dsh.mobile.linux

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class LinuxTarException(message: String) : Exception(message)

/**
 * 最小 ustar 解包器（不引第三方依赖）。
 *
 * 安全边界是硬要求：条目名不许绝对、不许 ..、链接目标不许逃出 rootfs。
 * 绝对符号链接（Alpine 里 /bin/sh -> /bin/busybox 这类）是 guest 内路径，
 * 这里改写成 rootfs 内的相对链接，既保留语义又不会指到宿主。
 */
object LinuxTar {

    fun extract(tarGz: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        GZIPInputStream(tarGz.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(input, header, 512)) return
                if (header.all { it == 0.toByte() }) return
                val shortName = cstr(header, 0, 100)
                if (shortName.isEmpty()) return
                val prefix = if (cstr(header, 257, 6) == "ustar") cstr(header, 345, 155) else ""
                if (prefix.isNotEmpty() && (shortName.startsWith("/") || shortName.startsWith("\\"))) {
                    throw LinuxTarException("unsafe path: absolute entry $shortName")
                }
                val name = if (prefix.isEmpty()) shortName else "$prefix/$shortName"
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8)
                val type = header[156].toInt().toChar()
                val link = cstr(header, 157, 100)
                val target = resolve(root, name)
                when (type) {
                    '5' -> {
                        if (!target.mkdirs() && !target.isDirectory) throw LinuxTarException("unsafe path: cannot create ${target.path}")
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> copyExactly(input, out, size) }
                        applyMode(target, mode)
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = resolveLink(root, target, link)
                        val relative = target.parentFile!!.canonicalFile.toPath().relativize(linkTarget.toPath()).toString()
                        runCatching { Files.createSymbolicLink(target.toPath(), File(relative).toPath()) }
                            .getOrElse { throw LinuxTarException("unsafe path: symlink ${target.path} -> $link") }
                    }
                    '1' -> {
                        val linkTarget = resolveLink(root, target, link)
                        if (linkTarget.isFile) {
                            target.parentFile?.mkdirs()
                            linkTarget.copyTo(target, overwrite = true)
                            if (linkTarget.canExecute()) target.setExecutable(true, false)
                        }
                    }
                    'L', 'K', 'x', 'g' -> throw LinuxTarException("unsupported tar long-name extension: $type")
                    else -> skip(input, size)
                }
                skipPadding(input, size)
            }
        }
    }

    private fun resolve(root: File, name: String): File {
        if (name.startsWith("/") || name.startsWith("\\")) throw LinuxTarException("unsafe path: absolute entry $name")
        val candidate = File(root, name).canonicalFile
        if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
            throw LinuxTarException("unsafe path: escapes rootfs: $name")
        }
        return candidate
    }

    private fun resolveLink(root: File, entry: File, link: String): File {
        if (link.isEmpty()) throw LinuxTarException("unsafe path: empty link at ${entry.path}")
        val base = if (link.startsWith("/")) root else entry.parentFile!!
        val guest = if (link.startsWith("/")) File(root, link.trimStart('/')) else File(base, link)
        val canonical = guest.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw LinuxTarException("unsafe path: link escapes rootfs: $link")
        }
        return canonical
    }

    /** Java/Android 的 FileOutputStream 默认建 0644；tar 里的执行位必须显式补。 */
    private fun applyMode(file: File, mode: Long) {
        if ((mode and 0b001_001_001L) != 0L) file.setExecutable(true, false)
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var value = 0L
        for (i in off until off + len) {
            val c = b[i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) continue
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
        }
        return value
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw LinuxTarException("truncated archive")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) skip(input, pad.toLong())
    }
}
