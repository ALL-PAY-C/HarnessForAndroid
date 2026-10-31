package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.data.preset.AgentPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预设锁定」状态机：没发消息前随便换，发出第一条消息后就锁死。
 */
class PresetSelectionTest {

    @Test
    fun beforeFirstMessageCanSwitch() {
        val s = presetSelectionOf(AgentPresets.defaultPresetId, hasMessages = false)
        assertFalse(s.locked)
        val switched = s.select(AgentPresets.batch.id)
        assertEquals(AgentPresets.batch.id, switched.presetId)
    }

    @Test
    fun afterFirstMessageLocked() {
        val s = presetSelectionOf(AgentPresets.standard.id, hasMessages = true)
        assertTrue(s.locked)
        val after = s.select(AgentPresets.creator.id)
        assertEquals(AgentPresets.standard.id, after.presetId)
    }

    @Test
    fun unknownPresetFallsBackToStandardName() {
        assertEquals(AgentPresets.standard.name, presetNameOf("no-such-preset"))
        assertEquals(AgentPresets.batch.name, presetNameOf(AgentPresets.batch.id))
    }
}
