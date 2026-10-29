package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Test

class CollapseDefaultsTest {
    @Test fun queueStartsCollapsed() {
        assertFalse(QUEUE_EXPANDED_DEFAULT)
    }
}
