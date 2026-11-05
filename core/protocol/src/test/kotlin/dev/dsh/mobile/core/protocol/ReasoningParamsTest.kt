package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.ReasoningStyle
import dev.dsh.mobile.core.protocol.llm.parseReasoningMap
import dev.dsh.mobile.core.protocol.llm.reasoningParams
import dev.dsh.mobile.core.protocol.llm.thinkingBudgetOf
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求体思考参数：none 一个字段都不发，effort 与 budget 各自形状。 */
class ReasoningParamsTest {

    @Test
    fun noneSendsNoThinkingFieldAtAll() {
        val body = reasoningParams(ReasoningStyle.none, "high")
        assertTrue(body.isEmpty())
        assertNull(body["thinking"])
        assertNull(body["reasoning_effort"])
        // auto 走到这里说明调用方漏了 resolve：同样一个字段都不发，宁可不出思考也不能乱发。
        assertTrue(reasoningParams(ReasoningStyle.auto, "high").isEmpty())
    }

    @Test
    fun reasoningEffortSendsEnabledThinkingAndEffort() {
        val body = reasoningParams(ReasoningStyle.reasoningEffort, "high")
        val thinking = body["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertNull(thinking?.get("budget_tokens"))
    }

    @Test
    fun reasoningEffortOffSendsExplicitDisable() {
        val body = reasoningParams(ReasoningStyle.reasoningEffort, "off")
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun thinkingBudgetSendsBudgetTokens() {
        val body = reasoningParams(ReasoningStyle.thinkingBudget, "medium")
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]?.jsonPrimitive?.content)
        assertEquals("4096", thinking["budget_tokens"]?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
        assertEquals(1024, thinkingBudgetOf("low"))
        assertEquals(8192, thinkingBudgetOf("high"))
    }

    @Test
    fun thinkingToggleSendsToggleOnly() {
        val body = reasoningParams(ReasoningStyle.thinkingToggle, "high")
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals(1, body.size)
    }

    @Test
    fun reasoningMapHitWritesMappedParamVerbatim() {
        // 命中当前档位：只发映射里那条，内置 thinking 结构一个都不出现。
        val body = reasoningParams(ReasoningStyle.thinkingBudget, "high", "high=thinking_budget:8192")
        assertEquals(1, body.size)
        assertEquals(8192, body.getValue("thinking_budget").jsonPrimitive.int)
        assertNull(body["thinking"])
    }

    @Test
    fun reasoningMapHitWithNonNumericValueSendsString() {
        val body = reasoningParams(ReasoningStyle.reasoningEffort, "low", "low=reasoning_effort:minimal")
        assertEquals("minimal", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertNull(body["thinking"])
    }

    @Test
    fun reasoningMapEmptyValueSendsNothing() {
        // off= 表示该档位一个思考字段都不发。
        val body = reasoningParams(ReasoningStyle.thinkingBudget, "off", "off=")
        assertTrue(body.isEmpty())
    }

    @Test
    fun reasoningMapMissFallsBackToBuiltInLogic() {
        // 只映射 high；当前是 low → 走内置 thinkingBudget 分支（budget=1024）。
        val body = reasoningParams(ReasoningStyle.thinkingBudget, "low", "high=thinking_budget:8192")
        assertEquals(1024, body.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.int)
        assertNull(body["thinking_budget"])
    }

    @Test
    fun emptyReasoningMapKeepsLegacyBehaviour() {
        assertEquals(reasoningParams(ReasoningStyle.thinkingBudget, "high"), reasoningParams(ReasoningStyle.thinkingBudget, "high", ""))
    }

    @Test
    fun parseReasoningMapSkipsBlankAndCommentLines() {
        val map = parseReasoningMap("# 说明\n\nhigh=thinking_budget:8192\nlow = reasoning_effort : low \nbroken-line")
        assertEquals(setOf("high", "low"), map.keys)
        assertEquals("thinking_budget", map.getValue("high").single().name)
        assertEquals("8192", map.getValue("high").single().value)
        assertEquals("reasoning_effort", map.getValue("low").single().name)
    }

}
