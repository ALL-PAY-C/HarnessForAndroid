package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文窗口：内置表 + 端点探测的优先级，以及 /models 返回的解析。
 *
 * 回归点：DeepSeek 官方 API（deepseek-chat / deepseek-reasoner）是 128k，
 * 以前内置表写 64_000，界面把 128k 的模型显示成「已用一半」，用户以为快满了。
 */
class ContextWindowTest {

    @Test
    fun deepSeekOfficialApisAre128k() {
        assertEquals(128_000, contextWindowOf("deepseek-chat"))
        assertEquals(128_000, contextWindowOf("deepseek-reasoner"))
        assertEquals(128_000, contextWindowOf("DeepSeek-V3"))
    }

    @Test
    fun v4AndOneMillionModelsWin() {
        assertEquals(1_000_000, contextWindowOf("deepseek-v4"))
        assertEquals(1_000_000, contextWindowOf("deepseek-v4.1-flash"))
        assertEquals(1_000_000, contextWindowOf("deepseek-v4-1"))
        assertEquals(1_000_000, contextWindowOf("gemini-2.5-pro"))
    }

    @Test
    fun otherVendorsKeepTheirTableValues() {
        assertEquals(200_000, contextWindowOf("claude-sonnet-4"))
        assertEquals(128_000, contextWindowOf("gpt-4o"))
        assertEquals(128_000, contextWindowOf("gpt-4.1-mini"))
        assertEquals(32_000, contextWindowOf("qwen3-8b"))
        assertEquals(8_192, contextWindowOf("llama-3-8b"))
        // 不认识的模型给 32k，至少「已用百分比」还是个有意义的数。
        assertEquals(32_000, contextWindowOf("some-unknown-model"))
        assertEquals(32_000, contextWindowOf(""))
    }

    @Test
    fun handFilledHintWinsOverEverything() {
        assertEquals(200_000, contextWindowOf("deepseek-chat", hint = 200_000, discovered = mapOf("deepseek-chat" to 99_000)))
        // hint <= 0 等于没填。
        assertEquals(128_000, contextWindowOf("deepseek-chat", hint = 0))
        assertEquals(128_000, contextWindowOf("deepseek-chat", hint = -1))
    }

    @Test
    fun discoveredFromModelsEndpointWinsOverBuiltinTable() {
        assertEquals(196_608, contextWindowOf("deepseek-chat", discovered = mapOf("deepseek-chat" to 196_608)))
        // 带厂商前缀的键也要认（"deepseek/deepseek-chat"）。
        assertEquals(163_840, contextWindowOf("deepseek/deepseek-chat", discovered = mapOf("deepseek-chat" to 163_840)))
        // 探测值非正数等于没探到。
        assertEquals(128_000, contextWindowOf("deepseek-chat", discovered = mapOf("deepseek-chat" to 0)))
    }

    @Test
    fun parsesContextLengthFromModelsEndpoint() {
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-chat","context_length":131072},
              {"id":"qwen3-8b","context_window":32768},
              {"id":"gpt-4o","max_input_tokens":128000},
              {"id":"no-window","owned_by":"x"},
              {"id":"empty"}
            ]}
        """.trimIndent()
        assertEquals(
            mapOf("deepseek-chat" to 131_072, "qwen3-8b" to 32_768, "gpt-4o" to 128_000),
            parseContextLengths(body),
        )
    }

    @Test
    fun parseToleratesOtherShapesAndGarbage() {
        // 有的端点把模型放在 models 里，或者直接回裸数组；名字字段也可能叫 name。
        assertEquals(mapOf("m1" to 64_000), parseContextLengths("""{"models":[{"name":"m1","context_length":64000}]}"""))
        assertEquals(mapOf("m2" to 8_192), parseContextLengths("""[{"id":"m2","context_length":8192}]"""))
        // 读不出来就是空表，调用方退回内置表——绝不抛。
        assertTrue(parseContextLengths("").isEmpty())
        assertTrue(parseContextLengths("not json").isEmpty())
        assertTrue(parseContextLengths("""{"data":[]}""").isEmpty())
    }
}
