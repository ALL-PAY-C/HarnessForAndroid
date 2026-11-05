package dev.dsh.mobile.pluginhost.builtin.gateway.stream

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 上游一帧的解析结果。Ignore = 认了但没内容（心跳、只有 usage 的空帧等）。 */
sealed class StreamEvent {
    data class Chunk(
        val text: String?,
        val thought: String?,
        val finishReason: String?,
        val usage: JSONObject?,
    ) : StreamEvent()

    object Ignore : StreamEvent()
}

/**
 * 上游 SSE 解析（B4 报告 §2 Q4）。
 *
 * 三条硬事实：
 * 1. 帧格式是 data: {"response":{...}}，正文嵌在 response 里，不是裸 candidate；
 * 2. **没有 [DONE]** —— 上游直接关流，读到 EOF 就是正常结束，不能当错误；
 * 3. 正文在 candidates[0].content.parts[]，thought:true 的 part 是思维链，
 *    混进 content 会让宿主把思考过程当成回答显示。
 *
 * 纯函数，无网络、无 Android 依赖。
 */
object SseFrames {

    private const val PREFIX = "data:"

    /**
     * 逐行解析。非 data 行（event: / 空行 / 注释）返回 null，表示「跳过这一行」。
     * 空 payload 也返回 null —— 上游偶尔发 data: 后什么都没有。
     */
    fun parseLine(line: String): StreamEvent? {
        if (!line.startsWith(PREFIX)) return null
        val payload = line.substring(PREFIX.length).trim()
        if (payload.isEmpty()) return null
        return parseFrame(payload)
    }

    /** 单帧 JSON → 增量。解析失败返回 Ignore，绝不抛：一条坏帧不该掐断整条回答。 */
    fun parseFrame(payload: String): StreamEvent {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return StreamEvent.Ignore
        val response = root.optJSONObject("response") ?: root
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)

        var text: String? = null
        var thought: String? = null
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val value = part.optString("text")
                if (value.isEmpty()) continue
                if (part.optBoolean("thought", false)) {
                    thought = (thought ?: "") + value
                } else {
                    text = (text ?: "") + value
                }
            }
        }

        val finish = mapFinishReason(candidate?.optString("finishReason"))
        val usage = response.optJSONObject("usageMetadata")
        if (text == null && thought == null && finish == null && usage == null) return StreamEvent.Ignore
        return StreamEvent.Chunk(text = text, thought = thought, finishReason = finish, usage = usage)
    }

    /** STOP → stop、MAX_TOKENS → length；其余小写透传（SAFETY、RECITATION 等宿主认不得的也留着）。 */
    fun mapFinishReason(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when (value.uppercase()) {
            "STOP" -> "stop"
            "MAX_TOKENS" -> "length"
            "FINISH_REASON_UNSPECIFIED" -> null
            else -> value.lowercase()
        }
    }

    /**
     * P1-6：usageMetadata → OpenAI usage。
     * completion_tokens 要把 thoughtsTokenCount 算进去，否则开了思考的模型 token 数会明显偏小。
     */
    fun openAiUsage(metadata: JSONObject?): JSONObject? {
        if (metadata == null) return null
        val prompt = metadata.optInt("promptTokenCount", 0)
        val candidates = metadata.optInt("candidatesTokenCount", 0)
        val thoughts = metadata.optInt("thoughtsTokenCount", 0)
        val cached = metadata.optInt("cachedContentTokenCount", 0)
        val total = metadata.optInt("totalTokenCount", prompt + candidates + thoughts)
        val usage = JSONObject()
            .put("prompt_tokens", prompt)
            .put("completion_tokens", candidates + thoughts)
            .put("total_tokens", total)
        if (thoughts > 0) {
            usage.put("completion_tokens_details", JSONObject().put("reasoning_tokens", thoughts))
        }
        if (cached > 0) {
            usage.put("prompt_tokens_details", JSONObject().put("cached_tokens", cached))
        }
        return usage
    }

    /** 一个 OpenAI 流式分片（chat.completion.chunk）。 */
    fun chunk(
        id: String,
        model: String,
        created: Long,
        delta: JSONObject,
        finishReason: String? = null,
        usage: JSONObject? = null,
    ): JSONObject {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        val out = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        if (usage != null) out.put("usage", usage)
        return out
    }

    /**
     * 非流式聚合：把增量拼成一条 chat.completion。
     * EOF 直接调 [toCompletion]，不需要任何结束标记 —— 上游没有 [DONE]。
     */
    class Aggregator(private val model: String, val id: String = "chatcmpl-" + UUID.randomUUID()) {

        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private var finishReason: String? = null
        private var usage: JSONObject? = null

        val text: String get() = content.toString()
        val reasoningText: String get() = reasoning.toString()
        val isFinished: Boolean get() = finishReason != null

        /** 返回 true 表示收到收尾帧（带 finishReason），调用方可以停读。 */
        fun accept(event: StreamEvent): Boolean {
            if (event !is StreamEvent.Chunk) return false
            event.thought?.let { reasoning.append(it) }
            event.text?.let { content.append(it) }
            event.usage?.let { usage = openAiUsage(it) }
            if (event.finishReason != null) {
                finishReason = event.finishReason
                return true
            }
            return false
        }

        fun toCompletion(): JSONObject {
            val message = JSONObject().put("role", "assistant")
            message.put("content", content.toString())
            if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
            val choice = JSONObject()
                .put("index", 0)
                .put("message", message)
                .put("finish_reason", finishReason ?: "stop")
            val out = JSONObject()
                .put("id", id)
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000L)
                .put("model", model)
                .put("choices", JSONArray().put(choice))
            usage?.let { out.put("usage", it) }
            return out
        }
    }
}
