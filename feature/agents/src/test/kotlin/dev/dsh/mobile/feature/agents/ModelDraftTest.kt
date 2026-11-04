package dev.dsh.mobile.feature.agents

import dev.dsh.mobile.core.data.settings.ModelCatalog
import dev.dsh.mobile.core.data.settings.ModelProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「添加模型」里 CLI 模板与多选保存的纯逻辑：不打网络、不碰存储。 */
class ModelDraftTest {

    @Test
    fun `未知 providerId 回落到自定义`() {
        assertEquals("custom", ModelProviders.byId("nope-not-exist").id)
        assertEquals("custom", ModelProviders.byId("自定义").id)
    }

    @Test
    fun `parseIds 解析 data 与 models 两种响应`() {
        assertEquals(
            listOf("a", "b"),
            ModelCatalog.parseIds("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"),
        )
        assertEquals(
            listOf("gemini-2.5-pro", "gpt-4o"),
            ModelCatalog.parseIds("{\"models\":[\"gemini-2.5-pro\",\"gpt-4o\"]}"),
        )
    }

    @Test
    fun `一次多选生成多条 profile 共用 baseUrl 与 key`() {
        val draft = ModelDraft(
            name = "",
            providerId = "custom",
            baseUrl = " http://192.168.1.10:11434/v1 ",
            apiKey = " local-key ",
            reasoningEffort = "",
            supportsTools = false,
        )
        val list = buildProfiles(draft, listOf("m1", " m2 ", "m1", " "), nowMs = 1_700_000_000_000L)
        assertEquals(listOf("m1", "m2"), list.map { it.model })
        assertEquals(list.size, list.map { it.id }.distinct().size)
        assertTrue(list.all { it.baseUrl == "http://192.168.1.10:11434/v1" })
        assertTrue(list.all { it.apiKey == "local-key" })
        // 名称留空时退回模型名，列表里不会出现一片空白条目。
        assertEquals(listOf("m1", "m2"), list.map { it.name })
        assertTrue(list.all { it.reasoningEffort == "high" })
    }

    @Test
    fun `编辑单条时沿用原 id`() {
        val draft = ModelDraft(providerId = "custom", baseUrl = "u", apiKey = "k")
        val list = buildProfiles(draft, listOf("m1"), existingId = "m-42", nowMs = 9L)
        assertEquals(listOf("m-42"), list.map { it.id })
        // 编辑却勾了多条：id 必须分开，否则后一条会覆盖前一条。
        val many = buildProfiles(draft, listOf("m1", "m2"), existingId = "m-42", nowMs = 9L)
        assertNotEquals(many[0].id, many[1].id)
    }

    @Test
    fun `没有勾选时输出空表`() {
        assertTrue(buildProfiles(ModelDraft(), emptyList()).isEmpty())
    }
}
