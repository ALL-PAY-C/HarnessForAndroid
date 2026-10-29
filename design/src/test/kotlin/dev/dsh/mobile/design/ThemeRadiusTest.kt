package dev.dsh.mobile.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** T9：气泡圆角必须跟着 ThemeTokens.cornerRadius 走，尖角是主圆角的 1/3。 */
class ThemeRadiusTest {

    @Test
    fun defaultTokensFollowCornerRadius() {
        val t = ThemeTokens()
        assertEquals(18.dp, t.bubbleRadius(true))
        assertEquals(18.dp, t.bubbleRadius(false))
        assertEquals(6.dp, t.tailRadius(true))
        assertEquals(6.dp, t.tailRadius(false))
    }

    @Test
    fun customCornerRadiusScalesBubbleAndTail() {
        val t = ThemeTokens(cornerRadius = 24.dp)
        assertEquals(24.dp, t.bubbleRadius(true))
        assertEquals(8.dp, t.tailRadius(true))
    }

    @Test
    fun compactModeClampsRadius() {
        val t = ThemeTokens(cornerRadius = 24.dp, compact = true)
        assertEquals(12.dp, t.bubbleRadius(true))
        assertEquals(4.dp, t.tailRadius(true))
    }
}
