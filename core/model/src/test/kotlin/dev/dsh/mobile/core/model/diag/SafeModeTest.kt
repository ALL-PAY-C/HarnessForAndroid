package dev.dsh.mobile.core.model.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeModeTest {

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
    private val safeMode = SafeMode(store, { now })

    @Test
    fun firstTwoBootsStayNormal() {
        assertEquals(BootState.Normal, safeMode.beginBoot())
        assertEquals(BootState.Normal, safeMode.beginBoot())
        assertFalse(safeMode.isSafeMode())
    }

    @Test
    fun thirdBootEntersSafeMode() {
        safeMode.beginBoot()
        safeMode.beginBoot()
        assertEquals(BootState.SafeMode, safeMode.beginBoot())
        assertTrue(safeMode.isSafeMode())
    }

    @Test
    fun safeModeIsStickyAcrossBoots() {
        repeat(3) { safeMode.beginBoot() }
        assertEquals(BootState.SafeMode, safeMode.beginBoot())
        assertTrue(safeMode.isSafeMode())
    }

    @Test
    fun markBootOkClearsAttemptCounter() {
        safeMode.beginBoot()
        safeMode.beginBoot()
        safeMode.markBootOk()
        assertEquals("0", store.get("boot.attempt"))
        assertEquals(BootState.Normal, safeMode.beginBoot())
    }

    @Test
    fun exitSafeModeClearsFlagButNotCounter() {
        repeat(3) { safeMode.beginBoot() }
        safeMode.exitSafeMode()
        assertFalse(safeMode.isSafeMode())
        assertEquals(BootState.SafeMode, safeMode.beginBoot())
    }

    @Test
    fun customThresholdIsRespected() {
        val strict = SafeMode(store, { now }, threshold = 0)
        assertEquals(BootState.SafeMode, strict.beginBoot())
    }

    @Test
    fun attemptTimestampUsesInjectedClock() {
        now = 4_242L
        safeMode.beginBoot()
        assertEquals("4242", store.get("boot.attempt.at"))
    }
}
