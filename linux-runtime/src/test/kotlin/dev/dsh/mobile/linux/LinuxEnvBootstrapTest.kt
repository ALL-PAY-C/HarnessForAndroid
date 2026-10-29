package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ChannelKind
import dev.dsh.mobile.toolbridge.ShellChannel
import dev.dsh.mobile.toolbridge.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6 脚本与解析的钉子：脚本是给设备上的 sh 跑的，这里只钉「生成出来的字符串里有没有该有的东西」，
 * 以及 runner 在一步失败时是否继续、python 失败是否补轻量集。
 */
class LinuxEnvBootstrapTest {

    @Test
    fun `五个步骤按顺序分组且都带超时`() {
        val steps = LinuxEnvBootstrap.steps()
        assertEquals(
            listOf(
                LinuxEnvBootstrap.STEP_TERMUX,
                LinuxEnvBootstrap.STEP_ALPINE,
                LinuxEnvBootstrap.STEP_PYTHON,
                LinuxEnvBootstrap.STEP_GIT,
                LinuxEnvBootstrap.STEP_VERIFY,
            ),
            steps.map { it.id },
        )
        assertEquals(LinuxEnvBootstrap.Group.Termux, steps.first().group)
        assertTrue(steps.drop(1).all { it.group == LinuxEnvBootstrap.Group.Proot })
        assertTrue(steps.all { it.timeoutMs >= 60_000L && it.script.isNotBlank() })
    }

