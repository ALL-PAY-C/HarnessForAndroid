package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginapi.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.31-b：插件设置里的 baseUrl 必须真的覆盖到模型源上。
 *
 * 清单里声明了 baseUrl 配置项却没人读，就是「填了没反应」的静默失效 —— 用户以为换了网关，
 * 请求还打在清单默认地址上。这里把三条路钉住：填了生效、空着用默认、读配置炸了也别崩。
 */
class PluginModelSourceBaseUrlTest {

    private class FakeBridge(
        private val values: Map<String, Map<String, String>> = emptyMap(),
        private val throws: Boolean = false,
    ) : PluginConfigBridge {
        override fun read(pluginId: String): Map<String, String> {
            if (throws) error("config store corrupted")
            return values[pluginId].orEmpty()
        }

        override fun write(pluginId: String, key: String, value: String) = Unit
        override fun reload() = Unit
    }

    private fun plugin(id: String = "acme.gw", baseUrls: List<String> = listOf("http://default/v1")) =
        object : DshPlugin {
            override val manifest = PluginManifest(
                id = id,
                name = id,
                version = "1",
                modelSources = baseUrls.mapIndexed { i, url ->
                    PluginModelSource(id = "m$i", label = "M$i", baseUrl = url)
                },
            )

            override fun register(registry: PluginRegistry) = Unit
        }

    @Test fun configuredBaseUrlOverridesEverySource() {
        val host = PluginHost(configBridge = FakeBridge(mapOf("acme.gw" to mapOf("baseUrl" to "  http://mine:9000/v1  "))))
        host.load(plugin(baseUrls = listOf("http://default/v1", "http://default2/v1")))

        val sources = host.modelSources()
        assertEquals(2, sources.size)
        assertEquals("用户填的地址要覆盖全部源，不能只改第一条", listOf("http://mine:9000/v1", "http://mine:9000/v1"), sources.map { it.baseUrl })
        assertEquals(listOf("plugin.acme.gw.m0", "plugin.acme.gw.m1"), sources.map { it.id })
    }

    @Test fun blankOrMissingKeyKeepsManifestDefault() {
        val blank = PluginHost(configBridge = FakeBridge(mapOf("acme.gw" to mapOf("baseUrl" to "   "))))
        blank.load(plugin())
        assertEquals("http://default/v1", blank.modelSources().single().baseUrl)

        val missing = PluginHost(configBridge = FakeBridge())
        missing.load(plugin())
        assertEquals("http://default/v1", missing.modelSources().single().baseUrl)
    }

    @Test fun modelSourcesStayOrderedByPluginId() {
        val host = PluginHost(configBridge = FakeBridge())
        // 真机 03:10:17 的两次 MODEL_SOURCES 里 local / stream 的位置对调过：列表项跟着
        // 「谁先 load 完」换位，两次全量注册之间还夹着混合态。顺序必须由 manifest.id 定。
        host.load(plugin(id = "zz.gw"))
        host.load(plugin(id = "aa.gw"))

        assertEquals(
            "顺序按 manifest.id 排，不许跟着加载完成顺序换位",
            listOf("plugin.aa.gw.m0", "plugin.zz.gw.m0"),
            host.modelSources().map { it.id },
        )
    }

    @Test fun throwingBridgeFallsBackToManifestDefault() {
        val host = PluginHost(configBridge = FakeBridge(throws = true))
        host.load(plugin())

        val sources = host.modelSources()
        assertTrue("坏配置不能让整张模型列表消失", sources.isNotEmpty())
        assertEquals("http://default/v1", sources.single().baseUrl)
    }
}
