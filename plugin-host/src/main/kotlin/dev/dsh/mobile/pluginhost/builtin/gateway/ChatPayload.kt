package dev.dsh.mobile.pluginhost.builtin.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 请求体 → 上游请求体的归一（B3-1 规格 §Q3）。
 *
 * 纯函数：只吃 JSON 字符串、只吐 org.json 对象，不碰 Android、不碰网络 —— 单测在
 * JVM 上直接跑，不需要 Robolectric，也不需要 emulator。
 *
 * 七处顺序即依赖，照抄规格里的流水线：强制 stream → stream_options → max_tokens 翻译 →
 * image_url 对象化 → role 归一 → tool_choice 归一 → tool 配对清理。
 * 后三步必须在 messages 上按顺序跑，tool_choice 删 tools 的那一步要早于配对清理，
 * 否则清完再删 tools 会把刚修好的配对又变成孤儿。
 */
object ChatPayload {

    const val CHAT_PATH = "/v2/chat/completions"

    /**
     * 官方客户端的 chat 走这条路径：/v2 上有渠道校验，回 11-128「不是官方客户端发出的」，
     * /console 放行（实测 2026-11-05）。网关默认先试 CHAT_PATH，被拦就切到这条并记住。
     */
    const val CONSOLE_PATH = "/console/chat/completions"
    const val CONFIG_PATH = "/v3/config"
    const val MODELS_PATH = "/models"
    const val REFRESH_PATH = "/auth/token/refresh"

    /** 客户端没给 system 时的兜底提示词：中性、不带任何品牌或产品名。 */
    const val DEFAULT_SYSTEM_PROMPT = "You are a helpful coding assistant."

    /**
     * /console 路径的硬约束（实测）：messages[0] 必须是 system，否则 400
     * 「first message is not system prompt」。客户端没给就补一条中性提示词；
     * 首条已经是 system/developer 时原样不动。
     */
    fun ensureSystemPrompt(root: JSONObject, prompt: String = DEFAULT_SYSTEM_PROMPT): JSONObject {
        val messages = root.optJSONArray("messages") ?: return root
        if (messages.length() == 0) return root
        val first = messages.optJSONObject(0) ?: return root
        val role = first.optString("role").trim().lowercase()
        if (role == "system" || role == "developer") return root
        val rebuilt = JSONArray()
        rebuilt.put(JSONObject().put("role", "system").put("content", prompt))
        for (i in 0 until messages.length()) rebuilt.put(messages.get(i))
        root.put("messages", rebuilt)
        return root
    }

    /** 上游不吃非流式，客户端要什么由网关自己聚合（P0-10）。 */
    fun prepare(raw: String): JSONObject {
        val root = JSONObject(raw)
        forceStream(root)
        ensureStreamOptions(root)
        translateMaxTokens(root)
        normalizeImageUrls(root)
        normalizeRoles(root)
        normalizeToolChoice(root)
        cleanupToolPairing(root)
        return root
    }

    /** P0-5：上游只支持流式，客户端给 false 也覆盖。 */
    fun forceStream(root: JSONObject) {
        root.put("stream", true)
    }

    /** P0-6：不补 include_usage，末帧没有 usage，聚合后的 completion 就缺 token 统计。 */
    fun ensureStreamOptions(root: JSONObject) {
        val opts = root.optJSONObject("stream_options")
        if (opts == null) {
            root.put("stream_options", JSONObject().put("include_usage", true))
        } else if (!opts.has("include_usage")) {
            opts.put("include_usage", true)
        }
    }

    /**
     * P0-7：上游只认 max_tokens；带 max_completion_tokens 会被回落默认 32000 并截断。
     * 显式 max_tokens 优先；别名值为 0/null/负/非数值时两个都不动（上游自己兜默认）。
     */
    fun translateMaxTokens(root: JSONObject) {
        val explicit = positiveInt(root.opt("max_tokens"))
        val alias = positiveInt(root.opt("max_completion_tokens"))
        if (explicit != null) {
            root.remove("max_completion_tokens")
        } else if (alias != null) {
            root.put("max_tokens", alias)
            root.remove("max_completion_tokens")
        }
    }

