package dev.dsh.mobile.core.data.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EndpointSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = File(tmp.root, "no_backup")

    @Test fun setThenGetRoundTrips() {
        val s = EndpointSecretStore(dir())
        s.set("ep-1", "tok-abc")
        assertEquals("tok-abc", s.get("ep-1"))
        assertNull(s.get("missing"))
    }

    @Test fun overwriteReplacesPreviousToken() {
        val s = EndpointSecretStore(dir())
        s.set("ep-1", "first")
        s.set("ep-1", "second")
        assertEquals("second", s.get("ep-1"))
    }

    @Test fun removeClearsToken() {
        val s = EndpointSecretStore(dir())
        s.set("ep-1", "tok")
        s.remove("ep-1")
        assertNull(s.get("ep-1"))
    }

    @Test fun allReturnsFullSnapshot() {
        val s = EndpointSecretStore(dir())
        s.set("a", "1")
        s.set("b", "2")
        assertEquals(mapOf("a" to "1", "b" to "2"), s.all())
    }

    @Test fun dataSurvivesReinstantiation() {
        val d = dir()
        EndpointSecretStore(d).set("ep-1", "tok")
        assertEquals("tok", EndpointSecretStore(d).get("ep-1"))
    }

    @Test fun corruptedFileYieldsNullLookup() {
        val d = dir()
        assertTrue(d.mkdirs())
        File(d, "endpoint-tokens.json").writeText("{not json!!")
        assertNull(EndpointSecretStore(d).get("ep-1"))
    }

    @Test fun corruptFileIsNotOverwrittenBySet() {
        val d = dir()
        assertTrue(d.mkdirs())
        val f = File(d, "endpoint-tokens.json")
        f.writeText("{not json!!")
        val s = EndpointSecretStore(d)
        try {
            s.set("ep-2", "new-token")
            fail("set on corrupt file must throw")
        } catch (e: Exception) {
            // expected：读坏文件失败，写路径不静默覆盖
        }
        assertEquals("{not json!!", f.readText()) // 原件未被动
    }

    @Test fun concurrentWritesFromTwoInstancesDoNotLoseKeys() {
        val d = dir()
        val a = EndpointSecretStore(d)
        val b = EndpointSecretStore(d)
        val n = 40
        val threads = (0 until n).map { i ->
            Thread {
                val s = if (i % 2 == 0) a else b
                s.set("k$i", "v$i")
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val all = a.all()
        assertEquals(n, all.size)
        for (i in 0 until n) assertEquals("v$i", all["k$i"])
    }

    @Test fun tokenWithSpecialCharactersSurvivesRoundTrip() {
        val s = EndpointSecretStore(dir())
        val weird = "tok:with:colons-and/slashes?q=1&x=2\nnewline"
        s.set("ep-1", weird)
        assertEquals(weird, s.get("ep-1"))
    }

    @Test fun mergeTokenPrefersSecretOverLegacy() {
        assertEquals("secret", mergeToken("legacy", "secret"))
        assertEquals("legacy", mergeToken("legacy", null))
        assertEquals("", mergeToken("", null))
    }

    @Test fun shouldMigrateOnlyWhenLegacyPresentAndSecretAbsent() {
        assertEquals(true, shouldMigrate("legacy", null))
        assertEquals(false, shouldMigrate("", null))
        assertEquals(false, shouldMigrate("   ", null))
        assertEquals(false, shouldMigrate("legacy", "secret"))
    }
}
