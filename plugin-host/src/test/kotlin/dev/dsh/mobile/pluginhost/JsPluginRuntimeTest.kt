package dev.dsh.mobile.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JsPluginRuntime 真实契约：
 *  - 装了 JS 入口的包，脚本里的 dsh.ctx.* 调用能被收集回来；
 *  - 入口缺失 / 脚本抛错 / 死循环一律返回 Result.failure，绝不外抛；
 *  - 入口解析按 dsh.main → dsh.entry → main → index.js → lib/index.js 顺序。
 */
class JsPluginRuntimeTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dsh-js-plugin-test/" + name + "-" + System.nanoTime())
            .apply { deleteRecursively(); mkdirs() }

    private fun pkg(dir: File, json: String) = File(dir, "package.json").apply { writeText(json) }

    private fun entry(dir: File, rel: String, code: String) =
        File(dir, rel).apply { parentFile?.mkdirs(); writeText(code) }

    @Test
    fun executesEntryAndCollectsRegistrations() {
        val dir = tempDir("ok")
        pkg(dir, """{"name":"share-pack","version":"1.2.3","dsh":{"main":"src/main.js"}}""")
        entry(
            dir, "src/main.js",
            """
            ctx.log("插件启动了，id=" + ctx.pluginId);
            ctx.registerContribution({
              slot: "chat.composer.plus",
              id: "share",
              label: "分享这段",
              subtitle: "复制成链接",
              action: { kind: "prompt", text: "把这轮对话整理成分享链接" }
            });
            ctx.command({ id: "daily", label: "每日摘要", payload: "/daily" });
            """.trimIndent(),
        )

        val result = JsPluginRuntime().execute(dir, "npm.share-pack")
        assertTrue(result.isSuccess)
        val run = result.getOrThrow()

        assertEquals("src/main.js", run.entryPath)
        assertEquals(1, run.contributions.size)
        assertEquals("share", run.contributions[0].id)
        assertEquals("分享这段", run.contributions[0].label)
        assertEquals("chat.composer.plus", run.contributions[0].slot)
        assertEquals("prompt", run.contributions[0].action?.kind)
        assertEquals(1, run.commands.size)
        assertEquals("daily", run.commands[0].id)
        assertEquals("/daily", run.commands[0].payload)
        assertEquals(1, run.logs.size)
        assertTrue(run.logs[0].contains("npm.share-pack"))
    }

    @Test
    fun resolvesEntryInDocumentedOrder() {
        val dir = tempDir("order")
        // dsh.main 优先于 dsh.entry / main / index.js
        pkg(dir, """{"dsh":{"main":"a.js","entry":"b.js"},"main":"c.js"}""")
        entry(dir, "a.js", "")
        entry(dir, "b.js", "")
        entry(dir, "c.js", "")
        entry(dir, "index.js", "")
        assertEquals("a.js", JsPluginRuntime.resolveEntry(dir)?.path)

        // dsh.main 不在磁盘上 → 退到 dsh.entry
        File(dir, "a.js").delete()
        assertEquals("b.js", JsPluginRuntime.resolveEntry(dir)?.path)

        // 两个 dsh 字段都没有 → main
        File(dir, "b.js").delete()
        assertEquals("c.js", JsPluginRuntime.resolveEntry(dir)?.path)

        // 连 main 都没有 → index.js，再退 lib/index.js
        File(dir, "c.js").delete()
        assertEquals("index.js", JsPluginRuntime.resolveEntry(dir)?.path)
        File(dir, "index.js").delete()
        entry(dir, "lib/index.js", "")
        assertEquals("lib/index.js", JsPluginRuntime.resolveEntry(dir)?.path)
    }

    @Test
    fun ignoresBundleField() {
        val dir = tempDir("bundle")
        // dsh.bundle 是别的端的打包产物，必须被忽略
        pkg(dir, """{"dsh":{"bundle":"dist/bundle.js"}}""")
        entry(dir, "dist/bundle.js", "")
        entry(dir, "index.js", "")
        assertEquals("index.js", JsPluginRuntime.resolveEntry(dir)?.path)
    }

    @Test
    fun missingEntryFailsWithStageNotCrash() {
        val dir = tempDir("noentry")
        pkg(dir, """{"name":"empty-pack"}""")
        val result = JsPluginRuntime().execute(dir, "npm.empty-pack")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as JsPluginError
        assertEquals(JsPluginRuntime.STAGE_ENTRY, err.stage)
        assertTrue(err.message.contains("没有 JS 入口"))
    }

    @Test
    fun throwingScriptFailsWithoutEscaping() {
        val dir = tempDir("boom")
        pkg(dir, """{"main":"index.js"}""")
        entry(dir, "index.js", """ctx.registerContribution({slot:"app.settings",id:"x",label:"x"}); throw new Error("故意炸");""")

        val result = JsPluginRuntime().execute(dir, "npm.boom")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as JsPluginError
        assertEquals(JsPluginRuntime.STAGE_EXEC, err.stage)
        assertTrue(err.message.contains("故意炸"))
    }

    @Test
    fun syntaxErrorFailsAtCompileStage() {
        val dir = tempDir("syntax")
        pkg(dir, """{"main":"index.js"}""")
        entry(dir, "index.js", "function ( { 这不是 JS")
        val err = JsPluginRuntime().execute(dir, "npm.syntax").exceptionOrNull() as JsPluginError
        assertEquals(JsPluginRuntime.STAGE_COMPILE, err.stage)
    }

    @Test
    fun infiniteLoopIsBoundedNotHung() {
        val dir = tempDir("loop")
        pkg(dir, """{"main":"index.js"}""")
        entry(dir, "index.js", "while (true) { }")
        val started = System.currentTimeMillis()
        val result = JsPluginRuntime().execute(dir, "npm.loop")
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.isFailure)
        assertTrue("用时 " + elapsed + "ms 应当被步数上限掐断", elapsed < 30_000)
    }

    @Test
    fun configReadsAndWritesBack() {
        val dir = tempDir("config")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.log("before=" + ctx.config("endpoint", "http://default"));
            ctx.setConfig("endpoint", "http://set-from-js");
            """.trimIndent(),
        )
        val writes = mutableMapOf<String, String>()
        val run = JsPluginRuntime(
            config = mapOf("endpoint" to "http://from-store"),
            onSetConfig = { k, v -> writes[k] = v },
        ).execute(dir, "npm.config").getOrThrow()

        assertEquals("before=http://from-store", run.logs.single())
        assertEquals("http://set-from-js", writes["endpoint"])
    }

    @Test
    fun sandboxHidesJavaAndFilesystem() {
        val dir = tempDir("sandbox")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.log("java=" + (typeof java));
            ctx.log("packages=" + (typeof Packages));
            ctx.log("readFile=" + (typeof readFile));
            """.trimIndent(),
        )
        val run = JsPluginRuntime().execute(dir, "npm.sandbox").getOrThrow()
        assertEquals(
            listOf("java=undefined", "packages=undefined", "readFile=undefined"),
            run.logs,
        )
    }

    @Test
    fun rendererTemplateSubstitutesText() {
        val dir = tempDir("renderer")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """ctx.renderer({ id: "quote", matcher: "^>", template: "引用：{{text}}" });""",
        )
        val run = JsPluginRuntime().execute(dir, "npm.renderer").getOrThrow()
        assertEquals(1, run.renderers.size)
        assertEquals("引用：正文", run.renderers[0].render("正文"))
    }

    @Test
    fun contributionUiAndActionRoundTripThroughJson() {
        val dir = tempDir("ui")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.registerContribution({
              slot: "app.settings",
              id: "card",
              label: "卡片",
              ui: { width: "fill", root: { type: "section", title: "标题", children: [
                { type: "text", text: "正文" }
              ] } },
              action: { kind: "link", uri: "https://example.com" }
            });
            """.trimIndent(),
        )
        val run = JsPluginRuntime().execute(dir, "npm.ui").getOrThrow()
        val c = run.contributions.single()
        val ui: JSONObject = c.ui ?: error("ui 没被转换出来")
        assertEquals("fill", ui.getString("width"))
        assertEquals("section", ui.getJSONObject("root").getString("type"))
        assertEquals(1, ui.getJSONObject("root").getJSONArray("children").length())
        assertEquals("link", c.action?.kind)
        assertEquals("https://example.com", c.action?.uri)
    }

    @Test
    fun registrationsAfterFailureAreDiscarded() {
        val dir = tempDir("partial")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.registerContribution({ slot: "app.settings", id: "half", label: "半截" });
            throw new Error("半路失败");
            """.trimIndent(),
        )
        // 必须整体失败：半截的注册不能漏进宿主。
        assertTrue(JsPluginRuntime().execute(dir, "npm.partial").isFailure)
    }
