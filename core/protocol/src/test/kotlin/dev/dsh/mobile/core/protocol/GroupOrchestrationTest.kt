package dev.dsh.mobile.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.2a 三处硬伤：静默截断 → 显式顺延；并行分片随发言顺序漂移；终止条件贫乏
 * （打回计数不清零 / 中文轮次不认 / 无僵局检测）。
 */
class GroupOrchestrationTest {

    private fun cfg() = LlmConfig("http://127.0.0.1:1", "k", "m")

    private fun planOf(vararg names: String, routing: GroupRouting = GroupRouting.All): GroupPlan =
        GroupPlan(names.map { GroupMember("id-" + it, it, "", cfg()) }, routing = routing)

    // ---- ① 静默截断：7 人房首轮只 4 人发言，其余 3 人进 deferred 并有可见说明

    @Test
    fun `seven member room speaks four and defers the rest with a visible notice`() {
        val plan = planOf("甲一", "乙二", "丙三", "丁四", "戊五", "己六", "庚七")
        val round = planGroupRound("s", plan, "看看这个报错", emptyList())
        assertEquals("本轮只叫 MAX_GROUP_SPEAKERS 位", MAX_GROUP_SPEAKERS, round.tasks.size)
        assertEquals("其余 3 位必须被记下来", 3, round.deferred.size)
        val notice = round.notice.orEmpty()
        assertTrue("说明要有 4/7：<" + notice + ">", notice.contains("4/7"))
        assertTrue("说明要点出 3 位：<" + notice + ">", notice.contains("其余 3 位"))
        val deferredNames = plan.members.filter { it.id in round.deferred }.map { it.name }
        assertTrue("说明要点名：<" + notice + ">", deferredNames.all { notice.contains(it) })
    }

    @Test
    fun `deferred members speak first next round`() {
        val plan = planOf("甲一", "乙二", "丙三", "丁四", "戊五", "己六", "庚七")
        val first = planGroupRound("s", plan, "看看这个报错", emptyList())
        val next = planGroupRound("s", plan, "继续", emptyList(), priorityIds = first.deferred)
        assertEquals(MAX_GROUP_SPEAKERS, next.tasks.size)
        assertEquals("上一轮没轮到的先上", first.deferred, next.tasks.map { it.agentId }.take(first.deferred.size))
        assertEquals("这一轮没轮到的换人了", 3, next.deferred.size)
        assertNotEquals(first.deferred, next.deferred)
    }

    @Test
    fun `plan carries pending speakers so the host can persist it`() {
        val plan = planOf("甲一", "乙二", "丙三", "丁四", "戊五")
        val round = planGroupRound("s", plan.copy(pendingSpeakerIds = listOf("id-戊五")), "看看这个报错", emptyList())
        assertEquals("plan 上的待发言成员本轮先上", "id-戊五", round.tasks.first().agentId)
    }

    @Test
    fun `small room produces no notice`() {
        val round = planGroupRound("s", planOf("甲一", "乙二"), "看看这个报错", emptyList())
        assertEquals(2, round.tasks.size)
        assertTrue("没截断就别刷说明", round.deferred.isEmpty())
        assertEquals(null, round.notice)
    }

    // ---- ② 并行分片：按 memberId 稳定序，与发言顺序解耦

    @Test
    fun `parallel slices follow member id order not list order`() {
        val c = cfg()
        val plan = GroupPlan(
            listOf(
                GroupMember("c", "丙", "", c),
                GroupMember("a", "甲", "", c),
                GroupMember("b", "乙", "", c),
            ),
        )
        val tasks = planGroupRound(
            "s", plan, "写登录页。写注册页。写找回密码页。", emptyList(), mode = GroupMode.Parallel,
        ).tasks
        assertTrue("a 拿第 1 片", tasks.first { it.agentId == "a" }.prompt.contains("登录页"))
        assertTrue("b 拿第 2 片", tasks.first { it.agentId == "b" }.prompt.contains("注册页"))
        assertTrue("c 拿第 3 片", tasks.first { it.agentId == "c" }.prompt.contains("找回密码页"))
    }

