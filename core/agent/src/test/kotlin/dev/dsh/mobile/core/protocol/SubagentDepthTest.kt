package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.llm.SUBAGENT_MAX_DEPTH
import dev.dsh.mobile.core.protocol.llm.SUBAGENT_TOOLS
import dev.dsh.mobile.core.protocol.llm.SubagentLoop
import dev.dsh.mobile.core.protocol.llm.depthRefusal
import dev.dsh.mobile.core.protocol.llm.subagentToolNames
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.3：子代理嵌套深度上限。
 *
 * 口径：depth = 当前执行者层级，主代理第 0 层。depth < maxDepth 时白名单里才含 subagent，
 * 于是 maxDepth=2 时第二层还能派、第三层被拒并回可读错误（不静默失败）。
 */
class SubagentDepthTest {

    /** 上游收到的请求体，用来断言回灌给模型的是哪条错误。 */
    private val bodies = mutableListOf<JsonObject>()

    @Test
    fun whitelistOnlyContainsSubagentBelowTheLimit() {
        assertTrue("subagent" in subagentToolNames(0))
        assertTrue("subagent" in subagentToolNames(1))
        assertEquals(SUBAGENT_TOOLS, subagentToolNames(SUBAGENT_MAX_DEPTH))
        assertEquals(SUBAGENT_TOOLS, subagentToolNames(SUBAGENT_MAX_DEPTH + 5))
        // 上限可配：maxDepth=1 时第 0 层能派、第 1 层不能；maxDepth=3 时第 2 层还能再深一层。
        assertEquals(SUBAGENT_TOOLS + "subagent", subagentToolNames(0, maxDepth = 1))
        assertEquals(SUBAGENT_TOOLS, subagentToolNames(1, maxDepth = 1))
        assertEquals(SUBAGENT_TOOLS + "subagent", subagentToolNames(2, maxDepth = 3))
        assertEquals(SUBAGENT_TOOLS, subagentToolNames(3, maxDepth = 3))
    }

    @Test
    fun refusalTextIsReadable() {
        val text = depthRefusal(2, 2)
        assertTrue(text, text.contains("子代理最多嵌套 2 层"))
        assertTrue(text, text.contains("第 2 层"))
    }

    @Test
    fun loopBelowLimitStillDispatchesNestedSubagent() = runBlocking {
        var toolRuns = 0
        val loop = SubagentLoop(
            model = "m",
            parentSystemPrompt = "",
            chat = { body ->
                bodies += body
                if (bodies.size == 1) toolCallResponse("subagent", """{"prompt":"再派一层"}""")
                else contentResponse("孙代理结论")
            },
            runTool = { _, _ -> toolRuns++; ToolExecResult(ok = true, output = "孙代理输出") },
            tools = JsonArray(emptyList()),
            // 第一层子代理：还没到顶，subagent 仍在白名单里。
            depth = 1,
        )

        val outcome = loop.run("派一层")

        assertTrue(outcome.text, outcome.ok)
        assertEquals(1, toolRuns)
    }

    @Test
    fun loopAtLimitRefusesInsteadOfSilentlyFailing() = runBlocking {
        var toolRuns = 0
        val loop = SubagentLoop(
            model = "m",
            parentSystemPrompt = "",
            chat = { body ->
                bodies += body
                if (bodies.size == 1) toolCallResponse("subagent", """{"prompt":"再派一层"}""")
                else contentResponse("到顶了")
            },
            runTool = { _, _ -> toolRuns++; ToolExecResult(ok = true, output = "不该执行") },
            tools = JsonArray(emptyList()),
            depth = SUBAGENT_MAX_DEPTH,
        )

        val outcome = loop.run("再派一层")

        assertTrue(outcome.text, outcome.ok)
        assertEquals(0, toolRuns)
        val toolMessage = bodies.last()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(toolMessage, toolMessage.contains("子代理最多嵌套 2 层"))
    }

    // ---- 夹具 ----

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
