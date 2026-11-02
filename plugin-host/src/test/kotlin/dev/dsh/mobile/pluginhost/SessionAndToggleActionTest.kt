package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginRegistry
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话 id 注入与「只切状态」槽位动作的契约：
 *  - ctx.sessionId() 读的是宿主给的读取口，装载那一刻拿不到就是空串；
 *  - action.kind = toggle / setConfig 落成 [SlotAction.SetConfig]，绝不落成 Prompt（不发消息）；
 *  - 点击时只写插件自己的 config 并重载，宿主没接线就如实返回 false；
 *  - 老的 kind = prompt 行为一字不变。
 */
class SessionAndToggleActionTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dsh-js-session-test/" + name + "-" + System.nanoTime())
            .apply { deleteRecursively(); mkdirs() }

    private fun pkg(dir: File, json: String) = File(dir, "package.json").apply { writeText(json) }

    private fun entry(dir: File, rel: String, code: String) =
        File(dir, rel).apply { parentFile?.mkdirs(); writeText(code) }

    private fun manifest(id: String) =
        PluginManifestFile.parse("""{"id":"$id","name":"测试包","version":"1.0.0"}""").getOrThrow()

    private class FakeBridge(initial: Map<String, String>) : PluginConfigBridge {
        val values: MutableMap<String, String> = initial.toMutableMap()
        var reloads = 0
        override fun read(pluginId: String): Map<String, String> = values.toMap()
        override fun write(pluginId: String, key: String, value: String) {
            values[key] = value
        }
        override fun reload() {
            reloads++
        }
    }

    @Test
    fun sessionIdComesFromHostProvider() {
        val dir = tempDir("session")
        pkg(dir, """{"main":"index.js"}""")
        entry(dir, "index.js", """ctx.log("session=" + ctx.sessionId());""")

        val run = JsPluginRuntime(sessionId = { "sess-42" }).execute(dir, "npm.session").getOrThrow()
        assertEquals("session=sess-42", run.logs.single())
    }

    @Test
    fun sessionIdFallsBackToEmptyString() {
        val dir = tempDir("session-none")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.log("session=[" + ctx.sessionId() + "]");
            ctx.log("type=" + (typeof ctx.sessionId()));
            """.trimIndent(),
        )

        // 没有读取口 / 读取口给 null，都必须是空串：JS 侧只判 falsy，不必分 null 与 undefined。
        val run = JsPluginRuntime(sessionId = { null }).execute(dir, "npm.session-none").getOrThrow()
        assertEquals(listOf("session=[]", "type=string"), run.logs)
    }

    @Test
    fun toggleActionBecomesSetConfigAndNeverSendsAMessage() {
        val dir = tempDir("toggle")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.registerContribution({
              slot: "chat.composer.plus",
              id: "toggle",
              label: "会话开关",
              action: { kind: "toggle", key: "session.enabled" }
            });
            ctx.registerContribution({
              slot: "chat.composer.plus",
              id: "set",
              label: "选模式",
              action: { kind: "setConfig", key: "mode", value: "fast" }
            });
            ctx.registerContribution({
              slot: "chat.composer.plus",
              id: "legacy",
              label: "老写法",
              action: { kind: "prompt", text: "旧插件照旧发消息" }
            });
            """.trimIndent(),
        )
        val run = JsPluginRuntime().execute(dir, "npm.toggle").getOrThrow()
        val registry = PluginRegistry()
        JsBackedPlugin(manifest("npm.toggle"), run).register(registry)

        val slots = registry.inSlot(SlotId.ComposerPlus)
        val toggle = slots.first { it.id == "toggle" }.action
        assertTrue(toggle is SlotAction.SetConfig)
        assertEquals("npm.toggle", (toggle as SlotAction.SetConfig).pluginId)
        assertEquals("session.enabled", toggle.key)
        assertTrue(toggle.toggle)
        // 关键：不是 Prompt —— 点一下不会往会话里发任何消息。
        assertFalse(toggle is SlotAction.Prompt)

        val set = slots.first { it.id == "set" }.action as SlotAction.SetConfig
        assertEquals("mode", set.key)
        assertEquals("fast", set.value)
        assertFalse(set.toggle)

        // 向后兼容：老插件声明 prompt 的仍然是 Prompt。
        val legacy = slots.first { it.id == "legacy" }.action
        assertEquals("旧插件照旧发消息", (legacy as SlotAction.Prompt).text)
    }

    @Test
    fun clickingToggleOnlyWritesConfigAndReloads() {
        val bridge = FakeBridge(mapOf("session.enabled" to "false"))
        val host = PluginHost(configBridge = bridge)

        val ok = host.applySlotConfig(
            SlotAction.SetConfig(pluginId = "npm.toggle", key = "session.enabled", toggle = true),
        )

        assertTrue(ok)
        assertEquals("true", bridge.values["session.enabled"])
        assertEquals(1, bridge.reloads)
    }

    @Test
    fun clickingWithoutBridgeReportsFailureInsteadOfPretending() {
        val host = PluginHost()
        assertFalse(
            host.applySlotConfig(SlotAction.SetConfig(pluginId = "npm.toggle", key = "k", toggle = true)),
        )
    }

    @Test
    fun setConfigActionCarriesLiteralValue() {
        val bridge = FakeBridge(emptyMap())
        val host = PluginHost(configBridge = bridge)

        assertTrue(host.applySlotConfig(SlotAction.SetConfig(pluginId = "npm.toggle", key = "mode", value = "fast")))
        assertEquals("fast", bridge.values["mode"])
    }
}
