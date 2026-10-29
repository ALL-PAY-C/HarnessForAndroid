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
        val why = loopDetail?.trim()?.takeIf { it.isNotEmpty() }?.let { "：" + it.take(120) }.orEmpty()
        return "[已中止] 检测到重复工具调用" + why + "。本轮已停止，避免继续空转。"
    }
    if (roundsExhausted) {
        return "[本轮未完成] 工具轮次已用尽（上限 " + maxRounds +
            "），最后一步没有产出正文。可以让我继续，或缩小任务范围重试。"
    }
    if (!lastToolError.isNullOrBlank()) {
        return "[工具失败] " + lastToolError.trim().take(200) + "。已把错误回灌模型，但没有得到后续正文。"
    }
    // 走到这里说明上游这一轮既没有正文、也没有工具调用，就是纯空回复。
    return "[空回复] 上游返回了空内容（没有正文也没有工具调用）。可以重发一次，或换个模型试试。"
}

/** 工具失败的一行摘要：命令前 60 字 + 错误，换行压平，免得把正文撑成多段。 */
internal fun toolFailureTrace(command: String, error: String?): String {
    val cmd = command.trim().replace('\n', ' ').take(60).ifBlank { "（空命令）" }
    val err = (error ?: "未知错误").trim().replace('\n', ' ').take(120)
    return cmd + "：" + err
}
