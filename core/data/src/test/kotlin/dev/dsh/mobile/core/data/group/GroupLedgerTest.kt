package dev.dsh.mobile.core.data.group

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.7a 规划书：状态机、排序、备注追加、序列化往返（纯函数，不碰 DataStore）。 */
class GroupLedgerTest {

    private fun entry(id: String, title: String = id, status: LedgerStatus = LedgerStatus.Todo) =
        LedgerEntry(id = id, title = title, status = status, owner = "worker")

    @Test
    fun upsertInsertsThenOverwritesInPlace() {
        val l0 = GroupLedger()
        val l1 = l0.upsert(entry("t1", "写角色卡"), now = 10)
        assertEquals(1, l1.entries.size)
        assertEquals(10L, l1.entry("t1")!!.updatedAt)

        val l2 = l1.upsert(entry("t1", "写角色卡与账本"), now = 20)
        assertEquals(1, l2.entries.size)
        assertEquals("写角色卡与账本", l2.entry("t1")!!.title)
        assertEquals(20L, l2.entry("t1")!!.updatedAt)
        // 空标题与空 id 是写入口的硬错误，不是静默忽略。
        try { l2.upsert(entry("t2", "  "), now = 30); throw AssertionError("空标题必须被拒") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("标题")) }
        try { l2.upsert(entry("  ", "x"), now = 30); throw AssertionError("空 id 必须被拒") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("id")) }
    }

    @Test
    fun updateStatusMovesEntryAndIgnoresUnknownId() {
        val l1 = GroupLedger().upsert(entry("t1"), now = 10)
        val l2 = l1.updateStatus("t1", LedgerStatus.Doing, now = 20)
        assertEquals(LedgerStatus.Doing, l2.entry("t1")!!.status)
        assertEquals(20L, l2.entry("t1")!!.updatedAt)
        // 同状态重复写不刷新时间戳（避免列表无意义地跳动）。
        assertEquals(20L, l2.updateStatus("t1", LedgerStatus.Doing, now = 99).entry("t1")!!.updatedAt)
        // 不存在的 id 不造幽灵条目。
        assertEquals(l2, l2.updateStatus("ghost", LedgerStatus.Done, now = 30))
    }

    @Test
    fun appendNoteJoinsWithNewlineAndSkipsBlank() {
        val l1 = GroupLedger().upsert(entry("t1"), now = 10)
        val l2 = l1.appendNote("t1", "  第一次回报  ", now = 20)
        assertEquals("第一次回报", l2.entry("t1")!!.note)
        val l3 = l2.appendNote("t1", "第二次回报", now = 30)
        assertEquals("第一次回报\n第二次回报", l3.entry("t1")!!.note)
        // 空备注既不写内容也不刷时间戳。
        assertEquals(l3, l3.appendNote("t1", "   ", now = 40))
        assertEquals(30L, l3.entry("t1")!!.updatedAt)
    }

    @Test
    fun listAndGroupsAreOrderedByUpdatedAtDescending() {
        val ledger = GroupLedger()
            .upsert(entry("a", "最早"), now = 10)
            .upsert(entry("b", "其次"), now = 20)
            .upsert(entry("c", "最新"), now = 30)
            .updateStatus("a", LedgerStatus.Done, now = 40)
        assertEquals(listOf("a", "c", "b"), ledger.sorted().map { it.id })
        val groups = ledger.byStatus()
        assertEquals(LedgerStatus.entries.toSet(), groups.keys)
        assertEquals(listOf("a"), groups.getValue(LedgerStatus.Done).map { it.id })
        assertEquals(listOf("c", "b"), groups.getValue(LedgerStatus.Todo).map { it.id })
        assertTrue(groups.getValue(LedgerStatus.Blocked).isEmpty())
    }

    @Test
    fun entryCapDropsOldestAndRemoveDeletes() {
        var ledger = GroupLedger()
        (1..GroupLedger.MAX_ENTRIES + 5).forEach { ledger = ledger.upsert(entry("t" + it), now = it.toLong()) }
        assertEquals(GroupLedger.MAX_ENTRIES, ledger.entries.size)
        assertNull(ledger.entry("t1"))
        assertEquals("t" + (GroupLedger.MAX_ENTRIES + 5), ledger.sorted().first().id)
        assertFalse(ledger.remove("t" + (GroupLedger.MAX_ENTRIES + 5)).entries.any { it.id == "t" + (GroupLedger.MAX_ENTRIES + 5) })
    }

    @Test
    fun serializationRoundTripKeepsEveryFieldAndStableStatusNames() {
        val ledger = GroupLedger()
            .upsert(
                LedgerEntry(id = "t1", title = "跑单测", status = LedgerStatus.Review, owner = "worker", note = "等待复检"),
                now = 1_700_000_000_000L,
            )
            .upsert(LedgerEntry(id = "t2", title = "写角色卡", status = LedgerStatus.Done, owner = "planner"), now = 2)
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(ledger)
        assertTrue("状态落盘名必须是 todo/doing/review/done/blocked 之一", text.contains("\"review\"") || text.contains("review"))
        val back = json.decodeFromString<GroupLedger>(text)
        assertEquals(ledger, back)
        assertEquals(LedgerStatus.Review, back.entry("t1")!!.status)
        assertEquals("等待复检", back.entry("t1")!!.note)
        assertEquals(1_700_000_000_000L, back.entry("t1")!!.updatedAt)
        assertEquals("planner", back.entry("t2")!!.owner)
        // 未知键（未来版本加的字段）不能让老版本崩。
        val tolerant = Json { ignoreUnknownKeys = true }.decodeFromString<GroupLedger>(
            """{"entries":[{"id":"x","title":"旧数据","status":"blocked","futureField":1}]}""",
        )
        assertEquals(LedgerStatus.Blocked, tolerant.entry("x")!!.status)
    }
}
