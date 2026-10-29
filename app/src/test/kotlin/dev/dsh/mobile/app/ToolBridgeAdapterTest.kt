package dev.dsh.mobile.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只钉 adapter 的参数映射与 JSON 透传：FileBridge / LinuxBackground 的真实行为由各自模块的测试负责，
 * 这里用打桩 lambda 把「工具参数 → 桥接调用参数」这一步固定下来。
 */
class ToolBridgeAdapterTest {

    /** adapter 的返回值都是 JSON 字符串，断言字段前先解析成对象。 */
    private fun j(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private class Recorder {
        var read: Triple<String, Long?, Int?>? = null
        var write: Triple<String, String, Boolean>? = null
        var list: Pair<String, Boolean>? = null
        var op: Triple<String, String, String?>? = null
        var shell: Pair<String, String?>? = null
        var channels = 0
    }

    private class StubJobs(
        private val pid: Int = 42,
        private val poll: BgPoll? = BgPoll(42, "running", "line", false),
        private val killed: Boolean = true,
    ) : BackgroundJobs {
        var started: Pair<String, String?>? = null
        var polled: String? = null
        var killedKey: String? = null

        override suspend fun start(command: String, name: String?): Int {
            started = command to name
            return pid
        }

        override fun poll(key: String): BgPoll? {
            polled = key
            return poll
        }

        override fun kill(key: String): Boolean {
            killedKey = key
            return killed
        }
    }

    private fun executors(rec: Recorder, jobs: BackgroundJobs? = null, raw: String = "{\"ok\":true}") =
        ToolExecutors(
            files = FileOps(
                read = { path, offset, limit -> rec.read = Triple(path, offset, limit); raw },
                write = { path, content, append -> rec.write = Triple(path, content, append); raw },
                list = { path, recursive -> rec.list = path to recursive; raw },
                op = { op, src, dst -> rec.op = Triple(op, src, dst); raw },
            ),
            shell = { command, channel -> rec.shell = command to channel; "stdout" },
            channels = { rec.channels++; "[]" },
            background = { jobs },
        )

    private fun args(vararg pairs: Pair<String, Any>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); is Number -> put(k, v.toLong()); else -> put(k, v.toString()) } } }

    @Test
    fun fsReadPassesOffsetAndLimit() = runBlocking {
        val rec = Recorder()
        executors(rec).fsExec("fs_read", args("path" to "/sdcard/a.txt", "offset" to 100, "limit" to 20))
        assertEquals(Triple("/sdcard/a.txt", 100L, 20), rec.read)
    }

    @Test
    fun fsReadOmitsMissingPaging() = runBlocking {
        val rec = Recorder()
        executors(rec).fsExec("fs_read", args("path" to "/sdcard/a.txt"))
        assertNull(rec.read!!.second)
        assertNull(rec.read!!.third)
    }

    @Test
    fun fsWriteDefaultsAppendFalse() = runBlocking {
        val rec = Recorder()
        executors(rec).fsExec("fs_write", args("path" to "/sdcard/a.txt", "content" to "hi"))
        assertEquals(Triple("/sdcard/a.txt", "hi", false), rec.write)
    }

    @Test
    fun fsWriteHonoursAppendAndEmptyContent() = runBlocking {
        val rec = Recorder()
        val ex = executors(rec)
        ex.fsExec("fs_write", args("path" to "/sdcard/a.txt", "content" to "", "append" to true))
        assertEquals(Triple("/sdcard/a.txt", "", true), rec.write)
    }

    @Test
    fun fsListDefaultsRecursiveFalse() = runBlocking {
        val rec = Recorder()
        val ex = executors(rec)
        ex.fsExec("fs_list", args("path" to "/sdcard"))
        assertEquals("/sdcard" to false, rec.list)
        ex.fsExec("fs_list", args("path" to "/sdcard", "recursive" to true))
        assertEquals("/sdcard" to true, rec.list)
    }

    @Test
    fun fsOpWithoutDstPassesNull() = runBlocking {
        val rec = Recorder()
        val ex = executors(rec)
        ex.fsExec("fs_op", args("op" to "mkdir", "src" to "/sdcard/x"))
        assertEquals(Triple("mkdir", "/sdcard/x", null), rec.op)
        ex.fsExec("fs_op", args("op" to "move", "src" to "/sdcard/x", "dst" to "/sdcard/y"))
        assertEquals(Triple("move", "/sdcard/x", "/sdcard/y"), rec.op)
    }

    @Test
    fun fsExecPassesBridgeJsonThroughVerbatim() = runBlocking {
        val body = "{\"ok\":false,\"code\":\"not_found\",\"error\":\"没有这个文件\"}"
        val out = executors(Recorder(), raw = body).fsExec("fs_read", args("path" to "/nope"))
        assertEquals(body, out)
    }

    @Test
    fun fsExecRejectsMissingPathWithoutTouchingBridge() = runBlocking {
        val rec = Recorder()
        val out = executors(rec).fsExec("fs_read", args())
        assertFalse(j(out)["ok"]!!.toString().toBoolean())
        assertEquals("\"invalid_argument\"", j(out)["code"].toString())
        assertNull(rec.read)
    }

    @Test
    fun unknownFsToolIsUnsupported() = runBlocking {
        val out = executors(Recorder()).fsExec("fs_chmod", args("path" to "/x"))
        assertEquals("\"unsupported_op\"", j(out)["code"].toString())
    }

    @Test
    fun shellAndChannelListForward() = runBlocking {
        val rec = Recorder()
        val ex = executors(rec)
        assertEquals("stdout", ex.shellExecChannel("ls", "proot"))
        assertEquals("ls" to "proot", rec.shell)
        assertEquals("[]", ex.channelListExec())
        assertEquals(1, rec.channels)
    }

    @Test
    fun bgExecWithoutLinuxIsUnsupported() = runBlocking {
        val out = executors(Recorder(), jobs = null).bgExec("run_bg", args("command" to "sleep 1"))
        assertEquals("\"unsupported_op\"", j(out)["code"].toString())
    }

    @Test
    fun runBgReturnsPidAndForwardsLogfileName() = runBlocking {
        val jobs = StubJobs()
        val out = executors(Recorder(), jobs).bgExec("run_bg", args("command" to "make", "logfile" to "make.log"))
        assertEquals("make" to "make.log", jobs.started)
        assertTrue(j(out)["pid"].toString() == "42")
    }

    @Test
    fun runBgWithDeadPidReportsError() = runBlocking {
        val out = executors(Recorder(), StubJobs(pid = 0)).bgExec("run_bg", args("command" to "false"))
        assertFalse(j(out)["ok"]!!.toString().toBoolean())
    }

    @Test
    fun bgStatusAcceptsPidOrLogfile() = runBlocking {
        val jobs = StubJobs()
        val ex = executors(Recorder(), jobs)
        val byPid = ex.bgExec("bg_status", args("pid" to 42))
        assertEquals("42", jobs.polled)
        assertEquals("\"running\"", j(byPid)["status"].toString())
        ex.bgExec("bg_status", args("logfile" to "make.log"))
        assertEquals("make.log", jobs.polled)
    }

    @Test
    fun bgStatusOfUnknownJobIsNotFound() = runBlocking {
        val out = executors(Recorder(), StubJobs(poll = null)).bgExec("bg_status", args("pid" to 7))
        assertEquals("\"not_found\"", j(out)["code"].toString())
    }

    @Test
    fun bgKillReportsSuccessAndMiss() = runBlocking {
        val jobs = StubJobs()
        val ok = executors(Recorder(), jobs).bgExec("bg_kill", args("pid" to 42))
        assertTrue(j(ok)["ok"]!!.toString().toBoolean())
        assertEquals("42", jobs.killedKey)
        val miss = executors(Recorder(), StubJobs(killed = false)).bgExec("bg_kill", args("pid" to 42))
        assertEquals("\"not_found\"", j(miss)["code"].toString())
    }
}