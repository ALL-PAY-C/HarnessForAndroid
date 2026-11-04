package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginapi.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件贡献的两样「软」东西：模型源与系统提示词追加段。
 *
 * 两条铁律在这里逐条钉住：解析时坏数据不炸包、运行时停用立刻消失。
 */
class PluginModelPromptTest {

    private fun manifest(json: String) = PluginManifestFile.parse(json).getOrThrow()

    private fun plugin(
        id: String,
        sources: List<PluginModelSource> = emptyList(),
        prompt: String = "",
        fail: Boolean = false,
    ) = object : DshPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            version = "1",
            modelSources = sources,
            systemPrompt = prompt,
        )

        override fun register(registry: PluginRegistry) {
            if (fail) error("broken registration")
        }
    }

    private fun source(id: String, baseUrl: String = "http://127.0.0.1:8099/v1") =
        PluginModelSource(id = id, label = id, baseUrl = baseUrl, defaultModel = id + "-model")

    private class FakeConfigBridge : PluginConfigBridge {
        val values = mutableMapOf<String, MutableMap<String, String>>()

        override fun read(pluginId: String): Map<String, String> = values[pluginId].orEmpty()

        override fun write(pluginId: String, key: String, value: String) {
            values.getOrPut(pluginId) { mutableMapOf() }[key] = value
        }

        override fun reload() = Unit
    }

    // ------------------------------------------------------------ manifest

    @Test fun parsesModelSourcesAndSystemPrompt() {
        val m = manifest(
            """{"id":"acme.src","name":"Src","systemPrompt":"加个标记 ★","modelSources":[{"id":"local","label":"本地","baseUrl":"http://127.0.0.1:8099/v1","defaultModel":"m1","apiKeyHint":"随便填","protocol":"openai"}]}""",
        )
        assertEquals("加个标记 ★", m.systemPrompt)
        val src = m.modelSources.single()
        assertEquals("local", src.id)
        assertEquals("本地", src.label)
        assertEquals("http://127.0.0.1:8099/v1", src.baseUrl)
        assertEquals("m1", src.defaultModel)
        assertEquals("随便填", src.apiKeyHint)
        assertEquals("openai", src.protocol)
    }

    /** 缺 id 或 baseUrl 的条目直接丢：一个写错的模型源不该让整包装不上。 */
    @Test fun modelSourceWithoutIdOrBaseUrlIsSkipped() {
        val m = manifest(
            """{"id":"acme.src","name":"Src","modelSources":[{"label":"没id","baseUrl":"http://a"},{"id":"nourl"},{"id":"ok","baseUrl":"http://b"}]}""",
        )
        assertEquals(listOf("ok"), m.modelSources.map { it.id })
        assertEquals("http://b", m.modelSources.single().baseUrl)
    }

    /** 老包：两个字段都没写 → 空表 + 空串，宿主行为逐字不变。 */
    @Test fun missingFieldsParseAsEmpty() {
        val m = manifest("""{"id":"acme.old","name":"Old"}""")
        assertTrue(m.modelSources.isEmpty())
        assertEquals("", m.systemPrompt)
    }

    @Test fun declarativePluginCarriesSourcesAndPrompt() {
        val spec = manifest(
            """{"id":"acme.src","name":"Src","systemPrompt":"提示","modelSources":[{"id":"local","label":"本地","baseUrl":"http://b"}]}""",
        )
        val declared = DeclarativePlugin(spec).manifest
        assertEquals(listOf("local"), declared.modelSources.map { it.id })
        assertEquals("提示", declared.systemPrompt)
    }

    // -------------------------------------------------------------- host

    @Test fun modelSourcesOnlyFromRunningPlugins() {
        val host = PluginHost()
        host.load(plugin("acme.on", sources = listOf(source("local"))))
        host.load(plugin("acme.off", sources = listOf(source("remote"))))
        host.load(plugin("acme.broken", sources = listOf(source("dead")), fail = true))
        host.setEnabled("acme.off", false)
        assertEquals(listOf("plugin.acme.on.local"), host.modelSources().map { it.id })
    }

    /** 同插件内两个同名 source：先到先得，后者加 __2 后缀，别在模型列表里撞车。 */
    @Test fun duplicateSourceIdsGetSuffix() {
        val host = PluginHost()
        host.load(plugin("acme.src", sources = listOf(source("local"), source("local"))))
        assertEquals(
            listOf("plugin.acme.src.local", "plugin.acme.src.local__2"),
            host.modelSources().map { it.id },
        )
        assertEquals("http://127.0.0.1:8099/v1", host.modelSources().first().baseUrl)
    }

    @Test fun promptAdditionsKeepOrderAndDedup() {
        val host = PluginHost()
        host.load(plugin("acme.a", prompt = "甲"))
        host.load(plugin("acme.b", prompt = "乙"))
        host.load(plugin("acme.c", prompt = "甲"))
        host.load(plugin("acme.blank", prompt = "   "))
        assertEquals(listOf("甲", "乙"), host.promptAdditions())
    }

    /** 停用后下一次读立刻没有它，不等重载。 */
    @Test fun promptAdditionDisappearsWhenDisabled() {
        val host = PluginHost()
        host.load(plugin("acme.a", prompt = "甲"))
        assertEquals(listOf("甲"), host.promptAdditions())
        host.setEnabled("acme.a", false)
        assertTrue(host.promptAdditions().isEmpty())
        host.setEnabled("acme.a", true)
        assertEquals(listOf("甲"), host.promptAdditions())
    }

    /** 插件自己的一键开关：关掉只掐提示词，模型源照旧。 */
    @Test fun promptEnabledSwitchGatesOnlyThePrompt() {
        val bridge = FakeConfigBridge()
        val host = PluginHost(configBridge = bridge)
        host.load(plugin("acme.a", sources = listOf(source("local")), prompt = "甲"))
        assertEquals(listOf("甲"), host.promptAdditions())

        bridge.write("acme.a", "prompt.enabled", "false")
        assertTrue("开关关掉后提示词不再追加", host.promptAdditions().isEmpty())
        assertEquals("插件本身照常启用", 1, host.modelSources().size)

        bridge.write("acme.a", "prompt.enabled", "0")
        assertTrue(host.promptAdditions().isEmpty())

        bridge.write("acme.a", "prompt.enabled", "true")
        assertEquals(listOf("甲"), host.promptAdditions())
    }

    /** 加载失败的行：模型源与提示词都不该露头（与 enabled 同一份过滤）。 */
    @Test fun loadFailureHidesBothContributions() {
        val host = PluginHost()
        host.load(plugin("acme.a", sources = listOf(source("local")), prompt = "甲"))
        host.noteLoadResult("acme.a", "引擎报错")
        assertTrue(host.modelSources().isEmpty())
        assertTrue(host.promptAdditions().isEmpty())
        assertFalse(host.plugins.value.single().enabled)
        assertNotNull(host.plugins.value.single().loadError)
    }
}
