package dev.dsh.mobile.pluginhost.builtin.gateway

import org.json.JSONArray
import org.json.JSONObject

/** 一行 SSE 的分类结果：正文帧 / 结束标记 / 无关行（注释、空行、心跳）。 */
sealed class SseEvent {
    data class Chunk(val json: JSONObject) : SseEvent()
    object Done : SseEvent()
    object Ignore : SseEvent()
}

/**
 * 上游 SSE 的解析与聚合（B3-1 规格 §Q4）。
 *
 * 上游吐的就是 OpenAI 原生 chunk，所以这里不做字段翻译，只做三件事：认 data: 前缀、
 * 把空 finish_reason / 空 delta 清干净、以及非流式请求时的整段聚合。
 * 纯函数 + 纯对象，单测不需要网络。
 */
object ChatSse {

    const val DONE_PAYLOAD = "[DONE]"

    fun classify(line: String): SseEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return SseEvent.Ignore
        if (!trimmed.startsWith("data:")) return SseEvent.Ignore
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return SseEvent.Ignore
        if (payload == DONE_PAYLOAD) return SseEvent.Done
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return SseEvent.Ignore
        return SseEvent.Chunk(json)
    }

    /** 出站帧：每帧一条 data: + 空行，立即 flush 由调用方负责。 */
    fun encode(chunk: JSONObject): String = "data: " + chunk.toString() + "\n\n"

    fun doneFrame(): String = "data: " + DONE_PAYLOAD + "\n\n"

    /**
     * 帧规范化：object 强制 chat.completion.chunk；finish_reason 的空串改成 null；
     * 丢掉空 content / 空 tool_calls 列表。整帧没有任何可下发内容时返回 null（不占带宽）。
     */
    fun normalize(chunk: JSONObject): JSONObject? {
        chunk.put("object", "chat.completion.chunk")
        val choices = chunk.optJSONArray("choices")
        if (choices != null) {
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                if (choice.has("finish_reason") && choice.opt("finish_reason") is String &&
                    choice.optString("finish_reason").isEmpty()
                ) {
                    choice.put("finish_reason", JSONObject.NULL)
                }
                val delta = choice.optJSONObject("delta") ?: continue
                if (delta.has("content") && delta.opt("content") is String && delta.optString("content").isEmpty()) {
                    delta.remove("content")
                }
                if (delta.has("refusal") && delta.isNull("refusal")) delta.remove("refusal")
                val calls = delta.optJSONArray("tool_calls")
                if (calls != null && calls.length() == 0) delta.remove("tool_calls")
            }
        }
        val hasUsage = chunk.has("usage") && !chunk.isNull("usage")
        if (choices == null && !hasUsage) return null
        if (choices != null && choices.length() == 0 && !hasUsage) return null
        return chunk
    }

    /**
     * 非流式聚合（P0-10）：边读边拼 content / reasoning_content / tool_calls，
     * 最后拼成 chat.completion。deltas 只给单测断言增量顺序用。
     */
    class Aggregator {

        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private val toolCalls = ArrayList<JSONObject>()
        private val deltas = ArrayList<String>()
        private var id: String? = null
        private var model: String? = null
        private var created: Long = 0L
        private var finishReason: String? = null
        private var usage: JSONObject? = null

        var sawDone: Boolean = false
            private set

        fun accept(chunk: JSONObject) {
            chunk.optString("id").takeIf { it.isNotEmpty() }?.let { id = it }
            chunk.optString("model").takeIf { it.isNotEmpty() }?.let { model = it }
            chunk.optLong("created", 0L).takeIf { it > 0 }?.let { created = it }
            if (chunk.has("usage") && !chunk.isNull("usage")) {
                chunk.optJSONObject("usage")?.let { usage = it }
            }
            val choices = chunk.optJSONArray("choices") ?: return
            val choice = choices.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val text = delta.optString("content")
                if (text.isNotEmpty()) {
                    content.append(text)
                    deltas.add(text)
                }
                val think = delta.optString("reasoning_content")
                if (think.isNotEmpty()) reasoning.append(think)
                val calls = delta.optJSONArray("tool_calls")
                if (calls != null) for (i in 0 until calls.length()) mergeToolCall(calls.optJSONObject(i))
            }
            val finish = choice.optString("finish_reason")
            if (finish.isNotEmpty()) finishReason = finish
        }

        fun markDone() {
            sawDone = true
        }

        /** 增量正文顺序，单测断言用。 */
        fun deltaOrder(): List<String> = ArrayList(deltas)

        fun toCompletion(): JSONObject {
            val finished = finishReason
            // finish_reason == length 或上游没发 [DONE]：tool_calls 的参数可能是半截 JSON，丢掉。
            val keepCalls = toolCalls.isNotEmpty() && sawDone && finished != "length"
            var text = content.toString()
            if (text.isEmpty() && !keepCalls && reasoning.isNotEmpty()) {
                // 整轮只有思维链：当正文下发，否则客户端看到空回复（sse.go:232-237）。
                text = reasoning.toString()
            }
            val message = JSONObject().put("role", "assistant").put("content", text)
            if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
            if (keepCalls) {
                val array = JSONArray()
                for (call in toolCalls) array.put(call)
                message.put("tool_calls", array)
            }
            val choice = JSONObject()
                .put("index", 0)
                .put("message", message)
                .put("finish_reason", finished ?: if (keepCalls) "tool_calls" else "stop")
            val result = JSONObject()
                .put("id", id ?: ("chatcmpl-" + System.currentTimeMillis()))
                .put("object", "chat.completion")
                .put("created", if (created > 0) created else System.currentTimeMillis() / 1000)
                .put("model", model ?: "")
                .put("choices", JSONArray().put(choice))
            result.put("usage", usageJson())
            return result
        }

        private fun usageJson(): JSONObject {
            val source = usage ?: return JSONObject()
                .put("prompt_tokens", 0)
                .put("completion_tokens", 0)
                .put("total_tokens", 0)
            val out = JSONObject(source.toString())
            if (!out.has("total_tokens") || out.optInt("total_tokens", 0) <= 0) {
                out.put("total_tokens", out.optInt("prompt_tokens", 0) + out.optInt("completion_tokens", 0))
            }
            return out
        }

        private fun mergeToolCall(delta: JSONObject?) {
            if (delta == null) return
            val index = delta.optInt("index", 0)
            var target: JSONObject? = null
            for (call in toolCalls) if (call.optInt("index", 0) == index) target = call
            if (target == null) {
                target = JSONObject()
                    .put("index", index)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", "").put("arguments", ""))
                toolCalls.add(target)
            }
            val id = delta.optString("id")
            if (id.isNotEmpty()) target.put("id", id)
            val fn = delta.optJSONObject("function") ?: return
            val targetFn = target.optJSONObject("function") ?: JSONObject().also { target.put("function", it) }
            val name = fn.optString("name")
            if (name.isNotEmpty()) targetFn.put("name", name)
            val args = fn.optString("arguments")
            if (args.isNotEmpty()) targetFn.put("arguments", targetFn.optString("arguments") + args)
        }
    }
}
