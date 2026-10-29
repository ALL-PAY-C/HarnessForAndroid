package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧 preferences 兼容 + 新字段往返（不碰 Context，纯字符串层面）。 */
class ModelCodecTest {

    private fun record(vararg fields: String) = fields.joinToString(ModelCodec.FIELD_SEP)

    @Test
    fun oldSevenFieldRecordReadsWithDefaults() {
        // 1.22 之前的落盘格式：7 个字段，连 contextWindow 都没有
        val raw = record("m-1", "DeepSeek", "deepseek", "https://api.deepseek.com/v1", "sk-abcdefgh", "deepseek-chat", "1")
        val list = ModelCodec.decode(raw)
        assertEquals(1, list.size)
        val m = list[0]
        assertEquals("m-1", m.id)
        assertEquals("deepseek-chat", m.model)
        assertTrue(m.isDefault)
        assertEquals(0, m.contextWindow)
        assertEquals("high", m.reasoningEffort)
        assertEquals(0, m.maxOutputTokens)
        assertEquals(-1f, m.temperature)
        assertEquals(-1f, m.topP)
        assertTrue(m.supportsTools)
    }

    @Test
    fun oldEightFieldRecordKeepsContextWindow() {
        val raw = record("m-2", "中转", "custom", "http://10.0.0.2:11434/v1", "k", "qwen2.5:7b", "0", "32768")
        val m = ModelCodec.decode(raw).single()
        assertEquals(32768, m.contextWindow)
        assertFalse(m.isDefault)
        assertEquals("high", m.reasoningEffort)
        assertEquals(0, m.maxOutputTokens)
        assertTrue(m.supportsTools)
    }

    @Test
    fun newFieldsRoundTrip() {
        val profile = ModelProfile(
            id = "m-3",
            name = "推理档",
            providerId = "openai-compatible",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-0123456789",
            model = "gpt-4o-mini",
            isDefault = false,
            contextWindow = 128_000,
            reasoningEffort = "medium",
            maxOutputTokens = 2048,
            temperature = 0.35f,
            topP = 0.9f,
            supportsTools = false,
        )
        val back = ModelCodec.decode(ModelCodec.encode(listOf(profile))).single()
        assertEquals(profile, back)
        assertEquals("中", back.reasoningLabel)
    }

    @Test
    fun unknownEffortAndNaNFieldsFallBack() {
        val raw = record("m-4", "x", "custom", "http://h/v1", "k", "m", "0", "0", "extreme", "0", "", "NaN", "1")
        val m = ModelCodec.decode(raw).single()
        assertEquals("high", m.reasoningEffort)
        assertEquals(0, m.maxOutputTokens)
        assertEquals(-1f, m.temperature)
        assertEquals(-1f, m.topP)
        assertTrue(m.supportsTools)
    }

    @Test
    fun multipleRecordsAndGarbageAreTolerated() {
        val raw = listOf(
            record("a", "A", "deepseek", "u", "k", "m1", "0"),
            "broken|record",
            record("b", "B", "custom", "u2", "k", "m2", "1", "8192", "low", "512", "0.2", "0.8", "0"),
        ).joinToString(ModelCodec.RECORD_SEP)
        val list = ModelCodec.decode(raw)
        assertEquals(2, list.size)
        assertEquals("a", list[0].id)
        assertEquals("low", list[1].reasoningEffort)
        assertEquals(0.2f, list[1].temperature)
        assertFalse(list[1].supportsTools)
    }

    @Test
    fun blankRawIsEmpty() {
        assertEquals(0, ModelCodec.decode("").size)
        assertEquals(0, ModelCodec.decode("   ").size)
    }

    @Test
    fun decimalInputDropsIllegalCharacters() {
        assertEquals("0.75", sanitizeDecimalInput("0.7a5"))
        assertEquals("0.5", sanitizeDecimalInput("0,5"))
        assertEquals("", sanitizeDecimalInput("abc"))
        assertEquals("0.5", sanitizeDecimalInput("0..5"))
        assertEquals("5", sanitizeDecimalInput(".5"))
    }

    @Test
    fun reasoningSanitizerAndLabels() {
        assertEquals("low", sanitizeReasoningEffort(" LOW "))
        assertEquals("high", sanitizeReasoningEffort("bogus"))
        assertEquals("off", sanitizeReasoningEffort("off"))
        assertEquals("关", reasoningLabelOf("off"))
        assertEquals("低", reasoningLabelOf("low"))
        assertEquals("中", reasoningLabelOf("medium"))
        assertEquals("高", reasoningLabelOf("high"))
    }
}
