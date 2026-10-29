package dev.dsh.mobile.feature.chat

import org.junit.Assert.*
import org.junit.Test

class ChatExportTest {
    @Test fun wrapsChineseAndPreservesNewlines() {
        val lines = layoutExportLines(listOf(ExportMsg("甲", "一二三四五\nabcdef")), 4)
        assertEquals(listOf("【甲】一", "二三四五", "abcd", "ef"), lines.map { it.text })
        assertEquals(ExportLineKind.AUTHOR, lines.first().kind)
    }

    @Test fun emptyConversationHasPlaceholder() {
        assertEquals(listOf("（空会话）"), layoutExportLines(emptyList(), 20).map { it.text })
    }

    @Test fun longBodyIsBoundedAndMarked() {
        val lines = layoutExportLines(listOf(ExportMsg("A", "文".repeat(100_000))), 24)
        assertEquals(EXPORT_MAX_LINES, lines.size)
        assertEquals("…（已截断）", lines.last().text)
        assertEquals(ExportLineKind.TRUNCATION, lines.last().kind)
    }

    @Test fun heightIsCappedWithoutIntegerOverflow() {
        assertEquals(8000, exportHeight(Int.MAX_VALUE, 48, 40, 120))
        assertEquals(296, exportHeight(2, 48, 40, 120))
        assertEquals(200, exportHeight(0, 48, 40, 120))
    }

    @Test fun surrogatePairsStayTogether() {
        assertEquals(listOf("【A】😀", "文"), layoutExportLines(listOf(ExportMsg("A", "😀文")), 4).map { it.text })
    }
}
