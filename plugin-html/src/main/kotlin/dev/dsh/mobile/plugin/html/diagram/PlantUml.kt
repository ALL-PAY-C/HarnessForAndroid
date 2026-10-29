package dev.dsh.mobile.plugin.html.diagram

import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.Deflater

/** 联网类 kind 的异步状态；Idle 之外都只是给 UI 换一行提示。 */
internal sealed interface DiagramRemote {
    data object Idle : DiagramRemote
    data object Pending : DiagramRemote
    data class Ok(val svg: String) : DiagramRemote
    data class Err(val reason: String) : DiagramRemote
}

/**
 * PlantUML 走 kroki.io：URL 里带 deflate + base64url 之后的源码，服务端直接回 SVG。
 *
 * 编码是「zlib 格式 deflate + base64url（无填充）」：kroki 服务端用 Inflater 的带 header 模式解压，
 * 给 raw deflate（nowrap = true）会被解成乱码，服务端回 400 "Assumed diagram type: sequence"。
 * 实测 2026-10：Deflater(x, false) → 200；Deflater(x, true) → 400。
 * 失败一律降级成源码卡（见 DiagramView），不抛异常、不白屏。
 */
internal object PlantUml {

    private const val BASE = "https://kroki.io/plantuml/svg/"

    /** 4s 超时：卡片预览等不起更久，超时就退回源码。 */
    private const val TIMEOUT_MS = 4000

    fun encode(source: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        deflater.setInput(source.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream(source.length / 2 + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun url(source: String): String = BASE + encode(source)

    suspend fun load(source: String): DiagramRemote = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url(source)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "image/svg+xml")
            }
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) return@withContext DiagramRemote.Err("HTTP " + code)
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (!body.contains("<svg")) return@withContext DiagramRemote.Err("返回内容不是 SVG")
                DiagramRemote.Ok(body)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            AppLog.w("dsh.diagram: plantuml 渲染失败", t)
            DiagramRemote.Err((t.message ?: t.javaClass.simpleName).take(120))
        }
    }
}
