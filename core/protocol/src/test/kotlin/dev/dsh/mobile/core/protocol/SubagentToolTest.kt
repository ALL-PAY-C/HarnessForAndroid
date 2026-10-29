package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * subagent 工具：工具表接线、白名单（不能嵌套）、以及「假上游 + 假工具」跑通整条委派路径。
 *
 * 父侧触发没法端到端（engine:// 路由只会回 content，不会回 tool_calls），
 * 所以委派算法由 [SubagentLoop] 直测——它正是 runSubagent 用的那一个。
 */
class SubagentToolTest {

    @Test
    fun subagentIsExposedWithPromptRequired() {
        val fn = toolsTable().single { nameOf(it) == "subagent" }["function"]!!.jsonObject
        val params = fn["parameters"]!!.jsonObject
        assertEquals(listOf("prompt"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val props = params["properties"]!!.jsonObject.keys
        assertTrue(props.toString(), props.containsAll(listOf("prompt", "name", "model")))
        // 说明里必须点明「看不到当前对话」和「不能再派子代理」，否则模型会写「上面那个文件」。
        val help = fn["description"]!!.jsonPrimitive.content
        assertTrue(help, help.contains("看不到当前对话"))
        assertTrue(help, help.contains("不能再派子代理"))
    }

    @Test
    fun subagentToolTableNeverContainsItself() {
        val transport = transport()
        val names = transport.subagentToolsJson().mapNotNull { nameOf(it) }
        assertFalse("子代理不能嵌套调用自己：$names", "subagent" in names)
        assertEquals(SUBAGENT_TOOLS, names.toSet())
        // 注入式白名单：只给 shell 就只剩 shell。
        assertEquals(listOf("shell"), transport.subagentToolsJson(setOf("shell")).mapNotNull { nameOf(it) })
    }

    @Test
    fun labelFallsBackToDefault() {
        val transport = transport()
        assertEquals("查日志", transport.subagentLabel(buildJsonObject { put("name", "查日志") }))
        assertEquals("子代理", transport.subagentLabel(JsonObject(emptyMap())))
        assertEquals("子代理", transport.subagentLabel(buildJsonObject { put("name", "   ") }))
    }

    @Test
    fun loopRunsToolThenReturnsOnlyTheConclusion() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val steps = mutableListOf<String>()
        var toolRuns = 0
        val loop = SubagentLoop(
            model = "child-model",
            parentSystemPrompt = "会话系统提示词",
            chat = { body ->
                bodies += body
                if (bodies.size == 1) toolCallResponse("shell", """{"command":"ls"}""")
                else contentResponse("子代理结论：目录里 3 个文件")
            },
            runTool = { name, _ -> toolRuns++; ToolExecResult(ok = true, output = name + " 输出 file1") },
            tools = JsonArray(emptyList()),
            label = "查目录",
            onStep = { steps += it },
        )

        val outcome = loop.run("看看当前目录")

        assertTrue(outcome.text, outcome.ok)
        assertEquals("子代理结论：目录里 3 个文件", outcome.text)
        assertEquals(1, outcome.steps)
        assertEquals(1, toolRuns)
        // 子代理自己的一轮上游往返 = 2 次；body 里的 model 用子代理模型。
        assertEquals(2, bodies.size)
        assertEquals("child-model", bodies[0]["model"]!!.jsonPrimitive.content)
        val system = bodies[0]["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system, system.startsWith("会话系统提示词"))
        assertTrue(system, system.contains(SUBAGENT_ROLE))
        // 第二轮带着上一轮的工具结果回灌，父代理历史里不会被插进这些消息。
        val toolMessage = bodies[1]["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals("shell 输出 file1", toolMessage["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("shell：ls"), steps)
    }

    @Test
    fun loopNeverRunsToolsOutsideTheWhitelist() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        var toolRuns = 0
        val loop = SubagentLoop(
            model = "m",
            parentSystemPrompt = "",
            chat = { body ->
                bodies += body
                if (bodies.size == 1) toolCallResponse("subagent", """{"prompt":"再派一层"}""")
                else contentResponse("查完了")
            },
            runTool = { _, _ -> toolRuns++; ToolExecResult(ok = true, output = "不该执行") },
            tools = JsonArray(emptyList()),
        )

        val outcome = loop.run("试试嵌套")

        assertTrue(outcome.text, outcome.ok)
        assertEquals(0, toolRuns)
        val toolMessage = bodies[1]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(toolMessage, toolMessage.contains("子代理不能用工具：subagent"))
    }

    @Test
    fun loopTimeoutReturnsReadableErrorInsteadOfEmptyText() = runBlocking {
        val loop = SubagentLoop(
            model = "m",
            parentSystemPrompt = "",
            chat = { delay(5_000); contentResponse("永远到不了") },
            runTool = { _, _ -> ToolExecResult(ok = true, output = "") },
            tools = JsonArray(emptyList()),
            label = "慢任务",
            budgetMs = 80,
        )

        val outcome = loop.run("慢一点")

        assertFalse(outcome.ok)
        assertTrue(outcome.text, outcome.text.contains("[子代理超时]"))
        assertTrue(outcome.text, outcome.text.contains("慢任务"))
        assertTrue(outcome.text.isNotBlank())
    }

    @Test
    fun loopWithoutConclusionReturnsReadableError() = runBlocking {
        val loop = SubagentLoop(
            model = "m",
            parentSystemPrompt = "",
            chat = { toolCallResponse("shell", """{"command":"ls"}""") },
            runTool = { _, _ -> ToolExecResult(ok = true, output = "file1") },
            tools = JsonArray(emptyList()),
            label = "只跑工具",
            maxRounds = 2,
        )

        val outcome = loop.run("一直跑工具")

        assertFalse(outcome.ok)
        assertEquals(2, outcome.steps)
        assertTrue(outcome.text, outcome.text.contains("[子代理无输出]"))
        assertTrue(outcome.text, outcome.text.contains("只跑工具"))
    }

    // ---- 夹具 ----

    private fun nameOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

    private fun transport() = LocalEngineTransport(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = { null },
        historyProvider = { emptyList() },
        shellExec = { "" },
    )

    private fun toolsTable() = transport().toolsJson().map { it.jsonObject }

    private fun contentResponse(text: String): JsonObject = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", text)
                }
            }
        }
    }

    private fun toolCallResponse(name: String, args: String): JsonObject = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", "")
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("id", "call_" + name)
                            putJsonObject("function") {
                                put("name", name)
                                put("arguments", args)
                            }
                        }
                    }
                }
            }
        }
    }
}
