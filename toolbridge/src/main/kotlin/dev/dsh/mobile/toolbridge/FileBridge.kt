package dev.dsh.mobile.toolbridge

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** 文件工具的错误码。模型靠 code 决定下一步，不靠猜中文文案。 */
object FsCode {
    const val PERMISSION_DENIED = "permission_denied"
    const val NOT_FOUND = "not_found"
    const val INVALID_ARGUMENT = "invalid_argument"
    const val CONTENT_TOO_LARGE = "content_too_large"
    const val UNSUPPORTED_OP = "unsupported_op"
    const val IO_ERROR = "io_error"
}

/**
 * 以 App 自身 uid 直接读写文件，绕开 proot 的路径重写。
 *
 * 为什么需要它：proot 用 ptrace 重写路径，`mount --bind` 在沙箱里静默失败，
 * 于是沙箱内看不到 /sdcard。而 App 进程本身带着 3003(sdcard_rw) 组，
 * 直接走 java.io 就能读写 —— 所以文件工具不该进沙箱。
 *
 * 两个硬约束：
 * - **路径白名单 + 真实路径复检**：先做纯字符串折叠（`..` 逃逸直接拒），
 *   再看 `canonicalPath`；符号链接指到白名单外同样拒。宁可多拒，不能静默放行。
 * - **单次 64MB 上限**：超限返回 `content_too_large`，不抛异常 —— 抛异常会变成
 *   一条模型读不懂的堆栈，正确行为是让模型自己分段。
 *
 * 纯 JVM 实现（不 import android.*），Android 相关的权限探测由调用方以
 * [storageAccess] lambda 注入，这样 JVM 单测不需要桩。
 */