    @Test
    fun `parallel split is reproducible across turns with different last speaker`() {
        val plan = planOf("甲一", "乙二", "丙三")
        val text = "写登录页。写注册页。写找回密码页。"
        val a = planGroupRound("s", plan, text, emptyList(), mode = GroupMode.Parallel)
            .tasks.associate { it.agentId to it.prompt }
        val b = planGroupRound("s", plan, text, emptyList(), lastSpeakerId = "id-丙三", mode = GroupMode.Parallel)
            .tasks.associate { it.agentId to it.prompt }
        assertEquals("换一个上一个发言人，分片结果必须一致", a, b)
    }

    // ---- ③ 打回计数：成功一轮后归零

    @Test
    fun `rework counter resets after any passing round`() {
        var n = nextReworkCount(0, Verdict.Rework)
        assertEquals(1, n)
        n = nextReworkCount(n, Verdict.Rework)
        assertEquals(2, n)
        assertEquals("【继续】算通过", 0, nextReworkCount(n, Verdict.More))
        assertEquals("【完成】也算通过", 0, nextReworkCount(n, Verdict.Done))
    }

    @Test
    fun `consecutive rework still stops at the cap`() {
        var n = 0
        repeat(MAX_AUTOMATON_REWORK) { n = nextReworkCount(n, Verdict.Rework) }
        assertEquals(MAX_AUTOMATON_REWORK, n)
        assertEquals("过一轮之后重新计数", 1, nextReworkCount(nextReworkCount(n, Verdict.More), Verdict.Rework))
    }

    // ---- 中文数量词轮次

    @Test
    fun `chinese round count is recognized`() {
        assertEquals(3, automatonRounds("跑三轮"))
        assertEquals(2, automatonRounds("跑两轮"))
        assertEquals(1, automatonRounds("跑一轮"))
        assertEquals(5, automatonRounds("跑五轮"))
    }

    @Test
    fun `explicit round count wins but is clamped to the cap`() {
        assertEquals(5, automatonRounds("跑 5 轮"))
        assertEquals("十轮不能超过上限", MAX_AUTOMATON_ROUNDS, automatonRounds("跑十轮"))
        assertEquals("十二轮不能超过上限", MAX_AUTOMATON_ROUNDS, automatonRounds("连续十二轮"))
        assertEquals(8, MAX_AUTOMATON_ROUNDS)
    }

    @Test
    fun `no explicit count keeps the old wording rules`() {
        assertEquals(MAX_AUTOMATON_ROUNDS, automatonRounds("CN 算 x+1 直到 x=10"))
        assertEquals(1, automatonRounds("帮我验收一下这个页面"))
    }

    // ---- 僵局检测

    @Test
    fun `stall trips after two quiet rounds`() {
        val d = StallDetector()
        assertFalse("第一轮还不够", d.onRound(listOf("嗯", "好"), toolCalls = 0, newTasks = 0))
        assertTrue("第二轮仍在原地", d.onRound(listOf("", "同上"), toolCalls = 0, newTasks = 0))
        assertTrue("判定依据要能照读", d.reason.orEmpty().contains("没有工具调用"))
    }

    @Test
    fun `tool call or new task breaks the stall streak`() {
        val a = StallDetector()
        a.onRound(listOf("嗯"), toolCalls = 0, newTasks = 0)
        assertFalse("动了工具就不算卡死", a.onRound(listOf("嗯"), toolCalls = 1, newTasks = 0))
        val b = StallDetector()
        b.onRound(listOf("嗯"), toolCalls = 0, newTasks = 0)
        assertFalse("有新任务就不算卡死", b.onRound(listOf("嗯"), toolCalls = 0, newTasks = 1))
    }

    @Test
    fun `long speech is not a stall`() {
        val d = StallDetector()
        val long = "x".repeat(STALL_MIN_CHARS)
        assertFalse(d.onRound(listOf(long), toolCalls = 0, newTasks = 0))
        assertFalse(d.onRound(listOf(long), toolCalls = 0, newTasks = 0))
    }
}
