package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.llm.parseSubagentTasks
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 契约模块自检：DshJson 配置、JsonObject 取值扩展、LlmConfig 默认值、子任务解析。 */
class ContractSelfCheckTest {
    @Test
    fun jsonExtensionsReadLeniently() {
        val o = DshJson.parseToJsonElement("""{"s":"x","n":12,"b":true,"o":{"k":"1"},"a":[1,2]}""").jsonObject
        assertEquals("x", o.str("s"))
        assertEquals(12L, o.long("n") ?: -1L)
        assertTrue(o.bool("b") == true)
        assertEquals("1", o.obj("o")?.str("k") ?: "")
        assertEquals(2, o.arr("a")?.size ?: -1)
        assertNull(o.str("missing"))
    }

    @Test
    fun llmConfigDefaultsAreStable() {
        val cfg = LlmConfig(baseUrl = "http://127.0.0.1", apiKey = "k", model = "m")
        assertEquals(0, cfg.maxTokens)
        assertTrue(cfg.supportsTools)
        assertTrue(cfg.fallbackModels.isEmpty())
        assertTrue(cfg.systemPrompt.isNotBlank())
    }

    @Test
    fun subagentTasksParseBothForms() {
        val fromJson = parseSubagentTasks("""收尾：[{"title":"A","detail":"B"}]""")
        assertEquals(1, fromJson.size)
        assertEquals("A", fromJson[0].title)
        val fromBullets = parseSubagentTasks("- 任务：甲｜乙\n- 任务：丙")
        assertEquals(2, fromBullets.size)
        assertEquals("甲", fromBullets[0].title)
        assertEquals("丙", fromBullets[1].title)
    }
}
