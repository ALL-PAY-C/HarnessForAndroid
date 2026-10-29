package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.protocol.DshEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直接驱动 [TurnLedger]（handleEvent / sendMessage / cancel 实际走的那份状态机），
 * 复现并锁定 H1/H2/H3 的四条硬验收路径。
 */
class TurnLedgerTest {

    // ---- 点 1：旧 chunk 不能污染正在跑的新轮（H1/H2 污染源） ----

    @Test
    fun oldChunkDuringNewTurnDoesNotPollute() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.update("s", "t1") { it.copy(text = it.text + "第一轮") }
        assertTrue(ledger.canFinalize("s", "t1"))
        assertTrue(ledger.markFinalized("s", "t1"))
        ledger.seal("s")

        ledger.seed("s", "t2")
        // 旧轮 t1 的迟到 chunk 到达：turnId 不符 → 丢，绝不能写进新轮。
        ledger.update("s", "t1") { it.copy(text = it.text + "迟到污染") }
        assertEquals("", ledger.current("s")!!.text)

        // 新轮自己的 chunk 正常写入。
        ledger.update("s", "t2") { it.copy(text = it.text + "第二轮") }
        assertEquals("第二轮", ledger.current("s")!!.text)
    }

    @Test
    fun nullTurnIdChunkAppliesWhileRunning() {
        // ACP / 旧生产者不带 turnId：只要在跑就照旧写（兼容路径）。
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.update("s", null) { it.copy(text = it.text + "acp") }
        assertEquals("acp", ledger.current("s")!!.text)
    }

    @Test
    fun chunkAfterTurnSettledIsDropped() {
        // H3：已结算（!running）后，任何迟到 chunk 都不复活幽灵轮。
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.update("s", "t1") { it.copy(text = it.text + "x") }
        ledger.markFinalized("s", "t1")
        ledger.seal("s")
        ledger.update("s", null) { it.copy(text = it.text + "幽灵") }
        assertFalse(ledger.current("s")!!.running)
        assertEquals("", ledger.current("s")!!.text)
    }

    // ---- 点 2：null-turnId 整轮 End 不给结算权 ----

    @Test
    fun nullWholeTurnEndIsIgnored() {
        assertEquals(TurnEndAction.Ignore, DshEvent.TurnEnd("s", "stop", null, null).action())
        val ledger = TurnLedger()
        ledger.seed("s", "t2")
        // 模拟 handleEvent 的 Ignore 分支：不做任何结算。
        if (DshEvent.TurnEnd("s", "stop", null, null).action() != TurnEndAction.Ignore) {
            ledger.markFinalized("s", ledger.current("s")!!.turnId)
            ledger.seal("s")
        }
        assertTrue("新轮必须仍在跑", ledger.current("s")!!.running)
        assertEquals("t2", ledger.current("s")!!.turnId)
    }

    @Test
    fun taggedTurnEndFinalizesCorrectTurnAndStaleIsDropped() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        assertFalse("迟到旧轮 End 不得结算新轮", ledger.canFinalize("s", "t2"))
        assertTrue(ledger.canFinalize("s", "t1"))
        assertTrue(ledger.markFinalized("s", "t1"))
        ledger.seal("s")
        // 结算后再来一次 t1 的 End（重复/迟到）→ 幂等拒绝。
        assertFalse(ledger.markFinalized("s", "t1"))
    }

    // ---- 点 3：cancel 先捕获 turnId，finalize 用捕获值 ----

    @Test
    fun cancelFinalizesOnlyCapturedTurnEvenIfNewTurnStarted() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        val captured = ledger.current("s")!!.turnId // 模拟 repo.cancel 先捕获
        // 打断期间新轮 t2 已启动（竞态窗口）：finalize 用捕获的 t1，绝不清掉 t2。
        ledger.seed("s", "t2")
        assertFalse("t1 已不是当前轮，不得结算", ledger.canFinalize("s", captured))
        assertTrue(ledger.current("s")!!.running)
        assertEquals("t2", ledger.current("s")!!.turnId)
    }

    @Test
    fun cancelFinalizesRunningTurnWhenStillCurrent() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        val captured = ledger.current("s")!!.turnId
        assertTrue(ledger.canFinalize("s", captured))
        assertTrue(ledger.markFinalized("s", captured))
        ledger.seal("s")
        assertFalse(ledger.current("s")!!.running)
    }

    // ---- 点 4：组成员 End 先核对 turnId 再 finishPart ----

    @Test
    fun staleMemberEndDoesNotFinishNewTurnsMember() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.update("s", "t1") { it.copy(parts = listOf(AuthorPart("a", "A", text = "旧发言"))) }
        ledger.markFinalized("s", "t1")
        ledger.seal("s")

        ledger.seed("s", "t2")
        ledger.update("s", "t2") { it.copy(parts = listOf(AuthorPart("a", "A", text = "新发言"))) }

        // 旧轮 t1 的成员 End 迟到 → 不得结算 t2 的成员 a。
        assertNull(ledger.finishPart("s", "a", "t1"))
        assertEquals(1, ledger.current("s")!!.parts.size)

        // 正确的 t2 成员 End 正常结算。
        val part = ledger.finishPart("s", "a", "t2")
        assertNotNull(part)
        assertEquals(0, ledger.current("s")!!.parts.size)
    }

    // ---- 点 5：N2 封口（close/isClosed）防 clear 后迟到写回 ----

    @Test
    fun closeMarksSessionClosedAndDropsLateChunk() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.update("s", "t1") { it.copy(text = it.text + "在跑") }
        ledger.close("s")
        assertTrue(ledger.isClosed("s"))
        assertNull(ledger.current("s")) // 封口同时清掉在跑轮次

        // 迟到 chunk（带 turnId 或不带）都不得复活轮次
        ledger.update("s", "t1") { it.copy(text = it.text + "迟到") }
        ledger.update("s", null) { it.copy(text = it.text + "迟到null") }
        assertNull(ledger.current("s"))
    }

    @Test
    fun seedReopensClosedSession() {
        val ledger = TurnLedger()
        ledger.seed("s", "t1")
        ledger.close("s")
        assertTrue(ledger.isClosed("s"))
        ledger.seed("s", "t2") // 新起一轮自动撤销封口
        assertFalse(ledger.isClosed("s"))
        assertEquals("t2", ledger.current("s")!!.turnId)
    }
}
