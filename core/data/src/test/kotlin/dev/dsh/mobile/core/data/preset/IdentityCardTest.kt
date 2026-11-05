package dev.dsh.mobile.core.data.preset

import dev.dsh.mobile.core.model.IdentityCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.dsh.mobile.core.model.preset.IdentityCards
import dev.dsh.mobile.core.model.preset.AgentPresets

/** 身份卡渲染与兼容口径：顺序固定、空字段不渲染、没卡时与改造前逐字等价。 */
class IdentityCardTest {

    private val full = IdentityCard(
        name = "审稿人",
        identity = "你是《冷咖啡》的技术审稿人",
        duties = listOf("挑逻辑漏洞", "给出可执行改法"),
        boundaries = listOf("不改用户的代码风格", "不删测试"),
        rules = listOf("先说结论"),
        tone = "克制、直给",
        examples = listOf("结论：不通过 —— 理由：X 行空指针"),
        freeform = "引用时带上路径与行号。",
    )

    @Test
    fun renderUsesTheDocumentedOrder() {
        val expected = listOf(
            "## 身份卡：审稿人",
            "你是：你是《冷咖啡》的技术审稿人",
            "职责：",
            "- 挑逻辑漏洞",
            "- 给出可执行改法",
            "禁区：",
            "- 不改用户的代码风格",
            "- 不删测试",
            "准则：",
            "- 先说结论",
            "口吻：克制、直给",
            "示例：",
            "- 结论：不通过 —— 理由：X 行空指针",
            "引用时带上路径与行号。",
        ).joinToString("\n")
        assertEquals(expected, full.render())
    }

    @Test
    fun emptyFieldsAreSkippedWithoutEmptyHeadings() {
        val card = IdentityCard(name = "极简", duties = listOf("   ", ""), tone = "冷淡")
        val rendered = card.render()
        assertEquals("## 身份卡：极简\n口吻：冷淡", rendered)
        // 空标题行一个都不留。
        assertFalse(rendered.contains("职责"))
        assertFalse(rendered.contains("禁区"))
        assertFalse(rendered.contains("准则"))
        assertFalse(rendered.contains("示例"))
    }

    @Test
    fun emptyCardRendersNothing() {
        assertEquals("", IdentityCard().render())
        assertFalse(IdentityCard().hasContent())
        // 只有卡名也算有内容：用户就是想让模型知道「你是谁」这一句。
        assertTrue(IdentityCard(name = "审稿人").hasContent())
        assertEquals("## 身份卡：审稿人", IdentityCard(name = "审稿人").render())
    }

    @Test
    fun composePutsCardBeforePrefixAndKeepsOldBehaviorWithoutCard() {
        val preset = AgentPresets.standard.copy(prefix = "PREFIX", card = full)
        assertEquals(
            listOf(full.render(), "PREFIX", "BASE").joinToString("\n\n"),
            AgentPresets.compose(preset, "BASE", "/tmp", emptySet()),
        )
        // 没卡的预设：与改造前逐字一致（prefix + base）。
        val legacy = AgentPresets.standard.copy(prefix = "PREFIX")
        assertEquals("PREFIX\n\nBASE", AgentPresets.compose(legacy, "BASE", "/tmp", emptySet()))
        // 连前后缀都没有：原样返回 base，不插空行。
        assertEquals("BASE", AgentPresets.compose(AgentPresets.standard, "BASE", "/tmp", emptySet()))
    }

    @Test
    fun legacyPersonaIsSynthesizedAsReadOnlyCard() {
        // 预设没卡 + 会话里有旧 persona = 合成一张自由文本卡（只读展示，不写库）。
        val synthesized = IdentityCards.of(AgentPresets.standard, "你是猫娘，用第一人称说话")
        assertTrue(synthesized != null)
        assertEquals("你是猫娘，用第一人称说话", synthesized!!.freeform)
        assertEquals("## 身份卡\n你是猫娘，用第一人称说话", synthesized.render())

        // 预设带了卡：卡优先，旧 persona 不再掺进来。
        val withCard = AgentPresets.standard.copy(card = full)
        assertEquals(full, IdentityCards.of(withCard, "你是猫娘"))
        // 两边都没有：null（调用方按「没有身份」处理）。
        assertNull(IdentityCards.of(AgentPresets.standard, "   "))
    }

    @Test
    fun storeOverlayCardWinsOverPresetBody() {
        // 覆盖表里拿到卡时走这个重载：卡优先，否则回落到 persona。
        assertEquals(full, IdentityCards.ofCard(full, "旧人设"))
        assertEquals("旧人设", IdentityCards.ofCard(IdentityCard(), "旧人设")!!.freeform)
        assertNull(IdentityCards.ofCard(null, ""))
    }
}