    /** P0-8：image_url 的字符串形态上游解析失败，必须包成 {"url": "..."}。 */
    fun normalizeImageUrls(root: JSONObject) {
        val messages = root.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val content = message.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") != "image_url") continue
                when (val value = part.opt("image_url")) {
                    is String -> part.put("image_url", JSONObject().put("url", value))
                    is JSONObject -> {
                        if (value.optString("url").isEmpty() && value.has("image_url")) {
                            value.put("url", value.optString("image_url"))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** P1-2：developer 角色上游不认（400 code 11-128），统一成 system。 */
    fun normalizeRoles(root: JSONObject) {
        val messages = root.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "developer") message.put("role", "system")
        }
    }

    /**
     * P1-1：tool_choice 只接受字符串。对象形态 {"type":"function","function":{"name":"x"}} → "x"；
     * none 要连 tools/functions 一起删（上游见到 none 还带 tools 会 400 code 11101）；
     * 其它对象形态上游不认，直接删字段让它走默认 auto。
     */
    fun normalizeToolChoice(root: JSONObject) {
        val raw = root.opt("tool_choice") ?: return
        if (raw is String) {
            if (raw == "none") {
                root.remove("tool_choice")
                root.remove("tools")
                root.remove("functions")
            }
            return
        }
        if (raw is JSONObject) {
            val name = raw.optJSONObject("function")?.optString("name").orEmpty()
            if (raw.optString("type") == "function" && name.isNotEmpty()) {
                root.put("tool_choice", name)
            } else {
                root.remove("tool_choice")
            }
        }
    }

    /**
     * P1-3：tool_call / tool 结果配对清理。断裂一次之后上游对**每条**后续消息都回 400 code 11148，
     * 所以这一步不能省。
     *
     * 两件事：
     * 1) 重排：tool 结果必须紧跟发起它的 assistant；夹在中间的非 tool 消息挪到结果块之后。
     * 2) 清理：没有结果的 tool_calls 删掉（只剩空 tool_calls 的 assistant 也删，避免 11151 空消息）；
     *    没有对应 tool_calls 的 tool 结果删掉。
     */
    fun cleanupToolPairing(root: JSONObject) {
        val array = root.optJSONArray("messages") ?: return
        val input = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { input.add(it) }

        // 1) 所有被发起过的 tool_call id：用来判断哪些 tool 结果是孤儿。
        val callIds = HashSet<String>()
        for (message in input) {
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (k in 0 until calls.length()) {
                val id = calls.optJSONObject(k)?.optString("id").orEmpty()
                if (id.isNotEmpty()) callIds.add(id)
            }
        }

        // 2) 所有真的有结果回来的 id：用来判断哪些 tool_calls 是孤儿。
        val resultIds = HashSet<String>()
        for (message in input) {
            if (message.optString("role") != "tool") continue
            val id = message.optString("tool_call_id")
            if (id.isNotEmpty()) resultIds.add(id)
        }

        // 3) 双向清理：孤儿结果删掉；没有结果的 tool_calls 删掉；删完只剩空壳的 assistant 整条删掉。
        val work = ArrayList<JSONObject>(input.size)
        for (message in input) {
            when (message.optString("role")) {
                "tool" -> {
                    val id = message.optString("tool_call_id")
                    if (callIds.contains(id) && resultIds.contains(id)) work.add(message)
                }
                "assistant" -> {
                    val calls = message.optJSONArray("tool_calls")
                    if (calls == null) {
                        work.add(message)
                        continue
                    }
                    val kept = JSONArray()
                    for (k in 0 until calls.length()) {
                        val call = calls.optJSONObject(k) ?: continue
                        val id = call.optString("id")
                        if (id.isNotEmpty() && resultIds.contains(id)) kept.put(call)
                    }
                    if (kept.length() == 0) {
                        message.remove("tool_calls")
                        if (message.optString("content").isEmpty()) continue
                    } else {
                        message.put("tool_calls", kept)
                    }
                    work.add(message)
                }
                else -> work.add(message)
            }
        }

        // 4) 重排：结果必须紧跟发起它的 assistant；夹在中间的消息挪到结果块之后。
        //    只搬「属于这条 assistant 的结果」，别的消息保持相对顺序。
        val out = ArrayList<JSONObject>(work.size)
        var i = 0
        while (i < work.size) {
            val message = work[i]
            i++
            out.add(message)
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            val wanted = HashSet<String>()
            for (k in 0 until calls.length()) {
                val id = calls.optJSONObject(k)?.optString("id").orEmpty()
                if (id.isNotEmpty()) wanted.add(id)
            }
            var j = i
            while (j < work.size && wanted.isNotEmpty()) {
                val candidate = work[j]
                if (candidate.optString("role") == "tool" && wanted.contains(candidate.optString("tool_call_id"))) {
                    out.add(candidate)
                    wanted.remove(candidate.optString("tool_call_id"))
                    work.removeAt(j)
                } else {
                    j++
                }
            }
        }

        val finalArray = JSONArray()
        for (message in out) finalArray.put(message)
        root.put("messages", finalArray)
    }

    /** 凭据两种写法：直接贴 access token，或贴 {"access":"…","refresh":"…"}。空/坏值一律当未填。 */
    fun parseCredential(raw: String?): Credential? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return Credential(text, null)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val access = json.optString("access").ifEmpty { json.optString("accessToken") }.trim()
        if (access.isEmpty()) return null
        val refresh = json.optString("refresh").ifEmpty { json.optString("refreshToken") }.trim()
        return Credential(access, refresh.ifEmpty { null })
    }

    /**
     * P0-11：模型目录 = /v3/config 的 data.models ∪ /v2/enterprises/personal/models 的
     * data.models + agents[name=="cli"].models。v3 优先、按 id 去重，剔掉非对话模型。
     */
    fun modelsFromUpstream(v3Body: String?, personalBody: String?): List<ModelInfo> {
        val merged = LinkedHashMap<String, ModelInfo>()
        for (model in entriesOfConfig(v3Body)) if (!merged.containsKey(model.id)) merged[model.id] = model
        for (model in entriesOfPersonal(personalBody)) if (!merged.containsKey(model.id)) merged[model.id] = model
        return merged.values.filter { isChatModel(it) }
    }

    fun isChatModel(model: ModelInfo): Boolean {
        if (model.id.isEmpty()) return false
        if (model.disabled) return false
        if (model.tags.any { it == "text-to-image" }) return false
        val prefixes = listOf("nes-", "completion-", "codewise-")
        if (prefixes.any { model.id.startsWith(it) }) return false
        if (model.maxOutputTokens in 1..256) return false
        return true
    }

    fun toOpenAiModel(model: ModelInfo): JSONObject = JSONObject()
        .put("id", model.id)
        .put("object", "model")
        .put("created", 0)
        .put("owned_by", "loopback")
        .put("max_input_tokens", model.maxInputTokens)
        .put("max_output_tokens", model.maxOutputTokens)
        .put("supports_reasoning", model.supportsReasoning)

    private fun entriesOfConfig(body: String?): List<ModelInfo> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = when (val data = root.opt("data")) {
            is JSONObject -> data.optJSONArray("models")
            is JSONArray -> data
            else -> root.optJSONArray("models")
        } ?: return emptyList()
        return entriesOf(array)
    }

