package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.protocol.GroupMember
import dev.dsh.mobile.core.protocol.GroupMode
import dev.dsh.mobile.core.protocol.GroupPlan
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.planGroupRound
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.2b 调度器单测：只打纯函数与可注入的「主持人」回调，不碰网络、不碰引擎。
 */
class ModeratorSchedulerTest {

    private fun cfg() = LlmConfig("http://127.0.0.1:1", "k", "m")

    private fun member(id: String, name: String = id) = GroupMember(id, name, "", cfg())

    private fun planOf(vararg ids: String): GroupPlan =
        GroupPlan(ids.map { member(it) }, mode = "chain")

    private fun roomOf(
        plan: GroupPlan,
        round: Int = 0,
        spoke: Int = 0,
        transcript: List<TranscriptLine> = emptyList(),
        lastSpeakerId: String? = null,
        charsUsed: Int = 0,
        charBudget: Int = 24_000,
        ledger: LedgerView = LedgerView(),
    ) = RoomView(
        transcript = transcript,
        members = plan.members.map { m ->
            MemberState(id = m.id, name = m.name, spokeCount = spoke, lastSpokeRound = if (spoke > 0) round - 1 else -1)
        },
        ledger = ledger,
        budgetLeft = BudgetView(round = round, charsUsed = charsUsed, charBudget = charBudget),
        round = round,
        lastSpeakerId = lastSpeakerId,
    )

    // ---- ① Phase A：首轮按 memberId 升序，全员各一次 ----

    @Test
    fun `首轮简报按 memberId 升序全员各发言一次`() = runBlocking {
        val plan = planOf("id-c", "id-a", "id-b")
        val room = roomOf(plan, round = 0, spoke = 0)
        assertTrue("首轮应判定为简报阶段", room.briefPhase)
        val scheduler = BriefThenModeratedScheduler { error("首轮不该问主持人") }
        val decision = scheduler.next(room)
        assertTrue("首轮必须是 Roundtable，实际 " + decision, decision is SchedulerDecision.Roundtable)
        val order = scheduler.roundtableOrder(room).map { it.id }
        assertEquals(listOf("id-a", "id-b", "id-c"), order)
        assertEquals("全员各一次，不多不少", plan.members.size, order.size)
    }

    // ---- ② Phase B：主持人指定的下一位被遵守 ----

    @Test
    fun `主持人指定的下一位发言者被遵守`() = runBlocking {
        val plan = planOf("id-a", "id-b", "id-c")
        val room = roomOf(plan, round = 2, spoke = 1, lastSpeakerId = "id-a")
        assertTrue("第 3 轮不该再是简报阶段", !room.briefPhase)
        val scheduler = BriefThenModeratedScheduler {
            """{"action":"next_speaker","memberId":"id-c","reason":"账本里它还有未完成的任务","n":1}"""
        }
        val decision = scheduler.next(room)
        assertTrue("应为 NextSpeaker，实际 " + decision, decision is SchedulerDecision.NextSpeaker)
        val next = decision as SchedulerDecision.NextSpeaker
        assertEquals("id-c", next.memberId)
        assertEquals("账本里它还有未完成的任务", next.reason)
    }

    // ---- ③ 解析失败重试 1 次后落回 Roundtable ----

