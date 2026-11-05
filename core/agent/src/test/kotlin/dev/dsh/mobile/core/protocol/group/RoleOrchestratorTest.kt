package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.model.GroupLedger
import dev.dsh.mobile.core.model.GroupLedgerSink
import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.model.LedgerEntry
import dev.dsh.mobile.core.model.LedgerStatus
import dev.dsh.mobile.core.protocol.GroupMember
import dev.dsh.mobile.core.protocol.LlmConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.7b 角色编排循环：棒次、返工、缺口降级、发言上限。
 *
 * 全部用脚本化 speak + 内存账本，不连模型、不碰 DataStore —— 编排规则本身就是被测对象。
 */
class RoleOrchestratorTest {

    private class MemorySink : GroupLedgerSink {
        private val books = MutableStateFlow<Map<String, GroupLedger>>(emptyMap())
        private var clock = 0L

        override fun ledger(sessionId: String): Flow<GroupLedger> =
            books.map { it[sessionId] ?: GroupLedger.EMPTY }

        override suspend fun list(sessionId: String): List<LedgerEntry> = ledger(sessionId).first().sorted()

        override suspend fun upsert(sessionId: String, entry: LedgerEntry): GroupLedger =
            write(sessionId) { it.upsert(entry, ++clock) }

        override suspend fun remove(sessionId: String, id: String): GroupLedger = write(sessionId) { it.remove(id) }

        override suspend fun updateStatus(sessionId: String, id: String, status: LedgerStatus): GroupLedger =
            write(sessionId) { it.updateStatus(id, status, ++clock) }

        override suspend fun appendNote(sessionId: String, id: String, note: String): GroupLedger =
            write(sessionId) { it.appendNote(id, note, ++clock) }

        suspend fun all(sessionId: String): List<LedgerEntry> = list(sessionId)

        private suspend fun write(sessionId: String, block: (GroupLedger) -> GroupLedger): GroupLedger {
            val next = block(books.value[sessionId] ?: GroupLedger.EMPTY)
            books.value = books.value + (sessionId to next)
            return next
        }
    }

    /** 脚本化的发言记录器：按顺序发放预设回复，同时记下每棒是谁。 */
    private class Scripted(vararg replies: String) {
        val order = mutableListOf<GroupRole>()
        val prompts = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())

