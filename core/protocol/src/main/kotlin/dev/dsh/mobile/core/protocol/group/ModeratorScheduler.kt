package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.protocol.STALL_MIN_CHARS
import dev.dsh.mobile.core.protocol.digest

/**
 * 群聊调度器契约（T5.2b）。
 *
 * 为什么单独成包：GroupSupport.kt 已经 786 行，再往里塞「主持人 + 终止条件 + 优先级打分」
 * 会把 T5.2a 的纯函数群搅成一片。调度器只回**决策**，不回任务 —— 任务仍由
 * planGroupRound 拼，这样 T5.2a 的截断/顺延/点名逻辑只有一份实现。
 *
 * 一次 [GroupScheduler.next] = 一轮。谁消费这个决策（在群聊里派活）由传输层决定。
 */
interface GroupScheduler {
    /** 看房间现状，给出这一轮该怎么办。 */
    suspend fun next(room: RoomView): SchedulerDecision

    /**
     * [SchedulerDecision.Roundtable] 时按什么顺序全员发言。
     * 默认沿用房间成员原序（旧调度行为）；brief 阶段覆盖成 memberId 升序。
     */
    fun roundtableOrder(room: RoomView): List<MemberState> = room.members
}

/** 编排器开关取值：默认旧调度（零行为变化），另一档走 brief-then-moderated。 */
const val GROUP_SCHEDULER_LEGACY = "legacy"
const val GROUP_SCHEDULER_MODERATED = "brief-moderated"

/** 房间一轮最多跑几位：沿用 T5.2a 的硬上限，别在调度层另造一个数字。 */
internal const val MAX_ROOM_ROUNDS = 8

/** 预算告急线：到这个比例就在群里喊一声（设计文档 §7）。 */
internal const val BUDGET_WARN_RATIO = 0.8f

/** 房间墙钟上限：再久就不是群聊而是挂机了。 */
internal const val ROOM_WALL_CLOCK_MS = 10 * 60_000L

/** 主持人看群聊记录时最多占预算的多少：剩下的一半留给成员状态与账本。 */
internal const val MODERATOR_TRANSCRIPT_SHARE = 0.5f

/** 连续几轮「除一人外没人说出新东西」判 SilenceAllButOne。 */
internal const val SILENCE_WINDOW = 2

/** 群聊记录的一行：谁在第几轮说了什么（摘要）。 */
data class TranscriptLine(
    val speakerId: String,
    val speakerName: String,
    val text: String,
    val round: Int = 0,
)

/**
 * 一个与会者在调度眼里的状态。
 *
 * [forced] / [named] 是**用户**这一轮的意图（强制 @ / 直呼），不是模型的判断；
 * [pendingTasks] 来自账本，[reworked] 表示上一轮被验收打回。
 */
data class MemberState(
    val id: String,
    val name: String,
    val spokeCount: Int = 0,
    val lastSpokeRound: Int = -1,
    val reworked: Boolean = false,
    val pendingTasks: Int = 0,
    val forced: Boolean = false,
    val named: Boolean = false,
    val blocked: Boolean = false,
) {
    /** 给主持人看的一行；不含模型名与任何上下文，只有可调度的状态。 */
    fun statusLine(round: Int): String = buildString {
        append(name).append("(").append(id).append(")")
        append(if (spokeCount <= 0) " 未发言" else " 已发言 " + spokeCount + " 次")
        if (lastSpokeRound >= 0) append(" / 上次第 ").append(lastSpokeRound + 1).append(" 轮")
        if (reworked) append(" / 被打回")
        if (pendingTasks > 0) append(" / 待办 ").append(pendingTasks)
        if (forced) append(" / 被强制@")
        if (named) append(" / 被点名")
        if (blocked) append(" / 已阻断")
        if (lastSpokeRound >= 0 && round - lastSpokeRound > 1) append(" / 已 ").append(round - lastSpokeRound).append(" 轮没说话")
    }
}

/** 房间账本摘要：未完成任务 + 未解决问题（T5.3 才真正产出任务，这里先留结构）。 */
data class LedgerView(
    val openTasks: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val doneCount: Int = 0,
    val reworkCount: Int = 0,
) {
    /** 一行摘要，进主持人输入。超长按条数截，别把预算吃光。 */
    fun summary(limit: Int = 400): String {
        val sb = StringBuilder()
        sb.append("未完成任务 ").append(openTasks.size).append(" 条")
        if (openTasks.isNotEmpty()) sb.append("：").append(openTasks.take(5).joinToString("；"))
        sb.append("；未解决问题 ").append(openQuestions.size).append(" 条")
        if (openQuestions.isNotEmpty()) sb.append("：").append(openQuestions.take(5).joinToString("；"))
        sb.append("；已完成 ").append(doneCount).append(" 条；被打回 ").append(reworkCount).append(" 次")
        return if (sb.length <= limit) sb.toString() else sb.substring(0, limit - 1) + "…"
    }
}

