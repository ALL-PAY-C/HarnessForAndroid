package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.model.AgentTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.3 房间账本单测：只打纯数据结构与纯函数（不碰网络、不碰执行池、不跑协程）。
 *
 * 覆盖规格 group-ledger.md 的可判定条款：去重键、三档上限淘汰、状态互斥、
 * openTasksOf 计数、view()/snapshot() 映射、空账本 digest、问号/关键词扫描、
 * 标题压缩到 120 字、「（无补充）」跳过、RoomLedgers 快照出口。
 */
class RoomLedgerTest {

    private var seq = 0

    private fun task(title: String, agent: String = "a1", id: String = "t" + (++seq)) = AgentTask(
        id = id,
        sessionId = "s1",
        agentId = agent,
        agentName = agent,
        prompt = title,
    )

    // ── 写入：任务 ────────────────────────────────────────────────────────────

    @Test
    fun mergeTasksDedupesByTitleNotById() {
        val ledger = RoomLedger("s1")
        ledger.mergeTasks(listOf(task("把导出功能加上去")), 0)
        // 同一件事换个 task id 再来一次（去重键是去空白标题前 60 字，不是 id）。
        ledger.mergeTasks(listOf(task("把导出功能加上去")), 1)
        assertEquals(1, ledger.view().openTasks.size)
    }

    @Test
    fun mergeTasksIgnoresSameTaskId() {
        val ledger = RoomLedger("s1")
        val t = task("写单元测试")
        ledger.mergeTasks(listOf(t), 0)
        ledger.mergeTasks(listOf(t), 1)
        assertEquals(1, ledger.view().openTasks.size)
    }

    @Test
    fun openTasksCap20DropsOldestFirst() {
        val ledger = RoomLedger("s1")
        repeat(MAX_OPEN_TASKS + 3) { i -> ledger.mergeTasks(listOf(task("任务编号 " + i)), 1) }
        val open = ledger.view().openTasks
        assertEquals(MAX_OPEN_TASKS, open.size)
        // 最旧的 3 条被挤掉：剩下的是第 3..22 条。
        assertFalse(open.any { it == "任务编号 0" })
        assertTrue(open.any { it == "任务编号 " + (MAX_OPEN_TASKS + 2) })
    }

    @Test
    fun titleTruncatedTo120AndKeepsFirstLineOnly() {
        val ledger = RoomLedger("s1")
        ledger.mergeTasks(listOf(task("甲".repeat(300) + "\n第二行不该进来")), 0)
        val title = ledger.view().openTasks.single()
        assertEquals(LEDGER_TITLE_CHARS + 1, title.length) // 120 字 + digest 的省略号
        assertFalse(title.contains("\n"))
        assertFalse(title.contains("第二行"))
    }

    // ── 状态机：Done / Reworked 都不再是 Open ─────────────────────────────────

    @Test
    fun markDoneRemovesFromOpenAndCountsOnce() {
        val ledger = RoomLedger("s1")
        val t = task("补日志")
        ledger.mergeTasks(listOf(t), 1)
        ledger.markDone(listOf(t.id))
        ledger.markDone(listOf(t.id))
        assertTrue(ledger.view().openTasks.isEmpty())
        assertEquals(1, ledger.view().doneCount)
        assertEquals(1, ledger.turnDoneCount)
        assertEquals(LedgerState.Done, ledger.snapshot().done.single().state)
    }

    @Test
    fun reworkNeverReturnsToOpenButStaysUnfinished() {
        val ledger = RoomLedger("s1")
        val t = task("改配色")
        ledger.mergeTasks(listOf(t), 1)
        ledger.markDone(listOf(t.id))
        ledger.markRework(listOf(t.id))
        ledger.markRework(listOf(t.id))
        assertEquals(LedgerState.Reworked, ledger.snapshot().open.single().state)
        assertEquals(1, ledger.view().reworkCount)
        assertEquals(1, ledger.turnReworkCount)
    }

    @Test
    fun beginTurnResetsPerTurnCountersOnly() {
        val ledger = RoomLedger("s1")
        val t = task("跑一次回归")
        ledger.mergeTasks(listOf(t), 1)
        ledger.markDone(listOf(t.id))
        ledger.beginTurn()
        assertEquals(0, ledger.turnDoneCount)
        assertEquals(0, ledger.turnReworkCount)
        assertEquals(1, ledger.view().doneCount) // 累计量不清
    }

    // ── 读侧：openTasksOf / view / snapshot ──────────────────────────────────

    @Test
    fun openTasksOfCountsOwnUnfinishedOnly() {
        val ledger = RoomLedger("s1")
        val a1 = task("甲的活一", "a1")
        val a2 = task("甲的活二", "a1")
        val b1 = task("乙的活", "a2")
        ledger.mergeTasks(listOf(a1, a2, b1), 1)
        ledger.markDone(listOf(a2.id))
        assertEquals(1, ledger.openTasksOf("a1"))
        assertEquals(1, ledger.openTasksOf("a2"))
        assertEquals(0, ledger.openTasksOf("不存在"))
        assertEquals(mapOf("a1" to 1, "a2" to 1), ledger.ownerCounts())
    }

