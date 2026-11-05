package dev.dsh.mobile.core.model.web

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
