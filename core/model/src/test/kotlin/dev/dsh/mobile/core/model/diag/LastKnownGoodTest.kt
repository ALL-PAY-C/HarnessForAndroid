package dev.dsh.mobile.core.model.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastKnownGoodTest {

    private class FakeStore : DiagStore {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
    }

    private var now = 1_000L
    private val store = FakeStore()
    private val lkg = LastKnownGood(store, { now })

    @Test
    fun currentIsNullBeforeAnySnapshot() {
        assertNull(lkg.current())
    }

    @Test
    fun markGoodBecomesCurrent() {
        lkg.markGood("theme=dark")
        assertEquals("theme=dark", lkg.current()?.payload)
        assertEquals(0, lkg.current()?.crashCount)
    }

    @Test
    fun markGoodDeduplicatesSamePayload() {
        lkg.markGood("theme=dark")
        now += 10
        lkg.markGood("theme=dark")
        assertEquals(1, store.keys("lkg.snap.").size)
    }

    @Test
    fun markGoodKeepsAtMostThreeSnapshots() {
        lkg.markGood("a"); now += 1
        lkg.markGood("b"); now += 1
        lkg.markGood("c"); now += 1
        lkg.markGood("d")
        assertEquals(3, store.keys("lkg.snap.").size)
        assertEquals("d", lkg.current()?.payload)
    }

    @Test
    fun rollbackWalksBackTwice() {
        lkg.markGood("a"); now += 1
        lkg.markGood("b"); now += 1
        lkg.markGood("c")
        assertEquals("b", lkg.rollbackOneLevel()?.payload)
        assertEquals("b", lkg.current()?.payload)
        assertEquals("a", lkg.rollbackOneLevel()?.payload)
        assertEquals("a", lkg.current()?.payload)
    }

    @Test
    fun rollbackReturnsNullOnOldestSnapshot() {
        lkg.markGood("a")
        assertNull(lkg.rollbackOneLevel())
        assertEquals("a", lkg.current()?.payload)
    }

    @Test
    fun rollbackReturnsNullWhenEmpty() {
        assertNull(lkg.rollbackOneLevel())
    }

    @Test
    fun recordCrashAccumulates() {
        lkg.markGood("a")
        val id = lkg.current()!!.id
        lkg.recordCrash(id)
        lkg.recordCrash(id)
        assertEquals(2, lkg.current()?.crashCount)
        assertNotNull(store.get("lkg.snap." + id))
    }

    @Test
    fun recordCrashOnUnknownSnapshotIsIgnored() {
        lkg.recordCrash("0000000009")
        assertTrue(store.keys("lkg.snap.").isEmpty())
    }
}