/** 剩余预算：轮次 / 字符 / 墙钟。三者任一用尽就是 GlobalBudget。 */
data class BudgetView(
    val round: Int = 0,
    val maxRounds: Int = MAX_ROOM_ROUNDS,
    val charsUsed: Int = 0,
    val charBudget: Int = 24_000,
    val elapsedMs: Long = 0L,
    val wallClockMs: Long = ROOM_WALL_CLOCK_MS,
) {
    val charRatio: Float get() = if (charBudget <= 0) 0f else charsUsed.toFloat() / charBudget
    val timeRatio: Float get() = if (wallClockMs <= 0) 0f else elapsedMs.toFloat() / wallClockMs
    val roundRatio: Float get() = if (maxRounds <= 0) 0f else round.toFloat() / maxRounds

    /** 取三者的最大占用率：哪个先到就是哪个先停。 */
    val ratio: Float get() = maxOf(charRatio, timeRatio)

    val exhausted: Boolean get() = charsUsed >= charBudget || elapsedMs >= wallClockMs

    /** 到告急线（80%）：只在群里喊一声，不散会。 */
    val tight: Boolean get() = ratio >= BUDGET_WARN_RATIO

    val percent: Int get() = (ratio * 100).toInt().coerceIn(0, 100)

    /** 给主持人看的一行剩余预算。 */
    fun line(): String = "轮次 " + (round + 1) + "/" + maxRounds +
        "；字符 " + charsUsed + "/" + charBudget + "（" + percent + "%）" +
        "；墙钟 " + (elapsedMs / 1000) + "s/" + (wallClockMs / 1000) + "s"
}

/**
 * 房间快照：调度器能看到的**全部**信息。
 *
 * 主持人刻意不持有与会者上下文（设计文档 §4）：它能看到的只有这四样 ——
 * 群聊记录（截断）、成员名单与状态、账本摘要、剩余预算。
 */
data class RoomView(
    val transcript: List<TranscriptLine>,
    val members: List<MemberState>,
    val ledger: LedgerView,
    val budgetLeft: BudgetView,
    /** 已经跑完几轮（0 = 还没开跑）。 */
    val round: Int = 0,
    val lastSpeakerId: String? = null,
) {
    /** 首轮简报阶段：还没人发过言。 */
    val briefPhase: Boolean get() = round <= 0 || members.none { it.spokeCount > 0 }

    val budget: BudgetView get() = budgetLeft

    /** 群聊记录按预算截断：优先保最近几轮，越早的越先丢。 */
    fun transcriptText(limitChars: Int = (budgetLeft.charBudget * MODERATOR_TRANSCRIPT_SHARE).toInt()): String {
        if (transcript.isEmpty()) return "（暂无发言）"
        val picked = ArrayList<TranscriptLine>()
        var used = 0
        for (line in transcript.asReversed()) {
            val cost = line.speakerName.length + line.text.length + 8
            if (used + cost > limitChars && picked.isNotEmpty()) break
            picked.add(line)
            used += cost
        }
        return picked.asReversed().joinToString("\n") { "[" + (it.round + 1) + "] " + it.speakerName + ": " + it.text }
    }

    /** 成员名单与状态（不含上下文、不含模型名）。 */
    fun memberLines(): String = members.joinToString("\n") { "- " + it.statusLine(round) }

    /** 可发言的候选（未被阻断）。 */
    fun candidates(): List<MemberState> = members.filter { !it.blocked }
}

/** 调度决策。reason 一律可读、进群聊 notice —— 任何自动行为都要能问责。 */
sealed interface SchedulerDecision {
    /** 点名让某一位说。 */
    data class NextSpeaker(val memberId: String, val reason: String) : SchedulerDecision

    /** 全员轮流说（圆桌）。顺序见 [GroupScheduler.roundtableOrder]。 */
    data class Roundtable(val reason: String) : SchedulerDecision

    /** 收工。 */
    data class Done(val reason: String) : SchedulerDecision

    /** 升级：这事超出房间能力（缺信息/缺权限），交回上层。 */
    data class Escalate(val reason: String) : SchedulerDecision

