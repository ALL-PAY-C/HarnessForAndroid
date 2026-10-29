package dev.dsh.mobile.core.protocol.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    private fun bingOnly() = FakeTransport { request ->
        if (request.url.contains("bing.com")) request.respond(body = BING_HTML)
        else request.respond(status = 500, body = "boom")
    }

    @Test
    fun freeEngineAnswersWithoutAnyKey() = runTest {
        val http = bingOnly()
        val outcome = SearchChain().search("kotlin", 5, http, WebSettings())
        assertEquals("bing", outcome.engine)
        assertEquals(SearchTier.Free, outcome.tier)
        assertEquals("免费引擎（无需密钥）", outcome.tierLabel)
        assertEquals(2, outcome.sources.size)
        assertEquals("https://example.com/a", outcome.sources[0].url)
        assertEquals("第一结果", outcome.sources[0].title)
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun keyedEngineWithoutKeyIsSkippedWithReason() = runTest {
        val http = bingOnly()
        val outcome = SearchChain().search("kotlin", 5, http, WebSettings(engine = "exa"))
        // 指定了 exa 但没密钥：跳过并给出原因，绝不静默当成 exa 成功
        assertEquals("bing", outcome.engine)
        assertEquals(1, outcome.skipped.size)
        assertEquals("exa", outcome.skipped[0].engine)
        assertEquals(SearchTier.Keyed, outcome.skipped[0].tier)
        assertTrue(outcome.skipped[0].reason.contains("Exa"))
        assertFalse(http.requests.any { it.url.contains("api.exa.ai") })
    }

    @Test
    fun keyedEngineUsedWhenKeyConfigured() = runTest {
        val http = FakeTransport { request ->
            if (request.url.contains("api.exa.ai")) {
                request.respond(body = EXA_JSON, contentType = "application/json")
            } else {
                request.respond(body = BING_HTML)
            }
        }
        val outcome = SearchChain().search("kotlin", 5, http, WebSettings(engine = "exa", exaApiKey = "k-1"))
        assertEquals("exa", outcome.engine)
        assertEquals(SearchTier.Keyed, outcome.tier)
        assertEquals("需密钥引擎", outcome.tierLabel)
        assertEquals(1, outcome.sources.size)
        assertEquals("https://example.net/exa", outcome.sources[0].url)
        assertEquals("k-1", http.requests.first { it.url.contains("api.exa.ai") }.headers["x-api-key"])
    }

    @Test
    fun failedEngineFallsBackToNextOne() = runTest {
        val http = FakeTransport { request ->
            when {
                request.url.contains("www.bing.com") -> request.respond(status = 500, body = "boom")
                request.url.contains("duckduckgo.com") -> request.respond(body = DDG_HTML)
                else -> request.respond(status = 500, body = "boom")
            }
        }
        val outcome = SearchChain().search("kotlin", 5, http, WebSettings())
        assertEquals("duckduckgo", outcome.engine)
        assertEquals(1, outcome.sources.size)
        assertEquals("https://example.org/ddg", outcome.sources[0].url)
        assertFalse(outcome.attempts.first().ok)
        assertTrue(outcome.attempts.first().error!!.contains("500"))
    }

    @Test
    fun allEnginesFailingIsAnErrorNotAnEmptySuccess() = runTest {
        val http = FakeTransport { request -> request.respond(status = 500, body = "boom") }
        val error = runCatching { SearchChain().search("kotlin", 5, http, WebSettings()) }.exceptionOrNull()
        assertTrue(error is WebToolException)
        val failure = error as WebToolException
        assertEquals(WebErrorCode.HttpStatus, failure.code)
        assertTrue(failure.message!!.contains("所有搜索引擎"))
        assertTrue(failure.message!!.contains("bing"))
        assertTrue(failure.message!!.contains("duckduckgo"))
    }

    @Test
    fun missingKeyOnlyReportsMissingKey() = runTest {
        val http = FakeTransport { request -> request.respond(status = 500, body = "boom") }
        val settings = WebSettings(engine = "searxng")
        val error = runCatching { SearchChain().search("kotlin", 5, http, settings) }.exceptionOrNull()
        val failure = error as WebToolException
        assertTrue(failure.message!!.contains("SearXNG"))
    }

    @Test
    fun wholeSearchRespectsOneBudgetAndReportsTimeout() = runTest {
        val http = FakeTransport { request ->
            delay(10_000)
            request.respond(body = BING_HTML)
        }
        val error = runCatching {
            SearchChain().search("kotlin", 5, http, WebSettings(searchBudgetMs = 200))
        }.exceptionOrNull()
        assertTrue(error is WebToolException)
        assertEquals(WebErrorCode.Timeout, (error as WebToolException).code)
    }

    @Test
    fun searchToolReturnsJsonWithTierAndSources() = runTest {
        val toolkit = WebToolkit(http = bingOnly(), settings = { WebSettings() })
        val payload = toolkit.search(
            kotlinx.serialization.json.buildJsonObject { put("query", "kotlin"); put("limit", 1) },
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        assertEquals("bing", json["engine"]!!.jsonPrimitive.content)
        assertEquals("free", json["tier"]!!.jsonPrimitive.content)
        assertEquals(1, json["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["sources"]!!.jsonArray.size)
    }
}
