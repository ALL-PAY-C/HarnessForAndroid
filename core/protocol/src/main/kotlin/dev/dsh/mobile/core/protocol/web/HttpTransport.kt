package dev.dsh.mobile.core.protocol.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 网络层失败分类。任何失败都必须以 [WebToolException] 冒泡到工具层，
 * 绝不允许被包装成成功结果（计划 T8「错误不被包装成成功」）。
 */
enum class WebErrorCode {
    /** 需要密钥的引擎没有配置密钥。 */
    MissingKey,
    Timeout,
    /** HTTP 非 2xx。 */
    HttpStatus,
    TooManyRedirects,
    TooLarge,
    /** 连接中断、DNS、TLS、读流中断等。 */
    Network,
    BadRequest,
    OutsideWorkspace,
    /** 缺少必要接入（例如没有注入用户工作区）。 */
    NotConfigured,
}

class WebToolException(val code: WebErrorCode, message: String) : Exception(message) {
    override fun toString(): String = "WebToolException($code): " + message.orEmpty()
}

/** 流式接收体：由调用方决定写到哪、超限就抛异常中止传输。 */
fun interface HttpSink {
    fun write(buffer: ByteArray, length: Int)
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
    val maxRedirects: Int = 5,
    val timeoutMs: Long = 15_000L,
    /** 非空时响应体逐块交给它，[HttpResponse.body] 为空、字节数见 receivedBytes。 */
    val sink: HttpSink? = null,
)

data class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val finalUrl: String,
    val receivedBytes: Long = body.size.toLong(),
    val redirects: Int = 0,
) {
    val contentType: String get() = headers["content-type"].orEmpty()
}

/**
 * 可注入的传输层。生产用 [OkHttpTransport]；JVM 测试注入 fake，或指向本地
 * com.sun.net.httpserver，不依赖公网。
 *
 * 与 git clone 走同一套真实网络环境：都走进程默认 DNS/代理，不改 dns.lookup。
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

internal const val WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

internal fun browserHeaders(accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"): Map<String, String> =
    mapOf(
        "user-agent" to WEB_USER_AGENT,
        "accept" to accept,
        "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

/** 只放行 http/https 且带主机名的绝对地址。 */
internal fun validateUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw WebToolException(WebErrorCode.BadRequest, "URL 为空")
    val parsed = try {
        URL(trimmed)
    } catch (e: Exception) {
        throw WebToolException(WebErrorCode.BadRequest, "URL 无法解析：" + raw)
    }
    val scheme = parsed.protocol.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw WebToolException(WebErrorCode.BadRequest, "只支持 http/https：" + raw + "（实际 " + scheme + "）")
    }
    if (parsed.host.isNullOrBlank()) throw WebToolException(WebErrorCode.BadRequest, "URL 缺少主机名：" + raw)
    return trimmed
}

internal fun resolveRedirect(base: String, location: String): String {
    val next = try {
        URL(URL(base), location).toString()
    } catch (e: Exception) {
        throw WebToolException(WebErrorCode.BadRequest, "重定向地址无法解析：" + location)
    }
    return validateUrl(next)
}

/**
 * OkHttp 实现。重定向自己走循环（不交给 OkHttp 的 followRedirects），
 * 这样才能拿到每一跳、并对每一跳做协议校验与次数上限。
 */
class OkHttpTransport(private val client: OkHttpClient = DEFAULT_CLIENT) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        var current = validateUrl(request.url)
        var hops = 0
        while (true) {
            val callClient = client.newBuilder()
                .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val builder = Request.Builder().url(current)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            val method = request.method.uppercase()
            val bodyBytes = request.body
            if (bodyBytes == null) {
                builder.method(method, null)
            } else {
                val media = request.headers["content-type"]?.toMediaTypeOrNull()
                builder.method(method, bodyBytes.toRequestBody(media))
            }
            val response = try {
                callClient.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw networkError(current, e)
            }
            response.use { resp ->
                val headers = resp.headers.names().associate { it.lowercase() to resp.header(it).orEmpty() }
                val location = headers["location"]
                if (request.followRedirects && resp.code in REDIRECT_CODES && !location.isNullOrBlank()) {
                    if (hops >= request.maxRedirects) {
                        throw WebToolException(
                            WebErrorCode.TooManyRedirects,
                            "重定向超过 " + request.maxRedirects + " 次：" + request.url,
                        )
                    }
                    current = resolveRedirect(current, location)
                    hops++
                } else {
                    return@withContext readResponse(request, resp, headers, current, hops)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private fun readResponse(
        request: HttpRequest,
        resp: okhttp3.Response,
        headers: Map<String, String>,
        url: String,
        hops: Int,
    ): HttpResponse {
        val sink = request.sink
        if (sink == null) {
            val bytes = try {
                resp.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                throw networkError(url, e)
            }
            return HttpResponse(resp.code, headers, bytes, url, bytes.size.toLong(), hops)
        }
        var total = 0L
        try {
            val stream = resp.body?.byteStream()
            if (stream != null) {
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n <= 0) break
                    sink.write(buffer, n)
                    total += n
                }
            }
        } catch (e: WebToolException) {
            throw e
        } catch (e: IOException) {
            throw networkError(url, e)
        }
        return HttpResponse(resp.code, headers, ByteArray(0), url, total, hops)
    }

    private fun networkError(url: String, e: IOException): WebToolException {
        val timeout = e is InterruptedIOException || e.message?.contains("timeout", ignoreCase = true) == true
        val code = if (timeout) WebErrorCode.Timeout else WebErrorCode.Network
        val text = if (timeout) {
            "请求超时：" + url
        } else {
            "网络请求失败：" + url + "：" + e.javaClass.simpleName + ": " + e.message.orEmpty()
        }
        return WebToolException(code, text)
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
