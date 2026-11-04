package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageTest {
    private fun state(
        usage: TokenUsage = TokenUsage(),
        contextWindow: Int = 0,
        tokensPerSecond: Double = 0.0,
    ) = ChatUiState(sessionId = "s", usage = usage, contextWindow = contextWindow, tokensPerSecond = tokensPerSecond)

    /** 用量行是结构化文案：断言资源 id + 参数，具体语言由 UI 侧决定。 */
    private fun ChatUiState.usageLine() = tokenText as UiText.Res

    @Test fun contextUsesLatestPromptInsteadOfAccumulatedTotal() {
        val s = state(TokenUsage(input = 1_300_000, output = 80_000, cached = 20_000, lastPrompt = 32_000), 1_000_000)
        assertEquals(UiText.Res(DesignR.string.chat_usage_line, listOf(3, "32k", "1M", "")), s.tokenText)
        assertEquals(1_400_000L, s.sessionTotalTokens)
    }

    @Test fun overWindowShowsRealCountAndPercentage() {
        val s = state(TokenUsage(lastPrompt = 1_380_000), 1_000_000)
        assertEquals(UiText.Res(DesignR.string.chat_usage_line, listOf(138, "1.4M", "1M", "")), s.tokenText)
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
        assertEquals(
            UiText.Res(DesignR.string.chat_usage_line, listOf(0, "0", "0", "")),
            state(TokenUsage(100, 20, 30)).tokenText,
        )
    }

    @Test fun compactDropsThousandsSeparator() {
        assertEquals("1.5k / 32k", compact(1_500) + " / " + compact(32_000))
        assertEquals(listOf(4, "1.5k", "32k", ""), state(TokenUsage(lastPrompt = 1_500), 32_000).usageLine().args)
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
        // 四段：百分比 / used / window / 速度后缀；没有额外的缓存段。
        assertEquals(4, s.usageLine().args.size)
        assertEquals(80, usage.cachePercent)
    }

    @Test fun speedUsesIntegerAboveTenAndOneDecimalBelow() {
        val fast = state(TokenUsage(lastPrompt = 1_500), 32_000, tokensPerSecond = 223.0)
        assertEquals(listOf(4, "1.5k", "32k", " · 223 tok/s"), fast.usageLine().args)
        val slow = state(TokenUsage(lastPrompt = 1_500), 32_000, 4.23)
        assertEquals(listOf(4, "1.5k", "32k", " · 4.2 tok/s"), slow.usageLine().args)
        val idle = state(TokenUsage(lastPrompt = 1_500), 32_000, 0.0)
        assertEquals(listOf(4, "1.5k", "32k", ""), idle.usageLine().args)
    }

    @Test fun tokenTextExamplesForTopBar() {
        assertEquals(
            UiText.Res(DesignR.string.chat_usage_line, listOf(0, "0", "32k", "")),
            state(contextWindow = 32_000).tokenText,
        )
        assertEquals(
            UiText.Res(DesignR.string.chat_usage_line, listOf(4, "1.5k", "32k", "")),
            state(TokenUsage(lastPrompt = 1_500), 32_000).tokenText,
        )
        assertEquals(
            UiText.Res(DesignR.string.chat_usage_line, listOf(4, "1.5k", "32k", " · 223 tok/s")),
            state(TokenUsage(lastPrompt = 1_500), 32_000, 223.0).tokenText,
        )
    }

    @Test fun contextUsageTextAlsoCompact() {
        val s = state(TokenUsage(lastPrompt = 1_500), 32_000)
        assertEquals(
            UiText.Res(DesignR.string.chat_context_usage_value, listOf("1.5k", "32k", 4)),
            s.contextUsageText,
        )
    }
}
