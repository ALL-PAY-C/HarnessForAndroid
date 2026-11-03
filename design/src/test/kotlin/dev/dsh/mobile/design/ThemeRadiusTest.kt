package dev.dsh.mobile.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** T9：气泡圆角必须跟着 ThemeTokens.cornerRadius 走，尖角是主圆角的 1/3。 */
class ThemeRadiusTest {

    @Test
    fun defaultTokensFollowCornerRadius() {
        val t = ThemeTokens()
        // 默认主圆角在 T2.1a 从 18dp 收到 16dp（16 是当时卡片最高频实测值），尖角恒为主圆角 1/3。
        assertEquals(16.dp, t.bubbleRadius(true))
        assertEquals(16.dp, t.bubbleRadius(false))
        assertEquals(16.dp / 3, t.tailRadius(true))
        assertEquals(16.dp / 3, t.tailRadius(false))
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
