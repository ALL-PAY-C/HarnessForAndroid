package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.QuickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T9.22：非 JS 形态的加载失败必须变成用户可见的一行。
 *
 * 以前 APK/dex 装载失败、插件目录丢失、assets/plugin.json 读不出来，装配路径只写
 * AppLog.w 就跳过 —— 列表里连一行都没有；目录丢了的那种更糟：行还在，按启用意图
 * 显示「已启用」。这里守两条线：
 *  1. 占位插件自己带原因，且行必须是 enabled=false + loadError 非空；
 *  2. 占位 ↔ 真插件互相顶替时，运行期失败态跟着更新/清掉。
 */
class BrokenPluginTest {

    private val dir = File("/data/user/0/dev.dsh.mobile/files/plugins/acme.demo")

    private fun realPlugin(id: String = "acme.demo") = object : DshPlugin {
        override val manifest = PluginManifest(id = id, name = "Demo", version = "1.0.0")
        override fun register(registry: PluginRegistry) {
            registry.quickCommand(QuickCommand("demo.run", "Run", "run", id))
        }
    }

    private fun apkBroken() = BrokenPlugin.apk(
        id = "acme.demo",
        name = "Demo",
        version = "1.0.0",
        message = "ClassNotFoundException: dev.dsh.mobile.plugin.html.HtmlPlugin",
    )

    private fun missingBroken() = BrokenPlugin.missingDir("acme.demo", "Demo", "1.0.0", dir)

    private fun manifestBroken() = BrokenPlugin.badManifest("acme.demo", "Demo", "1.0.0", "Expected ':' at 12")

    @Test
    fun apkFailurePlaceholderIsVisibleAndDisabled() {
        val host = PluginHost()
        val broken = apkBroken()
        host.load(broken)

        val row = host.plugins.value.single()
        assertTrue("loadFailure 必须非空", broken.loadFailure.isNotBlank())
        assertTrue("阶段前缀要写清断在哪", broken.loadFailure.startsWith("[apk] 加载失败："))
        assertFalse("失败占位不许显示已启用", row.enabled)
        assertEquals(broken.loadFailure, row.loadError)
        assertTrue("占位插件不许注册任何槽位", host.slots.quickCommands.isEmpty())
    }

    @Test
    fun missingDirPlaceholderIsVisibleAndDisabled() {
        val host = PluginHost()
        val broken = missingBroken()
        host.load(broken)

        val row = host.plugins.value.single()
        assertEquals("[missing] 插件目录已不存在：" + dir.absolutePath, broken.loadFailure)
        assertFalse(row.enabled)
        assertEquals(broken.loadFailure, row.loadError)
    }

    @Test
    fun badManifestPlaceholderIsVisibleAndDisabled() {
        val host = PluginHost()
        val broken = manifestBroken()
        host.load(broken)

        val row = host.plugins.value.single()
        assertEquals("[manifest] assets/plugin.json 解析失败：Expected ':' at 12", broken.loadFailure)
        assertFalse(row.enabled)
        assertEquals(broken.loadFailure, row.loadError)
    }

    /** 装配失败 → 占位插件的判定：清单读不出来算 manifest，其余算 apk。 */
    @Test
    fun brokenPluginForPicksStageFromManifestProbe() {
        val apk = brokenPluginFor(
            id = "acme.demo",
            name = "Demo",
            version = "1.0.0",
            manifestError = null,
            error = IllegalStateException("ClassNotFoundException: dev.dsh.mobile.plugin.html.HtmlPlugin"),
        )
        assertEquals(BrokenPlugin.STAGE_APK, apk.stage)
        assertEquals("[apk] 加载失败：ClassNotFoundException: dev.dsh.mobile.plugin.html.HtmlPlugin", apk.loadFailure)

        val manifest = brokenPluginFor(
            id = "acme.demo",
            name = "Demo",
            version = "1.0.0",
            manifestError = "Expected ':' at 12",
            error = IllegalStateException("ClassNotFoundException"),
        )
        assertEquals(BrokenPlugin.STAGE_MANIFEST, manifest.stage)
        assertEquals("[manifest] assets/plugin.json 解析失败：Expected ':' at 12", manifest.loadFailure)
    }

    /** 目录丢了但清单还在：包名/版本照用，占位插件的 id 才能和真实插件对上号。 */
    @Test
    fun missingDirPlaceholderKeepsManifestIdentity() {
        val spec = PluginManifestFile(id = "acme.node", name = "Node Demo", version = "2.1.0")
        val broken = BrokenPlugin.missingDir(spec, dir)

        assertEquals("acme.node", broken.manifest.id)
        assertEquals("Node Demo", broken.manifest.name)
        assertEquals("2.1.0", broken.manifest.version)
        assertEquals("[missing] 插件目录已不存在：" + dir.absolutePath, broken.loadFailure)
    }

    /** Node 形态的同一件事：占位实例自己就是 NodeBackedPlugin，不拿空目录去问引擎。 */
    @Test
    fun nodeMissingDirPlaceholderReportsPath() {
        val spec = PluginManifestFile(id = "acme.node", name = "Node Demo", version = "2.1.0")
        val broken = NodeMissingDirPlugin(spec, dir)
        val host = PluginHost()
        host.load(broken)

        val row = host.plugins.value.single()
        assertEquals("[missing] 插件目录已不存在：" + dir.absolutePath, broken.loadFailure)
        assertFalse(row.enabled)
        assertEquals(broken.loadFailure, row.loadError)
    }

    /** 已装好的同一 id 后来失败：旧实例被占位顶掉，失败态必须换成新的原因。 */
    @Test
    fun installedPluginReplacedByPlaceholderUpdatesFailure() {
        val host = PluginHost()
        host.load(realPlugin())
        assertTrue(host.plugins.value.single().enabled)

        host.load(apkBroken(), force = true)

        val row = host.plugins.value.single()
        assertFalse("被占位顶掉之后不许继续显示已启用", row.enabled)
        assertEquals(apkBroken().loadFailure, row.loadError)
        assertTrue("旧贡献要一起撤掉，别留半个界面", host.slots.quickCommands.isEmpty())
    }

    /** 目录找回 / 修好后重装：占位被真插件顶掉，失败态自动清。 */
    @Test
    fun repairedReinstallClearsPlaceholderFailure() {
        val host = PluginHost()
        host.load(missingBroken())
        assertFalse(host.plugins.value.single().enabled)

        host.load(realPlugin(), force = true)

        val row = host.plugins.value.single()
        assertTrue("修好重装必须自动恢复", row.enabled)
        assertNull("失败态要跟着旧占位一起清掉", row.loadError)
        assertEquals(1, host.slots.quickCommands.size)
    }
}
