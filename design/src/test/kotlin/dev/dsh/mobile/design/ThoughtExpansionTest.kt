package dev.dsh.mobile.design

import org.junit.Assert.assertEquals
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
    @Test fun thoughtSegmentsCountsParagraphsNotPresence() {
        // 折叠行说「几段」，不是「有没有」：旧实现把有思考记成 1 步再和工具数相加。
        assertEquals(0, thoughtSegments(""))
        assertEquals(0, thoughtSegments("  \n\n \t "))
        assertEquals(1, thoughtSegments("先看一眼目录。"))
        assertEquals(3, thoughtSegments("第一段\n\n第二段\n \n第三段"))
        // 单换行是同一段里的折行，不另算。
        assertEquals(1, thoughtSegments("一行\n另一行"))
    }

    @Test fun manualHistoryChoiceWins() {
        assertTrue(thoughtExpanded(live = true, hasContent = true, manual = true))
        assertTrue(thoughtExpanded(live = false, hasContent = true, manual = true))
        assertFalse(thoughtExpanded(live = true, hasContent = true, manual = false))
    }
}
