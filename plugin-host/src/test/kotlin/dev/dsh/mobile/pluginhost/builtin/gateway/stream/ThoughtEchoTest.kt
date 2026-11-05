package dev.dsh.mobile.pluginhost.builtin.gateway.stream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2：让 App 内置网关也能看到思考过程。
 *
 * 两件事各测一半：
 * 1. 出站要**要求**上游回传思考文本（generationConfig.thinkingConfig.includeThoughts）；
 *    只给预算/档位，上游照做思考但不回传，客户端拿到的 reasoning_content 永远是空。
 * 2. 入站要把 thought:true 的 part 认成思考，走 reasoning_content 通道，不混进正文。
 */
class ThoughtEchoTest {

    private val credential = StreamCredential(access = "t")

    private fun request(vararg extra: Pair<String, Any>): JSONObject {
        val openAi = JSONObject()
            .put("model", "gemini-3.5-flash-lite")
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "1+1=?")),
            )
        extra.forEach { (k, v) -> openAi.put(k, v) }
        return EnvelopeCodec.build(openAi, credential)
    }

    private fun thinkingOf(envelope: JSONObject): JSONObject? =
        envelope.optJSONObject("request")
            ?.optJSONObject("generationConfig")
            ?.optJSONObject("thinkingConfig")

    @Test
    fun offTierSendsNoIncludeThoughts() {
        // 关档（含上游根本没给档位）连这个键都不出现：写 false 只是多送一个字段。
        val off = request("reasoning_effort" to "off")
        val offThinking = thinkingOf(off)
        assertNotNull(offThinking)
        assertFalse(offThinking!!.has("includeThoughts"))
        assertFalse(off.toString().contains("includeThoughts"))

        val none = request()
        assertNull(thinkingOf(none))
        assertFalse(none.toString().contains("includeThoughts"))
    }

    @Test
    fun thinkingTiersAskUpstreamToReturnThoughts() {
        val high = thinkingOf(request("reasoning_effort" to "high"))
        assertNotNull(high)
        assertEquals("high", high!!.optString("thinkingLevel"))
        assertTrue(high.optBoolean("includeThoughts", false))

        // auto 档走 thinkingBudget=-1，同样要回传。
        val auto = thinkingOf(request("reasoning_effort" to "auto"))
        assertNotNull(auto)
        assertEquals(-1, auto!!.optInt("thinkingBudget"))
        assertTrue(auto.optBoolean("includeThoughts", false))

        // 其它档位不因这次改动变形：档位照写，多出来的只有 includeThoughts 一个键。
        val low = thinkingOf(request("reasoning_effort" to "low"))
        assertEquals("low", low!!.optString("thinkingLevel"))
        assertEquals(2, low.length())
    }

    @Test
    fun thoughtPartsGoToReasoningChannel() {
        // 一帧里思考与正文各一段：思考进 thought，正文进 text，互不混。
        val mixed = SseFrames.parseFrame(
            """{"response":{"candidates":[{"content":{"parts":[""" +
                """{"text":"先算加法。","thought":true},{"text":"2"}]},"finishReason":"STOP"}]}}""",
        )
        val chunk = mixed as StreamEvent.Chunk
        assertEquals("先算加法。", chunk.thought)
        assertEquals("2", chunk.text)
        assertEquals("stop", chunk.finishReason)

        // 只有思考的帧：解析出 reasoning_content，正文必须为空。
        val aggregator = SseFrames.Aggregator("gemini-3.5-flash-lite")
        aggregator.accept(
            SseFrames.parseFrame(
                """{"response":{"candidates":[{"content":{"parts":[{"text":"先算加法。","thought":true}]}}]}}""",
            ),
        )
        aggregator.accept(
            SseFrames.parseFrame("""{"response":{"candidates":[{"finishReason":"STOP"}]}}"""),
        )
        val message = aggregator.toCompletion()
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        assertEquals("先算加法。", message.getString("reasoning_content"))
        assertEquals("", message.getString("content"))
    }
}
