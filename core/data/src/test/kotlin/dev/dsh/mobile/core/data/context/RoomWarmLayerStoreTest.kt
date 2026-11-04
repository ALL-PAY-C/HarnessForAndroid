package dev.dsh.mobile.core.data.context

import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.WarmLayerDao
import dev.dsh.mobile.core.data.db.WarmLayerEntity
import dev.dsh.mobile.core.model.context.KeyFile
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 温层仓库的行为测试（T9.8 S1）。
 *
 * Dao 是接口，所以这里给它一个内存实现 —— 测的是仓库的决策（盖章、覆盖、懒回填不写库），
 * 不是 SQL 本身；区间 / LIKE 的 SQL 语义由 scripts 里的真 SQLite 校验跑。
 */
class RoomWarmLayerStoreTest {

    private class FakeDao : WarmLayerDao {
        val rows = LinkedHashMap<String, WarmLayerEntity>()
        val upserts = mutableListOf<WarmLayerEntity>()
        var messageRows = 0
        private val ticker = MutableStateFlow(0L)

        override fun observe(sessionId: String): Flow<WarmLayerEntity?> = ticker.map { rows[sessionId] }

        override suspend fun get(sessionId: String): WarmLayerEntity? = rows[sessionId]

        override suspend fun upsert(entity: WarmLayerEntity) {
            rows[entity.sessionId] = entity
            upserts += entity
            ticker.value += 1
        }

        override suspend fun delete(sessionId: String) {
            rows.remove(sessionId)
            ticker.value += 1
        }

        override suspend fun bySessionRange(sessionId: String, fromId: Long, toId: Long): List<MessageEntity> =
            error("温层仓库不查原文")

        override suspend fun searchInSession(sessionId: String, like: String, limit: Int): List<MessageEntity> =
            error("温层仓库不查原文")

        override suspend fun messageCount(sessionId: String): Int = messageRows
    }

    private fun store(dao: FakeDao, now: Long = 1_700_000_000_000L) = RoomWarmLayerStore(dao) { now }

    @Test fun writeStampsClockAndRedundantColumns() = runTest {
        val dao = FakeDao()
        val layer = WarmLayer(primaryGoal = "目标", coveredUntilMessageId = 12L, warmVersion = 2, source = WarmSource.PLUGIN, updatedAt = 0L)
        store(dao).write("s1", layer)

        val row = dao.rows.getValue("s1")
        assertEquals(1_700_000_000_000L, row.updatedAt)
        assertEquals(12L, row.coveredUntilMessageId)
        assertEquals(2, row.warmVersion)
        assertEquals("PLUGIN", row.source)
        assertEquals(layer.copy(updatedAt = 1_700_000_000_000L), store(dao).read("s1"))
    }

    @Test fun writeOverwritesSameSession() = runTest {
        val dao = FakeDao()
        val s = store(dao)
        s.write("s1", WarmLayer(primaryGoal = "v1", coveredUntilMessageId = 5L, warmVersion = 1))
        s.write("s1", WarmLayer(primaryGoal = "v2", coveredUntilMessageId = 9L, warmVersion = 2))

        assertEquals(1, dao.rows.size)
        val read = s.read("s1")
        assertEquals("v2", read?.primaryGoal)
        assertEquals(9L, read?.coveredUntilMessageId)
        assertEquals(2, read?.warmVersion)
    }

    @Test fun observePushesAfterWrite() = runTest {
        val dao = FakeDao()
        val s = store(dao)
        assertNull(s.observe("s1").first())
        s.write("s1", WarmLayer(primaryGoal = "目标"))
        assertEquals("目标", s.observe("s1").first()?.primaryGoal)
    }

    @Test fun layerBackfillsEmptyLayerWithoutWriting() = runTest {
        val dao = FakeDao().apply { messageRows = 3 }
        val layer = store(dao).layer("old-session")

        requireNotNull(layer)
        assertTrue(layer.isEmpty())
        assertEquals(0L, layer.coveredUntilMessageId)
        assertEquals(WarmSource.LOCAL, layer.source)
        // 懒回填只读不写：库里仍然没有行，也没有发生过一次 upsert。
        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.upserts.isEmpty())
    }

    @Test fun layerReturnsNullForSessionWithoutHistory() = runTest {
        val dao = FakeDao()
        assertNull(store(dao).layer("brand-new"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun layerPrefersStoredOverBackfill() = runTest {
        val dao = FakeDao().apply { messageRows = 99 }
        val s = store(dao)
        s.write("s1", WarmLayer(primaryGoal = "已生成", coveredUntilMessageId = 20L))
        assertEquals("已生成", s.layer("s1")?.primaryGoal)
    }

    @Test fun clearRemovesRow() = runTest {
        val dao = FakeDao()
        val s = store(dao)
        s.write("s1", WarmLayer(primaryGoal = "目标"))
        s.clear("s1")
        assertNull(s.read("s1"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun codecRoundTripsAndRejectsCorruptPayload() {
        val layer = WarmLayer(
            primaryGoal = "目标",
            keyFiles = listOf(KeyFile("a.kt", 7, "入口")),
            coveredUntilMessageId = 33L,
            warmVersion = 4,
            source = WarmSource.PLUGIN,
            updatedAt = 88L,
        )
        assertEquals(layer, WarmLayerCodec.decode(WarmLayerCodec.encode(layer)))
        assertNull(WarmLayerCodec.decode("{ not json"))
        assertEquals(
            WarmLayer(coveredUntilMessageId = 6L, warmVersion = 3, source = WarmSource.PLUGIN, updatedAt = 5L),
            WarmLayerCodec.decode(WarmLayerEntity(sessionId = "s", payload = "", updatedAt = 5L, coveredUntilMessageId = 6L, warmVersion = 3, source = "PLUGIN")),
        )
    }
}
