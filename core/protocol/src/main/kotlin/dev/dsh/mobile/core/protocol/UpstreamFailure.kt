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
        400 -> "HTTP 400 请求被上游拒绝（模型名、参数或消息格式不对）"
        401 -> "HTTP 401 鉴权失败（API Key 无效或已过期）"
        402 -> "HTTP 402 上游要求付费（额度用完了）"
        403 -> "HTTP 403 没有访问权限（Key 被限制或地区/模型未开通）"
        404 -> "HTTP 404 接口不存在（baseUrl 多写或少写了 /v1）"
        408 -> "HTTP 408 上游等待超时"
        413 -> "HTTP 413 请求体过大（图片或上下文太长）"
        429 -> "HTTP 429 触发限流（请求太快或额度耗尽）"
        in 500..599 -> "HTTP " + status + " 上游服务端错误（不是这台手机的问题，稍后重试）"
        in 300..399 -> "HTTP " + status + " 上游重定向（baseUrl 指错了地址）"
        else -> "HTTP " + status + " 上游返回错误"
    }
    val detail = body.replace(Regex("\\s+"), " ").trim().take(200)
    return if (detail.isEmpty()) head else head + "；上游报文：" + detail
}
