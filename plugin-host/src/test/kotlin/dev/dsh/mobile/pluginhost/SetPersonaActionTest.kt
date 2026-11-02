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
 * 「一键写会话人设」的契约：
 *  - ctx.setSessionPersona(text) 落到宿主回调，空串表示清除，返回值就是宿主说的实话；
 *  - action.kind = setpersona 落成 [SlotAction.SetPersona]（toggle 语义），绝不落成 Prompt；
 *  - 同一份 plugin.json 换成声明式安装，行为不变；
 *  - 点击时只写会话人设：没有接线 / 没有前台会话 / 结果没变化都返回 false。
 */
class SetPersonaActionTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dsh-js-persona-test/" + name + "-" + System.nanoTime())
            .apply { deleteRecursively(); mkdirs() }

    private fun pkg(dir: File, json: String) = File(dir, "package.json").apply { writeText(json) }

    private fun entry(dir: File, rel: String, code: String) =
        File(dir, rel).apply { parentFile?.mkdirs(); writeText(code) }

    private fun manifest(id: String) =
        PluginManifestFile.parse("""{"id":"$id","name":"测试包","version":"1.0.0"}""").getOrThrow()

    /** 假的人设口：记下每次写入，读的永远是最近一次的值。 */
    private class FakePersonaBridge(
        private var session: String?,
        private var persona: String = "",
    ) : SessionPersonaBridge {
        val writes = mutableListOf<String>()
        override fun sessionId(): String? = session
        override fun readPersona(sessionId: String): String = persona
        override fun writePersona(sessionId: String, text: String): Boolean {
            writes += text
            persona = text
            return true
        }
    }

    @Test
    fun jsSetSessionPersonaLandsInHostCallback() {
        val dir = tempDir("js-persona")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.log("ok=" + ctx.setSessionPersona("人设正文"));
            ctx.log("cleared=" + ctx.setSessionPersona(""));
            """.trimIndent(),
        )
        val writes = mutableListOf<String>()
        val run = JsPluginRuntime(onSessionPersona = { writes += it; true })
            .execute(dir, "npm.persona").getOrThrow()

        assertEquals(listOf("人设正文", ""), writes)
        assertEquals(listOf("ok=true", "cleared=true"), run.logs)
    }

    @Test
    fun setPersonaKindBecomesSetPersonaAndNeverPrompt() {
        val dir = tempDir("kind-persona")
        pkg(dir, """{"main":"index.js"}""")
        entry(
            dir, "index.js",
            """
            ctx.registerContribution({
              slot: "chat.settings",
              id: "one-click",
              label: "破甲注入：一键开 / 关",
              action: { kind: "setpersona", text: "人设正文" }
            });
            ctx.registerContribution({
              slot: "chat.settings",
              id: "clear",
              label: "清除人设",
              action: { kind: "persona", text: "" }
            });
            """.trimIndent(),
        )
        val run = JsPluginRuntime().execute(dir, "npm.kind-persona").getOrThrow()
        val registry = PluginRegistry()
        JsBackedPlugin(manifest("npm.kind-persona"), run).register(registry)

        val slots = registry.inSlot(SlotId.ChatSettings)
        val toggle = slots.first { it.id == "one-click" }.action
        assertTrue(toggle is SlotAction.SetPersona)
        assertEquals("人设正文", (toggle as SlotAction.SetPersona).text)
        assertTrue(toggle.toggle)
        // 关键：不是 Prompt —— 点一下不会往会话里发任何消息。
        assertFalse(toggle is SlotAction.Prompt)

        val clear = slots.first { it.id == "clear" }.action as SlotAction.SetPersona
        assertEquals("", clear.text)
        assertFalse(clear.toggle)
    }

    @Test
    fun declarativePackageGetsTheSameAction() {
        val spec = PluginManifestFile.parse(
            """
            {"id":"json.persona","name":"声明式","version":"1.0.0","contributions":[
              {"slot":"chat.settings","id":"one-click","label":"一键","action":{"kind":"setpersona","text":"正文"}}
            ]}
            """.trimIndent(),
        ).getOrThrow()
        val registry = PluginRegistry()
        DeclarativePlugin(spec).register(registry)

        val action = registry.inSlot(SlotId.ChatSettings).first().action
        assertEquals("正文", (action as SlotAction.SetPersona).text)
        assertTrue(action.toggle)
    }

    @Test
    fun clickingTheSwitchWritesThenClears() {
        val bridge = FakePersonaBridge(session = "sess-1")
        val host = PluginHost(personaBridge = bridge)

        // 空 → 写入正文
        assertTrue(host.applySlotPersona(SlotAction.SetPersona("人设正文", toggle = true)))
        assertEquals(listOf("人设正文"), bridge.writes)

        // 有 → 清空
        assertTrue(host.applySlotPersona(SlotAction.SetPersona("人设正文", toggle = true)))
        assertEquals(listOf("人设正文", ""), bridge.writes)
    }

    @Test
    fun noSessionOrNoBridgeOrNoChangeIsHonestlyFalse() {
        // 没接线
        assertFalse(PluginHost().applySlotPersona(SlotAction.SetPersona("正文", toggle = true)))
        // 没有前台会话
        assertFalse(PluginHost(personaBridge = FakePersonaBridge(session = null)).applySlotPersona(SlotAction.SetPersona("正文", toggle = true)))
        // 正文为空 + 本来就没有人设 → 什么都没改，别报成功
        val empty = FakePersonaBridge(session = "s", persona = "")
        assertFalse(PluginHost(personaBridge = empty).applySlotPersona(SlotAction.SetPersona("", toggle = true)))
        assertTrue(empty.writes.isEmpty())
    }
}
