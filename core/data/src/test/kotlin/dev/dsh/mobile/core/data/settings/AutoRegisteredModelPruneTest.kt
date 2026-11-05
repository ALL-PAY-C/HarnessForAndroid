package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.dsh.mobile.core.model.settings.ModelProfile

/** 插件自动注册条目的清理：升级后列表里不该再冒出旧插件塞的模型。 */
class AutoRegisteredModelPruneTest {

    private fun profile(id: String, model: String = id, isDefault: Boolean = false) = ModelProfile(
        id = id,
        name = model,
        providerId = "custom",
        baseUrl = "http://192.168.1.10:11434/v1",
        apiKey = "local-key",
        model = model,
        isDefault = isDefault,
    )

    @Test
    fun pruneDropsPluginPrefixOnly() {
        val list = listOf(
            profile("m-1700000000000", "手填"),
            profile("plugin.dev.dsh.router.gpt-4o", "gpt-4o"),
            profile("plugin.dev.dsh.router.gemini-2.5-pro", "gemini-2.5-pro"),
        )
        assertEquals(listOf("m-1700000000000"), pruneAutoRegistered(list).map { it.id })
    }

    @Test
    fun pruneKeepsListWhenNothingToDrop() {
        val list = listOf(profile("m-1"), profile("m-2"))
        assertEquals(list, pruneAutoRegistered(list))
    }

    @Test
    fun pruneOnEmptyListIsEmpty() {
        assertTrue(pruneAutoRegistered(emptyList()).isEmpty())
    }

    @Test
    fun prefixIsPluginDot() {
        assertEquals("plugin.", AUTO_REGISTERED_MODEL_PREFIX)
    }
}
