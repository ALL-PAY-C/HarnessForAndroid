package dev.dsh.mobile.pluginhost.builtin.gateway.stream

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * 上游通道凭据（B4-2）。
 *
 * 两种输入形态，都由 [EnvelopeCodec.parseCredential] 认：
 * 1. 授权 json 全文（access_token / refresh_token / project_id）—— 带 refresh_token 就能自动续期；
 * 2. 裸 access_token —— 没有刷新能力，过期只能让用户重贴。
 */
data class StreamCredential(
    val access: String,
    val refresh: String? = null,
    val project: String = "",
) {
    fun isUsable(): Boolean = access.isNotBlank()
}

/**
 * OpenAI 请求 → 上游信封（B4 报告 §2 Q3 的实现）。
 *
 * 纯函数，无网络、无 Android 依赖：单测直接喂 JSON 断言，不需要模拟器。
 * 字段口径来自实测抓包（协议笔记留在 research/，不进仓库发布物）。
 */
object EnvelopeCodec {


    /**
     * 目录拉不到时的兜底：id 与上游 id 完全一致（不做别名）。
     * 少了这几条，模型源里会是一个空列表，用户连选都没得选。
     */
    val SEED_MODELS: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash-high",
        "gemini-3-flash",
        "gemini-3.1-flash-image",
        "claude-opus-4-6-thinking",
        "claude-sonnet-4-6",
        "gpt-oss-120b-medium",
    )

    /**
     * 凭据解析。JSON 全文 → 三字段；裸串 → 只有 access（无刷新、无 project）。
     * 解析不出来返回 null，调用方按「未填凭据」处理，不要抛。
     */
    fun parseCredential(raw: String?): StreamCredential? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return StreamCredential(access = text)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val access = json.optString("access_token")
            .ifBlank { json.optString("token") }
            .ifBlank { json.optString("accessToken") }
            .trim()
        if (access.isEmpty()) return null
        val refresh = json.optString("refresh_token")
            .ifBlank { json.optString("refreshToken") }
            .trim()
            .ifEmpty { null }
        val project = json.optString("project_id")
            .ifBlank { json.optString("projectId") }
            .ifBlank { json.optString("project") }
            .ifBlank { json.optJSONObject(UpstreamSpec.HINTS_KEY)?.optString("project_id").orEmpty() }
            .trim()
        return StreamCredential(access = access, refresh = refresh, project = project)
    }

    /**
     * 刷新成功后回写配置：在原 auth json 上就地改三个字段，其它字段原样留着，
     * 用户下次重新粘贴授权 json 也不会丢东西。
     */
    fun patchCredentialJson(raw: String?, access: String, refresh: String?, expiresInSeconds: Long): String {
        val root = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: JSONObject()
        root.put("access_token", access)
        if (!refresh.isNullOrBlank()) root.put("refresh_token", refresh)
        if (expiresInSeconds > 0) {
            root.put("expires_in", expiresInSeconds)
            root.put("timestamp", System.currentTimeMillis() / 1000L)
        }
        root.put("type", UpstreamSpec.CREDENTIAL_TYPE)
        return root.toString()
    }

    /** 图片模型走 image_gen 分支，其余一律 agent（与上游口径一致）。 */
    fun requestTypeFor(model: String): String = if (model.contains("image")) "image_gen" else "agent"

    /**
     * P1-7：sessionId 由首条 user 文本派生 —— sha256 前 8 字节按大端拼成 int64，前缀减号。
     * 同一段开场白稳定得到同一个 id，多轮之间上游才会当成同一条会话。
     */
    fun deriveSessionId(firstUserText: String?): String {
        val text = firstUserText?.trim().orEmpty()
        if (text.isEmpty()) return "-" + (Random().nextLong() ushr 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFFL)
        return "-" + value
    }

    /** 信封：P0-3 的六个顶层字段 + request 内的 contents/systemInstruction/generationConfig。 */
    fun build(
        openAi: JSONObject,
        credential: StreamCredential,
        requestId: String = "agent-" + UUID.randomUUID(),
    ): JSONObject {
        val model = openAi.optString("model").trim().ifEmpty { "gemini-3.5-flash-lite" }
        val messages = openAi.optJSONArray("messages") ?: JSONArray()
        val mapped = contentsOf(messages)
        val request = JSONObject()
            .put("contents", mapped.getJSONArray("contents"))
            .put("sessionId", deriveSessionId(firstUserText(messages)))
        mapped.optJSONObject("systemInstruction")?.let { request.put("systemInstruction", it) }
        generationConfig(openAi)?.let { request.put("generationConfig", it) }

        val envelope = JSONObject()
            .put("model", model)
            .put("userAgent", UpstreamSpec.USER_AGENT_ALIAS)
            .put("requestType", requestTypeFor(model))
            .put("requestId", requestId)
            .put("request", request)
        if (credential.project.isNotBlank()) envelope.put("project", credential.project)
        return envelope
    }

    /**
     * P0-4 + P1-8：messages → contents。
     * 角色只有 user / model 两种；system 与 developer 全部收进 systemInstruction。
     */
    fun contentsOf(messages: JSONArray): JSONObject {
        val contents = JSONArray()
        val systemParts = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            when (message.optString("role")) {
                "system", "developer" -> textOf(message.opt("content"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { systemParts.put(JSONObject().put("text", it)) }

                "assistant" -> contents.put(modelContent(message))

                // ponytail: tool 回传（P1-2）不做。上游要求 functionResponse.response.result
                // 必须是字符串，且要按 tool_call_id 与前面的 functionCall 配对；
                // 半套实现比不实现更容易让上游 400。需要时照 B4 报告 §2 Q3 补这一支。
                "tool" -> Unit

                else -> contents.put(userContent(message))
            }
        }
        val out = JSONObject().put("contents", contents)
        if (systemParts.length() > 0) {
            out.put(
                "systemInstruction",
                JSONObject().put("role", "user").put("parts", systemParts),
            )
        }
        return out
    }

    /** 首条 user 文本：sessionId 的派生输入，也用来判断「这条请求到底有没有内容」。 */
    fun firstUserText(messages: JSONArray): String? {
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") != "user") continue
            val text = textOf(message.opt("content"))?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun userContent(message: JSONObject): JSONObject {
        val parts = JSONArray()
        when (val content = message.opt("content")) {
            is String -> if (content.isNotBlank()) parts.put(JSONObject().put("text", content))
            is JSONArray -> for (j in 0 until content.length()) {
                partOf(content.optJSONObject(j))?.let { parts.put(it) }
            }
            else -> Unit
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    private fun modelContent(message: JSONObject): JSONObject {
        val parts = JSONArray()
        message.optString("reasoning_content")
            .takeIf { it.isNotBlank() }
            ?.let { parts.put(JSONObject().put("text", it).put("thought", true)) }
        textOf(message.opt("content"))
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.put(JSONObject().put("text", it)) }
        return JSONObject().put("role", "model").put("parts", parts)
    }

    /**
     * P1-3 的落地范围：只认 data URL 内联，远端 http(s) 图片直接丢。
     * ponytail: 上游不会替我们抓远端图片（参考实现同样直接丢弃），
     * 真要支持得先在本地下载再 base64，属于额外网络与体积，不做。
     */
    fun partOf(part: JSONObject?): JSONObject? {
        if (part == null) return null
        return when (part.optString("type")) {
            "text" -> part.optString("text").takeIf { it.isNotEmpty() }?.let { JSONObject().put("text", it) }
            "image_url" -> inlineData(part.optJSONObject("image_url")?.optString("url").orEmpty())
            else -> null
        }
    }

    /** data:image/png;base64,xxxx → {inlineData:{mimeType,data}}；不是 data URL 返回 null。 */
    fun inlineData(url: String): JSONObject? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(",")
        if (comma <= 0) return null
        val header = url.substring(5, comma)
        if (!header.endsWith(";base64")) return null
        val data = url.substring(comma + 1)
        if (data.isEmpty()) return null
        val mime = header.removeSuffix(";base64").ifEmpty { "application/octet-stream" }
        return JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", mime).put("data", data),
        )
    }

    /** content 可能是字符串，也可能是 [{type:text,text:...}] 数组。 */
    private fun textOf(content: Any?): String? = when (content) {
        is String -> content
        is JSONArray -> {
            val builder = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val text = part.optString("text")
                if (text.isEmpty()) continue
                if (builder.isNotEmpty()) builder.append("\n")
                builder.append(text)
            }
            builder.toString().ifEmpty { null }
        }
        else -> null
    }

    /** 档位是不是「关」：只有这几种写法算关，其余（low/medium/high/auto）都要上游回传思考文本。 */
    private fun isThinkingOff(effort: String): Boolean =
        effort == "off" || effort == "none" || effort == "disabled"

    /** P1-1：temperature / top_p / top_k / max_tokens 与思考预算。 */
    private fun generationConfig(openAi: JSONObject): JSONObject? {
        val config = JSONObject()
        val temperature = openAi.optDouble("temperature", Double.NaN)
        if (!temperature.isNaN()) config.put("temperature", temperature)
        val topP = openAi.optDouble("top_p", Double.NaN)
        if (!topP.isNaN()) config.put("topP", topP)
        val topK = openAi.optInt("top_k", 0)
        if (topK > 0) config.put("topK", topK)
        val maxTokens = openAi.optInt("max_tokens", 0).takeIf { it > 0 }
            ?: openAi.optInt("max_completion_tokens", 0).takeIf { it > 0 }
        if (maxTokens != null) config.put("maxOutputTokens", maxTokens)

        val effort = openAi.optString("reasoning_effort").trim()
        if (effort.isNotEmpty()) {
            val thinking = JSONObject()
            if (effort == "auto") thinking.put("thinkingBudget", -1) else thinking.put("thinkingLevel", effort)
            // 只给预算/档位，上游会照着思考但不把思考文本回传：客户端拿到的 reasoning_content 永远是空。
            // 要求回传要靠 includeThoughts，且只在档位不是「关」时写；关档连这个键都不出现（写 false 只是多送字段）。
            if (!isThinkingOff(effort)) thinking.put("includeThoughts", true)
            config.put("thinkingConfig", thinking)
        }
        return if (config.length() == 0) null else config
    }

    /**
     * P1-5：fetchAvailableModels 的响应是 {"models":{ "<id>": {...} }}，key 就是模型 id。
     * 过滤掉内部/实验模型（tab_/chat_ 前缀与 disabled 后缀），否则模型源里会出现一堆选不了的条目。
     */
    fun modelIds(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val models = root.optJSONObject("models") ?: return emptyList()
        return models.keys().asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("tab_") || it.startsWith("chat_") }
            .filterNot { it.endsWith("-disabled") }
            .sorted()
            .toList()
    }
}
