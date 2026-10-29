package dev.dsh.mobile.linux

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxMaintenanceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun boundedLogDropsOldestWholeLines() {
        assertEquals("new\n", appendBounded("old\n", "new", 4))
        assertEquals("old\nnew\n", appendBounded("old\n", "new", 8))
    }
    @Test fun boundedLogPreservesUtf8AndNeverExceedsCapacity() {
        val log = appendBounded("旧行\n", "新行", 7)
        assertEquals("新行\n", log)
        assertTrue(log.toByteArray(Charsets.UTF_8).size <= 7)
        assertEquals("", appendBounded("", "too-long", 2))
    }
    @Test fun logTailUsesCharactersWithoutBreakingSurrogatePairs() {
        assertEquals("新行", readTail("旧行新行", 2))
        assertEquals("", readTail("😀", 1))
    }
    @Test fun cleanupPreservesActiveRootfsAndReportsExactBytes() {
        val base = tmp.newFolder("linux")
        File(base, "current").writeText("new-x86_64")
        val active = File(base, "versions/new-x86_64/rootfs").apply { mkdirs() }
        File(active, "keep").writeText("active")
        val old = File(base, "versions/old-x86_64/rootfs").apply { mkdirs() }
        File(old, "remove").writeBytes(ByteArray(123))
        val environment = LinuxEnvironment(base, "x86_64", { _, _ -> error("unexpected download") })
        val result = cleanupInactive(environment)
        assertEquals(1, result.versions)
        assertEquals(123L, result.bytes)
        assertTrue(File(active, "keep").isFile)
        assertFalse(old.exists())
        assertEquals(0, cleanupInactive(environment).versions)
    }
    @Test fun cleanupWithoutActiveVersionDoesNotRemoveAnything() {
        val base = tmp.newFolder("unconfigured")
        val old = File(base, "versions/old/rootfs").apply { mkdirs() }
        val environment = LinuxEnvironment(base, "x86_64", { _, _ -> error("unexpected download") })
        assertEquals(0, cleanupInactive(environment).versions)
        assertTrue(old.isDirectory)
    }
}
