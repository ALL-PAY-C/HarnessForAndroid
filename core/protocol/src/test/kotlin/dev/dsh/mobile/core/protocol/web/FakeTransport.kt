package dev.dsh.mobile.core.protocol.web

/**
 * 可编程的传输层替身：记录每次请求，按 handler 决定响应。
 * 全部 JVM 测试都用它或本地 fake server，不依赖公网。
 */
internal class FakeTransport(
    private val handler: suspend (HttpRequest) -> HttpResponse,
) : HttpTransport {

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
}

internal fun HttpRequest.respond(
    status: Int = 200,
    body: String = "",
    contentType: String = "text/html; charset=utf-8",
    headers: Map<String, String> = emptyMap(),
): HttpResponse = respondBytes(status, body.toByteArray(Charsets.UTF_8), contentType, headers)

/** 按 1KB 分块喂给 sink，模拟真实流式传输（超限/断网才可能在中途触发）。 */
internal fun HttpRequest.respondBytes(
    status: Int,
    body: ByteArray,
    contentType: String,
    headers: Map<String, String> = emptyMap(),
): HttpResponse {
    val target = this.sink
    if (target != null) {
        var offset = 0
        while (offset < body.size) {
            val n = minOf(1024, body.size - offset)
            val chunk = body.copyOfRange(offset, offset + n)
            target.write(chunk, chunk.size)
            offset += n
        }
    }
    val all = headers + mapOf("content-type" to contentType)
    return HttpResponse(
        code = status,
        headers = all,
        body = if (target == null) body else ByteArray(0),
        finalUrl = url,
        receivedBytes = body.size.toLong(),
        redirects = 0,
    )
}

internal const val BING_HTML = """
<html><head><title>bing</title></head><body>
<li class="b_algo"><h2><a href="https://example.com/a">第一结果</a></h2><p>第一段摘要</p></li>
<li class="b_algo"><h2><a href="https://example.com/b">第二结果</a></h2><p>第二段摘要</p></li>
</body></html>
"""

internal const val DDG_HTML = """
<html><body>
<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fddg&amp;rut=x">Duck 结果</a>
<a class="result__snippet" href="https://example.org/ddg">Duck 摘要</a>
</body></html>
"""

internal const val EXA_JSON = """
{"results":[{"title":"Exa 结果","url":"https://example.net/exa","text":"Exa 摘要"}]}
"""
