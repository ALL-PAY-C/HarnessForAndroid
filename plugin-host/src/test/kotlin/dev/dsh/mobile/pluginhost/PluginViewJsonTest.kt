package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginViewCode
import dev.dsh.mobile.pluginapi.PluginViewNode
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiButtonStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式视图协议 v1：校验器 + 适配器 + 示例插件。
 *
 * 断言落在「产出什么节点 / 什么错误码」上，不碰 Compose —— 与 [PluginUiJsonDesktopTest]
 * 同一套思路：协议是数据约定，渲染是另一层的事。
 */
class PluginViewJsonTest {

    // ---------------------------------------------------------------- 示例插件

    @Test
    fun demoManifestRendersThroughHostNodes() {
        val manifest = PluginManifestFile.parse(demoManifest()).getOrThrow()
        val panel = manifest.contributions.first { it.slot == "plugin.page" }
        // 带版本号的 view 归新协议，不再被当成老 ui 子树。
        assertNull("view 里有 version，就不该再进 ui 字段", panel.ui)
        assertNotNull("view 字段必须解析出来", panel.view)

        val known = manifest.contributions.map { it.id }.toSet()
        val result = PluginViewJson.parse(panel.view, known)
        assertTrue("合法视图不该被拒收：" + result.summary(), result.ok)
        assertTrue("合法视图不该有降级：" + result.summary(), result.issues.isEmpty())
        assertEquals(1, result.spec?.version)

        val ui = PluginViewAdapter.toUiSpec(checkNotNull(result.spec))
        assertEquals(dev.dsh.mobile.pluginapi.WidthPolicy.Fill, ui.widthPolicy)
        assertTrue(ui.scrollable)

        val root = ui.root as PluginUiNode.Column
        assertEquals(7, root.children.size)
        assertEquals("声明式视图", (root.children[0] as PluginUiNode.Text).text)
        assertTrue(root.children[2] is PluginUiNode.Divider)

        val chips = (root.children[3] as PluginUiNode.Row).children
        assertEquals(2, chips.size)
        assertEquals(UiBadgeTone.Info, (chips[0] as PluginUiNode.Badge).tone)

        // 有序列表 → 每项前面补序号，序号本身是宿主画的，插件管不着。
        val list = root.children[4] as PluginUiNode.Column
        assertEquals(3, list.children.size)
        val firstRow = list.children[0] as PluginUiNode.Row
        assertEquals("1.", (firstRow.children[0] as PluginUiNode.Text).text)
        assertEquals("插件交 JSON", (firstRow.children[1] as PluginUiNode.Text).text)

        // 动作只带 id：点击后走既有 custom 通道，视图里没有任何可执行内容。
        val refresh = root.children[5] as PluginUiNode.Button
        assertEquals(SlotAction.Custom("demo.view.refresh"), refresh.action)
        assertEquals(UiButtonStyle.Filled, refresh.style)
        val settings = root.children[6] as PluginUiNode.Button
        assertEquals(SlotAction.Custom("demo.view.settings"), settings.action)
        assertEquals(UiButtonStyle.Text, settings.style)
    }

    @Test
    fun demoPluginRegistersPanelThroughExistingPipeline() {
        val manifest = PluginManifestFile.parse(demoManifest()).getOrThrow()
        val registry = PluginRegistry()
        DeclarativePlugin(manifest).register(registry)

        val page = registry.contributions.first { it.slot == SlotId.PluginPage }
        assertEquals("demo.view.panel", page.id)
        assertNotNull("plugin.page 贡献必须带上翻译好的界面", page.ui)

        val buttons = (page.ui!!.root as PluginUiNode.Column).children
            .filterIsInstance<PluginUiNode.Button>()
            .map { (it.action as SlotAction.Custom).id }
        assertEquals(listOf("demo.view.refresh", "demo.view.settings"), buttons)
        assertTrue(
            "示例插件不该有任何降级：" + registry.degradedReasons("demo.view"),
            registry.degradedReasons("demo.view").isEmpty(),
        )
    }

    @Test
    fun legacyUiAliasStaysOnTheOldPath() {
        // 老包把 view 当 ui 的别名（没有版本号）——行为必须一字不差。
        val legacy = PluginManifestFile.parse(
            """
            {
              "id": "old.plugin",
              "contributions": [
                { "slot": "app.drawer", "id": "old.a", "label": "老面板",
                  "view": { "type": "column", "children": [ { "type": "markdown", "text": "**hi**" } ] } }
              ]
            }
            """.trimIndent(),
        ).getOrThrow()
        val contribution = legacy.contributions.single()
        assertNotNull("没有版本号的 view 仍旧当 ui 子树", contribution.ui)
        assertNull(contribution.view)

        val ui = PluginUiJson.spec(contribution.ui)
        val child = (ui!!.root as PluginUiNode.Column).children.single()
        assertTrue("老路径照旧认 markdown", child is PluginUiNode.Markdown)
    }

    // ---------------------------------------------------------------- 未知节点 / 未知动作

    @Test
    fun unknownNodeDegradesToGrayPlaceholder() {
        val result = PluginViewJson.parse(
            JSONObject(
                """
                { "version": 1, "root": { "type": "column", "children": [
                  { "type": "sparkline", "data": [1, 2, 3] },
                  { "type": "text", "text": "还能看见我" }
                ] } }
                """.trimIndent(),
            ),
        )
        assertTrue("未知节点不该拒收整块界面：" + result.summary(), result.ok)
        assertEquals(1, result.issues.size)
        assertEquals(PluginViewCode.UnknownNode, result.issues.single().code)

        val root = PluginViewAdapter.toUiSpec(checkNotNull(result.spec)).root as PluginUiNode.Column
        assertEquals(2, root.children.size)
        val placeholder = root.children[0] as PluginUiNode.Text
        assertTrue("占位文本要点出是哪个节点：" + placeholder.text, placeholder.text.contains("sparkline"))
        assertEquals(dev.dsh.mobile.pluginapi.UiColorRole.OnSurfaceVariant, placeholder.colorRole)
        assertEquals("还能看见我", (root.children[1] as PluginUiNode.Text).text)
    }

