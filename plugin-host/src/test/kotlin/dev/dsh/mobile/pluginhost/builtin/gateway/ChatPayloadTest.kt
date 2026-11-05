package dev.dsh.mobile.pluginhost.builtin.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 七处归一逐条断言（B3-1 §Q3）。全部离线跑，不需要 emulator、不需要网络。
 */
class ChatPayloadTest {

    private fun prepare(raw: String): JSONObject = ChatPayload.prepare(raw)

    private fun messages(root: JSONObject): JSONArray = root.getJSONArray("messages")

    @Test
    fun maxCompletionTokensIsTranslatedToMaxTokens() {
        val root = prepare("""{"model":"m","max_completion_tokens":512,"messages":[]}""")
        assertEquals(512, root.getInt("max_tokens"))
        assertFalse(root.has("max_completion_tokens"))
    }

    @Test
    fun explicitMaxTokensWinsOverAlias() {
        val root = prepare("""{"model":"m","max_tokens":64,"max_completion_tokens":512,"messages":[]}""")
        assertEquals(64, root.getInt("max_tokens"))
        assertFalse(root.has("max_completion_tokens"))
    }

    @Test
    fun nonPositiveAliasIsLeftAlone() {
        val root = prepare("""{"model":"m","max_completion_tokens":0,"messages":[]}""")
        assertFalse(root.has("max_tokens"))
    }

    @Test
    fun streamIsForcedTrue() {
        val root = prepare("""{"model":"m","stream":false,"messages":[]}""")
        assertTrue(root.getBoolean("stream"))
    }

