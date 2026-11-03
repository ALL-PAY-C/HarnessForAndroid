package dev.dsh.mobile.core.data.group

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.7a 规划书仓库：DataStore 往返、Flow 观察、按会话隔离、时钟注入后的排序。 */
class GroupLedgerStoreTest {

    @Test
    fun writesAreVisibleThroughTheFlowAndSurviveReopen() = runBlocking {
        val file = File.createTempFile("groupledger", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 100L
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = GroupLedgerStore(dataStore) { now }
            assertTrue(store.list("s1").isEmpty())

            store.upsert("s1", LedgerEntry(id = "t1", title = "第一件", owner = "worker"))
            now = 200L
            store.upsert("s1", LedgerEntry(id = "t2", title = "第二件", owner = "planner"))
            // 排序：updatedAt 倒序。
            assertEquals(listOf("t2", "t1"), store.list("s1").map { it.id })
            // 观察通道与写入同源。
            assertEquals(2, store.ledger("s1").first().entries.size)

            now = 300L
            store.updateStatus("s1", "t1", LedgerStatus.Doing)
            now = 400L
            store.appendNote("s1", "t1", "已开工")
            assertEquals(listOf("t1", "t2"), store.list("s1").map { it.id })
            assertEquals(LedgerStatus.Doing, store.list("s1").first { it.id == "t1" }.status)
            assertEquals("已开工", store.list("s1").first { it.id == "t1" }.note)

            // 另开一个实例读同一份文件：落盘真的写进去了。
            val reopened = GroupLedgerStore(dataStore) { now }
            assertEquals(2, reopened.list("s1").size)
            assertEquals("已开工", reopened.list("s1").first { it.id == "t1" }.note)
        } finally {
            scope.cancel(); file.delete()
        }
    }

    @Test
    fun sessionsAreIsolatedAndEmptyLedgerIsNotPersisted() = runBlocking {
        val file = File.createTempFile("groupledger", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = GroupLedgerStore(dataStore) { 7L }
            store.upsert("s1", LedgerEntry(id = "a", title = "只属于 s1"))
            assertTrue(store.list("s2").isEmpty())
            assertEquals(listOf("a"), store.list("s1").map { it.id })
            // 删光后该会话整条 key 被移除，读侧回到空账本。
            store.remove("s1", "a")
            assertTrue(store.ledger("s1").first().isEmpty)
            // 空会话 id 是写入口的硬错误（读侧空账本，不写盘）。
            try { store.upsert("  ", LedgerEntry(id = "x", title = "x")); throw AssertionError("空会话 id 必须被拒") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("会话 id")) }
            assertTrue(store.ledger("  ").first().isEmpty)
        } finally {
            scope.cancel(); file.delete()
        }
    }

    @Test
    fun roleCardOverlayFallsBackToDefaultAndClears() = runBlocking {
        val file = File.createTempFile("presets", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = dev.dsh.mobile.core.data.preset.AgentPresetStore(dataStore)
            // 没有覆盖时读默认卡。
            assertEquals(GroupRole.Worker.defaultCard(), store.roleCard(GroupRole.Worker))
            val custom = GroupRole.Worker.defaultCard().copy(name = "改过的执行者")
            store.setRoleCard(GroupRole.Worker, custom)
            assertEquals(custom, store.roleCard(GroupRole.Worker))
            assertEquals(custom, store.roleCards.first()["worker"])
            // 清空覆盖 → 回到默认卡；别的角色不受影响。
            store.setRoleCard(GroupRole.Worker, null)
            assertEquals(GroupRole.Worker.defaultCard(), store.roleCard(GroupRole.Worker))
            assertEquals(GroupRole.Supervisor.defaultCard(), store.roleCard(GroupRole.Supervisor))
        } finally {
            scope.cancel(); file.delete()
        }
    }
}
