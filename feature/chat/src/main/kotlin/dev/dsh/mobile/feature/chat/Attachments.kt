package dev.dsh.mobile.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 把用户选中的文件/图片/照片复制进应用私有目录，返回可读路径。
 * Agent 通过 shell 工具直接读这个路径，所以必须落在应用自己的 filesDir 下。
 */
object Attachments {

    private const val DIR = "attachments"

    fun copy(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val raw = displayName(context, uri) ?: "attachment"
        val safe = raw.replace(Regex("[^A-Za-z0-9._\\-\u4e00-\u9fa5]"), "_").takeLast(64)
        // 同一个 uri 复用已落盘的那份：重选同一张图不再复制第二份。
        val key = Integer.toHexString(uri.toString().hashCode())
        val out = File(dir, "$key-$safe")
        if (out.exists() && out.length() > 0L) return@runCatching out
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()

    /** 相对 filesDir 的路径（如 `attachments/1a2b-IMG_001.jpg`），存进 draft 状态用。 */
    fun rel(context: Context, file: File): String = runCatching {
        file.relativeTo(context.filesDir).path.replace('\\', '/')
    }.getOrElse { file.name }

    /** 相对路径还原成绝对路径：Agent 的 shell 工具只认绝对路径。 */
    fun abs(context: Context, rel: String): String =
        if (rel.startsWith("/") || rel.contains(":")) rel
        else File(context.filesDir, rel).absolutePath

    /** 气泡里那行小字用的文件名。 */
    fun label(rel: String): String = rel.substringAfterLast('/').substringAfterLast('\\')

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
