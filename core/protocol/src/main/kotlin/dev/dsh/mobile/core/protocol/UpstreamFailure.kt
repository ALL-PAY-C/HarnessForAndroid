package dev.dsh.mobile.core.protocol

import java.io.IOException

/**
 * 上游返回非 2xx。
 *
 * 以前这里只拼一句 "HTTP 401: {...}"，到 friendlyRequestError 又被原样透上去，
 * 上层只看到一句干巴巴的报文；现在把状态码翻成「谁的问题、去哪改」。
 */
internal class UpstreamHttpException(val status: Int, val body: String) :
    IOException(httpFailureReason(status, body))

/** 把状态码 + 上游报文翻成一句用户能照着动手的话。 */
internal fun httpFailureReason(status: Int, body: String): String {
    val head = when (status) {
        400 -> "HTTP 400 — the request was rejected upstream (bad model name, parameter, or message format)"
        401 -> "HTTP 401 — authentication failed (invalid or expired API key)"
        402 -> "HTTP 402 — upstream requires payment (out of quota)"
        403 -> "HTTP 403 — no access (key restricted, or the region/model is not enabled)"
        404 -> "HTTP 404 — endpoint not found (baseUrl has a stray or missing /v1)"
        408 -> "HTTP 408 — upstream timed out waiting"
        413 -> "HTTP 413 — request body too large (image or context too long)"
        429 -> "HTTP 429 — rate limited (too fast or quota exhausted)"
        in 500..599 -> "HTTP " + status + " — upstream server error (not this phone's fault; retry later)"
        in 300..399 -> "HTTP " + status + " — upstream redirect (baseUrl points at the wrong address)"
        else -> "HTTP " + status + " — upstream returned an error"
    }
    val detail = body.replace(Regex("\\s+"), " ").trim().take(200)
    return if (detail.isEmpty()) head else head + "; upstream body: " + detail
}
