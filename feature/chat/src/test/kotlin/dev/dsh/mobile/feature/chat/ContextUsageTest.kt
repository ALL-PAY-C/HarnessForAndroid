package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageTest {
    private fun state(
        usage: TokenUsage = TokenUsage(),
        contextWindow: Int = 0,
        tokensPerSecond: Double = 0.0,
    ) = ChatUiState(sessionId = "s", usage = usage, contextWindow = contextWindow, tokensPerSecond = tokensPerSecond)

    @Test fun contextUsesLatestPromptInsteadOfAccumulatedTotal() {
        val s = state(TokenUsage(input = 1_300_000, output = 80_000, cached = 20_000, lastPrompt = 32_000), 1_000_000)
        assertTrue(s.tokenText.contains("上下文 3%"))
        assertTrue(s.tokenText.contains("32k / 1M"))
        assertEquals(1_400_000L, s.sessionTotalTokens)
    }

    @Test fun overWindowShowsRealCountAndPercentage() {
        val s = state(TokenUsage(lastPrompt = 1_380_000), 1_000_000)
        assertTrue(s.tokenText.contains("上下文 138%"))
        assertTrue(s.tokenText.contains("1.4M / 1M"))
    }

    @Test fun additionAccumulatesUsageButKeepsLatestPrompt() {
        val total = TokenUsage(100, 20, 50, 150) + TokenUsage(20, 5, 80, 100)
        assertEquals(120L, total.input)
        assertEquals(25L, total.output)
        assertEquals(130L, total.cached)
        assertEquals(100L, total.lastPrompt)
    }

    @Test fun legacyUsageHasNoInventedContext() {
        assertEquals(0L, TokenUsage(100, 20, 30).lastPrompt)
        assertTrue(state(TokenUsage(100, 20, 30)).tokenText.contains("上下文 0%"))
    }

    @Test fun compactDropsThousandsSeparator() {
        assertEquals("1.5k / 32k", compact(1_500) + " / " + compact(32_000))
        assertFalse(state(TokenUsage(lastPrompt = 1_500), 32_000).tokenText.contains(","))
        assertEquals("999", compact(999))
    }

    @Test fun compactKeepsOneDecimalOnlyWhenNeeded() {
        assertEquals("32k", compact(32_000))
        assertEquals("64k", compact(64_000))
        assertEquals("1.5k", compact(1_500))
        assertEquals("1M", compact(1_000_000))
        assertEquals("1.4M", compact(1_380_000))
    }

    @Test fun cacheSegmentIsDroppedButCachePercentStays() {
        val usage = TokenUsage(input = 20_000, cached = 80_000, lastPrompt = 1_500)
        val s = state(usage, 32_000)
        assertFalse(s.tokenText.contains("缓存"))
        assertEquals(80, usage.cachePercent)
    }

    @Test fun speedUsesIntegerAboveTenAndOneDecimalBelow() {
        val s = state(TokenUsage(lastPrompt = 1_500), 32_000, tokensPerSecond = 223.0)
        assertEquals("上下文 4% · 1.5k / 32k · 223 tok/s", s.tokenText)
        assertTrue(state(TokenUsage(lastPrompt = 1_500), 32_000, 4.23).tokenText.endsWith("· 4.2 tok/s"))
        assertFalse(state(TokenUsage(lastPrompt = 1_500), 32_000, 0.0).tokenText.contains("tok/s"))
    }

    @Test fun tokenTextExamplesForTopBar() {
        assertEquals("上下文 0% · 0 / 32k", state(contextWindow = 32_000).tokenText)
        assertEquals("上下文 4% · 1.5k / 32k", state(TokenUsage(lastPrompt = 1_500), 32_000).tokenText)
        assertEquals(
            "上下文 4% · 1.5k / 32k · 223 tok/s",
            state(TokenUsage(lastPrompt = 1_500), 32_000, 223.0).tokenText,
        )
    }

    @Test fun contextUsageTextAlsoCompact() {
        val s = state(TokenUsage(lastPrompt = 1_500), 32_000)
        assertEquals("1.5k / 32k（4%）", s.contextUsageText)
    }
}
