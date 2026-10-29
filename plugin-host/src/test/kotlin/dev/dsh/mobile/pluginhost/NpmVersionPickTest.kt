package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本挑选是纯函数，单测它比走网络稳：npm 只认精确版本或 dist-tag，
 * package.json 里的 ^7.8.5 这类范围必须先在这里落到一个具体版本。
 */
class NpmVersionPickTest {

    private val versions = listOf("1.0.0", "1.2.3", "1.9.0", "2.0.0", "2.1.5", "3.0.0-beta.1")
    private val tags = mapOf("latest" to "2.1.5", "next" to "3.0.0-beta.1")

    @Test
    fun caretStaysInsideMajor() {
        assertEquals("1.9.0", npmPickVersion("^1.2.3", versions, tags))
        assertEquals("2.1.5", npmPickVersion("^2.0.0", versions, tags))
    }

    @Test
    fun tildeStaysInsideMinor() {
        assertEquals("1.2.3", npmPickVersion("~1.2.3", versions, tags))
    }

    @Test
    fun exactAndTagsAreHonoured() {
        assertEquals("1.0.0", npmPickVersion("1.0.0", versions, tags))
        assertEquals("2.1.5", npmPickVersion("latest", versions, tags))
        assertEquals("3.0.0-beta.1", npmPickVersion("next", versions, tags))
    }

    @Test
    fun prereleaseIsNeverPickedByRange() {
        assertEquals("2.1.5", npmPickVersion(">=1.0.0", versions, tags))
    }

    @Test
    fun unsatisfiableRangeReturnsNull() {
        assertNull(npmPickVersion("^9.0.0", versions, tags))
    }

    @Test
    fun unparsableSpecReturnsNull() {
        assertNull(npmPickVersion("git+https://example.com/x.git", versions, tags))
    }

    @Test
    fun packumentUrlEncodesScope() {
        assertEquals("https://registry.npmjs.org/@scope%2fpkg", npmPackumentUrl("@scope/pkg"))
    }

    @Test
    fun expandRangeRecognisesShapes() {
        assertTrue(expandRange("^1.2.3")!!.size == 2)
        assertTrue(expandRange("1.2")!!.size == 2)
        assertTrue(expandRange(">=2.0.0")!!.size == 1)
        assertNull(expandRange("workspace:*"))
    }
}