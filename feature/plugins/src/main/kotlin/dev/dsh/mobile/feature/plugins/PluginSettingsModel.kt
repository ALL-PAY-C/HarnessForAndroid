package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.ConfigField
import dev.dsh.mobile.pluginapi.ConfigFieldType
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile

/** 没有任何设置可操作时的统一文案（列表副标题与详情空态共用，避免两处说法不一致）。 */
const val NO_SETTINGS_TEXT = "该插件没有设置项"

/** 空态补充说明：告诉用户这不是 bug，而是插件自己没声明。 */
const val NO_SETTINGS_HINT = "插件在 plugin.json 里声明 config 字段或 ui 界面后，这里会变成可操作的设置页。"

/**
 * 「插件设置」里的一行 = 一个插件的一项设置页。
 *
 * 之所以按**插件**（而不是按 config 页 / slot 贡献）建模：一个插件可能同时有
 * plugin.json 的 config 字段、自己的声明式 ui、以及落在 Settings 槽位的条目，
 * 按贡献拆会让同一个插件在文件夹里出现好几行，用户根本不知道自己改的是谁。
 */
data class PluginSettingsEntry(
    val pluginId: String,
    val name: String,
    val version: String,
    val description: String,
    val fields: List<ConfigField>,
    val values: Map<String, String>,
    /** 插件自己声明的整页界面（声明式 ui 或 HTML 面板），没有就是 null。 */
    val page: SlotContribution?,
    /** 插件声明的网页控制台；只有 http(s) 才值得给「在浏览器打开」。 */
    val webUrl: String?,
) {
    /** 有没有能画出来的插件自绘界面（ui 或 content 任一即可，都没有只剩 label 一行）。 */
    val hasPage: Boolean get() = page?.ui != null || page?.content != null

    /** 有没有可操作的东西：config 字段或自绘界面。 */
    val hasSettings: Boolean get() = fields.isNotEmpty() || hasPage

    /**
     * 列表副标题。顺序即优先级：没设置项时先喊空态（用户抱怨的就是「点进去是介绍页」），
     * 有 description 就用插件自己的说明，否则用设置项数量兜底。
     */
    val rowSubtitle: String
        get() = when {
            !hasSettings -> NO_SETTINGS_TEXT
            description.isNotBlank() -> description
            fields.isNotEmpty() -> "共 " + fields.size + " 项设置"
            else -> "插件自带设置界面"
        }

    /** 详情页空态文案；非 null 表示这页只能显示「没有设置项」。 */
    val emptyText: String? get() = if (hasSettings) null else NO_SETTINGS_TEXT

    /**
     * 加载失败提示。JsBrokenPlugin 把失败阶段写进 subtitle（「加载失败：…」），
     * 这里把它捞出来单独显示，避免用户以为插件只是没有设置项。
     */
    val failureText: String? get() = page?.subtitle?.takeIf { it.startsWith("加载失败") }

    /** 当前生效值：用户改过的值优先，否则回落到 manifest 声明的 default。 */
    fun valueOf(field: ConfigField): String = values[field.key] ?: field.default
}

/**
 * 组装「插件设置」文件夹的全部行。
 *
 * [installed] 是已装插件的 manifest（顺序无关，这里按名称排序保证稳定），
 * [pages] 是各插件注册到 PluginPage 槽位的贡献（声明式 ui 从这里来），
 * [values] 读某个插件的已保存配置。
 *
 * 纯函数：不碰 SharedPreferences，方便单测，也方便以后换存储。
 */
fun pluginSettingsEntries(
    installed: List<PluginManifestFile>,
    pages: List<SlotContribution>,
    values: (String) -> Map<String, String> = { emptyMap() },
): List<PluginSettingsEntry> = installed
    .sortedWith(compareBy({ it.name }, { it.id }))
    .map { manifest ->
        val mine = pages.filter { it.pluginId == manifest.id }
        PluginSettingsEntry(
            pluginId = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            fields = manifest.config.map { it.toConfigField() },
            values = values(manifest.id),
            // 优先取带 ui 的那条：没有 ui 的只剩 label，画不出设置界面。
            page = mine.firstOrNull { it.ui != null } ?: mine.firstOrNull(),
            webUrl = manifest.homepage.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )
    }

/** plugin.json 的 config 声明 → 宿主可渲染的字段；未知 type 一律当文本，不让插件「消失」。 */
internal fun ConfigSpec.toConfigField(): ConfigField = ConfigField(
    key = key,
    label = label.ifBlank { key },
    type = fieldTypeOf(type),
    default = default,
    hint = hint,
    options = options,
)

internal fun fieldTypeOf(raw: String): ConfigFieldType = when (raw.trim().lowercase()) {
    "switch", "toggle", "bool", "boolean", "checkbox" -> ConfigFieldType.Switch
    "secret", "password" -> ConfigFieldType.Secret
    "number", "int", "integer", "float", "double" -> ConfigFieldType.Number
    "select", "enum", "choice", "radio" -> ConfigFieldType.Select
    else -> ConfigFieldType.Text
}

/** 字符串 → 开关状态。插件存的是字符串，这里集中解释，避免各调用点各写一套。 */
fun switchOn(value: String): Boolean = when (value.trim().lowercase()) {
    "1", "true", "on", "yes", "enabled" -> true
    else -> false
}
