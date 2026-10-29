package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ChannelKind
import dev.dsh.mobile.toolbridge.ShellChannel
import dev.dsh.mobile.toolbridge.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5：自检输出的结构化解析（T10 直接消费这份结果）。 */
class LinuxSelfCheckTest {

    private val okOutput = """
        #T5-CHECK name=python3 ok=1 detail=3.12.13
        #T5-CHECK name=pip3 ok=1 detail=pip 24.0 from /usr/lib/python3.12/site-packages/pip (python 3.12)
        #T5-CHECK name=git ok=1 detail=git version 2.45.4
        #T5-CHECK name=curl ok=1 detail=curl 8.14.1 (x86_64-alpine-linux-musl)
        #T5-CHECK name=wget ok=1 detail=GNU Wget 1.24.5 built on musl.
        #T5-CHECK name=apk ok=1 detail=apk-tools 2.14.4, compiled for x86_64.
        #T5-CHECK name=ca-certificates ok=1 detail=217208 bytes
        #T5-CHECK name=pip-config ok=1 detail=[global] break-system-packages = true index-url = https://pypi.org/simple
        #T5-CHECK name=resolv ok=1 detail=nameserver 10.0.2.3
        #T5-CHECK name=dns ok=1 detail=104.20.23.154
        #T5-CHECK name=https ok=1 detail=http_code=200
        #T5-CHECK name=stderr ok=1 detail=captured dsh-stderr
        #T5-CHECK name=exitcode ok=1 detail=rc=7
    """.trimIndent()

    @Test
    fun `全通过时 ok 为真且每项都能读到版本`() {
        val r = LinuxSelfCheck.parse(okOutput)
        assertEquals(13, r.checks.size)
        assertTrue(r.ok)
        assertTrue(r.failed().isEmpty())
        assertEquals("3.12.13", r.checks.first { it.name == "python3" }.detail)
        assertEquals("git version 2.45.4", r.checks.first { it.name == "git" }.detail)
        assertEquals("http_code=200", r.checks.first { it.name == "https" }.detail)
    }

    @Test
    fun `任何一项失败则整体失败并列出失败项`() {
        val out = """
            #T5-CHECK name=python3 ok=1 detail=3.12.13
            #T5-CHECK name=dns ok=0 detail=rc=1 gaierror [Errno -3] Try again
            #T5-CHECK name=https ok=0 detail=rc=28 timeout
        """.trimIndent()
        val r = LinuxSelfCheck.parse(out)
        assertFalse(r.ok)
        assertEquals(listOf("dns", "https"), r.failed().map { it.name })
        assertTrue(r.failed().first().detail, r.failed().first().detail.contains("Errno -3"))
        assertTrue(r.summary(), r.summary().contains("dns=FAIL"))
    }

    @Test
    fun `detail 里带等号与空格也能完整保留`() {
        val r = LinuxSelfCheck.parse("#T5-CHECK name=pip3 ok=1 detail=pip 24.0 from /usr/lib (python 3.12) a=b\n")
        assertEquals("pip 24.0 from /usr/lib (python 3.12) a=b", r.checks.single().detail)
    }

    @Test
    fun `没有 CHECK 行时 ok 为假 并回退到 stderr`() {
        val r = LinuxSelfCheck.parse("", "proot: cannot exec /bin/sh")
        assertFalse(r.ok)
        assertTrue(r.checks.isEmpty())
        assertEquals("proot: cannot exec /bin/sh", r.raw)
    }

    @Test
    fun `run 走真实自检脚本并解析出结果`() = runTest {
        val executed = mutableListOf<String>()
        val channel = object : ShellChannel {
            override val id = "fake"
            override val label = "fake"
            override val kind = ChannelKind.Proot
            override fun available() = true
            override fun detail() = "fake"
            override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
                executed += command
                return ShellResult(0, okOutput, "")
            }
        }
        val r = LinuxSelfCheck(channel).run()
        assertTrue(r.ok)
        assertEquals(13, r.checks.size)
        assertEquals(LinuxScripts.selfCheck(), executed.single())
        assertTrue(executed.single(), executed.single().contains("sys.exit(7)"))
    }
}
