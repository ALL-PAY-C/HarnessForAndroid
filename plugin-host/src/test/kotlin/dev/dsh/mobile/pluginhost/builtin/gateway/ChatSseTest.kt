package dev.dsh.mobile.pluginhost.builtin.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE 帧解析与聚合（B3-1 §Q4）。三段假帧：普通 delta / usage 帧 / [DONE]。 */
class ChatSseTest {

    private val frameOne =
        """data: {"id":"chat-1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}"""

    private val frameTwo =
        """data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""

    private val frameThree = "data: [DONE]"

    @Test
    fun classifyRecognisesFrames() {
        assertTrue(ChatSse.classify(frameOne) is SseEvent.Chunk)
        assertTrue(ChatSse.classify(frameThree) is SseEvent.Done)
        assertTrue(ChatSse.classify("") is SseEvent.Ignore)
        assertTrue(ChatSse.classify(": ping") is SseEvent.Ignore)
        assertTrue(ChatSse.classify("event: message") is SseEvent.Ignore)
    }

    @Test
    fun aggregateThreeFrames() {
        val aggregator = ChatSse.Aggregator()
        for (line in listOf(frameOne, frameTwo, frameThree)) {
            when (val event = ChatSse.classify(line)) {
                is SseEvent.Chunk -> aggregator.accept(event.json)
                SseEvent.Done -> aggregator.markDone()
                SseEvent.Ignore -> Unit
            }
        }
        assertEquals(listOf("你", "好"), aggregator.deltaOrder())

        val completion = aggregator.toCompletion()
        assertEquals("chat.completion", completion.getString("object"))
        assertEquals("chat-1", completion.getString("id"))
        assertEquals("deepseek-v4.1-flash", completion.getString("model"))
        assertEquals(1700000000L, completion.getLong("created"))
        val choice = completion.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
        val message = choice.getJSONObject("message")
        assertEquals("assistant", message.getString("role"))
        assertEquals("你好", message.getString("content"))
        val usage = completion.getJSONObject("usage")
        assertEquals(9, usage.getInt("total_tokens"))
    }

    @Test
    fun missingTotalTokensIsSynthesised() {
        val aggregator = ChatSse.Aggregator()
        val usageFrame = JSONObject(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":11,"completion_tokens":5}}""",
        )
        aggregator.accept(usageFrame)
        aggregator.markDone()
        assertEquals(16, aggregator.toCompletion().getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun reasoningOnlyTurnFallsBackToContent() {
        val aggregator = ChatSse.Aggregator()
        aggregator.accept(
            JSONObject("""{"choices":[{"index":0,"delta":{"reasoning_content":"想一下"},"finish_reason":null}]}"""),
        )
        aggregator.markDone()
        val message = aggregator.toCompletion().getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("想一下", message.getString("content"))
    }

    @Test
    fun toolCallsAreMergedAndDroppedWithoutDone() {
        val first = JSONObject(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1",
               "function":{"name":"read_file","arguments":"{\"pa"}}]},"finish_reason":null}]}""",
        )
        val second = JSONObject(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
               "function":{"arguments":"th\":\"a\"}"}}]},"finish_reason":"tool_calls"}]}""",
        )
        val truncated = ChatSse.Aggregator()
        truncated.accept(first)
        truncated.accept(second)
        val message = truncated.toCompletion().getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertFalse(message.has("tool_calls"))

        val complete = ChatSse.Aggregator()
        complete.accept(first)
        complete.accept(second)
        complete.markDone()
        val calls = complete.toCompletion().getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("read_file", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("""{"path":"a"}""", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
    }

    @Test
    fun normalizeFixesEmptyFinishReasonAndEmptyDelta() {
        val chunk = JSONObject("""{"choices":[{"index":0,"delta":{"content":""},"finish_reason":""}]}""")
        val normalized = ChatSse.normalize(chunk)!!
        val choice = normalized.getJSONArray("choices").getJSONObject(0)
        assertTrue(choice.isNull("finish_reason"))
        assertFalse(choice.getJSONObject("delta").has("content"))
        assertEquals("chat.completion.chunk", normalized.getString("object"))
    }

    @Test
    fun encodeProducesSingleDataLine() {
        val text = ChatSse.encode(JSONObject().put("a", 1))
        assertTrue(text.startsWith("data: {"))
        assertTrue(text.endsWith("\n\n"))
        assertEquals("data: [DONE]\n\n", ChatSse.doneFrame())
    }
}
