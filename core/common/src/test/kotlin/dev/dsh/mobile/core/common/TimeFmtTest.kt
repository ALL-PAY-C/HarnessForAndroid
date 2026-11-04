package dev.dsh.mobile.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFmtTest {

    /** UTC 日内 22:13:20，前后各一小时都不会跨天，档位断言与机器时区无关。 */
    private val now = 1_700_000_000_000L

    private fun rel(delta: Long): RelativeTime? = TimeFmt.relativeParts(now - delta, now)

    @Test
    fun invalidTimestampYieldsNull() {
        assertNull(TimeFmt.relativeParts(0L, now))
        assertNull(TimeFmt.relativeParts(-1L, now))
    }

    @Test
    fun justNowCoversFirstMinute() {
        assertEquals(RelativeTime.JustNow, rel(0L))
        assertEquals(RelativeTime.JustNow, rel(59_999L))
    }

    @Test
    fun minutesTierStartsAtOneMinute() {
        assertEquals(RelativeTime.Minutes(1), rel(60_000L))
        assertEquals(RelativeTime.Minutes(59), rel(3_599_999L))
    }

    @Test
    fun clockTierCoversFirstDay() {
        val start = rel(3_600_000L)
        assertTrue(start is RelativeTime.Clock)
        assertTrue(Regex("\\d{2}:\\d{2}").matches((start as RelativeTime.Clock).hhmm))
        val end = rel(86_399_999L)
        assertTrue(end is RelativeTime.Clock)
    }

    @Test
    fun daysTierCoversFirstWeek() {
        assertEquals(RelativeTime.Days(1), rel(86_400_000L))
        assertEquals(RelativeTime.Days(6), rel(7 * 86_400_000L - 1))
    }

    @Test
    fun absoluteTierStartsAfterOneWeek() {
        val value = rel(7 * 86_400_000L)
        assertTrue(value is RelativeTime.Absolute)
        assertTrue(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches((value as RelativeTime.Absolute).text))
    }

    @Test
    fun dividerSplitsTodayYesterdayAndOlder() {
        val today = TimeFmt.dividerParts(now - 3_600_000L, now)
        assertTrue(today is DividerLabel.Today)
        assertTrue(Regex("\\d{2}:\\d{2}").matches((today as DividerLabel.Today).hhmm))

        val yesterday = TimeFmt.dividerParts(now - 86_400_000L, now)
        assertTrue(yesterday is DividerLabel.Yesterday)
        assertTrue(Regex("\\d{2}:\\d{2}").matches((yesterday as DividerLabel.Yesterday).hhmm))

        val older = TimeFmt.dividerParts(now - 10 * 86_400_000L, now)
        assertTrue(older is DividerLabel.Text)
        assertTrue(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches((older as DividerLabel.Text).text))

        assertEquals(DividerLabel.None, TimeFmt.dividerParts(0L, now))
        assertEquals(DividerLabel.None, TimeFmt.dividerParts(-5L, now))
    }
}