        val speak: suspend (RoleSeat, String) -> String = { seat, prompt ->
            order += seat.role
            prompts += prompt
            queue.removeFirstOrNull().orEmpty()
        }
    }

    private fun seat(role: GroupRole, id: String = role.id) = RoleSeat(
        GroupMember(
            id = id,
            name = id,
            persona = "",
            cfg = LlmConfig(baseUrl = "http://127.0.0.1", apiKey = "k", model = "m"),
            role = role,
        ),
        role,
    )

    private fun orchestrator(
        seats: List<RoleSeat>,
        sink: GroupLedgerSink,
        script: Scripted,
        notices: MutableList<String>,
    ) = RoleOrchestrator(
        seats = seats,
        ledger = sink,
        speak = script.speak,
        notice = { notices += it },
        clock = { 1_700_000_000_000L },
    )

    @Test
    fun singleEntryRunsPlanWorkCheckCloseSuperviseInOrder() = runBlocking {
        val sink = MemorySink()
        val script = Scripted("- 修好登录页", "改完了，跑了单测", "通过：单测全绿", "本轮收口", "无异常")
        val notices = mutableListOf<String>()
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = notices,
        ).run("s1", "修一下登录页")

        // 单条目不返工：B → D → C → B → A，五棒。
        assertEquals(
            listOf(GroupRole.Planner, GroupRole.Worker, GroupRole.Inspector, GroupRole.Planner, GroupRole.Supervisor),
            script.order,
        )
        val entries = sink.all("s1")
        assertEquals(1, entries.size)
        assertEquals(LedgerStatus.Done, entries[0].status)
        assertEquals("修好登录页", entries[0].title)
        // 系统说明一律带角色前缀，用户能看出这句是谁在说。
        assertTrue(notices.isNotEmpty())
        notices.forEach { assertTrue(it, it.startsWith("[") && it.contains("] ")) }
        assertTrue(notices.any { it.startsWith("[规划者]") })
    }

    @Test
    fun oneReworkAddsTwoSticksAndKeepsTheEntry() = runBlocking {
        val sink = MemorySink()
        val script = Scripted(
            "- 修好登录页",
            "第一版",
            "不通过：没跑单测",
            "第二版，跑了单测",
            "通过",
            "收口",
            "无异常",
        )
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = mutableListOf(),
        ).run("s2", "修一下登录页")

        // 一次返工 = B D C D C B A，正好 7 棒，没撞上限。
        assertEquals(7, script.order.size)
        assertEquals(GroupRole.Worker, script.order[3])
        val entry = sink.all("s2").single()
        assertEquals(LedgerStatus.Done, entry.status)
        assertTrue(entry.note, entry.note.contains("返工"))
    }

    @Test
    fun reworkStopsAtTwoAndLeavesEntryInReview() = runBlocking {
        val sink = MemorySink()
        val script = Scripted(
            "- 修好登录页",
            "第一版", "不通过：没跑单测",
            "第二版", "不通过：还是没跑",
            "第三版", "不通过：第三次没过",
            "收口",
            "无异常",
        )
        val notices = mutableListOf<String>()
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = notices,
        ).run("s3", "修一下登录页")

        val entry = sink.all("s3").single()
        // 三次不通过就不再返工：留在「需改」，并且写明原因，不假装完成。
        assertEquals(LedgerStatus.Review, entry.status)
        assertTrue(entry.note, entry.note.contains("返工 2 次仍未通过"))
        // 打回的系统说明也带检测者前缀。
        assertTrue(notices.any { it.startsWith("[检测者] 打回") })
    }

    @Test
    fun missingInspectorMarksDoneWithoutCheckStick() = runBlocking {
        val sink = MemorySink()
        val script = Scripted("- 只改一行", "改完了", "收口", "无异常")
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = mutableListOf(),
        ).run("s4", "改一行")

        // 缺 C：B → D → B → A，没有检测这一棒，条目直接完成（缺角色降级）。
        assertEquals(
            listOf(GroupRole.Planner, GroupRole.Worker, GroupRole.Planner, GroupRole.Supervisor),
            script.order,
        )
        assertEquals(LedgerStatus.Done, sink.all("s4").single().status)
    }

    @Test
    fun missingPlannerDoesNothingAtAll() = runBlocking {
        val sink = MemorySink()
        val script = Scripted("不该被叫到")
        val notices = mutableListOf<String>()
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Worker), seat(GroupRole.Inspector)),
            sink = sink,
            script = script,
            notices = notices,
        ).run("s5", "随便说点什么")

        // 没有 B 就没有计划：一棒都不叫，账本不动（调用方据此退回老轮转）。
        assertTrue(script.order.isEmpty())
        assertTrue(sink.all("s5").isEmpty())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun fourEntriesHitTheEightStickCeiling() = runBlocking {
        val sink = MemorySink()
        val script = Scripted(
            "- 改登录页\n- 补单测\n- 修文案\n- 更文档",
            "登录页好了", "通过",
            "单测补了", "通过",
            "文案改了", "通过",
            "文档更了",
        )
        val notices = mutableListOf<String>()
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = notices,
        ).run("s6", "四件事")

        // 8 棒硬截断：第 9 棒（第四条的自检）不再叫模型。
        assertEquals(RoleOrchestrator.MAX_STICKS, script.order.size)
        val entries = sink.all("s6")
        assertEquals(4, entries.size)
        val byTitle = entries.associateBy { it.title }
        assertEquals(LedgerStatus.Done, byTitle.getValue("改登录页").status)
        assertEquals(LedgerStatus.Done, byTitle.getValue("补单测").status)
        assertEquals(LedgerStatus.Done, byTitle.getValue("修文案").status)
        // 第四条只跑到「执行完」：没检测就绝不标完成。
        assertEquals(LedgerStatus.Review, byTitle.getValue("更文档").status)
        assertTrue(notices.any { it.contains("用满 8 棒上限") })
    }

    @Test
    fun planWithoutBulletsDegradesToASingleEntry() = runBlocking {
        val sink = MemorySink()
        val script = Scripted("我直接说吧，就是修登录页", "改完了", "通过", "收口", "无异常")
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector), seat(GroupRole.Worker)),
            sink = sink,
            script = script,
            notices = mutableListOf(),
        ).run("s7", "修登录页")

        // 规划者没按格式出条目：退化成一件事，不让整轮空转。
        val entry = sink.all("s7").single()
        assertTrue(entry.title.isNotBlank())
        assertEquals(5, script.order.size)
    }

    @Test
    fun workerSeatIsReusedWhenMissingAndExecutorFallsBackToPlanner() = runBlocking {
        val sink = MemorySink()
        val script = Scripted("- 一条", "规划者自己动手", "通过", "收口", "无异常")
        orchestrator(
            seats = listOf(seat(GroupRole.Supervisor), seat(GroupRole.Planner), seat(GroupRole.Inspector)),
            sink = sink,
            script = script,
            notices = mutableListOf(),
        ).run("s8", "一件事")

        // 缺 D：由 B 兼执行者，棒次仍是 B → B(执行) → C → B → A。
        assertEquals(GroupRole.Planner, script.order[1])
        assertEquals(LedgerStatus.Done, sink.all("s8").single().status)
    }
}