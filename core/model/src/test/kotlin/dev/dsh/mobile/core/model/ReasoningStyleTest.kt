package dev.dsh.mobile.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** auto 推断表：四类 provider + 本机中转 + 手选值优先级。 */
class ReasoningStyleTest {

    @Test
    fun deepSeekFamilyInfersReasoningEffort() {
        assertEquals(
            ReasoningStyle.reasoningEffort,
            inferReasoningStyle("deepseek", "https://api.deepseek.com/v1", "deepseek-chat"),
        )
        // 名字里带 ds4 的老写法也要认
        assertEquals(
            ReasoningStyle.reasoningEffort,
            inferReasoningStyle("custom", "https://relay.example.com/v1", "ds4-flash"),
        )
    }

    @Test
    fun geminiFamilyInfersThinkingBudget() {
        assertEquals(
            ReasoningStyle.thinkingBudget,
            inferReasoningStyle("gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        )
        assertEquals(
            ReasoningStyle.thinkingBudget,
            inferReasoningStyle("vertex", "https://us-central1-aiplatform.googleapis.com/v1", "gemini-2.5-pro"),
        )
    }

    @Test
    fun anthropicFamilyInfersReasoningEffort() {
        assertEquals(
            ReasoningStyle.reasoningEffort,
            inferReasoningStyle("anthropic", "https://api.anthropic.com", "claude-sonnet-4-6"),
        )
    }

    @Test
    fun unknownFamilyInfersNone() {
        assertEquals(
            ReasoningStyle.none,
            inferReasoningStyle("custom", "https://api.openai.com/v1", "gpt-4o-mini"),
        )
        assertEquals(ReasoningStyle.none, inferReasoningStyle("", "", "qwen2.5:7b"))
    }

    @Test
    fun geminiThroughRelayInfersReasoningEffort() {
        // 本机/第三方中转只读 reasoning_effort（budget_tokens 它看不懂），所以经中转的 Gemini 按 effort 发。
        assertEquals(
            ReasoningStyle.reasoningEffort,
            inferReasoningStyle("custom", "http://127.0.0.1:8317/v1", "gemini-2.5-flash"),
        )
        assertEquals(
            ReasoningStyle.reasoningEffort,
            inferReasoningStyle("relay", "http://10.0.2.2:8318/v1", "gemini-3-flash"),
        )
    }

    @Test
    fun autoResolvesAndManualValueWins() {
        assertEquals(
            ReasoningStyle.thinkingBudget,
            resolveReasoningStyle(ReasoningStyle.auto, "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        )
        // 手选 none 不被推断覆盖
        assertEquals(
            ReasoningStyle.none,
            resolveReasoningStyle(ReasoningStyle.none, "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        )
        assertEquals(
            ReasoningStyle.thinkingToggle,
            resolveReasoningStyle(ReasoningStyle.thinkingToggle, "deepseek", "https://api.deepseek.com/v1", "deepseek-chat"),
        )
    }

    @Test
    fun fromWireFallsBackToAuto() {
        assertEquals(ReasoningStyle.auto, ReasoningStyle.fromWire(null))
        assertEquals(ReasoningStyle.auto, ReasoningStyle.fromWire(""))
        assertEquals(ReasoningStyle.auto, ReasoningStyle.fromWire("garbage"))
        assertEquals(ReasoningStyle.thinkingBudget, ReasoningStyle.fromWire(" ThinkingBudget "))
        assertEquals(ReasoningStyle.none, ReasoningStyle.fromWire("none"))
    }

    @Test
    fun deepSeekFamilyMatchesEchoGate() {
        assertTrue(isDeepSeekFamily("deepseek", "https://api.deepseek.com/v1", "deepseek-chat"))
        assertFalse(isDeepSeekFamily("anthropic", "https://api.anthropic.com", "claude-sonnet-4-6"))
    }
}
