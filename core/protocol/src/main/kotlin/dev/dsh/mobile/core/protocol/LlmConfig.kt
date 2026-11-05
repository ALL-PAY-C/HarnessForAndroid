package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ReasoningStyle

/** 一个可接入的模型后端描述（协议层只认这些字段，不依赖数据层）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = DEFAULT_SYSTEM,
    /** 单轮 max_tokens；0 表示不限制（推理模型会写出几万字思维链，群组一轮能跑十分钟）。 */
    val maxTokens: Int = 0,
    /** 思考强度 off|low|medium|high；实际发不发、怎么发由 [reasoningStyle] 决定。 */
    val reasoningEffort: String = "high",
    /** 提供方 id（推断思考风格用）；空串 = 未知，按 baseUrl/模型名推。 */
    val providerId: String = "",
    /** 思考参数发送方式；[ReasoningStyle.auto] = 按 providerId/baseUrl/模型名推断。 */
    val reasoningStyle: ReasoningStyle = ReasoningStyle.auto,
    /** 采样温度，<0 表示不发送该参数（由上游默认）。 */
    val temperature: Float = -1f,
    /** top_p，<0 表示不发送该参数。 */
    val topP: Float = -1f,
    /** 模型是否支持 function calling；false 时不发 tools，避免 400。 */
    val supportsTools: Boolean = true,
    /**
     * 兜底模型链（默认空）：人设会话被拒答时，第 2 次重试用这里的下一个模型。
     * 留空就只用主模型重试 —— 上游只有一把 Key/一个模型时什么都不用配。
     */
    val fallbackModels: List<String> = emptyList(),
    /** 推理档位映射（M5）：每行 `档位=参数名:值`；空串 = 不映射，行为与旧版一致。 */
    val reasoningMap: String = "",
) {
    companion object {
        val DEFAULT_SYSTEM = """
            You are a DSH agent running on the user's Android phone.
            - Call the shell tool when you need to operate the phone (commands run on the device itself and return stdout).
            - Common capabilities: am/pm/settings/dumpsys, file read/write, curl, python (if Termux is installed).
            - Never invent command output; when a command fails, adjust based on stderr.
            - Reply in Chinese, concise and direct.
            - When the user must pick one of several options, write the body as usual and append a separate options block at the end, in this format:
              [[OPTIONS]]
              A: first option
              B: second option
              [[/OPTIONS]]
              The client renders it as tappable buttons; tapping one means the user picked that line.
        """.trimIndent()
    }
}
