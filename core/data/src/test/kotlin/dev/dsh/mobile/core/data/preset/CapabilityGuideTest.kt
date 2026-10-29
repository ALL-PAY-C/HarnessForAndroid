package dev.dsh.mobile.core.data.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGuideTest {
    @Test fun augmentsBaseWithinPromptBudget() {
        val result = CapabilityGuide.augment("BASE")
        assertEquals("BASE\n\n" + CapabilityGuide.TEXT, result)
        assertTrue(result.lines().contains("[DSH_MOBILE_CAPABILITY_GUIDE_V1]"))
        assertTrue(CapabilityGuide.TEXT.length in 1500..4000)
        assertTrue(result.length < 6000)
    }

    @Test fun augmentationIsIdempotent() {
        val once = CapabilityGuide.augment("BASE")
        assertEquals(once, CapabilityGuide.augment(once))
    }

    @Test fun includesVerifiedToolsAndExtensionEntrypoints() {
        listOf("fs_read", "run_bg", "phone", "插件", "预设", "技能").forEach { term ->
            assertTrue("Missing verified capability: $term", CapabilityGuide.TEXT.contains(term))
        }
    }

    @Test fun customPresetKeepsGuideBetweenPersonaParts() {
        val base = CapabilityGuide.augment("BASE")
        val preset = AgentPreset("custom", "Custom", prefix = "PREFIX", suffix = "SUFFIX {{cwd}}")
        val expandedBase = base.replace("{{cwd}}", "/work")
        assertEquals("PREFIX\n\n$expandedBase\n\nSUFFIX /work", AgentPresets.composeSystemPrompt(preset, base, "/work"))
        assertEquals(base, AgentPresets.composeSystemPrompt(AgentPresets.standard, base, "/work"))
    }
}
