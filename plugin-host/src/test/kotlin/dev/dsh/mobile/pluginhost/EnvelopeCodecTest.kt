package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginhost.builtin.gateway.stream.StreamCredential
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.EnvelopeCodec
import dev.dsh.mobile.pluginhost.builtin.gateway.stream.UpstreamSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4-2 验收 1 的信封部分：纯函数，无网络、无 Android。
 * 断言口径直接对应 B4 报告 §2 Q3 的字段表。
 */
class EnvelopeCodecTest {

    private fun openAi(vararg messages: JSONObject): JSONObject = JSONObject()
        .put("model", "gemini-3.5-flash-lite")
        .put("messages", JSONArray().apply { messages.forEach { put(it) } })

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private val credential = StreamCredential(access = "t", refresh = "r", project = "aicode-consumers")

    @Test
    fun systemMessageBecomesSystemInstruction() {
        val envelope = EnvelopeCodec.build(
            openAi(
                message("system", "你是助手"),
                message("user", "你好"),
            ),
            credential,
        )
        val request = envelope.getJSONObject("request")
        val system = request.getJSONObject("systemInstruction")
        assertEquals("user", system.getString("role"));
        assertEquals(
            "你是助手",
            system.getJSONArray("parts").getJSONObject(0).getString("text"),
        );
        // system 不进 contents：上游把 systemInstruction 与 contents 分开收。
        assertEquals(1, request.getJSONArray("contents").length());
    }

    @Test
    fun rolesMapUserToUserAndAssistantToModel() {
        val contents = EnvelopeCodec
            .build(
                openAi(
                    message("user", "第一问"),
                    message("assistant", "第一答"),
                    message("user", "第二问"),
                ),
                credential,
            )
            .getJSONObject("request")
            .getJSONArray("contents")
        assertEquals(3, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("model", contents.getJSONObject(1).getString("role"));
        assertEquals("user", contents.getJSONObject(2).getString("role"));
    }

    @Test
    fun base64ImageBecomesInlineData() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "这是什么"))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/png;base64,iVBORw0KGgo="),
                ),
            )
        val user = JSONObject().put("role", "user").put("content", parts)
        val contents = EnvelopeCodec
            .build(openAi(user), credential)
            .getJSONObject("request")
            .getJSONArray("contents");
        val mapped = contents.getJSONObject(0).getJSONArray("parts");
        assertEquals(2, mapped.length());
        val inline = mapped.getJSONObject(1).getJSONObject("inlineData");
        assertEquals("image/png", inline.getString("mimeType"));
        assertEquals("iVBORw0KGgo=", inline.getString("data"));
    }

    @Test
    fun sessionIdIsStableForSameOpening() {
        val first = EnvelopeCodec.deriveSessionId("你好，报一下你的模型名");
        val second = EnvelopeCodec.deriveSessionId("你好，报一下你的模型名");
        val other = EnvelopeCodec.deriveSessionId("换一句话");
        assertEquals(first, second);
        assertFalse(first == other);
        assertTrue(first.startsWith("-"));
        // 上游要的就是减号 + int64 的字面形状。
        assertTrue(first.substring(1).toLongOrNull() != null);
    }

    @Test
    fun requestTypeIsAgentForChatModels() {
        val envelope = EnvelopeCodec.build(openAi(message("user", "hi")), credential);
        assertEquals("agent", envelope.getString("requestType"));
        assertEquals(UpstreamSpec.USER_AGENT_ALIAS, envelope.getString("userAgent"));
        assertEquals("image_gen", EnvelopeCodec.requestTypeFor("gemini-3.1-flash-image"));
    }

    @Test
    fun projectPassesThrough() {
        val envelope = EnvelopeCodec.build(openAi(message("user", "hi")), credential);
        assertEquals("aicode-consumers", envelope.getString("project"));
        // 裸 token 没有 project：字段整个不出现，而不是塞空串。
        val bare = EnvelopeCodec.build(
            openAi(message("user", "hi")),
            StreamCredential(access = "t"),
        );
        assertFalse(bare.has("project"));
    }

    @Test
    fun credentialParsesAuthJsonAndBareToken() {
        val json = EnvelopeCodec.parseCredential(
            "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"project_id\":\"p\"}",
        )
        assertNotNull(json);
        assertEquals("a", json!!.access);
        assertEquals("r", json.refresh);
        assertEquals("p", json.project);

        val bare = EnvelopeCodec.parseCredential("ya29.token");
        assertEquals("ya29.token", bare!!.access);
        assertNull(bare.refresh);
        assertNull(EnvelopeCodec.parseCredential("   "));
    }

    @Test
    fun modelIdsDropInternalEntries() {
        val body = JSONObject()
            .put(
                "models",
                JSONObject()
                    .put("gemini-3.5-flash-lite", JSONObject())
                    .put("tab_flash", JSONObject())
                    .put("chat_ui", JSONObject())
                    .put("gemini-4-argon-disabled", JSONObject())
                    .put("claude-sonnet-4-6", JSONObject()),
            )
            .toString()
        assertEquals(
            listOf("claude-sonnet-4-6", "gemini-3.5-flash-lite"),
            EnvelopeCodec.modelIds(body),
        );
        assertTrue(EnvelopeCodec.modelIds(null).isEmpty());
    }
}
