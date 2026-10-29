package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageTest {
    @Test fun contextUsesLatestPromptInsteadOfAccumulatedTotal() {
        val state = ChatUiState(sessionId = "s", usage = TokenUsage(input = 1_300_000, output = 80_000, cached = 20_000, lastPrompt = 32_000), contextWindow = 1_000_000)
        assertTrue(state.tokenText.contains("上下文 3%"))
        assertTrue(state.tokenText.contains("32,000 / 1,000,000"))
        assertEquals(1_400_000L, state.sessionTotalTokens)
    }

    @Test fun overWindowShowsRealCountAndPercentage() {
        val state = ChatUiState(sessionId = "s", usage = TokenUsage(lastPrompt = 1_380_000), contextWindow = 1_000_000)
        assertTrue(state.tokenText.contains("上下文 138%"))
        assertTrue(state.tokenText.contains("1,380,000 / 1,000,000"))
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
        assertTrue(ChatUiState(sessionId = "s", usage = TokenUsage(100, 20, 30)).tokenText.contains("上下文 0%"))
    }
}