class FileBridge(
    roots: List<String> = DEFAULT_ROOTS,
    private val maxBytes: Long = MAX_BYTES,
    private val storageAccess: () -> Boolean = { false },
) {

    /** 一个白名单根：specKey 用于字符串折叠后的初筛，canonKey 用于真实路径复检。 */
    private class Root(spec: String) {
        val specKey: String = key(File(spec).absolutePath)
        val canonKey: String = key(runCatching { File(spec).canonicalPath }.getOrDefault(File(spec).absolutePath))
    }

    private val roots: List<Root> = roots.filter { it.isNotBlank() }.map { Root(it) }

    /** 上层据此引导用户去系统设置授权；本模块只回答「现在有没有」，不做 UI。 */
    fun hasFullFileAccess(): Boolean = runCatching { storageAccess() }.getOrDefault(false)

    /** 白名单根（绝对路径），便于上层在提示里写清可访问范围。 */
    fun roots(): List<String> = roots.map { it.specKey }

    fun read(path: String, offset: Long? = null, limit: Int? = null): String {
        val file = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (!file.exists()) return fail(FsCode.NOT_FOUND, "文件不存在：$path")
        if (file.isDirectory) return fail(FsCode.INVALID_ARGUMENT, "目标是目录，请用 fs_list：$path")

        val total = file.length()
        val start = offset ?: 0L
        if (start < 0) return fail(FsCode.INVALID_ARGUMENT, "offset 不能为负：$start")
        if (start > total) return fail(FsCode.INVALID_ARGUMENT, "offset $start 超出文件大小 $total")
        val span = if (limit != null && limit > 0) minOf(limit.toLong(), total - start) else total - start
        if (span > maxBytes) return fail(FsCode.CONTENT_TOO_LARGE, tooLarge("读取"))

        val bytes = try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                ByteArray(span.toInt()).also { raf.readFully(it) }
            }
        } catch (e: IOException) {
            return fail(FsCode.IO_ERROR, "读取失败：${e.message}")
        }
        return MiniJson.obj(
            "ok" to true,
            "path" to file.absolutePath,
            "size" to total,
            "offset" to start,
            "length" to bytes.size,
            "truncated" to (start + bytes.size < total),
            "content" to String(bytes, Charsets.UTF_8),
        )
    }

    fun write(path: String, content: String, append: Boolean = false): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return fail(FsCode.CONTENT_TOO_LARGE, tooLarge("写入"))

        val file = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (file.isDirectory) return fail(FsCode.INVALID_ARGUMENT, "目标是目录：$path")
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return fail(FsCode.IO_ERROR, "无法创建父目录：${parent.absolutePath}")
        }
        try {
            if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
        } catch (e: IOException) {
            return fail(FsCode.IO_ERROR, "写入失败：${e.message}")
        }
        return MiniJson.obj(
            "ok" to true,
            "path" to file.absolutePath,
            "bytes" to bytes.size,
            "size" to file.length(),
            "append" to append,
        )
    }

    fun list(path: String, recursive: Boolean = false): String {
        val dir = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (!dir.exists()) return fail(FsCode.NOT_FOUND, "目录不存在：$path")
        if (!dir.isDirectory) return fail(FsCode.INVALID_ARGUMENT, "不是目录，请用 fs_read：$path")

        val items = mutableListOf<String>()
        var truncated = false
        val children = if (recursive) {
            dir.walkTopDown().drop(1)
        } else {
            dir.listFiles()?.sortedBy { it.name }?.asSequence() ?: emptySequence()
        }
        for (f in children.take(MAX_ENTRIES + 1)) {
            // 多取一条只为了判断「还有更多」，不把整棵树都走完。
            if (items.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val rel = f.absolutePath.removePrefix(dir.absolutePath).trimStart(File.separatorChar, '/')
            items += MiniJson.obj(
                "name" to rel,
                "path" to f.absolutePath,
                "dir" to f.isDirectory,
                "size" to if (f.isFile) f.length() else 0L,
                "mtime" to f.lastModified(),
            )
        }
        return MiniJson.obj(
            "ok" to true,
            "path" to dir.absolutePath,
            "recursive" to recursive,
            "count" to items.size,
            "truncated" to truncated,
            "entries" to MiniJson.Raw(MiniJson.arr(items)),
        )
    }

    fun op(op: String, src: String, dst: String? = null): String = when (op.trim().lowercase()) {
        "mkdir" -> mkdir(src)
        "stat" -> stat(src)
        "delete" -> delete(src)
        "move" -> if (dst == null) fail(FsCode.INVALID_ARGUMENT, "move 需要 dst") else transfer(src, dst, move = true)
        "copy" -> if (dst == null) fail(FsCode.INVALID_ARGUMENT, "copy 需要 dst") else transfer(src, dst, move = false)
        else -> fail(FsCode.UNSUPPORTED_OP, "不支持的操作：$op（可选 move/copy/delete/mkdir/stat）")
    }

    private fun mkdir(path: String): String {
        val dir = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (dir.isDirectory) return MiniJson.obj("ok" to true, "path" to dir.absolutePath, "created" to false)
        if (dir.exists()) return fail(FsCode.INVALID_ARGUMENT, "同名文件已存在：$path")
        if (!dir.mkdirs()) return fail(FsCode.IO_ERROR, "创建目录失败：$path")
        return MiniJson.obj("ok" to true, "path" to dir.absolutePath, "created" to true)
    }

    private fun stat(path: String): String {
        val file = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (!file.exists()) {
            // stat 的「不存在」是有效答案而不是错误，但仍带 code，避免调用方猜。
            return MiniJson.obj(
                "ok" to false,
                "code" to FsCode.NOT_FOUND,
                "error" to "路径不存在：$path",
                "path" to file.absolutePath,
                "exists" to false,
            )
        }
        return MiniJson.obj(
            "ok" to true,
            "path" to file.absolutePath,
            "exists" to true,
            "dir" to file.isDirectory,
            "size" to file.length(),
            "mtime" to file.lastModified(),
            "readable" to file.canRead(),
            "writable" to file.canWrite(),
        )
    }

    private fun delete(path: String): String {
        val (file, rootKey) = when (val c = check(path)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file to c.rootKey
        }
        // 删白名单根本身等于自毁权限范围（比如 /sdcard 整个删掉），一律拒绝。
        if (key(file.absolutePath) == rootKey) return fail(FsCode.INVALID_ARGUMENT, "拒绝删除白名单根目录：$path")
        if (!file.exists()) return fail(FsCode.NOT_FOUND, "路径不存在：$path")
        val removed = file.deleteRecursively()
        if (!removed) return fail(FsCode.IO_ERROR, "删除失败：$path")
        return MiniJson.obj("ok" to true, "path" to file.absolutePath, "removed" to true)
    }

    private fun transfer(src: String, dst: String, move: Boolean): String {
        val from = when (val c = check(src)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        val to = when (val c = check(dst)) {
            is Check.Denied -> return fail(c.code, c.reason)
            is Check.Ok -> c.file
        }
        if (!from.exists()) return fail(FsCode.NOT_FOUND, "源不存在：$src")
        if (from.isDirectory) return fail(FsCode.INVALID_ARGUMENT, "暂不支持目录的 move/copy：$src")
        if (from.length() > maxBytes) return fail(FsCode.CONTENT_TOO_LARGE, tooLarge(if (move) "移动" else "复制"))
        if (to.isDirectory) return fail(FsCode.INVALID_ARGUMENT, "目标是目录：$dst")
        val parent = to.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return fail(FsCode.IO_ERROR, "无法创建父目录：${parent.absolutePath}")
        }
        val done = try {
            if (move) {
                // renameTo 在同一文件系统内是原子的；跨卷（internal -> sdcard）会失败，退回「复制后删」。
                from.renameTo(to) || (from.copyTo(to, overwrite = true).exists() && from.delete())
            } else {
                from.copyTo(to, overwrite = true).exists()
            }
        } catch (e: IOException) {
            return fail(FsCode.IO_ERROR, "${if (move) "移动" else "复制"}失败：${e.message}")
        }
        if (!done) return fail(FsCode.IO_ERROR, "${if (move) "移动" else "复制"}失败：$src -> $dst")
        return MiniJson.obj(
            "ok" to true,
            "op" to if (move) "move" else "copy",
            "src" to from.absolutePath,
            "dst" to to.absolutePath,
            "size" to to.length(),
        )
    }

    // —— 路径校验 ——

    private sealed interface Check {
        data class Ok(val file: File, val rootKey: String) : Check
        data class Denied(val code: String, val reason: String) : Check
    }

    private fun check(raw: String): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Denied(FsCode.INVALID_ARGUMENT, "路径为空")
        if (text.contains('\u0000')) return Check.Denied(FsCode.INVALID_ARGUMENT, "路径含非法字符")

        // 先做纯字符串折叠：`..` 在 canonicalPath 之前就被拦掉，不给它「先出去再解析」的机会。
        val normalized = lexical(text)
            ?: return Check.Denied(FsCode.PERMISSION_DENIED, "路径越权：$raw（必须绝对路径，且不允许用 .. 走出白名单）")
        val root = roots.firstOrNull { under(normalized, it.specKey) }
            ?: return Check.Denied(
                FsCode.PERMISSION_DENIED,
                "路径不在可访问范围：$raw（允许 ${roots.joinToString("、") { it.specKey }}）",
            )

        // 再复检真实路径：符号链接指向白名单外时，canonicalPath 会露出来。
        val real = try {
            File(normalized).canonicalPath
        } catch (e: IOException) {
            return Check.Denied(FsCode.IO_ERROR, "无法解析真实路径：$raw（${e.message}）")
        }
        if (!under(real, root.canonKey)) {
            return Check.Denied(FsCode.PERMISSION_DENIED, "符号链接指向白名单外：$raw -> $real")
        }
        return Check.Ok(File(real), root.specKey)
    }

    private fun fail(code: String, message: String): String =
        MiniJson.obj("ok" to false, "code" to code, "error" to message)

    private fun tooLarge(action: String): String =
        "内容过大，请分段${if (action == "读取") "读取" else "写入"}（单次上限 ${maxBytes / 1024 / 1024}MB）"

    companion object {
        /** 单次读写上限 64MB。 */
        const val MAX_BYTES: Long = 64L * 1024 * 1024

        /** 目录列举一次最多返回多少条，防止一次 ls 把上下文灌爆。 */
        const val MAX_ENTRIES: Int = 1000

        /**
         * 白名单：外部存储三处别名 + 本应用私有目录。
         *
         * 私有目录两个别名都要留：拿到的路径可能来自 `context.filesDir`（/data/user/0/...），
         * 也可能来自硬编码或日志（/data/data/...），后者在多数设备上只是前者的符号链接，
         * 两侧都放行才不会让同一个目录因为写法不同被判越权。
         */
        val DEFAULT_ROOTS: List<String> = listOf(
            "/sdcard",
            "/storage/emulated/0",
            "/data/media/0",
            "/data/data/dev.dsh.mobile",
            "/data/user/0/dev.dsh.mobile",
        )

        /** 统一成「斜杠 + 去掉尾部分隔符」再比较，Android/Windows 下都不会因分隔符误判。 */
        internal fun key(path: String): String = path.replace('\\', '/').trimEnd('/')

        internal fun under(path: String, root: String): Boolean {
            val p = key(path)
            val r = key(root)
            return p == r || p.startsWith("$r/")
        }

        /**
         * 纯字符串折叠：消掉 `.`、`..` 与重复分隔符。返回 null 表示越权：
         * 相对路径（会以进程 CWD 为基准，白名单判断没有意义）或 `..` 走出根。
         *
         * 只按 `/` 切：Android 上反斜杠是合法文件名字符，不能当分隔符。
         */
        internal fun lexical(path: String): String? {
            if (!path.startsWith("/") && !File(path).isAbsolute) return null
            val drive = if (path.length >= 2 && path[1] == ':') path.substring(0, 2) else ""
            val stack = ArrayDeque<String>()
            for (seg in path.removePrefix(drive).split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                    else -> stack.addLast(seg)
                }
            }
            return drive + "/" + stack.joinToString("/")
        }
    }
}

/**
 * Android 侧的「是否有全盘文件访问权限」探测。
 *
 * 单独一个文件、单独一个对象：FileBridge 是纯 JVM 的，这里才 import android.*，
 * 免得 JVM 单测为了跑白名单逻辑还得给 Environment 打桩。
 */
object AppStorage {

    /** Android 11+ 才有 MANAGE_EXTERNAL_STORAGE 对应的这个查询；更低版本交给上层按运行时权限判断。 */
    fun hasFullFileAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            true
        }

    /** 生产形态的 FileBridge：权限查询接到真实系统 API 上。 */
    fun fileBridge(extraRoots: List<String> = emptyList()): FileBridge = FileBridge(
        roots = FileBridge.DEFAULT_ROOTS + extraRoots,
        storageAccess = { hasFullFileAccess() },
    )
}
