package dev.dsh.mobile.core.common.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset

/**
 * 非 2xx 响应。status 是 HTTP 码，body 是错误流原文（服务端没给错误体时为 null）。
 *
 * 分开保留 status 与 body，是因为调用方既要按码分支（401 与 500 的处理不同），
 * 又要能把服务端原文截一段显示给用户。
 */
class HttpJsonException(
    val status: Int,
    message: String,
    val body: String?,
) : IOException(message)

/**
 * 超时。继承 SocketTimeoutException 而不是另立类型：既有调用方用 `is SocketTimeoutException`
 * 把超时与其它 IO 失败分开处理，换类型会让那些分支静默失效。
 */
class HttpJsonTimeoutException(message: String) : SocketTimeoutException(message)

/**
 * 最小 HTTP 门面：发一个请求，拿回响应体文本。
 *
 * 放在 core/common 是因为 data 层原来有三处各自拷贝的连接配置（超时、header、错误流处理），
 * 口径改一次要改三遍，而且都用 `bufferedReader()` 读——非 UTF-8 响应会乱码。
 */
object HttpJson {

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        readTimeoutMs: Int = timeoutMs,
    ): String = withContext(Dispatchers.IO) { getTextBlocking(url, headers, timeoutMs, readTimeoutMs) }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        readTimeoutMs: Int = timeoutMs,
    ): String = withContext(Dispatchers.IO) {
        postJsonBlocking(url, body, headers, timeoutMs, readTimeoutMs)
    }

    /**
     * 同步版。ModelProbe.probeBlocking 是同步 API，在它内部再套一层 withContext 只会自找调度麻烦。
     */
    fun getTextBlocking(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        readTimeoutMs: Int = timeoutMs,
    ): String = execute("GET", url, headers, timeoutMs, readTimeoutMs, null)

    fun postJsonBlocking(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
        readTimeoutMs: Int = timeoutMs,
    ): String {
        val withType = if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) headers
        else headers + ("Content-Type" to "application/json")
        return execute("POST", url, withType, timeoutMs, readTimeoutMs, body)
    }

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        readTimeoutMs: Int,
        body: String?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = readTimeoutMs
            for ((key, value) in headers) setRequestProperty(key, value)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readBody(if (code in 200..299) conn.inputStream else conn.errorStream, conn)
            if (code !in 200..299) {
                throw HttpJsonException(code, "HTTP " + code + ": " + text.take(160), text)
            }
            return text
        } catch (e: HttpJsonTimeoutException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HttpJsonTimeoutException("请求超时（连接 " + timeoutMs + "ms / 读取 " + readTimeoutMs + "ms）: " + url)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(stream: InputStream?, conn: HttpURLConnection): String {
        val bytes = stream?.use { it.readBytes() } ?: return ""
        // 按响应自己的 charset 解码；原来用 bufferedReader() 会拿平台默认字符集，非 UTF-8 端点直接乱码。
        return String(bytes, charsetOf(conn.contentType))
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType
            ?.substringAfter("charset=", missingDelimiterValue = "")
            ?.substringBefore(';')
            ?.trim()
            ?.trim('"', '\'')
        if (name.isNullOrEmpty()) return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }
}