    private fun entriesOfPersonal(body: String?): List<ModelInfo> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        data.optJSONArray("models")?.let { out.addAll(entriesOf(it)) }
        val agents = data.optJSONArray("agents")
        if (agents != null) {
            for (i in 0 until agents.length()) {
                val agent = agents.optJSONObject(i) ?: continue
                if (agent.optString("name") != "cli") continue
                val ids = agent.optJSONArray("models") ?: continue
                for (j in 0 until ids.length()) {
                    val id = ids.optString(j)
                    if (id.isNotEmpty()) out.add(ModelInfo(id, id, 0, 0, false, emptyList(), null, emptyList(), false))
                }
            }
        }
        return out
    }

    private fun entriesOf(array: JSONArray): List<ModelInfo> {
        val out = ArrayList<ModelInfo>(array.length())
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> if (item.isNotEmpty()) {
                    out.add(ModelInfo(item, item, 0, 0, false, emptyList(), null, emptyList(), false))
                }
                is JSONObject -> {
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    val reasoning = item.optJSONObject("reasoning")
                    val efforts = ArrayList<String>()
                    reasoning?.optJSONArray("supportedEfforts")?.let { list ->
                        for (k in 0 until list.length()) {
                            val effort = list.optString(k)
                            if (effort.isNotEmpty()) efforts.add(effort)
                        }
                    }
                    val tags = ArrayList<String>()
                    item.optJSONArray("tags")?.let { list ->
                        for (k in 0 until list.length()) {
                            val tag = list.optString(k)
                            if (tag.isNotEmpty()) tags.add(tag)
                        }
                    }
                    out.add(
                        ModelInfo(
                            id = id,
                            name = item.optString("name").ifEmpty { id },
                            maxInputTokens = item.optInt("maxInputTokens", 0),
                            maxOutputTokens = item.optInt("maxOutputTokens", 0),
                            supportsReasoning = item.optBoolean("supportsReasoning", false),
                            supportedEfforts = efforts,
                            defaultEffort = reasoning?.optString("defaultEffort")?.ifEmpty { null },
                            tags = tags,
                            disabled = item.optBoolean("disabled", false),
                        )
                    )
                }
                else -> Unit
            }
        }
        return out
    }

    private fun positiveInt(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it > 0 }
        is Long -> value.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
        is Double -> value.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
        is Number -> value.toInt().takeIf { it > 0 }
        else -> null
    }
}

/** 凭据：access 必需，refresh 可无（只有 access 时刷新不了，401 直接让用户改设置）。 */
data class Credential(val access: String, val refresh: String?)

/**
 * 模型条目。supportedEfforts / defaultEffort 留着是因为 P1-4 的 reasoning_effort 降级只认它；
 * 这一版没做降级（见 ChatGateway 的 ponytail 注释），字段先带着不花成本。
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val supportsReasoning: Boolean,
    val supportedEfforts: List<String>,
    val defaultEffort: String?,
    val tags: List<String> = emptyList(),
    val disabled: Boolean = false,
)
