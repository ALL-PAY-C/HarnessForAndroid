package dev.dsh.mobile.feature.settings

import org.junit.Assert.*
import org.junit.Test

class AgentPresetSettingsStateTest {
    @Test fun initialStateKeepsDefaultPresetIdAndIsIdle() {
        val state = SettingsUiState()
        assertEquals("default", state.settings.defaultPresetId)
        assertFalse(state.busy)
        assertNull(state.message)
    }
}
