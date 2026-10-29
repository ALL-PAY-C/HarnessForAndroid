package dev.dsh.mobile.core.data.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPresetsTest {

    @Test
    fun builtins_coverTheSixModes() {
        assertEquals(
            listOf("standard", "batch", "creator", "token-saver", "liangshen", "whale-elite"),
            AgentPresets.builtins.map { it.id },
        )
        assertTrue(AgentPresets.builtins.all { it.builtin })
        assertTrue(AgentPresets.builtins.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun builtins_keepPrefixWithinBudget() {
        // 预设会随每次请求进系统提示词，prefix 超预算就是在烧上下文。
        AgentPresets.builtins.forEach { preset ->
            assertTrue("${preset.id} prefix 过长：${preset.prefix.length}", preset.prefix.length <= 1200)
            assertTrue("${preset.id} suffix 过长：${preset.suffix.length}", preset.suffix.length <= 1200)
        }
    }

    @Test
    fun defaultPreset_isWhaleElite() {
        assertEquals("whale-elite", AgentPresets.defaultPresetId)
        assertEquals(AgentPresets.whaleElite, AgentPresets.builtinOf(AgentPresets.defaultPresetId))
    }

    @Test
    fun resolveId_mapsLegacyDefaultToStandard() {
        assertEquals("standard", AgentPresets.resolveId("default"))
        assertEquals("liangshen", AgentPresets.resolveId("liangshen"))
        assertNull(AgentPresets.builtinOf("default"))
    }

    @Test
    fun standard_composesToBaseWithoutDecorations() {
        val base = "BASE"
        assertEquals(base, AgentPresets.compose(AgentPresets.standard, base, "/work", emptySet()))
        assertEquals(base, AgentPresets.composeSystemPrompt(AgentPresets.standard, base, "/work"))
    }

    @Test
    fun compose_appendsSwitchPrefixOnlyWhenOn() {
        val off = AgentPresets.compose(AgentPresets.liangshen, "BASE", "/work", emptySet())
        val on = AgentPresets.compose(AgentPresets.liangshen, "BASE", "/work", setOf("dispatcher"))
        assertFalse(off.contains(AgentPresets.liangshen.switchPrefixes.getValue("dispatcher")))
        assertTrue(on.contains(AgentPresets.liangshen.switchPrefixes.getValue("dispatcher")))
        assertTrue(on.startsWith(AgentPresets.liangshen.prefix))
        assertTrue(on.contains("\n\nBASE\n\n"))
        assertTrue(on.contains("工作目录：/work"))
        assertFalse(on.contains("{{cwd}}"))
    }

    @Test
    fun composeSystemPrompt_equalsDefaultsOn() {
        // 老签名必须等价于「按预设自带的默认开关」——现有调用点还没改成新签名时不能变味。
        val expected = AgentPresets.compose(
            AgentPresets.whaleElite,
            "BASE",
            "/work",
            AgentPresets.defaultSwitches(AgentPresets.whaleElite),
        )
        assertEquals(expected, AgentPresets.composeSystemPrompt(AgentPresets.whaleElite, "BASE", "/work"))
        assertTrue(expected.contains(AgentPresets.whaleElite.switchPrefixes.getValue("lean")))
    }

    @Test
    fun switches_declareDispatcherAndLean() {
        assertEquals(listOf("dispatcher"), AgentPresets.liangshen.switches.map { it.id })
        assertEquals("调度模式", AgentPresets.liangshen.switches.single().label)
        assertTrue(AgentPresets.liangshen.switches.single().defaultOn)
        assertEquals(listOf("lean"), AgentPresets.whaleElite.switches.map { it.id })
        assertEquals("省 Token", AgentPresets.whaleElite.switches.single().label)
        assertTrue(AgentPresets.whaleElite.switches.single().defaultOn)
        // 其它预设没有开关：面板上就不该出现开关区。
        listOf(AgentPresets.standard, AgentPresets.batch, AgentPresets.creator, AgentPresets.tokenSaver)
            .forEach { assertTrue(it.switches.isEmpty()) }
    }

    @Test
    fun switchOn_fallsBackToDefaultsAndDropsUnknownIds() {
        val preset = AgentPresets.whaleElite
        assertEquals(setOf("lean"), AgentPresets.switchOn(preset, null))
        assertEquals(emptySet<String>(), AgentPresets.switchOn(preset, emptySet()))
        assertEquals(setOf("lean"), AgentPresets.switchOn(preset, setOf("lean", "ghost")))
        assertEquals(emptySet<String>(), AgentPresets.switchOn(preset, setOf("ghost")))
    }

    @Test
    fun builtinOf_returnsEachBuiltin() {
        AgentPresets.builtins.forEach { assertNotNull(AgentPresets.builtinOf(it.id)) }
    }
}
