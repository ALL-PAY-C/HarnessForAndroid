package dev.dsh.mobile.core.model

/**
 * 思考参数的发送方式（能力驱动，不再按模型名白名单判断）。
 *
 * - [none]：一个思考字段都不发（不发 `thinking:{type:disabled}`，那是另一回事）。
 * - [reasoningEffort]：OpenAI / DeepSeek 风格 —— `thinking:{type:enabled}` + `reasoning_effort`。
 * - [thinkingBudget]：预算风格 —— `thinking:{type:enabled, budget_tokens}`（Gemini 系经兼容层）。
 * - [thinkingToggle]：只有开关 —— `thinking:{type:enabled}`，不给强度。
 * - [auto]：由 [inferReasoningStyle] 按 providerId / baseUrl / 模型名推断。
 */
enum class ReasoningStyle {
    none,
    reasoningEffort,
    thinkingBudget,
    thinkingToggle,
    auto,
    ;

    companion object {
        /** 落盘/读配置用：未知、空串、大小写差异一律落回 [auto]，老数据没有这个字段。 */
        fun fromWire(raw: String?): ReasoningStyle {
            val key = raw?.trim().orEmpty()
            if (key.isEmpty()) return auto
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: auto
        }
    }
}

/** 原生 Google 端点：只有这些端点认 budget_tokens 形状（其余都是 OpenAI 兼容中转）。 */
private fun isGoogleNativeEndpoint(baseUrl: String): Boolean {
    val url = baseUrl.trim().lowercase()
    return "googleapis.com" in url || "generativelanguage" in url || "vertex" in url
}

/** DeepSeek 系：思考参数与历史里回填 `reasoning_content` 只对它成立，别的上游会 400。 */
fun isDeepSeekFamily(providerId: String, baseUrl: String, model: String): Boolean {
    val hay = (providerId + " " + baseUrl + " " + model).lowercase()
    return "deepseek" in hay || "ds4" in model.lowercase()
}

/**
 * auto 的推断表：**唯一一处**决定「这类模型该发哪种思考参数」。
 *
 * 判定顺序：DeepSeek 系 > 原生 Google 端点上的 Gemini > 其它端点上的 Gemini（中转只认
 * `reasoning_effort`）> Anthropic/Claude > 其余（一个字段都不发）。
 */
fun inferReasoningStyle(providerId: String, baseUrl: String, model: String): ReasoningStyle {
    val hay = (providerId + " " + baseUrl + " " + model).lowercase()
    val geminiFamily = "gemini" in hay || "vertex" in hay
    return when {
        isDeepSeekFamily(providerId, baseUrl, model) -> ReasoningStyle.reasoningEffort
        geminiFamily && isGoogleNativeEndpoint(baseUrl) -> ReasoningStyle.thinkingBudget
        // Gemini 经 OpenAI 兼容中转（本机网关、one-api 之类）：中转只认 reasoning_effort，
        // 给它发 budget_tokens 等于不发（实测本机网关只读 reasoning_effort）。
        geminiFamily -> ReasoningStyle.reasoningEffort
        // Anthropic 原生接口不吃 OpenAI 形状，走兼容层时能用的旋钮只有 reasoning_effort；
        // 选 none 等于让 Claude 永远不出思考过程（就是这次要修的 bug）。
        "anthropic" in hay || "claude" in hay -> ReasoningStyle.reasoningEffort
        else -> ReasoningStyle.none
    }
}

/** 手选值优先；[ReasoningStyle.auto] 才走推断。调用点只调这一个函数。 */
fun resolveReasoningStyle(
    style: ReasoningStyle,
    providerId: String,
    baseUrl: String,
    model: String,
): ReasoningStyle = if (style == ReasoningStyle.auto) inferReasoningStyle(providerId, baseUrl, model) else style
