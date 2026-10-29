package dev.dsh.mobile.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapseDefaultsTest {
    @Test fun settingsSectionsStartCollapsed() {
        assertEquals("", OPEN_DEFAULT)
    }
}
