package dev.dsh.mobile.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtExpansionTest {
    @Test fun historyStartsCollapsed() {
        assertFalse(thoughtExpanded(live = false, hasContent = true, manual = null))
    }
    @Test fun streamingOpensWhenContentArrives() {
        assertFalse(thoughtExpanded(live = true, hasContent = false, manual = null))
        assertTrue(thoughtExpanded(live = true, hasContent = true, manual = null))
    }
    @Test fun completionCollapsesAutomaticExpansion() {
        assertTrue(thoughtExpanded(live = true, hasContent = true, manual = null))
        assertFalse(thoughtExpanded(live = false, hasContent = true, manual = null))
    }
    @Test fun manualHistoryChoiceWins() {
        assertTrue(thoughtExpanded(live = true, hasContent = true, manual = true))
        assertTrue(thoughtExpanded(live = false, hasContent = true, manual = true))
        assertFalse(thoughtExpanded(live = true, hasContent = true, manual = false))
    }
}
