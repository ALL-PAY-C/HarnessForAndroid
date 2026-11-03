package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5.4 群聊 notice 分段：结论行取末段、detail 折在后面，且句中「主持人：」不能把结论切成半句。 */
class GroupNoticeTest {

    @Test
    fun headlineKeepsReasonAfterConclusionMarker() {
        val run = NoticeRun(
            start = 0,
            firstId = "",
            messages = 1,
            segments = noticeSegments("主持人：首轮简报：全员各发言一次[群聊已散会] mock 主持人：要点已覆盖，散会"),
        )
        assertEquals("[群聊已散会] mock 主持人：要点已覆盖，散会", run.headline)
        assertEquals(listOf("主持人：首轮简报：全员各发言一次"), run.details)
    }

    @Test
    fun hostMarkerOnItsOwnStartsANotice() {
        val segments = noticeSegments("主持人：首轮简报：全员各发言一次")
        assertEquals(listOf("主持人：首轮简报：全员各发言一次"), segments)
    }

    @Test
    fun newlineSeparatedNoticesSplitWithoutMarkers() {
        val segments = noticeSegments("第一阶段结束\n第二阶段开始")
        assertEquals(listOf("第一阶段结束", "第二阶段开始"), segments)
    }

    @Test
    fun blankTextHasNoSegments() {
        assertTrue(noticeSegments("   ").isEmpty())
    }

    @Test
    fun unknownNoticeFallsBackToLastSegment() {
        val run = NoticeRun(0, "", 1, noticeSegments("自动机连续打回 2 次，改为串行"))
        assertTrue(run.headline.startsWith("自动机连续打回"))
        assertTrue(run.details.isEmpty())
    }
}
