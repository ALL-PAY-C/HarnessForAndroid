package dev.dsh.mobile.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * 「破甲插件」App 侧产物（D:\\DSH\\dsh-sauce-plugin 出的 dsh-sauce-packs-0.1.0-android.zip）的窄测：
 * zip 只有单一顶层目录、plugin.json 解析得出、index.js 在 Rhino 里跑得起来、四个开关真的改变贡献集合。
 *
 * 全部走真实文件系统与真实 zip，无 Robolectric、无设备。跑法：
 *   ./gradlew :plugin-host:testDebugUnitTest --tests '*SaucePacksAndroidPluginTest'
 */
class SaucePacksAndroidPluginTest {

    private val zipResource = "sauce-packs/dsh-sauce-packs-0.1.0-android.zip"

    private fun zipBytes(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(zipResource)) {
            "测试资源缺失：" + zipResource
        }.use { input -> input.readBytes() }

    /** 解开 zip 到临时目录，返回包根（单一顶层目录内部那一层）。 */
    private fun unzipToPackageDir(): File {
        val bytes = zipBytes()
        val root = Files.createTempDirectory("sauce-pack").toFile()
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(root, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.writeBytes(zin.readBytes())
                }
            }
        }
        val tops = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals("zip 必须只有一个顶层目录，实际：" + tops.map { it.name }, 1, tops.size)
        return tops.single()
    }

    private fun defaults(spec: PluginManifestFile): Map<String, String> =
        spec.config.associate { it.key to it.default }

    @Test
    fun zipHasSingleTopDirWithManifestAtRoot() {
        val names = mutableListOf<String>()
        ZipInputStream(zipBytes().inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue("plugin.json 不在顶层目录根：" + names, "dsh-sauce-packs-0.1.0-android/plugin.json" in names)
        assertTrue("index.js 缺失：" + names, "dsh-sauce-packs-0.1.0-android/index.js" in names)
        val tops = names.map { it.substringBefore('/') }.toSet()
        assertEquals("zip 顶层只能有一个目录：" + tops, 1, tops.size)
    }

    @Test
    fun manifestParsesWithSauceIdentity() {
        val dir = unzipToPackageDir()
        val text = File(dir, "plugin.json").readText()
        val spec = PluginManifestFile.parse(text).getOrThrow()
        assertEquals("dev.dsh.sauce.packs", spec.id)
        assertEquals("破甲插件", spec.name)
        assertEquals("0.1.0", spec.version)

        val keys = spec.config.map { it.key }
        assertEquals(listOf("coldcoffee", "lilcode", "autoReply", "injectPreset", "presetMode"), keys)
        val byKey = spec.config.associateBy { it.key }
        listOf("coldcoffee", "lilcode", "autoReply", "injectPreset").forEach { key ->
            assertEquals(key + " 必须是开关", "switch", byKey.getValue(key).type)
        }
        assertEquals("true", byKey.getValue("coldcoffee").default)
        assertEquals("false", byKey.getValue("autoReply").default)
        assertEquals("full", byKey.getValue("presetMode").default)
        assertEquals(listOf("lite", "full", "ultra"), byKey.getValue("presetMode").options)

        // 根 ui 是 plugin.page 卡的数据来源（DesktopUiAdapter.fromDirectory 读它）。
        assertNotNull("plugin.json 缺少根 ui", JSONObject(text).optJSONObject("ui"))
    }

    @Test
    fun indexJsRegistersSlotsAccordingToSwitches() {
        val dir = unzipToPackageDir()
        val spec = PluginManifestFile.parse(File(dir, "plugin.json").readText()).getOrThrow()

        val run = JsPluginRuntime(config = defaults(spec), log = {}).execute(dir, spec.id).getOrThrow()

        val composerIds = run.contributions.filter { it.slot == "chat.composer.plus" }.map { it.id }.toSet()
        assertEquals(setOf("sauce.coldcoffee", "sauce.lilcode", "sauce.preset"), composerIds)

        val panel = run.contributions.firstOrNull { it.slot == "chat.settings" }
        assertNotNull("chat.settings 贡献缺失，会话设置里就选不到它", panel)
        assertEquals("sauce.packs.panel", panel!!.id)
        assertNotNull("会话设置卡片没有 ui", panel.ui)
        assertTrue("状态卡副标题应带开关计数：" + panel.subtitle, panel.subtitle.contains("/4 开"))

        // 默认关掉的 autoReply 不该注册，开关不是摆设。
        assertTrue("autoReply 默认 false 却注册了入口", run.contributions.none { it.id == "sauce.autoreply" })
    }

    @Test
    fun flippingSwitchesChangesContributions() {
        val dir = unzipToPackageDir()
        val spec = PluginManifestFile.parse(File(dir, "plugin.json").readText()).getOrThrow()

        val flipped = defaults(spec).toMutableMap().apply {
            put("coldcoffee", "false")
            put("autoReply", "true")
            put("presetMode", "ultra")
        }
        val run = JsPluginRuntime(config = flipped, log = {}).execute(dir, spec.id).getOrThrow()
        val composer = run.contributions.filter { it.slot == "chat.composer.plus" }
        val ids = composer.map { it.id }.toSet()

        assertTrue("coldcoffee 关了还注册：" + ids, "sauce.coldcoffee" !in ids)
        assertTrue("autoReply 开了却没注册：" + ids, "sauce.autoreply" in ids)
        assertTrue("presetMode 没写进提示词", composer.first { it.id == "sauce.preset" }.action?.text?.contains("ultra") == true)

        // 全关：一个输入框入口都不该剩。
        val allOff = defaults(spec).toMutableMap().apply {
            listOf("coldcoffee", "lilcode", "autoReply", "injectPreset").forEach { put(it, "false") }
        }
        val offRun = JsPluginRuntime(config = allOff, log = {}).execute(dir, spec.id).getOrThrow()
        assertTrue(
            "全关后仍有输入框入口：" + offRun.contributions.map { it.id },
            offRun.contributions.none { it.slot == "chat.composer.plus" },
        )
        // 但状态卡始终在，插件的会话设置组件不会凭空消失。
        assertNotNull(offRun.contributions.firstOrNull { it.slot == "chat.settings" })
        assertNull(offRun.contributions.firstOrNull { it.slot == "plugin.page" })
    }
}
