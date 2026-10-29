package dev.dsh.mobile.core.data.preset

import dev.dsh.mobile.core.data.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class AgentPresetDefaultsTest {
    @Test fun standardPresetIsTheDefaultSetting() {
        val getter = AppSettings::class.java.methods.firstOrNull { it.name == "getDefaultPresetId" }
        assertNotNull("AppSettings must persist a default preset ID", getter)
        assertEquals("default", getter!!.invoke(AppSettings.DEFAULT))
    }
}