    @Test
    fun `主持人输出无法解析时重试一次后落回圆桌`() = runBlocking {
        val plan = planOf("id-a", "id-b")
        val room = roomOf(plan, round = 2, spoke = 1, lastSpeakerId = "id-a")
        var calls = 0
        val scheduler = BriefThenModeratedScheduler {
            calls++
            "我觉得应该让 id-b 先说" // 没有 JSON
        }
        val decision = scheduler.next(room)
        assertEquals("解析失败必须重试且只重试 1 次", 2, calls)
        assertTrue("应落回 Roundtable，实际 " + decision, decision is SchedulerDecision.Roundtable)
        assertTrue(
            "原因里要说明是解析失败：" + (decision as SchedulerDecision.Roundtable).reason,
            decision.reason.contains("无法解析"),
        )
        // 主持人不可用（空串）时走可解释的优先级兜底，而不是硬停。
        val fallback = BriefThenModeratedScheduler { "" }.next(room)
        assertTrue("空输出应走优先级兜底，实际 " + fallback, fallback is SchedulerDecision.NextSpeaker)
        assertTrue(
            "兜底原因要带得分明细：" + (fallback as SchedulerDecision.NextSpeaker).reason,
            fallback.reason.contains("得分"),
        )
    }

    // ---- ④ 三种终止各自触发并给可读原因 ----

    @Test
    fun `轮次上限预算耗尽与僵局各自触发并给出可读原因`() {
        val maxRounds = evaluateStop(
            StopSignals(round = MAX_ROOM_ROUNDS),
            BudgetView(round = MAX_ROOM_ROUNDS, maxRounds = MAX_ROOM_ROUNDS),
        )
        assertNotNull(maxRounds)
        assertEquals(StopKind.MaxRounds, maxRounds!!.kind)
        assertTrue("原因要能读：" + maxRounds.reason, maxRounds.reason.contains("轮次上限"))

        val budget = evaluateStop(
            StopSignals(round = 1),
            BudgetView(round = 1, charsUsed = 24_000, charBudget = 24_000),
        )
        assertNotNull(budget)
        assertEquals(StopKind.GlobalBudget, budget!!.kind)
        assertTrue("原因要能读：" + budget.reason, budget.reason.contains("预算用完"))

        val stall = evaluateStop(
            StopSignals(round = 2, stall = true, stallReason = "连续 2 轮没有新信息"),
            BudgetView(round = 2),
        )
        assertNotNull(stall)
        assertEquals(StopKind.Stall, stall!!.kind)
        assertTrue("原因要带判定依据：" + stall.reason, stall.reason.contains("连续 2 轮没有新信息"))

        // 优先级：用户打断压过一切；预算耗尽压过僵局。
        assertEquals(
            StopKind.UserInterrupt,
            evaluateStop(
                StopSignals(userInterrupted = true, round = MAX_ROOM_ROUNDS, stall = true),
                BudgetView(round = MAX_ROOM_ROUNDS, charsUsed = 24_000),
            )!!.kind,
        )
        assertEquals(
            StopKind.GlobalBudget,
            evaluateStop(
                StopSignals(stall = true),
                BudgetView(charsUsed = 24_000),
            )!!.kind,
        )
        assertNull(
            "没到任何一条线就不该停",
            evaluateStop(StopSignals(round = 1), BudgetView(round = 1)),
        )
    }

    // ---- ⑤ LegacyScheduler 与原 planGroupRound 逐字段等价 ----

    @Test
    fun `LegacyScheduler 与 planGroupRound 逐字段等价`() = runBlocking {
        val plan = planOf("id-a", "id-b", "id-c", "id-d", "id-e", "id-f")
        val userText = "大家都说说各自看到的情况"
        val round = planGroupRound(
            sessionId = "s",
            plan = plan,
            userText = userText,
            history = emptyList(),
            mode = GroupMode.Chain,
        )
        val scheduler = LegacyScheduler(plan, userText)
        val room = roomOf(plan, round = 0, spoke = 0)
        // 候选顺序：同一份 orderForTurn + withPriority
        assertEquals(
            round.tasks.map { it.agentId },
            scheduler.speakerOrder(room).take(round.tasks.size).map { it.id },
        )
        // 顺延名单：逐字段相同
        assertEquals(round.deferred, scheduler.deferred(room))
        // notice 文案：同一个现象不能有两种说法
        val decision = scheduler.next(room)
        assertEquals(round.notice, (decision as SchedulerDecision.Roundtable).reason)
    }
}
