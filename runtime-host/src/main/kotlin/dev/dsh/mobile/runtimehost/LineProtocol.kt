package dev.dsh.mobile.runtimehost

import org.json.JSONObject
import java.io.IOException

/**
 * 引擎的行协议编解码（UTF-8，一行一个 JSON）。
 *
 * 请求：{"id":"1","method":"ping","params":{...}}
 * 响应：{"id":"1","ok":true,"result":{...}}
 *       {"id":"1","ok":false,"error":{"stage":"load|apply|rpc|parse|request|internal","message":"..."}}
 *
 * 纯 Kotlin，不碰 socket，单测直接覆盖。
 */
object LineProtocol {

    sealed interface Reply {
        val id: String?

        data class Ok(override val id: String?, val result: JSONObject) : Reply

        /**
         * [detail] 是引擎返回的整个 error 对象（stage/message 之外还可能有 stack/dir/entries）：
         * 插件装不上时只有 logcat 一个出口，宿主需要把现场原样带出来而不是只留一行 message。
         */
        data class Error(
            override val id: String?,
            val stage: String,
            val message: String,
            val detail: JSONObject? = null,
        ) : Reply
    }

    /**
     * 引擎主动发给宿主的请求（反向通道）：{"id":"h1","method":"host.call","params":{"method","payload"}}。
     *
     * 为什么同一个 socket 上要有反方向：模型网关与密钥都在 Kotlin 侧，插件要出模型只能回到宿主。
     * 两个方向的 id 空间由引擎保证不重叠（反向请求用 h 前缀）。
     */
    data class Request(val id: String?, val method: String, val params: JSONObject)

    sealed interface Inbound {
        data class Response(val reply: Reply) : Inbound

        data class RequestIn(val request: Request) : Inbound
    }

    /** 序列化成一行（不含结尾换行，写 socket 时由调用方补）。 */
    fun encode(method: String, params: JSONObject = JSONObject(), id: String): String {
        val root = JSONObject()
        root.put("id", id)
        root.put("method", method)
        if (params.length() > 0) root.put("params", params)
        return root.toString()
    }

    /**
     * 解析一行响应。
     *
     * 失败只用于「这一行根本不是合法响应」（空行 / 非法 JSON）；引擎明确报错时仍然是
     * success，返回 [Error] 由调用方转成 [NodeEngineException]。
     */
    fun decode(line: String): Result<Reply> {
        val inbound = decodeInbound(line).getOrElse { return Result.failure(it) }
        return when (inbound) {
            is Inbound.Response -> Result.success(inbound.reply)
            is Inbound.RequestIn ->
                Result.failure(NodeEngineException("protocol", "期望响应但收到引擎请求 ${inbound.request.method}"))
        }
    }

    /** 一行可能是宿主请求的响应，也可能是引擎发来的反向请求 —— 由这里分流。 */
    fun decodeInbound(line: String): Result<Inbound> {
        val text = line.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("空行"))
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return Result.failure(NodeEngineException("parse", "非法 JSON 行: ${t.message}", t))
        }
        val id = idOf(obj)
        if (!obj.has("ok")) {
            val method = obj.optString("method")
            if (method.isNotEmpty()) {
                return Result.success(Inbound.RequestIn(Request(id, method, obj.optJSONObject("params") ?: JSONObject())))
            }
        }
        return Result.success(Inbound.Response(replyOf(obj, id)))
    }

    private fun replyOf(obj: JSONObject, id: String?): Reply {
        if (obj.optBoolean("ok", false)) {
            return Reply.Ok(id, obj.optJSONObject("result") ?: JSONObject())
        }
        val err = obj.optJSONObject("error")
        val stage = err?.optString("stage").orEmpty().ifEmpty { "internal" }
        val message = err?.optString("message").orEmpty().ifEmpty { "引擎返回失败但没有 message" }
        return Reply.Error(id, stage, message, err)
    }

    private fun idOf(obj: JSONObject): String? = when (val raw = obj.opt("id")) {
        null, JSONObject.NULL -> null
        is String -> raw
        else -> raw.toString()
    }

    /** 宿主的应答（写回反向请求的 id）。 */
    fun encodeReply(id: String?, result: JSONObject): String {
        val root = JSONObject()
        root.put("id", id ?: JSONObject.NULL)
        root.put("ok", true)
        root.put("result", result)
        return root.toString()
    }

    fun encodeError(id: String?, stage: String, message: String): String {
        val root = JSONObject()
        root.put("id", id ?: JSONObject.NULL)
        root.put("ok", false)
        root.put("error", JSONObject().put("stage", stage).put("message", message))
        return root.toString()
    }

    /**
     * 逐行读回复，直到拿到 id == [expectedId] 的那一行。
     *
     * 错配的行（上一轮迟到回复 / 事件或日志泄漏到 stdout）经 [onStale] 记日志后丢弃并继续读：
     * 保证当前 call 不会把别人的结果当自己的、后续 call 也不会再取到迟到行。
     * 读到 EOF（[readLine] 返回 null）则抛 [IOException]。
     */
    fun readMatchingReply(
        readLine: () -> String?,
        expectedId: String,
        onStale: (staleId: String?) -> Unit = {},
        onRequest: (Request) -> Unit = {},
    ): Reply {
        while (true) {
            val line = readLine() ?: throw IOException("引擎关闭了连接")
            when (val inbound = decodeInbound(line).getOrThrow()) {
                is Inbound.RequestIn -> onRequest(inbound.request)
                is Inbound.Response -> {
                    if (inbound.reply.id == expectedId) return inbound.reply
                    onStale(inbound.reply.id)
                }
            }
        }
    }
}
