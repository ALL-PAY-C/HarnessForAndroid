package dev.dsh.mobile.feature.agents

import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelProvider
import dev.dsh.mobile.core.data.settings.sanitizeReasoningEffort
import dev.dsh.mobile.core.model.ReasoningStyle

/**
 * 「添加模型」对话框里用户填的东西；多选保存时每条 profile 共用这一份。
 *
 * 纯数据 + 纯函数：不碰网络也不碰存储，JVM 单测直接构造。
 */
data class ModelDraft(
    val name: String = "",
    val providerId: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val contextWindow: Int = 0,
    val reasoningEffort: String = "high",
    val maxOutputTokens: Int = 0,
    val temperature: Float = -1f,
    val topP: Float = -1f,
    val supportsTools: Boolean = true,
    val isDefault: Boolean = false,
    /**
     * 思考参数的发送形状。表单没有选择器（默认 auto = 按 provider/模型推断），
     * 编辑已有条目时由调用方把原 profile 的值透传进来，保存一次不会把它打回 auto。
     */
    val reasoningStyle: ReasoningStyle = ReasoningStyle.auto,
    /** 支持图片输入（M4）；表单默认关，打开后输入区才允许挂图片。 */
    val supportsImages: Boolean = false,
    /** 支持图片内文字识别（M4）；只在 supportsImages 打开时可改。 */
    val supportsOcr: Boolean = false,
    /** 推理档位映射（M5），原样落进 profile。 */
    val reasoningMap: String = "",
)

/** 模板选中后表单要填的默认值。 */
data class ModelTemplate(val name: String, val baseUrl: String, val apiKey: String, val model: String)

/** 纯函数：内置模板 → 表单默认值（模板自带 Key 时表单直接预填）。 */
fun templateOf(provider: ModelProvider): ModelTemplate = ModelTemplate(
    name = provider.defaultName,
    baseUrl = provider.defaultBaseUrl,
    apiKey = provider.defaultApiKey,
    model = provider.defaultModel,
)

/**
 * 纯函数：勾选的模型 → 待保存的 profile 列表。
 *
 * 空白与重复的模型名先剔掉；[existingId] 非空（编辑已有条目）且只勾了一条时沿用原 id，
 * 否则按「m-<时间戳>-<序号>」生成 —— 一次多选不会互相覆盖，也不会覆盖手填的旧条目。
 */
fun buildProfiles(
    draft: ModelDraft,
    models: List<String>,
    existingId: String? = null,
    nowMs: Long = 0L,
): List<ModelProfile> {
    val picked = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    return picked.mapIndexed { index, model ->
        ModelProfile(
            id = existingId?.takeIf { picked.size == 1 } ?: ("m-" + nowMs + "-" + index),
            name = draft.name.trim().ifBlank { model },
            providerId = draft.providerId.ifBlank { "openai-compatible" },
            baseUrl = draft.baseUrl.trim(),
            apiKey = draft.apiKey.trim(),
            model = model,
            isDefault = draft.isDefault,
            contextWindow = draft.contextWindow,
            reasoningEffort = sanitizeReasoningEffort(draft.reasoningEffort),
            maxOutputTokens = draft.maxOutputTokens,
            temperature = draft.temperature,
            topP = draft.topP,
            supportsTools = draft.supportsTools,
            reasoningStyle = draft.reasoningStyle,
            supportsImages = draft.supportsImages,
            supportsOcr = draft.supportsOcr,
            reasoningMap = draft.reasoningMap,
        )
    }
}
