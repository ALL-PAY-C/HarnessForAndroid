package dev.dsh.mobile.core.protocol.web

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class DownloadRequest(
    /** 远程地址，只支持 http/https。 */
    val url: String,
    /** 工作区内的相对目录；空串 = 工作区根。 */
    val dir: String = "",
    /** 目标文件名；空 = 由 Content-Disposition 或 URL 推断。 */
    val fileName: String? = null,
    val maxBytes: Long = 64L * 1024 * 1024,
    val timeoutMs: Long = 20_000L,
    val overwrite: Boolean = false,
)

data class DownloadOutcome(
    /** 工作区内的相对路径，正斜杠分隔。 */
    val path: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
    val finalUrl: String,
    val contentType: String,
    val elapsedMs: Long,
    /** 因同名而自动改名（file-1.ext）。 */
    val renamed: Boolean,
)

/**
 * 下载器：先写 .part 临时文件，边写边算 SHA-256，成功后原子改名到目标。
 * 超限、断网、非 2xx、改名失败都会清掉半成品并抛 [WebToolException]。
 */
class FileDownloader(private val sandbox: WorkspaceSandbox) {

    suspend fun download(
        request: DownloadRequest,
        http: HttpTransport,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): DownloadOutcome {
        val started = System.currentTimeMillis()
        val url = validateUrl(request.url)
        val dir = sandbox.resolve(request.dir)
        if (dir.exists() && !dir.isDirectory) {
            throw WebToolException(WebErrorCode.BadRequest, "目标不是目录：" + request.dir)
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw WebToolException(WebErrorCode.Network, "无法创建目录：" + request.dir)
        }
        val tmp = File(dir, ".dsh-download-" + UUID.randomUUID().toString() + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            val response = FileOutputStream(tmp).use { stream ->
                val sink = HttpSink { buffer, length ->
                    if (written + length > request.maxBytes) {
                        throw WebToolException(
                            WebErrorCode.TooLarge,
                            "文件超过上限 " + request.maxBytes + " 字节：" + url,
                        )
                    }
                    stream.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                    written += length
                    onProgress?.invoke(written, -1L)
                }
                withBudgetOrTimeout(request.timeoutMs, "下载超时（" + request.timeoutMs + "ms）：" + url) {
                    http.execute(
                        HttpRequest(
                            url = url,
                            headers = browserHeaders(),
                            timeoutMs = request.timeoutMs,
                            sink = sink,
                        ),
                    )
                }
            }
            if (response.code !in 200..299) {
                throw WebToolException(WebErrorCode.HttpStatus, "HTTP " + response.code + "：" + url)
            }
            val pick = resolveTarget(dir, request, response)
            val target = pick.first
            val replace = request.overwrite
            try {
                if (replace) {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } else {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (e: AtomicMoveNotSupportedException) {
                // 没有原子移动的文件系统：overwrite 时同样必须带上 REPLACE_EXISTING，
                // 否则目标已存在会抛 FileAlreadyExistsException，overwrite=true 实际不可用。
                if (replace) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(tmp.toPath(), target.toPath())
                }
            } catch (e: FileAlreadyExistsException) {
                throw WebToolException(WebErrorCode.BadRequest, "目标文件已存在：" + target.name)
            } catch (e: IOException) {
                throw WebToolException(WebErrorCode.Network, "写入失败：" + target.name + "：" + e.message.orEmpty())
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            onProgress?.invoke(written, written)
            return DownloadOutcome(
                path = relativeToRoot(target),
                fileName = target.name,
                bytes = written,
                sha256 = sha,
                url = url,
                finalUrl = response.finalUrl,
                contentType = response.contentType,
                elapsedMs = System.currentTimeMillis() - started,
                renamed = pick.second,
            )
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun relativeToRoot(file: File): String {
        val rootPath = sandbox.root.path
        val path = file.path
        val rel = if (path == rootPath) "" else path.removePrefix(rootPath + File.separator)
        return rel.replace(File.separatorChar, '/')
    }

    private fun resolveTarget(dir: File, request: DownloadRequest, response: HttpResponse): Pair<File, Boolean> {
        val baseName = request.fileName?.takeIf { it.isNotBlank() }
            ?: fileNameFromDisposition(response.headers["content-disposition"])
            ?: fileNameFromUrl(response.finalUrl.ifBlank { request.url })
        val clean = sanitize(baseName)
        val first = sandbox.ensureInside(File(dir, clean))
        if (request.overwrite || !first.exists()) return first to false
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 1
        while (i <= 1000) {
            val candidate = sandbox.ensureInside(File(dir, stem + "-" + i + ext))
            if (!candidate.exists()) return candidate to true
            i++
        }
        throw WebToolException(WebErrorCode.BadRequest, "同名文件过多：" + clean)
    }

    private fun sanitize(name: String): String {
        val stripped = name.replace(BAD_NAME_CHARS, "_").trim().trim('.')
        val capped = if (stripped.length > 120) stripped.substring(0, 120).trim().trim('.') else stripped
        return capped.ifBlank { "download.bin" }
    }

    private fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*=[^']*''([^;]+)").find(header)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)
        val raw = (star ?: plain)?.trim() ?: return null
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }

    private fun fileNameFromUrl(url: String): String {
        val path = try {
            URL(url).path.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return "download.bin"
        return try {
            URLDecoder.decode(last, "UTF-8")
        } catch (e: Exception) {
            last
        }
    }

    private companion object {
        val BAD_NAME_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    }
}

/** 整次操作一个预算：超时抛 Timeout，而不是把半成品当成功。 */
internal suspend fun <T> withBudgetOrTimeout(budgetMs: Long, message: String, block: suspend () -> T): T {
    val value = kotlinx.coroutines.withTimeoutOrNull(budgetMs) { block() }
    return value ?: throw WebToolException(WebErrorCode.Timeout, message)
}
