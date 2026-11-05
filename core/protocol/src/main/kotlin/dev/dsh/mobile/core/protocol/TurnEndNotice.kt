package dev.dsh.mobile.core.protocol

internal fun shouldOfferTools(round: Int, maxRounds: Int): Boolean = round < maxRounds - 1

/**
 * 回合收尾的「留痕」文案。
 *
 * 背景：工具超时 / 工具死循环 / 轮数用尽这几条路径以前只发 ToolCallEvent + TurnEnd，
 * 一条正文都不发。ChatRepository.finalizeTurn 只在正文非空时落库
 * （core/data/.../repo/ChatRepository.kt:596 附近），所以会话里只剩一张工具卡，
 * 用户得自己来问「怎么断了」。这里把「该不该补一条说明」抽成纯函数，方便单测。
 *
 * @param reason 即将发出的 TurnEnd 的 reason
 * @param visibleChars 本轮已发出的正文字符数
 * @param lastToolError 最近一次工具失败摘要（[toolFailureTrace] 的产物）
 * @param roundsExhausted 是否因为工具轮数用尽而收尾
 * @param maxRounds 轮数上限，只用于文案
 * @param loopDetail 死循环判定给出的说明
 * @return 需要补发的正文；null = 什么都不用补
 */
internal fun turnEndNotice(
    reason: String,
    visibleChars: Int,
    lastToolError: String?,
    roundsExhausted: Boolean,
    maxRounds: Int,
    loopDetail: String? = null,
): String? {
    // 本轮已经说过话就不补：MessageChunk 是追加到同一个气泡的，
    // 收尾再塞一行会把模型的正文劈成两半，比不说更难看。
    if (visibleChars > 0) return null
    if (reason == "tool_loop") {
        val why = loopDetail?.trim()?.takeIf { it.isNotEmpty() }?.let { ": " + it.take(120) }.orEmpty()
        return "[Stopped] Repeated tool call detected" + why + ". This turn was stopped to avoid spinning."
    }
    if (roundsExhausted) {
        return "[Turn incomplete] Tool rounds exhausted (limit " + maxRounds +
            "), and the last step produced no text. Ask me to continue, or narrow the task and retry."
    }
    if (!lastToolError.isNullOrBlank()) {
        return "[Tool failed] " + lastToolError.trim().take(200) + ". The error was fed back to the model, but no follow-up text came out."
    }
    // 走到这里说明上游这一轮既没有正文、也没有工具调用，就是纯空回复。
    return "[Empty reply] Upstream returned empty content (no text and no tool call). Send it again, or try another model."
}

/** 工具失败的一行摘要：命令前 60 字 + 错误，换行压平，免得把正文撑成多段。 */
internal fun toolFailureTrace(command: String, error: String?): String {
    val cmd = command.trim().replace('\n', ' ').take(60).ifBlank { "(empty command)" }
    val err = (error ?: "unknown error").trim().replace('\n', ' ').take(120)
    return cmd + ": " + err
}