@Test
    fun bridgeRegistersIntoPluginRegistry() {
        val dir = tempDir("bridge")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.registerContribution({ slot: "chat.composer.plus", id: "share", label: "分享", action: { kind: "link", uri: "https://e.x" } });
            ctx.command({ id: "daily", label: "每日摘要", payload: "/daily" });
            ctx.composerButton({ id: "quote", label: "引用", text: "> " });
            ctx.prompt("总结这条会话");
            ctx.renderer({ id: "quote", matcher: "^>", template: "引用：{{text}}" });
            """.trimIndent(),
        )
        val run = JsPluginRuntime().execute(dir, "npm.bridge").getOrThrow()
        val spec = PluginManifestFile.parse("""{"id":"npm.bridge","name":"桥接包","version":"1.0.0"}""").getOrThrow()
        val registry = dev.dsh.mobile.pluginapi.PluginRegistry()

        JsBackedPlugin(spec, run).register(registry)

        // 两条：脚本注册的 share + ctx.prompt 落成的提示词项
        assertEquals(2, registry.inSlot(dev.dsh.mobile.pluginapi.SlotId.ComposerPlus).size)
        assertEquals("分享", registry.inSlot(dev.dsh.mobile.pluginapi.SlotId.ComposerPlus)[0].label)
        assertTrue(registry.inSlot(dev.dsh.mobile.pluginapi.SlotId.ComposerPlus)[0].action is dev.dsh.mobile.pluginapi.SlotAction.Link)
        // ctx.prompt 落成第二条 ComposerPlus 贡献（action=Prompt）
        assertEquals("总结这条会话", (registry.inSlot(dev.dsh.mobile.pluginapi.SlotId.ComposerPlus)[1].action as dev.dsh.mobile.pluginapi.SlotAction.Prompt).text)
        assertEquals(1, registry.quickCommands.size)
        assertEquals("/daily", registry.quickCommands[0].payload)
        assertEquals(1, registry.composerButtons.size)
        assertEquals("> ", registry.composerButtons[0].transform(""))
        assertTrue(registry.messageRenderers.containsKey("npm.bridge.quote"))
    }

    @Test
    fun brokenPluginRegistersAnErrorCardInsteadOfThrowing() {
        val spec = PluginManifestFile.parse("""{"id":"npm.bad","name":"坏包","version":"1.0.0"}""").getOrThrow()
        val registry = dev.dsh.mobile.pluginapi.PluginRegistry()
        JsBrokenPlugin(spec, JsPluginError(JsPluginRuntime.STAGE_EXEC, "故意炸")).register(registry)
        val card = registry.inSlot(dev.dsh.mobile.pluginapi.SlotId.Settings).single()
        assertTrue(card.subtitle.contains("故意炸"))
        assertEquals("npm.bad", card.pluginId)
    }
}