    @Test
    fun unknownOrNonStringActionIsDropped() {
        val result = PluginViewJson.parse(
            JSONObject(
                """
                { "version": 1, "root": { "type": "column", "children": [
                  { "type": "button", "label": "声明的", "action": "demo.a" },
                  { "type": "button", "label": "没声明的", "action": "demo.zzz" },
                  { "type": "button", "label": "塞代码的", "action": { "js": "alert(1)" } }
                ] } }
                """.trimIndent(),
            ),
            knownActions = setOf("demo.a"),
        )
        assertTrue(result.ok)
        assertEquals(2, result.issues.count { it.code == PluginViewCode.UnknownAction })

        val buttons = (PluginViewAdapter.toUiSpec(checkNotNull(result.spec)).root as PluginUiNode.Column)
            .children.map { it as PluginUiNode.Button }
        assertEquals(SlotAction.Custom("demo.a"), buttons[0].action)
        assertNull("没声明的动作必须丢掉", buttons[1].action)
        assertNull("动作只能是 id 字符串，对象一律丢掉", buttons[2].action)
        assertEquals("按钮文字还在，只是点不动", "塞代码的", buttons[2].label)
    }

    // ---------------------------------------------------------------- 配额

    @Test
    fun oversizedViewIsRejected() {
        val fat = "x".repeat(17 * 1024)
        val result = PluginViewJson.parse(
            JSONObject("""{ "version": 1, "root": { "type": "text", "text": "$fat" } }"""),
        )
        assertNull(result.spec)
        assertEquals(PluginViewCode.TooLarge, result.rejection?.code)
        assertTrue(
            "错误信息要能读懂：" + result.summary(),
            result.summary().contains("16384") && result.summary().contains("字节"),
        )
    }

    @Test
    fun tooManyNodesIsRejected() {
        // 每层都不超子节点上限、字节数也远在 16 KiB 内，但总数 1 + 64 + 256 = 321 超过 256 ——
        // 深度、宽度、体积三道闸都不响时靠节点数兜住。
        val mids = JSONArray()
        for (i in 0 until 64) {
            val leaves = JSONArray()
            for (j in 0 until 4) leaves.put(JSONObject().put("type", "text").put("text", "x"))
            mids.put(JSONObject().put("type", "column").put("children", leaves))
        }
        val root = JSONObject().put("type", "column").put("children", mids)
        val result = PluginViewJson.parse(JSONObject().put("version", 1).put("root", root))
        assertNull(result.spec)
        assertEquals(PluginViewCode.TooManyNodes, result.rejection?.code)
    }

    @Test
    fun tooDeepIsRejected() {
        var node = JSONObject().put("type", "text").put("text", "底")
        repeat(20) { node = JSONObject().put("type", "column").put("children", JSONArray().put(node)) }
        val result = PluginViewJson.parse(JSONObject().put("version", 1).put("root", node))
        assertNull(result.spec)
        assertEquals(PluginViewCode.TooDeep, result.rejection?.code)
        assertTrue("要指出是哪一层：" + result.summary(), checkNotNull(result.rejection).path.startsWith("/root/children"))
    }

    @Test
    fun tooManyChildrenAndLongStringsAreRejected() {
        val wide = JSONArray()
        repeat(65) { wide.put(JSONObject().put("type", "text").put("text", "x")) }
        val tooWide = PluginViewJson.parse(
            JSONObject().put("version", 1).put("root", JSONObject().put("type", "column").put("children", wide)),
        )
        assertEquals(PluginViewCode.TooManyChildren, tooWide.rejection?.code)

        val tooLong = PluginViewJson.parse(
            JSONObject().put("version", 1)
                .put("root", JSONObject().put("type", "text").put("text", "字".repeat(513))),
        )
        assertEquals(PluginViewCode.StringTooLong, tooLong.rejection?.code)
    }

    @Test
    fun versionGuardRejectsNewerAndMalformed() {
        val newer = PluginViewJson.parse(
            JSONObject().put("version", 2).put("root", JSONObject().put("type", "text").put("text", "hi")),
        )
        assertEquals(PluginViewCode.VersionUnsupported, newer.rejection?.code)

        val malformed = PluginViewJson.parse(
            JSONObject().put("version", "v1beta").put("root", JSONObject().put("type", "text").put("text", "hi")),
        )
        assertEquals(PluginViewCode.VersionInvalid, malformed.rejection?.code)

        val zero = PluginViewJson.parse(
            JSONObject().put("version", 0).put("root", JSONObject().put("type", "text").put("text", "hi")),
        )
        assertEquals(PluginViewCode.VersionInvalid, zero.rejection?.code)

        // 缺省版本 = 本端版本，直连 API 的调用方不必写 version。
        val omitted = PluginViewJson.parse(JSONObject().put("root", JSONObject().put("type", "text").put("text", "hi")))
        assertTrue(omitted.ok)
    }

    @Test
    fun notAnObjectAndEmptyAreRejected() {
        assertEquals(PluginViewCode.Empty, PluginViewJson.parse("   ").rejection?.code)
        assertEquals(PluginViewCode.NotObject, PluginViewJson.parse("[1,2,3]").rejection?.code)
    }

    private fun demoManifest(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("plugin-view-demo.json"),
    ) { "缺少测试资源 plugin-view-demo.json" }.bufferedReader().use { it.readText() }
}
