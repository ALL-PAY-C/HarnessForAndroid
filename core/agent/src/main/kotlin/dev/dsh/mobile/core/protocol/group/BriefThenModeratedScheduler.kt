package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.platform.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 主持人系统提示：只决定谁说话，不参与讨论、不写正文。 */
internal const val MODERATOR_SYSTEM_PROMPT =
    "你是多 Agent 群聊的主持人。你不参与讨论、不写正文、不评价内容质量，" +
        "只决定「下一位由谁说」。你手上没有任何与会者的上下文，只有调度所需的房间状态。" +
        "只输出一行 JSON，不要解释、不要 markdown 代码块。"

/** 主持人一次最多输出多少 token：它只回一行 JSON，给多了纯浪费。 */
internal const val MODERATOR_MAX_TOKENS = 300

/**
 * brief-then-moderated 调度（设计文档 §4）。
 *
 * Phase A（简报，首轮）：全员各发言一次，顺序按 memberId 的 **code-unit 升序** ——
 *   可复现、可审计，不用随机也不用 join 顺序。第 1 轮人人有输出时信息量最大，成本却只有 N 次调用。
 *
 * Phase B（主持，后续）：把房间快照交给主持人模型，由它指定下一位发言者。
 *   主持人**不持有任何与会者上下文**，输入只有四样：群聊记录（按预算截断）、成员名单与状态、
 *   账本摘要、剩余预算；输出必须是 {action, memberId?, reason, n?}。
 *   解析失败重试 1 次，仍失败就落回圆桌（全员按 id 序轮流），不猜。
 *
 * 无模型时的兜底：按 [scoreOf] 的数值优先级挑人，并把得分明细写进 reason，
 * 保证「为什么又让它说话」永远答得出来。
 */
class BriefThenModeratedScheduler(
    /** 主持人一次往返：prompt → 原始文本。空串表示没有可用模型（走优先级兜底）。 */
    private val moderator: suspend (String) -> String,
) : GroupScheduler {

    /** Phase A 的顺序：memberId code-unit 升序（可复现）。 */
    override fun roundtableOrder(room: RoomView): List<MemberState> = room.members.sortedBy { it.id }

    override suspend fun next(room: RoomView): SchedulerDecision {
        if (room.briefPhase) {
            return SchedulerDecision.Roundtable(
                "首轮简报：全员各发言一次，按 memberId 升序（" + room.members.size + " 位）",
            )
        }
        val prompt = room.moderatorPrompt()
        val first = ask(prompt)
        if (first.isBlank()) return priorityFallback(room, "主持人不可用")
        parse(first, room)?.let { return it }
        AppLog.w("主持人：输出无法解析，重试 1 次；原文=<" + first.take(200) + ">")
        val second = ask(prompt + "\n\n（上一次输出无法解析。请严格只输出一行 JSON，不要任何其它字符。）")
        parse(second, room)?.let { return it }
        return SchedulerDecision.Roundtable(
            "主持人输出无法解析（已重试 1 次），落回圆桌：全员按 memberId 升序轮流发言",
        )
    }

    private suspend fun ask(prompt: String): String =
        runCatching { moderator(prompt) }.getOrElse { e ->
            AppLog.w("主持人：调用失败 " + (e.message ?: e.javaClass.simpleName))
            ""
        }

    /**
     * 解析主持人输出：取第一个 '{' 到最后一个 '}' 之间那段（模型常裹一层说明或代码块）。
     * 返回 null 表示这次输出不可用（调用方重试/落回圆桌）。
     */
    fun parse(raw: String, room: RoomView): SchedulerDecision? {
        val from = raw.indexOf('{')
        val to = raw.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        val obj = runCatching { LENIENT.parseToJsonElement(raw.substring(from, to + 1)).jsonObject }
            .getOrNull() ?: return null
        val action = obj.str("action")?.trim()?.lowercase() ?: return null
        val reason = obj.str("reason")?.trim().orEmpty().ifBlank { "主持人未给原因" }
        val candidates = room.candidates()
        return when (action) {
            "next_speaker" -> {
                val id = obj.str("memberId")?.trim().orEmpty()
                val target = candidates.firstOrNull { it.id == id } ?: return null
                SchedulerDecision.NextSpeaker(target.id, reason)
            }
            "roundtable" -> SchedulerDecision.Roundtable(reason)
            "done" -> {
                // 设计文档 §5：AND(VerdictDone, 无未完成任务)。账本还有活就不能收工。
                val open = room.ledger.openTasks.size
                if (open > 0) {
                    SchedulerDecision.Roundtable(
                        "主持人说收工，但账本还有 " + open + " 条未完成任务，改为继续（" + reason + "）",
                    )
                } else {
                    SchedulerDecision.Done(reason)
                }
            }
            "escalate" -> SchedulerDecision.Escalate(reason)
            "ask_user" -> SchedulerDecision.AskUser(obj.str("question")?.trim()?.ifBlank { reason } ?: reason)
            else -> null
        }
    }

    /**
     * 无模型时的可解释兜底：数值化发言优先级（设计文档 §4）。
     *
     * 强制 @点名 1000 / 用户直呼 500 / 账本有它的待办 300+未完成数 / 未发言过 200 /
     * 与上一位发言人不同 +50（防同一人连续占麦）/ 久未说话 +轮次间隔。
     * 同分按 id 升序取，保证同一房间状态两次运行结果一致。
     */
    fun priorityFallback(room: RoomView, why: String): SchedulerDecision {
        val candidates = room.candidates()
        if (candidates.isEmpty()) return SchedulerDecision.Done("没有可发言的成员（全部被阻断）")
        val best = candidates.sortedWith(compareByDescending<MemberState> { scoreOf(it, room) }.thenBy { it.id })
            .first()
        return SchedulerDecision.NextSpeaker(
            best.id,
            why + "，按发言优先级兜底：" + best.name + " 得分 " + scoreOf(best, room) +
                "（" + breakdownOf(best, room) + "）",
        )
    }
}

