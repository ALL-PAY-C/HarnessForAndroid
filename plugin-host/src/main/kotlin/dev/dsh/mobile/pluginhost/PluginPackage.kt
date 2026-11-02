package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.SlotAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk contract for a plugin package.
 *
 * A package is a zip whose root holds `plugin.json`; everything else is payload
 * the plugin itself may read. The manifest is intentionally declarative: a
 * plugin that only needs slots and a settings form never has to ship code, so
 * installing it is a data-only change that cannot break the host.
 *
 * ```json
 * {
 *   "id": "dsh.rollback",
 *   "name": "会话回滚",
 *   "version": "1.0.0",
 *   "description": "…",
 *   "contributions": [
 *     { "slot": "chat.composer.plus", "id": "rollback", "label": "回滚到此处",
 *       "action": { "kind": "prompt", "text": "回滚到这条消息之前的状态" } }
 *   ],
 *   "config": [
 *     { "key": "base", "label": "网关地址", "type": "text", "default": "http://127.0.0.1:3425" }
 *   ]
 * }
 * ```
 *
 * Parsed with org.json rather than kotlinx.serialization so the plugin host
 * stays free of a serialization dependency.
 */
data class PluginManifestFile(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val contributions: List<ContributionSpec> = emptyList(),
    val config: List<ConfigSpec> = emptyList(),
    /** 插件主页/控制台地址；plugin.json 写 homepage，旧包可能只写 url。 */
    val homepage: String = "",
) {
    companion object {
        fun parse(text: String): Result<PluginManifestFile> = runCatching {
            val root = JSONObject(text)
            val id = root.optString("id").ifBlank { error("plugin.json 缺少 id") }
            PluginManifestFile(
                id = id,
                name = root.optString("name").ifBlank { id },
                version = root.optString("version").ifBlank { "0.0.0" },
                description = root.optString("description"),
                author = root.optString("author"),
                contributions = root.optJSONArray("contributions").mapObjects { it.toContribution() },
                config = root.optJSONArray("config").mapObjects { it.toConfig() },
                homepage = root.optString("homepage").ifBlank { root.optString("url") },
            )
        }
    }
}

data class ContributionSpec(
    val slot: String,
    val id: String,
    val label: String,
    val subtitle: String = "",
    val icon: String? = null,
    val action: ActionSpec? = null,
    /**
     * 声明式 UI 子树（plugin.json 的 `ui` 字段）。
     *
     * 非空时宿主用 [PluginUiJson] 把它翻译成 [dev.dsh.mobile.pluginapi.PluginUiSpec]，
     * 再用宿主自己的主题画出来 —— 第三方插件不需要认识 Compose，也不需要打包资源。
     */
    val ui: org.json.JSONObject? = null,
)

/** What tapping a contribution does. Data-only verbs, no code. */
data class ActionSpec(
    val kind: String,
    val text: String = "",
    val method: String = "GET",
    val url: String = "",
    val body: String = "",
    val uri: String = "",
    val pageId: String = "",
    val custom: String = "",
    /** SetConfig 用：写哪个 config 键、写什么值、是否取反。 */
    val key: String = "",
    val value: String = "",
    val toggle: Boolean = false,
)

/**
 * 「只切状态、不发消息」的 action：kind = setconfig / set_config / config / toggle。
 *
 * 单列一个函数，是因为三个消费点（[JsBackedPlugin] / [DeclarativePlugin] / [PluginUiJson]）
 * 必须同一套语义 —— 否则同一个插件换个安装形态，点下去的行为就变了。
 * [pluginId] 由宿主在装配时填，插件没有机会改成别人的 config。
 */
internal fun ActionSpec.configAction(pluginId: String): SlotAction.SetConfig? {
    val k = kind.lowercase()
    if (k != "setconfig" && k != "set_config" && k != "config" && k != "toggle") return null
    if (key.isBlank()) return null
    return SlotAction.SetConfig(
        pluginId = pluginId,
        key = key,
        value = value,
        toggle = toggle || k == "toggle",
    )
}

/**
 * 「一键写会话人设」的 action：kind = setpersona / set_persona / persona。
 *
 * 与 [configAction] 同一条理由分出来：三个消费点必须同一套语义。setpersona / set_persona 是开关
 * （点一下取反），persona 是照写（[ActionSpec.text] 为空串即清除）。
 */
internal fun ActionSpec.personaAction(): SlotAction.SetPersona? {
    val k = kind.lowercase()
    return when (k) {
        "setpersona", "set_persona" -> SlotAction.SetPersona(text = text, toggle = true)
        "persona" -> SlotAction.SetPersona(text = text, toggle = false)
        else -> null
    }
}

data class ConfigSpec(
    val key: String,
    val label: String,
    val type: String = "text",
    val default: String = "",
    val hint: String = "",
    val options: List<String> = emptyList(),
)

private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(block) }
}

private fun JSONObject.toContribution(): ContributionSpec = ContributionSpec(
    slot = optString("slot"),
    id = optString("id"),
    label = optString("label"),
    subtitle = optString("subtitle"),
    icon = optString("icon").ifBlank { null },
    ui = optJSONObject("ui") ?: optJSONObject("panel") ?: optJSONObject("view"),
    action = optJSONObject("action")?.let { a ->
        ActionSpec(
            kind = a.optString("kind").ifBlank { "prompt" },
            text = a.optString("text"),
            method = a.optString("method").ifBlank { "GET" },
            url = a.optString("url"),
            body = a.optString("body"),
            uri = a.optString("uri"),
            pageId = a.optString("pageId"),
            custom = a.optString("custom"),
            key = a.optString("key"),
            value = a.optString("value"),
            toggle = a.optBoolean("toggle", false),
        )
    },
)

private fun JSONObject.toConfig(): ConfigSpec = ConfigSpec(
    key = optString("key"),
    label = optString("label").ifBlank { optString("key") },
    type = optString("type").ifBlank { "text" },
    default = optString("default"),
    hint = optString("hint"),
    options = optJSONArray("options")?.let { arr ->
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }.orEmpty(),
)
