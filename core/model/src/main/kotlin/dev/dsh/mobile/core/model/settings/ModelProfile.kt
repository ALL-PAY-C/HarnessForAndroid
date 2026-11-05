package dev.dsh.mobile.core.model.settings

import dev.dsh.mobile.core.model.ReasoningStyle

/** 一个可接入的模型后端（DeepSeek / OpenAI 兼容 / 自定义）。 */
data class ModelProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val isDefault: Boolean = false,
    /** Context window discovered from the endpoint or entered by hand; 0 = unknown. */
    val contextWindow: Int = 0,
    /** 思考强度，取值 ["off", "low", "medium", "high"]；写进请求体的 reasoning effort。 */
    val reasoningEffort: String = "high",
    /** 最大输出 tokens；0 = 请求里不发送 max_tokens。 */
    val maxOutputTokens: Int = 0,
    /** 温度；负数 = 不发送（用 -1f 而不是 NaN，方便序列化与比较）。 */
    val temperature: Float = -1f,
    /** Top P；负数 = 不发送。 */
    val topP: Float = -1f,
    /** 支持工具调用：false 时请求里不带 tools。 */
    val supportsTools: Boolean = true,
    /**
     * 思考参数发送方式；[ReasoningStyle.auto] = 按 providerId/baseUrl/模型名推断
     * （映射表在 core:model 的 inferReasoningStyle，一处）。
     */
    val reasoningStyle: ReasoningStyle = ReasoningStyle.auto,
    /** 支持图片输入：false 时输入区的图片附件按钮禁用。 */
    val supportsImages: Boolean = false,
    /** 支持图片内文字识别；只在 [supportsImages] 为 true 时有意义。 */
    val supportsOcr: Boolean = false,
    /**
     * 推理档位映射（M5）：每行 `档位=参数名:值`，空行与 # 开头忽略。
     * 空串 = 不映射，行为与旧版完全一致。示例：
     * ```
     * high=thinking_budget:8192
     * off=
     * ```
     */
    val reasoningMap: String = "",
)