    @Test
    fun streamOptionsGetsIncludeUsage() {
        val root = prepare("""{"model":"m","messages":[]}""")
        assertTrue(root.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun explicitStreamOptionsIsNotOverwritten() {
        val root = prepare("""{"model":"m","stream_options":{"include_usage":false},"messages":[]}""")
        assertFalse(root.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun imageUrlStringBecomesObject() {
        val root = prepare(
            """{"model":"m","messages":[{"role":"user","content":[
               {"type":"text","text":"看图"},
               {"type":"image_url","image_url":"https://x/y.png"}]}]}""",
        )
        val parts = messages(root).getJSONObject(0).getJSONArray("content")
        assertEquals("https://x/y.png", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun imageUrlObjectIsKept() {
        val root = prepare(
            """{"model":"m","messages":[{"role":"user","content":[
               {"type":"image_url","image_url":{"url":"https://x/z.png","detail":"high"}}]}]}""",
        )
        val part = messages(root).getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("https://x/z.png", part.getJSONObject("image_url").getString("url"))
        assertEquals("high", part.getJSONObject("image_url").getString("detail"))
    }

    @Test
    fun developerRoleBecomesSystem() {
        val root = prepare("""{"model":"m","messages":[{"role":"developer","content":"hi"}]}""")
        assertEquals("system", messages(root).getJSONObject(0).getString("role"))
    }

    @Test
    fun toolChoiceObjectBecomesNameString() {
        val root = prepare(
            """{"model":"m","tool_choice":{"type":"function","function":{"name":"read_file"}},
               "tools":[{"type":"function","function":{"name":"read_file","parameters":{}}}],"messages":[]}""",
        )
        assertEquals("read_file", root.getString("tool_choice"))
    }

    @Test
    fun toolChoiceNoneDropsTools() {
        val root = prepare(
            """{"model":"m","tool_choice":"none",
               "tools":[{"type":"function","function":{"name":"f","parameters":{}}}],"messages":[]}""",
        )
        assertFalse(root.has("tool_choice"))
        assertFalse(root.has("tools"))
    }

    @Test
    fun orphanToolCallIsRemoved() {
        val root = prepare(
            """{"model":"m","messages":[
               {"role":"user","content":"hi"},
               {"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
               {"role":"user","content":"继续"}]}""",
        )
        val list = messages(root)
        assertEquals(2, list.length())
        assertEquals("user", list.getJSONObject(1).getString("role"))
    }

    @Test
    fun orphanToolResultIsRemoved() {
        val root = prepare(
            """{"model":"m","messages":[
               {"role":"user","content":"hi"},
               {"role":"tool","tool_call_id":"ghost","content":"x"}]}""",
        )
        assertEquals(1, messages(root).length())
    }

    @Test
    fun nonToolMessageBetweenToolResultsIsMovedBehind() {
        val root = prepare(
            """{"model":"m","messages":[
               {"role":"user","content":"hi"},
               {"role":"assistant","content":"","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
               {"role":"user","content":"插队的话"},
               {"role":"tool","tool_call_id":"call_1","content":"result"}]}""",
        )
        val list = messages(root)
        assertEquals(4, list.length())
        assertEquals("assistant", list.getJSONObject(1).getString("role"))
        assertEquals("tool", list.getJSONObject(2).getString("role"))
        assertEquals("user", list.getJSONObject(3).getString("role"))
    }

    @Test
    fun matchedToolPairSurvives() {
        val root = prepare(
            """{"model":"m","messages":[
               {"role":"user","content":"hi"},
               {"role":"assistant","content":"","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
               {"role":"tool","tool_call_id":"call_1","content":"ok"}]}""",
        )
        val list = messages(root)
        assertEquals(3, list.length())
        assertEquals(1, list.getJSONObject(1).getJSONArray("tool_calls").length())
    }

    @Test
    fun credentialPlainToken() {
        val credential = ChatPayload.parseCredential("  abc.def.ghi  ")
        assertEquals("abc.def.ghi", credential?.access)
        assertNull(credential?.refresh)
    }

    @Test
    fun credentialJsonPair() {
        val credential = ChatPayload.parseCredential("""{"access":"a","refresh":"r"}""")
        assertEquals("a", credential?.access)
        assertEquals("r", credential?.refresh)
    }

    @Test
    fun blankCredentialIsNull() {
        assertNull(ChatPayload.parseCredential("   "))
        assertNull(ChatPayload.parseCredential(null))
    }

    @Test
    fun modelsMergePrefersConfigAndFiltersNonChat() {
        val config = """{"code":0,"data":{"models":[
            {"id":"deepseek-v4.1-flash","maxInputTokens":1000000,"maxOutputTokens":384000,"supportsReasoning":true},
            {"id":"nes-embed","maxOutputTokens":4096},
            {"id":"tiny","maxOutputTokens":128},
            {"id":"img","maxOutputTokens":4096,"tags":["text-to-image"]},
            {"id":"off","maxOutputTokens":4096,"disabled":true}]}}"""
        val personal = """{"code":0,"data":{"models":[{"id":"deepseek-v4.1-flash","name":"dup"}],
            "agents":[{"name":"cli","models":["kimi-k3"]},{"name":"other","models":["nope"]}]}}"""
        val models = ChatPayload.modelsFromUpstream(config, personal)
        assertEquals(listOf("deepseek-v4.1-flash", "kimi-k3"), models.map { it.id })
        assertEquals(384000, models[0].maxOutputTokens)
    }

    @Test
    fun modelCatalogToleratesStringArrayShape() {
        val config = """{"code":0,"data":["glm-5.3","gpt-5.5"]}"""
        val models = ChatPayload.modelsFromUpstream(config, null)
        assertEquals(listOf("glm-5.3", "gpt-5.5"), models.map { it.id })
    }
    @Test
    fun systemPromptIsPrependedWhenFirstMessageIsUser() {
        val root = prepare("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        ChatPayload.ensureSystemPrompt(root)
        val list = messages(root)
        assertEquals(2, list.length())
        assertEquals("system", list.getJSONObject(0).getString("role"))
        assertEquals(ChatPayload.DEFAULT_SYSTEM_PROMPT, list.getJSONObject(0).getString("content"))
        assertEquals("user", list.getJSONObject(1).getString("role"))
    }

    @Test
    fun existingSystemPromptIsNotDuplicated() {
        val root = prepare("""{"model":"m","messages":[{"role":"system","content":"自定义"},{"role":"user","content":"hi"}]}""")
        ChatPayload.ensureSystemPrompt(root)
        val list = messages(root)
        assertEquals(2, list.length())
        assertEquals("自定义", list.getJSONObject(0).getString("content"))
    }
}