    @Test
    fun viewMapsTasksQuestionsDoneAndRework() {
        val ledger = RoomLedger("s1")
        val t = task("写报告")
        ledger.mergeTasks(listOf(t), 1)
        ledger.addQuestion("导出格式要 CSV 还是 XLSX？", 1)
        ledger.markDone(listOf(t.id))
        ledger.markRework(listOf(t.id))
        val view = ledger.view()
        assertEquals(listOf("写报告"), view.openTasks)
        assertEquals(listOf("导出格式要 CSV 还是 XLSX？"), view.openQuestions)
        assertEquals(1, view.doneCount)
        assertEquals(1, view.reworkCount)
    }

    // ── 摘要：空账本不崩 ─────────────────────────────────────────────────────

    @Test
    fun emptyLedgerDigestIsBlankAndSnapshotEmpty() {
        val ledger = RoomLedger("s1")
        assertEquals("", ledger.digest())
        assertTrue(ledger.snapshot().isEmpty)
        assertEquals(0, ledger.snapshot().pending)
    }

    @Test
    fun digestMentionsOpenQuestionsAndDoneCount() {
        val ledger = RoomLedger("s1")
        ledger.mergeTasks(listOf(task("把导出功能加上去")), 1)
        ledger.addFact("结论：改用流式写文件", 1)
        val line = ledger.digest()
        assertTrue(line.contains("未完成 1 条"))
        assertTrue(line.contains("把导出功能加上去"))
        assertTrue(line.contains("已记事实 1 条"))
        assertTrue(line.length <= LEDGER_DIGEST_CHARS)
    }

    // ── 抽取：问号 / 关键词 / 每轮上限 ───────────────────────────────────────

    @Test
    fun questionsOfTakesQuestionMarkAndHintLinesOnly() {
        val text = listOf(
            "这轮的导出格式要不要支持 XLSX？",
            "这里待确认一下字段命名",
            "正常的一句话结论，不带问号也不带关键词",
            "太短？",
            "需要确认：删除后是否要软删",
            "第三条问题应该被每轮上限挡掉吧？",
        ).joinToString("\n")
        val got = questionsOf(text)
        assertEquals(QUESTIONS_PER_ROUND, got.size)
        assertEquals("这轮的导出格式要不要支持 XLSX？", got[0])
        assertEquals("这里待确认一下字段命名", got[1])
    }

    @Test
    fun factsOfTakesLeadOrColonHintLinesOnly() {
        val text = listOf(
            "结论：缓存层去掉，直接读库",
            "排查后：根因是时区没统一",
            "普通描述，没有关键词",
            "已修复登录态丢失",
        ).joinToString("\n")
        val got = factsOf(text)
        assertEquals(listOf("结论：缓存层去掉，直接读库", "排查后：根因是时区没统一"), got)
    }

    @Test
    fun addQuestionDedupesAndCapsAt10() {
        val ledger = RoomLedger("s1")
        ledger.addQuestion("要不要支持 XLSX 导出？", 1)
        ledger.addQuestion("要不要支持 XLSX 导出？", 2) // 同一句：去重
        assertEquals(1, ledger.view().openQuestions.size)
        repeat(MAX_OPEN_QUESTIONS + 2) { i -> ledger.addQuestion("第 " + i + " 个问题需要确认吗？", 1) }
        assertEquals(MAX_OPEN_QUESTIONS, ledger.view().openQuestions.size)
    }

    @Test
    fun noAddPlaceholderIsSkippedEverywhere() {
        val ledger = RoomLedger("s1")
        ledger.addQuestion("（无补充）", 1)
        ledger.addFact("（无补充）", 1)
        ledger.mergeTasks(listOf(task("（无补充）")), 0)
        assertEquals("", ledger.digest())
        assertTrue(isNoAddText("（无补充）"))
        assertTrue(isNoAddText("  "))
        assertFalse(isNoAddText("结论：改完了"))
    }

    // ── UI 出口：RoomLedgers ────────────────────────────────────────────────

    @Test
    fun roomLedgersPublishesAndClears() {
        val ledger = RoomLedger("s-hub")
        ledger.mergeTasks(listOf(task("给 UI 用的待办")), 1)
        assertEquals(1, RoomLedgers.snapshot("s-hub").pending)
        assertEquals("给 UI 用的待办", RoomLedgers.flow("s-hub").value.open.single().title)
        RoomLedgers.clear("s-hub")
        assertTrue(RoomLedgers.snapshot("s-hub").isEmpty)
    }
}
