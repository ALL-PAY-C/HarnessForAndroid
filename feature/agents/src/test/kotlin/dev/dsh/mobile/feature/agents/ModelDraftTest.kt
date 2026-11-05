package dev.dsh.mobile.feature.agents

import dev.dsh.mobile.core.platform.net.ModelCatalog
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.model.ReasoningStyle
import dev.dsh.mobile.core.model.settings.ModelProviders
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

    @Test
    fun `保存时带上草稿的思考风格`() {
        val explicit = buildProfiles(
            ModelDraft(providerId = "custom", baseUrl = "u", apiKey = "k", reasoningStyle = ReasoningStyle.thinkingBudget),
            listOf("m1"),
        )
        assertEquals(ReasoningStyle.thinkingBudget, explicit.single().reasoningStyle)
        // 表单没有选择器：草稿默认 auto，交给协议层按 provider/模型推断。
        assertEquals(ReasoningStyle.auto, buildProfiles(ModelDraft(), listOf("m1")).single().reasoningStyle)
    }

    @Test
    fun `编辑已有条目一次不会把思考风格打回 auto`() {
        val saved = ModelProfile(
            id = "m-1",
            name = "g",
            providerId = "custom",
            baseUrl = "u",
            apiKey = "k",
            model = "m1",
            reasoningStyle = ReasoningStyle.thinkingBudget,
        )
        // 表单回填原 profile 的值，保存一次后必须还是它。
        val draft = ModelDraft(
            providerId = saved.providerId,
            baseUrl = saved.baseUrl,
            apiKey = saved.apiKey,
            reasoningStyle = saved.reasoningStyle,
        )
        val list = buildProfiles(draft, listOf(saved.model), existingId = saved.id, nowMs = 7L)
        assertEquals(ReasoningStyle.thinkingBudget, list.single().reasoningStyle)
    }

    @Test
    fun `draft 的新能力位与推理映射一路带到 profile`() {
        val draft = ModelDraft(
            name = "多模态",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:8318/v1",
            apiKey = "sk-x",
            supportsImages = true,
            supportsOcr = true,
            reasoningMap = "high=thinking_budget:8192",
        )
        val profile = buildProfiles(draft, listOf("vision-1"), nowMs = 1L).single()
        assertTrue(profile.supportsImages)
        assertTrue(profile.supportsOcr)
        assertEquals("high=thinking_budget:8192", profile.reasoningMap)
    }

    @Test
    fun `默认 draft 不打开任何能力位`() {
        val profile = buildProfiles(ModelDraft(baseUrl = "http://x/v1", apiKey = "k"), listOf("m"), nowMs = 2L).single()
        assertEquals(false, profile.supportsImages)
        assertEquals(false, profile.supportsOcr)
        assertEquals("", profile.reasoningMap)
    }

}
