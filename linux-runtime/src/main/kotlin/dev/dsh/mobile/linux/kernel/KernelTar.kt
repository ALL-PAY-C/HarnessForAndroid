package dev.dsh.mobile.linux.kernel

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class KernelTarException(message: String) : Exception(message)

/**
 * 带 pax / GNU 长名支持的 tar.gz 解包器，只给 Node 运行时用。
 *
 * 为什么不用 [dev.dsh.mobile.linux.LinuxTar]：官方 node-*.tar.xz 里 npm 的路径超过 100 字节，
 * tar 用 pax 扩展头（'x'）承载真实路径；LinuxTar 只读 header 的 name 字段（无 prefix、无 pax），
 * 解出来会截断重名。这里补上 pax('x'/'g')、GNU 长名('L'/'K') 与 ustar prefix，
 * 并保留 LinuxTar 的安全边界：条目名不许绝对、不许 ..、链接不许逃出目标目录。
 * 同时按 header 的 mode 设置可执行位 —— node 二进制必须能 exec。
 */
object KernelTar {

    fun extract(tarGz: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        GZIPInputStream(tarGz.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            var paxPath: String? = null
            var paxLink: String? = null
            var longName: String? = null
            var longLink: String? = null
            while (true) {
                if (!readFully(input, header, 512)) return
                if (header.all { it == 0.toByte() }) return
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                when (type) {
                    'x', 'X' -> {
                        val payload = readPayload(input, size)
                        paxPath = paxValue(payload, "path") ?: paxPath
                        paxLink = paxValue(payload, "linkpath") ?: paxLink
                        continue
                    }
                    'g' -> {
                        skip(input, size)
                        skipPadding(input, size)
                        continue
                    }
                    'L' -> {
                        longName = readPayload(input, size).trimEnd('\u0000')
                        continue
                    }
                    'K' -> {
                        longLink = readPayload(input, size).trimEnd('\u0000')
                        continue
                    }
                }

                val rawName = cstr(header, 0, 100)
                val prefix = cstr(header, 345, 155)
                val mode = octal(header, 100, 8)
                val name = longName ?: paxPath ?: if (prefix.isEmpty()) rawName else prefix + "/" + rawName
                val link = longLink ?: paxLink ?: cstr(header, 157, 100)
                longName = null
                longLink = null
                paxPath = null
                paxLink = null
                if (name.isEmpty()) return

                val target = resolve(root, name)
                when (type) {
                    '5' -> {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw KernelTarException("cannot create directory: " + target.path)
                        }
                    }
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> copyExactly(input, out, size) }
                        applyMode(target, mode)
                        skipPadding(input, size)
                        continue
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = resolveLink(root, target, link)
                        val relative = target.parentFile!!.canonicalFile.toPath()
                            .relativize(linkTarget.toPath()).toString()
                        target.delete()
                        val linked = runCatching {
                            Files.createSymbolicLink(target.toPath(), File(relative).toPath())
                        }.isSuccess
                        // Windows 上非管理员/未开开发者模式建不了符号链接：退回实体拷贝，
                        // 内容一致即可（bin/npm 这类软链在 guest 里本来就按实体文件跑）。
                        if (!linked && linkTarget.isFile) linkTarget.copyTo(target, overwrite = true)
                        if (!linked && linkTarget.isDirectory) target.mkdirs()
                    }
                    '1' -> {
                        val linkTarget = resolveLink(root, target, link)
                        if (linkTarget.isFile) {
                            target.parentFile?.mkdirs()
                            linkTarget.copyTo(target, overwrite = true)
                            applyMode(target, mode)
                        }
                    }
                    else -> skip(input, size)
                }
                skipPadding(input, size)
            }
        }
    }

    private fun applyMode(target: File, mode: Long) {
        if (mode and 0b001_000_000L != 0L) {
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }

    private fun resolve(root: File, name: String): File {
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw KernelTarException("absolute entry: " + name)
        }
        val candidate = File(root, name).canonicalFile
        if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
            throw KernelTarException("escapes root: " + name)
        }
        return candidate
    }

    private fun resolveLink(root: File, entry: File, link: String): File {
        if (link.isEmpty()) throw KernelTarException("empty link at " + entry.path)
        val guest = if (link.startsWith("/")) File(root, link.trimStart('/')) else File(entry.parentFile!!, link)
        val canonical = guest.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw KernelTarException("link escapes root: " + link)
        }
        return canonical
    }

    /** pax 扩展头是一串 "len key=value\n"，只取 path / linkpath。 */
    private fun paxValue(payload: String, key: String): String? {
        var index = 0
        while (index < payload.length) {
            val space = payload.indexOf(' ', index)
            if (space < 0) return null
            val length = payload.substring(index, space).toIntOrNull() ?: return null
            if (length <= 0) return null
            val end = minOf(index + length, payload.length)
            val record = payload.substring(space + 1, end).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == key) return record.substring(eq + 1)
            index = end
        }
        return null
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

    private fun readPayload(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw KernelTarException("truncated archive")
            read += n
        }
        skipPadding(input, size)
        return String(bytes, Charsets.UTF_8)
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
            if (n < 0) throw KernelTarException("truncated archive")
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
