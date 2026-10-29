package dev.dsh.mobile.feature.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val EXPORT_MAX_HEIGHT_PX = 8_000
private const val WIDTH = 1_080
private const val PADDING = 40
private const val HEADER = 120
private const val LINE_HEIGHT = 48
internal const val EXPORT_MAX_LINES = (EXPORT_MAX_HEIGHT_PX - PADDING * 2 - HEADER) / LINE_HEIGHT

data class ExportMsg(val author: String, val body: String)
internal enum class ExportLineKind { AUTHOR, BODY, TRUNCATION }
internal data class ExportLine(val text: String, val kind: ExportLineKind = ExportLineKind.BODY)

internal fun layoutExportLines(messages: List<ExportMsg>, maxCharsPerLine: Int): List<ExportLine> {
    require(maxCharsPerLine > 0)
    if (messages.isEmpty()) return listOf(ExportLine("（空会话）"))
    val lines = mutableListOf<ExportLine>()
    for (message in messages) {
        val source = "【" + message.author.ifBlank { "未知" } + "】" + message.body
        var offset = 0
        var first = true
        do {
            val start = offset
            var count = 0
            while (offset < source.length && source[offset] != '\n' && count < maxCharsPerLine) {
                offset += Character.charCount(source.codePointAt(offset))
                count++
            }
            lines += ExportLine(source.substring(start, offset).trimEnd('\r'), if (first) ExportLineKind.AUTHOR else ExportLineKind.BODY)
            first = false
            if (lines.size > EXPORT_MAX_LINES) {
                return lines.take(EXPORT_MAX_LINES - 1) + ExportLine("…（已截断）", ExportLineKind.TRUNCATION)
            }
            if (offset < source.length && source[offset] == '\n') offset++
        } while (offset < source.length)
    }
    return lines
}

internal fun exportHeight(lineCount: Int, lineHeightPx: Int, paddingPx: Int, headerPx: Int): Int {
    require(lineCount >= 0 && lineHeightPx > 0 && paddingPx >= 0 && headerPx >= 0)
    return (headerPx.toLong() + paddingPx.toLong() * 2 + lineCount.toLong() * lineHeightPx)
        .coerceIn(1, EXPORT_MAX_HEIGHT_PX.toLong()).toInt()
}

suspend fun exportChatToPng(context: Context, title: String, messages: List<ExportMsg>): Uri? = withContext(Dispatchers.IO) {
    var bitmap: Bitmap? = null
    try {
        val lines = layoutExportLines(messages, 24)
        val image = Bitmap.createBitmap(WIDTH, exportHeight(lines.size, LINE_HEIGHT, PADDING, HEADER), Bitmap.Config.ARGB_8888)
        bitmap = image
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 40); textSize = 42f; typeface = Typeface.DEFAULT_BOLD }
        val heading = title.ifBlank { "会话" }.replace('\n', ' ')
        val headingLength = paint.breakText(heading, true, (WIDTH - PADDING * 2).toFloat(), null)
        canvas.drawText(heading.take(headingLength), PADDING.toFloat(), 72f, paint)
        paint.textSize = 38f
        lines.forEachIndexed { index, line ->
            paint.typeface = if (line.kind == ExportLineKind.AUTHOR) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (line.kind == ExportLineKind.TRUNCATION) Color.GRAY else Color.rgb(35, 35, 40)
            canvas.drawText(line.text, PADDING.toFloat(), (PADDING + HEADER + index * LINE_HEIGHT - paint.fontMetrics.top), paint)
        }
        val name = "DSH_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8) + ".png"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DSH")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("创建相册文件失败")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("打开相册文件失败")
                output.use { if (!image.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG 编码失败") }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) <= 0) throw IOException("发布相册文件失败")
                uri
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DSH")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("创建 Pictures/DSH 失败")
            val file = File(dir, name)
            try {
                file.outputStream().use { if (!image.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG 编码失败") }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                Uri.fromFile(file)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("ChatExport", "PNG export failed", error)
        throw error
    } finally {
        bitmap?.recycle()
    }
}
