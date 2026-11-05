package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginhost.builtin.gateway.stream.StreamEvent
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.SseFrames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4-2 验收 1 的 SSE 部分。上游没有 [DONE]，所以第三条断言的重点是
 * 「EOF 之后聚合照样出结果」，而不是「等到结束标记」。
 */
class SseFramesTest {

    private fun frame(parts: String, finish: String? = null, usage: String? = null): String {
        val builder = StringBuilder()
        builder.append("{\"response\":{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[")
        builder.append(parts)
        builder.append("]}")
        if (finish != null) builder.append(",\"finishReason\":\"").append(finish).append("\"")
        builder.append("}]")
        if (usage != null) builder.append(",\"usageMetadata\":").append(usage)
        builder.append("}}")
        return builder.toString()
    }

    @Test
    fun textPartBecomesContent() {
        val event = SseFrames.parseLine("data: " + frame("{\"text\":\"你好\"}"));
        assertTrue(event is StreamEvent.Chunk);
        val chunk = event as StreamEvent.Chunk
        assertEquals("你好", chunk.text);
        assertNull(chunk.thought);
        assertNull(chunk.finishReason);
    }

    @Test
    fun thoughtPartGoesToReasoning() {
        val event = SseFrames.parseLine(
            "data: " + frame("{\"text\":\"先想一下\",\"thought\":true}"),
        ) as StreamEvent.Chunk
        assertEquals("先想一下", event.thought);
        assertNull(event.text);
    }

    @Test
    fun finishReasonAndUsageSurviveEof() {
        val aggregator = SseFrames.Aggregator("gemini-3.5-flash-lite");
        val lines = listOf(
            "data: " + frame("{\"text\":\"答\"}"),
            "",
            "data: " + frame(
                "{\"text\":\"案\"}",
                finish = "STOP",
                usage = "{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"thoughtsTokenCount\":5,\"totalTokenCount\":10}",
            ),
        )
        var finished = false
        for (line in lines) {
            val event = SseFrames.parseLine(line) ?: continue
            if (aggregator.accept(event)) finished = true
        }
        assertTrue(finished);
        // 上游到此直接关流：没有 [DONE]，EOF 后照样能出完整结果。
        val completion = aggregator.toCompletion()
        val choice = completion.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"));
        assertEquals("答案", choice.getJSONObject("message").getString("content"));
        val usage = completion.getJSONObject("usage");
        assertEquals(3, usage.getInt("prompt_tokens"));
        assertEquals(7, usage.getInt("completion_tokens"));
        assertEquals(5, usage.getJSONObject("completion_tokens_details").getInt("reasoning_tokens"));
    }

    @Test
    fun nonDataLinesAreSkipped() {
        assertNull(SseFrames.parseLine(""));
        assertNull(SseFrames.parseLine("event: message"));
        assertNull(SseFrames.parseLine("data: "));
        // 坏 JSON 不抛，返回 Ignore —— 一条坏帧不该掐断整条回答。
        assertTrue(SseFrames.parseLine("data: {oops") === StreamEvent.Ignore);
    }

    @Test
    fun usageCountsThoughtTokens() {
        val usage = SseFrames.openAiUsage(
            JSONObject().put("promptTokenCount", 11).put("candidatesTokenCount", 4),
        );
        assertNotNull(usage);
        assertEquals(11, usage!!.getInt("prompt_tokens"));
        assertEquals(4, usage.getInt("completion_tokens"));
        assertEquals(15, usage.getInt("total_tokens"));
    }
}
