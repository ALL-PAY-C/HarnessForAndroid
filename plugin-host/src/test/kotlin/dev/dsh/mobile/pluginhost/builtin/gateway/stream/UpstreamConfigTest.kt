package dev.dsh.mobile.pluginhost.builtin.gateway.stream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M6：上游形状（路径 / 项目 ID / UA / 凭据形态）全部由设置项决定。
 *
 * 三条铁律：
 * 1. 设置项缺省时行为与改动前完全一致（示例路径、空项目、示例 UA）；
 * 2. 设置项有值时以它为准，且路径必须原样拼在 base 后面（含查询串）；
 * 3. 凭据既可能是授权 json 全文，也可能是裸令牌，两种都不能崩。
 */
class UpstreamConfigTest {

    private fun envelopeWith(config: StreamConfig): JSONObject {
        val openAi = JSONObject()
            .put("model", "gemini-3.5-flash-lite")
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "1+1=?")),
            )
        return EnvelopeCodec.build(openAi, resolveCredential(config))
    }

    @Test
    fun configuredPathReplacesDefault() {
        val config = StreamConfig(baseUrl = "http://10.0.2.2:8899", path = "/probe/chat?alt=sse")
        assertEquals("/probe/chat?alt=sse", chatPathOf(config))
        // 拼接口径：base 去尾斜杠 + 路径原样，查询串不许被吃掉。
        val url = config.baseUrl.trimEnd { it == '/' } + chatPathOf(config)
        assertEquals("http://10.0.2.2:8899/probe/chat?alt=sse", url)
    }

    @Test
    fun blankPathFallsBackToDefault() {
        assertEquals(UpstreamSpec.CHAT_PATH, chatPathOf(StreamConfig()))
        assertEquals(UpstreamSpec.CHAT_PATH, chatPathOf(StreamConfig(path = "   ")))
        // 不是 / 开头的一律当没填：否则会拼出 http://hostchat/completions 这种怪 url。
        assertEquals(UpstreamSpec.CHAT_PATH, chatPathOf(StreamConfig(path = "chat/completions")))
    }

    @Test
    fun projectIdLandsInEnvelope() {
        val envelope = envelopeWith(StreamConfig(token = "demo-token", projectId = "demo-proj"))
        assertEquals("demo-proj", envelope.optString("project"))
        // 空项目保持老行为：信封里干脆没有这个键，不是空串。
        val blank = envelopeWith(StreamConfig(token = "demo-token"))
        assertEquals(false, blank.has("project"))
    }

    @Test
    fun userAgentOverrideApplied() {
        assertEquals("ProbeUA/9.9", userAgentOf(StreamConfig(userAgent = "ProbeUA/9.9")))
        assertEquals(UpstreamSpec.DEFAULT_USER_AGENT, userAgentOf(StreamConfig()))
        assertEquals(UpstreamSpec.DEFAULT_USER_AGENT, userAgentOf(StreamConfig(userAgent = "  ")))
    }

    @Test
    fun authJsonCredentialYieldsTokenAndProject() {
        val json = """{"access_token":"demo-token","refresh_token":"r1","project_id":"json-proj"}"""
        val credential = resolveCredential(StreamConfig(token = json))
        assertEquals("demo-token", credential.access)
        assertEquals("r1", credential.refresh)
        assertEquals("json-proj", credential.project)

        // 配置项优先：两边都有项目时以设置页为准。
        val overridden = resolveCredential(StreamConfig(token = json, projectId = "demo-proj"))
        assertEquals("demo-proj", overridden.project)
        assertEquals("demo-token", overridden.access)
        assertEquals("demo-proj", envelopeWith(StreamConfig(token = json, projectId = "demo-proj")).optString("project"))
    }

    @Test
    fun bareTokenStillWorks() {
        val credential = resolveCredential(StreamConfig(token = "sk-raw-token"))
        assertEquals("sk-raw-token", credential.access)
        assertEquals("", credential.project)

        // 坏 json 不崩：整段当裸令牌，别把用户卡在「未填凭据」。
        val broken = resolveCredential(StreamConfig(token = "{not json"))
        assertEquals("{not json", broken.access)
    }
}