/** 发言优先级打分。数值本身写在 [BriefThenModeratedScheduler.priorityFallback] 的注释里。 */
fun scoreOf(m: MemberState, room: RoomView): Int {
    var s = 0
    if (m.forced) s += 1000 else if (m.named) s += 500
    if (m.pendingTasks > 0) s += 300 + m.pendingTasks
    if (m.spokeCount <= 0) s += 200
    if (room.lastSpeakerId != null && m.id != room.lastSpeakerId) s += 50
    s += if (m.lastSpokeRound < 0) room.round + 1 else (room.round - m.lastSpokeRound)
    return s
}

/** 得分明细，进 reason：用户看得见每一分是怎么来的。 */
fun breakdownOf(m: MemberState, room: RoomView): String {
    val parts = ArrayList<String>()
    if (m.forced) parts.add("强制@+1000") else if (m.named) parts.add("直呼+500")
    if (m.pendingTasks > 0) parts.add("账本待办+" + (300 + m.pendingTasks))
    if (m.spokeCount <= 0) parts.add("未发言+200")
    if (room.lastSpeakerId != null && m.id != room.lastSpeakerId) parts.add("非上一位+50")
    parts.add("久未说话+" + (if (m.lastSpokeRound < 0) room.round + 1 else room.round - m.lastSpokeRound))
    return parts.joinToString(" ")
}

private val LENIENT = Json { isLenient = true; ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

/**
 * 主持人输入：只有四样东西（设计文档 §4）——群聊记录、成员名单与状态、账本摘要、剩余预算。
 * 刻意不带成员人设、模型名、历史全文：主持人只做调度，不该拥有与会者上下文。
 */
fun RoomView.moderatorPrompt(): String = buildString {
    append("群聊记录（已按预算截断，[轮次] 名字: 摘要）：\n")
    append(transcriptText())
    append("\n\n成员名单与状态：\n")
    append(memberLines())
    append("\n\n账本摘要：\n")
    append(ledger.summary())
    append("\n\n剩余预算：\n")
    append(budgetLeft.line())
    if (budgetLeft.tight) append("\n（预算已过 80%，倾向收工）")
    append("\n\n请只输出一行 JSON：")
    append("{\"action\":\"next_speaker|roundtable|done|escalate|ask_user\",")
    append("\"memberId\":\"<成员 id，仅 next_speaker 需要>\",")
    append("\"reason\":\"<一句话，会显示在群里>\",\"n\":1}")
}