    @Test
    fun `termux 脚本装全部包并且失败也 exit0`() {
        val s = LinuxEnvBootstrap.termuxScript()
        LinuxEnvBootstrap.TERMUX_PACKAGES.split(' ').forEach { assertTrue(it, s.contains(it)) }
        assertTrue(s.contains("pkg update -y"))
        assertTrue(s.contains("pkg install -y"))
        // update 的 rc 单独留一份，install 成功不能掩盖 update 的失败。
        assertTrue(s.contains("|| upd="))
        assertTrue(s.contains("#T6-STEP id=termux-packages rc="))
        assertTrue(s.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun `alpine 脚本走 apk add 且套了 apk 锁`() {
        val s = LinuxEnvBootstrap.alpineScript()
        assertTrue(s.contains("apk add --no-cache"))
        assertTrue(s.contains("nodejs npm"))
        assertTrue(s.contains(LinuxToolchain.LOCK_LINE))
        assertTrue(s.contains("#T6-STEP id=alpine-packages rc="))
    }

    @Test
    fun `python 全量脚本带镜像源 体积上限与轻量回退`() {
        val s = LinuxEnvBootstrap.pythonScript()
        assertTrue(s.contains(LinuxEnvBootstrap.PIP_INDEX))
        assertTrue(s.contains("numpy"))
        assertTrue(s.contains(LinuxEnvBootstrap.PIP_LIGHT))
        assertTrue(s.contains(LinuxEnvBootstrap.PIP_BULK_LIMIT_KB.toString()))
        assertTrue(s.contains("--break-system-packages"))
        assertTrue(s.contains("uninstall -y numpy pandas"))
        assertTrue(s.contains("id=python rc="))
    }

    @Test
    fun `python 轻量脚本只装轻量集`() {
        val s = LinuxEnvBootstrap.pythonScript(light = true)
        assertTrue(s.contains("install_set \"" + LinuxEnvBootstrap.PIP_LIGHT + "\""))
        // 轻量模式只装轻量集：全量集一个包都不许出现在 install_set 里。
        assertFalse(s.contains("install_set \"" + LinuxEnvBootstrap.PIP_FULL + "\""))
        assertTrue(s.contains("id=python-light rc="))
        assertEquals(LinuxEnvBootstrap.STEP_PYTHON_LIGHT, LinuxEnvBootstrap.lightStep().id)
    }

    @Test
    fun `git 镜像只改 github 前缀并清掉 api 规则`() {
        val s = LinuxEnvBootstrap.gitScript()
        assertTrue(s.contains(LinuxEnvBootstrap.GIT_MIRROR))
        assertTrue(s.contains(LinuxEnvBootstrap.GIT_UPSTREAM))
        assertTrue(s.contains("api.github.com"))
        assertTrue(s.contains("--unset-all"))
        assertTrue(s.contains("id=git-mirror rc="))
    }

    @Test
    fun `验收脚本覆盖四项并能 clone 到共享存储`() {
        val s = LinuxEnvBootstrap.verifyScript("/sdcard/dsh-env-check")
        assertTrue(s.contains("chk node node -v"))
        assertTrue(s.contains("chk zip zip -v"))
        assertTrue(s.contains("import requests, bs4, PIL, yaml"))
        assertTrue(s.contains("git clone --depth 1 " + LinuxEnvBootstrap.CLONE_REPO))
        assertTrue(s.contains("/sdcard/dsh-env-check"))
        assertTrue(s.contains("#T6-CHECK name="))
        assertTrue(s.contains("#T6-SUMMARY OK="))
        // 目录建不出来要退到 /workspace，否则没有 /sdcard 的设备整步直接挂。
        assertTrue(s.contains("/workspace/.dsh-env-check"))
    }

    @Test
    fun `解析 STEP 行`() {
        val out = """
            [python] 全量集失败 rc=1，退回轻量集
            #T6-STEP id=python rc=0 set=light size_kb=41234
        """.trimIndent()
        val p = requireNotNull(LinuxEnvBootstrap.parseStep(out, "python"))
        assertEquals(0, p.rc)
        assertEquals("set=light size_kb=41234", p.detail)
        assertNull(LinuxEnvBootstrap.parseStep(out, "verify"))
    }

    @Test
    fun `解析 CHECK 行 detail 可以带空格`() {
        val out = """
            #T6-CHECK name=node ok=1 detail=v20.11.1
            #T6-CHECK name=git-clone ok=0 detail=rc=128 fatal: unable to access
            #T6-SUMMARY OK= node FAIL= git-clone
        """.trimIndent()
        val checks = LinuxEnvBootstrap.parseChecks(out)
        assertEquals(listOf("node", "git-clone"), checks.map { it.name })
        assertTrue(checks[0].ok)
        assertEquals("v20.11.1", checks[0].detail)
        assertFalse(checks[1].ok)
        assertEquals("rc=128 fatal: unable to access", checks[1].detail)
        assertEquals("#T6-SUMMARY OK= node FAIL= git-clone", LinuxEnvBootstrap.parseSummary(out))
    }

    @Test
    fun `runner 一步失败继续跑 并在 python 失败后补轻量集`() = runTest {
        val seen = mutableListOf<String>()
        val verifyOut = """
            #T6-CHECK name=node ok=1 detail=v20.11.1
            #T6-SUMMARY OK= node FAIL=
        """.trimIndent()
        val runner = LinuxEnvBootstrapRunner(channelFor = {
            object : ShellChannel {
                override val id = "fake"
                override val label = "fake"
                override val kind = ChannelKind.Proot
                override fun available() = true
                override fun detail() = "fake"
                override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
                    val marker = command.lineSequence().first {
                        it.contains("#T6-STEP id=") || it.contains("chk node")
                    }
                    val stepId = marker.substringAfter("#T6-STEP id=").substringBefore(' ')
                    seen += stepId
                    return when {
                        marker.contains("chk node") -> ShellResult(0, verifyOut, "")
                        stepId == LinuxEnvBootstrap.STEP_PYTHON -> ShellResult(1, "", "timeout")
                        else -> ShellResult(0, "#T6-STEP id=" + stepId + " rc=0", "")
                    }
                }
            }
        })
        val report = runner.run()
        assertEquals(
            listOf(
                LinuxEnvBootstrap.STEP_TERMUX,
                LinuxEnvBootstrap.STEP_ALPINE,
                LinuxEnvBootstrap.STEP_PYTHON,
                LinuxEnvBootstrap.STEP_GIT,
                LinuxEnvBootstrap.STEP_VERIFY,
                LinuxEnvBootstrap.STEP_PYTHON_LIGHT,
            ),
            report.steps.map { it.id },
        )
        assertTrue(report.steps.first { it.id == LinuxEnvBootstrap.STEP_PYTHON_LIGHT }.ok)
        assertTrue(report.steps.first { it.id == LinuxEnvBootstrap.STEP_VERIFY }.ok)
        assertTrue(report.checks.isNotEmpty() && report.checks.all { it.ok })
        assertTrue(report.summary.orEmpty().startsWith("#T6-SUMMARY OK="))
        assertTrue(report.ok)
    }

    @Test
    fun `runner 通道缺失记失败不抛异常`() = runTest {
        val report = LinuxEnvBootstrapRunner(channelFor = { null }).run()
        assertEquals(5, report.steps.size)
        assertFalse(report.ok)
        assertEquals(5, report.failed().size)
        assertTrue(report.steps.all { it.rc == -1 && it.detail.startsWith("通道不可用") })
    }
}
