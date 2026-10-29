package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R2.4：后台任务原语。真进程/真 /proc 一律打桩（probe + killer + exec 都注入）。 */
class LinuxBackgroundTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `launch 真的把进程 detach 掉并交回 pid`() {
        val s = bg().launchScript("ffmpeg -i a.mp4 b.mp4", "/workspace/.dsh-bg/j1.log", "/workspace/.dsh-bg/j1.rc")
        assertTrue(s, s.contains("mkdir -p '/workspace/.dsh-bg'"))
        assertTrue(s, s.contains(": > '/workspace/.dsh-bg/j1.log'"))      // 同名重跑不混旧日志
        assertTrue(s, s.contains("rm -f '/workspace/.dsh-bg/j1.rc'"))
        assertTrue(s, s.contains("nohup setsid sh -c 'ffmpeg -i a.mp4 b.mp4; echo \$? > /workspace/.dsh-bg/j1.rc'"))
        assertTrue(s, s.contains("> '/workspace/.dsh-bg/j1.log' 2>&1 &"))
        assertTrue(s, s.contains("echo \$!"))
    }

    @Test
    fun `命令里的单引号不会打穿包装`() {
        val s = bg().launchScript("echo 'a b'", "/w/x.log", "/w/x.rc")
        // 单引号被换成 '\'' —— 否则 sh -c 的引号会被命令里的引号提前闭合
        val escaped = "echo " + "'" + "\\" + "''a b" + "'" + "\\" + "''"
        assertTrue(s, s.contains(escaped))
        assertTrue(s, s.contains("; echo " + "\$" + "? > /w/x.rc'"))
    }

    @Test
    fun `parsePid 取最后一行数字`() {
        assertEquals(4242, bg().parsePid("mkdir: ok\n4242\n"))
        assertEquals(7, bg().parsePid("7"))
        assertNull(bg().parsePid("no pid here"))
        assertNull(bg().parsePid(""))
    }

    @Test
    fun `run 起任务并落记录`() = runTest {
        val seen = mutableListOf<String>()
        val job = bg().run(
            exec = { cmd -> seen.add(cmd); ShellResult(0, "\n4242\n", "") },
            command = "sleep 30",
            name = "j1",
        )
        assertEquals(4242, job.pid)
        assertEquals("sleep 30", job.command)
        assertEquals(File(tmp.root, "logs/j1.log").absolutePath, job.logPath)
        assertEquals(File(tmp.root, "logs/j1.rc").absolutePath, job.rcPath)
        assertTrue(seen.single().contains("/workspace/.dsh-bg/j1.log"))
        assertEquals(4242, bg().load(4242)?.pid)
    }

    @Test
    fun `pid 解析不出来时不落记录`() = runTest {
        val job = bg().run(exec = { ShellResult(0, "", "") }, command = "x", name = "j2")
        assertEquals(0, job.pid)
        assertTrue(File(tmp.root, "jobs").listFiles().isNullOrEmpty() || File(tmp.root, "jobs").listFiles()!!.isEmpty())
    }

    @Test
    fun `状态机 running exited unknown`() {
        File(tmp.root, "logs").mkdirs()
        val job = job(logPath = "/nowhere.log", rcPath = File(tmp.root, "logs/j3.rc").absolutePath)
        assertEquals(BgStatus.Unknown, bg(alive = false).status(job).status)   // 没 rc 就不知道退出码
        File(job.rcPath).writeText("7\n")
        assertEquals(BgStatus.Exited(7), bg(alive = false).status(job).status)
        assertEquals(BgStatus.Running, bg(alive = true).status(job).status)   // 活着就是 running，不看 rc
        assertEquals(BgStatus.Unknown, bg(alive = true).status(job.copy(pid = 0)).status)
    }

    @Test
    fun `增量输出只吐新内容`() {
        val log = File(tmp.root, "logs/j4.log").also { it.parentFile.mkdirs(); it.writeText("line1\n") }
        val job = job(logPath = log.absolutePath)
        val first = bg().status(job)
        assertEquals("line1\n", first.newOutput)
        assertFalse(first.truncated)

        log.appendText("line2\n")
        val second = bg().status(job)
        assertEquals("line2\n", second.newOutput)

        assertEquals("", bg().status(job).newOutput)   // 没新内容就是空
        assertEquals(log.length(), job.offset)
    }

    @Test
    fun `偏移量持久化 重启后不重复吐`() {
        val log = File(tmp.root, "logs/j5.log").also { it.parentFile.mkdirs(); it.writeText("abcdef") }
        val job = job(logPath = log.absolutePath, pid = 5555)
        val background = bg()
        background.save(job)
        assertEquals("abcdef", background.status(job).newOutput)

        val reborn = background.load(5555)!!
        assertEquals(6L, reborn.offset)
        assertEquals("", bg().status(reborn).newOutput)
    }

    @Test
    fun `超过上限分块吐并标 truncated`() {
        val log = File(tmp.root, "logs/j6.log").also { it.parentFile.mkdirs(); it.writeText("abcdefgh") }
        val job = job(logPath = log.absolutePath)
        val small = bg(maxIncrement = 4)
        val one = small.status(job)
        assertEquals("abcd", one.newOutput)
        assertTrue(one.truncated)
        val two = small.status(job)
        assertEquals("efgh", two.newOutput)
        assertFalse(two.truncated)
    }

    @Test
    fun `日志被截断后从头读`() {
        val log = File(tmp.root, "logs/j7.log").also { it.parentFile.mkdirs(); it.writeText("abcdef") }
        val job = job(logPath = log.absolutePath).also { it.offset = 6 }
        log.writeText("xy")
        assertEquals("xy", bg().status(job).newOutput)
        assertEquals(2L, job.offset)
    }

    @Test
    fun `kill 只对活 pid 下手`() {
        val killed = mutableListOf<Int>()
        val background = bg(killed = killed)
        assertTrue(background.kill(job(pid = 99)))
        assertEquals(listOf(99), killed)
        assertFalse(background.kill(job(pid = 0)))
        assertEquals(1, killed.size)
    }

    @Test
    fun `记录编解码往返 含换行与反斜杠`() {
        val background = bg()
        val job = job(command = "printf 'a\nb' \\\\ x", startedAt = 123L).also { it.offset = 9 }
        val back = background.decode(background.encode(job))!!
        assertEquals("printf 'a\nb' \\\\ x", back.command)
        assertEquals(9L, back.offset)
        assertEquals(123L, back.startedAt)
        assertEquals(job.logPath, back.logPath)
    }

    @Test
    fun `list 按启动时间排序`() {
        val background = bg()
        background.save(job(pid = 2, startedAt = 200))
        background.save(job(pid = 1, startedAt = 100))
        assertEquals(listOf(1, 2), background.list().map { it.pid })
    }

    @Test
    fun `exec 超时但 pid 文件已写好 仍拿回 pid`() = runTest {
        File(tmp.root, "logs").mkdirs()
        File(tmp.root, "logs/j8.pid").writeText("7788\n")
        val job = bg(pollDelayMs = 10, pidWaitMs = 50).run(
            exec = { throw java.util.concurrent.TimeoutException("执行超时（30000ms）") },
            command = "sleep 30",
            name = "j8",
        )
        assertEquals(7788, job.pid)
        assertEquals(7788, bg().load(7788)?.pid)
    }

    @Test
    fun `exec 超时且没有 pid 文件 返回 0 不抛`() = runTest {
        val job = bg(pollDelayMs = 10, pidWaitMs = 50).run(
            exec = { throw java.util.concurrent.TimeoutException("执行超时（30000ms）") },
            command = "x",
            name = "j9",
        )
        assertEquals(0, job.pid)
    }

    @Test
    fun `exec 被取消时 run 把 CancellationException 抛出去`() = runTest {
        val thrown = runCatching {
            bg(pollDelayMs = 10, pidWaitMs = 50).run(
                exec = { throw kotlinx.coroutines.CancellationException("cancelled") },
                command = "x",
                name = "j10",
            )
        }.exceptionOrNull()
        assertTrue("取消必须抛出去，实际=$thrown", thrown is kotlinx.coroutines.CancellationException)
        assertTrue(File(tmp.root, "jobs").listFiles().isNullOrEmpty())   // 取消路径不留 job 记录
    }

    private fun job(
        pid: Int = 4242,
        command: String = "sleep 1",
        logPath: String = "/nowhere.log",
        rcPath: String = "/nowhere.rc",
        startedAt: Long = 1_000L,
    ): BgJob = BgJob(pid, command, logPath, rcPath, startedAt)

    /** 私有/日志目录都落在临时目录；probe/killer/now 全注入，不碰真 /proc。 */
    private fun bg(
        alive: Boolean = false,
        killed: MutableList<Int> = mutableListOf(),
        maxIncrement: Int = LinuxBackground.MAX_INCREMENT,
        pollDelayMs: Long = 250,
        pidWaitMs: Long = 4_000,
    ): LinuxBackground = LinuxBackground(
        jobsDir = File(tmp.root, "jobs"),
        logDir = File(tmp.root, "logs"),
        guestLogDir = "/workspace/.dsh-bg",
        probe = PidProbe { alive },
        killer = { pid -> killed.add(pid); true },
        maxIncrement = maxIncrement,
        pollDelayMs = pollDelayMs,
        pidWaitMs = pidWaitMs,
        now = { 1_000_000L },
    )
}
