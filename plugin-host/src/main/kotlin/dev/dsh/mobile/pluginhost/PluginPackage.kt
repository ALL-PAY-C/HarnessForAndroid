package dev.dsh.mobile.pluginhost

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
)

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
