package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.ToolCall.ToolStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * P0-5/P0-6：todo_write / goal / read_output 走的是和 shell 同一条工具链路，
 * 执行结果必须落到会话投影（UI 只读），而不是只留在某次回复的正文里。
 */
class ToolStateToolsTest {

    @Test fun todoWriteUpdatesProjectionAndEchoesList() = runBlocking {
        val run = harness(
            Reply.toolCall(
                "todo_write",
                """{"todos":[{"content":"读上下文","status":"completed"},{"content":"改装配","status":"in_progress"}]}""",
            ),
            Reply.text("记下了。"),
        )
        val snap = run.transport.projections.snapshot("session")
        assertEquals(snap.todos.toString(), 2, snap.todos.size)
        assertEquals(1, snap.doneCount)
        assertEquals("改装配", snap.runningTodo?.content)
        val detail = run.toolDetail("todo_write")
        assertTrue(detail, detail.contains("已更新待办（2 条，1/2 完成）"))
        assertTrue(detail, detail.contains("[x] 读上下文"))
        assertTrue(detail, detail.contains("[~] 改装配"))
    }

    @Test fun todoWriteRejectsBadShapeWithoutTouchingProjection() = runBlocking {
        val run = harness(
            Reply.toolCall("todo_write", """{"todos":[{"content":"","status":"pending"}]}"""),
            Reply.text("参数不对。"),
        )
        assertFalse(run.transport.projections.snapshot("session").hasTodos)
        val detail = run.toolDetail("todo_write")
        assertTrue(detail, detail.contains("[执行失败]"))
    }

    @Test fun goalLifecycleLandsInProjection() = runBlocking {
        val run = harness(
            Reply.toolCall("goal", """{"action":"create","objective":"端到端跑通压缩"}"""),
            Reply.toolCall("goal", """{"action":"update","revision":1,"phase":"blocked","blocked_reason":"等构建"}"""),
            Reply.text("目标已建。"),
        )
        val goal = run.transport.projections.snapshot("session").goal
        assertNotNull(goal)
        assertEquals("端到端跑通压缩", goal!!.objective)
        assertEquals(ProjectedGoal.PHASE_BLOCKED, goal.phase)
        assertEquals("等构建", goal.blockedReason)
        assertEquals(2, goal.revision)
    }

    @Test fun goalUpdateWithStaleRevisionFailsLoudly() = runBlocking {
        val run = harness(
            Reply.toolCall("goal", """{"action":"create","objective":"先建一个"}"""),
            Reply.toolCall("goal", """{"action":"update","revision":9,"phase":"complete"}"""),
            Reply.text("版本不对。"),
        )
        // 冲突时不能覆盖：目标仍是创建时那份，phase 没被改成 complete。
        val goal = run.transport.projections.snapshot("session").goal!!
        assertEquals("先建一个", goal.objective)
        assertEquals(ProjectedGoal.PHASE_ACTIVE, goal.phase)
        assertTrue(run.toolDetail("goal"), run.toolDetail("goal").contains("版本冲突"))
    }

    @Test fun readOutputRefusesPathsOutsideSpillRoot() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("spill-read").toFile()
        val run = harness(
            Reply.toolCall("read_output", """{"path":"C:\\\\Windows\\\\win.ini"}"""),
            Reply.text("不读。"),
            spill = ToolOutputSpill(rootProvider = { root }),
        )
        val detail = run.toolDetail("read_output")
        assertTrue(detail, detail.contains("拒绝回读"))
    }

    @Test fun hugeShellOutputGoesToDiskAndComesBackByOffset() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("spill-shell").toFile()
        // 超过 6000 字符阈值才走落盘（4000 行 ≈ 2.8 万字符）。
        val big = (1..4000).joinToString("\n") { "row-" + it }
        val spill = ToolOutputSpill(rootProvider = { root })
        val run = harness(
            Reply.toolCall("shell", """{"command":"dump"}"""),
            Reply.text("看完了。"),
            spill = spill,
            shell = { big },
        )
        // 卡片和回灌模型的正文字段都是「头 + 尾 + locator」，不是 6000 字硬截断。
        val detail = run.toolDetail("shell")
        assertTrue(detail, detail.contains("[工具输出已落盘]"))
        assertTrue(detail, detail.contains("row-1"))
        assertTrue(detail, detail.contains("row-4000"))
        // 落盘文件按 400 行写全，read_output 能从中间接着读。
        val spilled = File(root, "turn").listFiles()!!.first()
        assertEquals(4000, spilled.readLines().size)
        // offset 是 0 起的行下标，header 里换算成真实的第几行。
        val mid = spill.read(spilled.absolutePath, offset = 99, limit = 2)
        assertTrue(mid, mid.contains("本次返回第 100-101 行"))
        assertTrue(mid, mid.contains("row-100"))
        assertTrue(mid, mid.contains("row-101"))
    }

    // ---- 夹具 ----

    private data class Reply(val text: String, val tools: List<Pair<String, String>>) {
        companion object {
            fun text(text: String) = Reply(text, emptyList())
            fun toolCall(name: String, args: String) = Reply("", listOf(name to args))
        }
    }

    private class Run(val transport: LocalEngineTransport, val events: List<DshEvent>) {
        /** 同一调用先发 InProgress 空壳、再发终态；只有终态带输出。 */
        fun tools(name: String) = events.filterIsInstance<DshEvent.ToolCallEvent>()
            .filter { it.call.kind == name && it.call.status != ToolStatus.InProgress }
        /** 终态那条的 detail 才是工具输出（InProgress 那条是空壳）。 */
        fun toolDetail(name: String): String =
            tools(name).lastOrNull()?.call?.detail.orEmpty()
        fun ends() = events.filterIsInstance<DshEvent.TurnEnd>()
    }

    private suspend fun harness(
        vararg replies: Reply,
        spill: ToolOutputSpill = ToolOutputSpill(),
        shell: suspend (String) -> String = { "ok" },
    ): Run = coroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cursor = intArrayOf(0)
        val transport = LocalEngineTransport(
            scope = scope,
            configProvider = {
                LlmConfig(
                    "engine://deepseek-v4.1-flash",
                    "test-key",
                    "deepseek-v4.1-flash",
                    systemPrompt = "test system",
                    maxTokens = 256,
                    supportsTools = true,
                )
            },
            historyProvider = { emptyList<ChatMessage>() },
            shellExec = shell,
            autoApprove = { true },
            // 不接 engineRpc：镜像失败只能告警，不能影响本地投影。
            engineRpc = null,
            spill = spill,
            engineChat = { payload ->
                val reply = replies.getOrElse(cursor[0]++) { Reply.text("收尾。") }
                val value = JSONObject().put("content", reply.text)
                if (reply.tools.isNotEmpty()) {
                    val arr = JSONArray()
                    reply.tools.forEachIndexed { i, one ->
                        arr.put(
                            JSONObject()
                                .put("id", "call_" + i)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", one.first).put("arguments", one.second)),
                        )
                    }
                    value.put("toolCalls", arr)
                }
                JSONObject().put("ok", true).put("value", value)
            },
        )
        val events = Collections.synchronizedList(mutableListOf<DshEvent>())
        val done = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect {
                events += it
                if (it is DshEvent.TurnEnd) done.complete(Unit)
            }
        }
        try {
            transport.prompt("session", "干活", emptyList(), "turn")
            withTimeout(30_000) { done.await() }
            Run(transport, events.toList())
        } finally {
            collector.cancelAndJoin()
            scope.cancel()
        }
    }
}