    /** 反问用户。 */
    data class AskUser(val question: String) : SchedulerDecision
}

/** 给群里看的一句话（任何自动行为都要能问责）。 */
val SchedulerDecision.explain: String
    get() = when (this) {
        is SchedulerDecision.NextSpeaker -> reason
        is SchedulerDecision.Roundtable -> reason
        is SchedulerDecision.Done -> reason
        is SchedulerDecision.Escalate -> reason
        is SchedulerDecision.AskUser -> question
    }

/** 终止条件的种类。顺序即优先级（设计文档 §5）。 */
enum class StopKind {
    UserInterrupt,
    VerdictDone,
    GlobalBudget,
    Stall,
    SilenceAllButOne,
    MaxRounds,
}

/** 一次终止判定：kind + 可读原因（原因直接进群聊 notice）。 */
data class StopSignal(val kind: StopKind, val reason: String)

/** 终止判定要看的信号；都由调用方按轮次喂进来。 */
data class StopSignals(
    val userInterrupted: Boolean = false,
    val verdictDone: Boolean = false,
    val stall: Boolean = false,
    val stallReason: String? = null,
    val silentStreak: Int = 0,
    val silentWindow: Int = SILENCE_WINDOW,
    val round: Int = 0,
)

/**
 * 终止条件组合（纯函数，单测直接打）。
 *
 * 优先级固定：UserInterrupt > VerdictDone > GlobalBudget > Stall > SilenceAllButOne > MaxRounds。
 * 为什么这个顺序：用户的手 > 验收者的结论 > 钱 > 卡死 > 没人说话 > 兜底轮次。
 * 每次触发都必须给可读原因 —— 群里要看得见「为什么停了」。
 */
fun evaluateStop(signals: StopSignals, budget: BudgetView): StopSignal? = when {
    signals.userInterrupted ->
        StopSignal(StopKind.UserInterrupt, "用户打断，立即散会。")
    signals.verdictDone ->
        StopSignal(StopKind.VerdictDone, "验收结论【完成】，散会。")
    budget.exhausted ->
        StopSignal(
            StopKind.GlobalBudget,
            "房间预算用完（字符 " + budget.charsUsed + "/" + budget.charBudget +
                "，墙钟 " + (budget.elapsedMs / 1000) + "s/" + (budget.wallClockMs / 1000) +
                "s），取消本轮全员广播并散会。",
        )
    signals.stall ->
        StopSignal(
            StopKind.Stall,
            "判定僵局：" + (signals.stallReason ?: "连续多轮没有新信息") + "，停止空转。",
        )
    signals.silentStreak >= signals.silentWindow ->
        StopSignal(
            StopKind.SilenceAllButOne,
            "连续 " + signals.silentStreak + " 轮除一人外没人说出新内容，散会。",
        )
    signals.round >= budget.maxRounds ->
        StopSignal(StopKind.MaxRounds, "撞到轮次上限 " + budget.maxRounds + "，散会。")
    else -> null
}

/**
 * 「除一人外无人有新增信息」计数器。
 *
 * 为什么单独一个：StallDetector 看的是「没有工具调用 + 发言都短」，那是原地打转；
 * 这里看的是**参与面**——一直是同一个人在说、别人只回「（无补充）」，也该停。
 */
class SilenceTracker(private val window: Int = SILENCE_WINDOW) {
    var streak: Int = 0
        private set

    /** 记录一轮：本轮真正说出新内容的成员 id。≤1 人算「只有一个人在说」。 */
    fun onRound(contributorIds: List<String>): Int {
        streak = if (contributorIds.distinct().size <= 1) streak + 1 else 0
        return streak
    }

    val silent: Boolean get() = streak >= window

    /** 判定依据一句话，进群聊 notice。 */
    val reason: String? get() = if (streak <= 0) null else "连续 " + streak + " 轮除一人外没人说出新内容"
}

/** 本轮真正说出新内容的人：非空、不是「（无补充）」、且够长（复用僵局字数线）。 */
fun contributorsOf(outputs: List<Pair<String, String>>): List<String> = outputs
    .filter { (_, text) ->
        val t = text.trim()
        t.length >= STALL_MIN_CHARS && t != "（无补充）" && t != "(无补充)"
    }
    .map { it.first }

/** 群聊记录的一行摘要（写账本/进 transcript 时统一用这个）。 */
fun transcriptOf(memberId: String, memberName: String, text: String, round: Int): TranscriptLine =
    TranscriptLine(memberId, memberName, digest(text, 200), round)
