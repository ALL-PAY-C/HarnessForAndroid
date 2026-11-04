package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.pluginapi.ConfigField
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.design.R
import dev.dsh.mobile.pluginapi.ConfigFieldType
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.pluginhost.ConfigSpec
import dev.dsh.mobile.pluginhost.PluginManifestFile

/** 没有任何设置可操作时的统一文案（列表副标题与详情空态共用，避免两处说法不一致）。 */
val NO_SETTINGS_TEXT: UiText = UiText.Res(R.string.plugins_no_settings)

/** 空态补充说明：告诉用户这不是 bug，而是插件自己没声明。 */
val NO_SETTINGS_HINT: UiText = UiText.Res(R.string.plugins_no_settings_hint)

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
    /** type = "action" 的行（重置按钮）：不占输入框，点一下把 [ConfigAction.target] 写回默认。 */
    val actions: List<ConfigAction> = emptyList(),
    val values: Map<String, String>,
    /** 插件自己声明的整页界面（声明式 ui 或 HTML 面板），没有就是 null。 */
    val page: SlotContribution?,
    /** 插件声明的网页控制台；只有 http(s) 才值得给「在浏览器打开」。 */
    val webUrl: String?,
    /**
     * 宿主自渲染的设置页（含分组标题 / 滑杆 / 说明行）。
     *
     * 与 [fields] 同源但更宽：fields 只有输入型字段（老路径），schema 还包含分组和只读行，
     * 且有「未知类型降级成说明行」的明确落点。
     */
    val schema: SettingsSchema = SettingsSchema(emptyList()),
) {
    /** 有没有能画出来的插件自绘界面（ui 或 content 任一即可，都没有只剩 label 一行）。 */
    val hasPage: Boolean get() = page?.ui != null || page?.content != null

    /**
     * config 之外用户自己加的键值。插件读 ctx.config 时同样生效，
     * 所以「插件没声明设置项」不该等于「这页没东西可改」。
     *
     * 插件自己声明了 config（fields 或 actions 非空）时一律返回空：那些没被声明的存储键
     * 必然是旧版本遗留（改名/删除的字段），平铺出来就是噪声。数据仍留在存储里，插件
     * ctx.config 照旧读得到。
     */
    val extraValues: Map<String, String>
        get() = if (fields.isNotEmpty() || actions.isNotEmpty()) {
            emptyMap()
        } else {
            values.filterKeys { key -> fields.none { it.key == key } }
        }

    /** 有没有可操作的东西：config 字段、自绘界面，或用户自己加的键值。 */
    val hasSettings: Boolean
        get() = !schema.isEmpty || fields.isNotEmpty() || actions.isNotEmpty() || hasPage || extraValues.isNotEmpty()

    /**
     * 列表副标题。顺序即优先级：没设置项时先喊空态（用户抱怨的就是「点进去是介绍页」），
     * 有 description 就用插件自己的说明，否则用设置项数量兜底。
     */
    val rowSubtitle: UiText
        get() = when {
            !hasSettings -> NO_SETTINGS_TEXT
            description.isNotBlank() -> UiText.Raw(description)
            fields.isNotEmpty() || actions.isNotEmpty() ->
                UiText.Res(R.string.plugins_settings_count, listOf(fields.size + actions.size))
            else -> UiText.Res(R.string.plugins_has_own_ui)
        }

    /** 详情页空态文案；非 null 表示这页只能显示「没有设置项」。 */
    val emptyText: UiText? get() = if (hasSettings) null else NO_SETTINGS_TEXT

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
            // action 行不是输入框：留在这里会画出一个叫「重置」的文本框，和按钮重复。
            fields = manifest.config.filterNot { isActionRow(it.type) }.map { it.toConfigField() },
            actions = configActions(manifest.config),
            values = values(manifest.id),
            // 优先取带 ui 的那条：没有 ui 的只剩 label，画不出设置界面。
            page = mine.firstOrNull { it.ui != null } ?: mine.firstOrNull(),
            webUrl = manifest.homepage.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            schema = settingsSchema(manifest.config).also { parsed ->
                // 未知类型不静默：日志 + 设置页说明行，两个地方都能看到。
                if (parsed.unsupported.isNotEmpty()) {
                    AppLog.w("plugin " + manifest.id + " has " + parsed.unsupported.size + " unsupported setting types: " + parsed.unsupported.joinToString())
                }
            },
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

/**
 * 「重置」行：plugin.json 里 { "type": "action", "target": "entryText" } 那一条。
 *
 * [default] 是同插件 config 里 [target] 那行声明的默认值；target 缺失或找不到对应行时为 null，
 * 界面据此降级成不可点（不写脏值、不崩）。
 */
data class ConfigAction(
    val key: String,
    val label: String,
    val target: String,
    val default: String?,
)

internal fun isActionRow(raw: String): Boolean = raw.trim().lowercase() == "action"

/** 纯函数：把 config 声明里的 action 行摘出来，顺手解析各自的默认值。 */
fun configActions(config: List<ConfigSpec>): List<ConfigAction> = config
    .filter { isActionRow(it.type) }
    .map { spec ->
        val target = spec.target.trim()
        ConfigAction(
            key = spec.key,
            label = spec.label.ifBlank { spec.key },
            target = target,
            default = target.takeIf { it.isNotBlank() }
                ?.let { t -> config.firstOrNull { it.key == t }?.default },
        )
    }

/**
 * 纯函数：一次「重置」之后的配置快照。
 *
 * 默认值拿不到就原样返回 —— 调用方据此提示「找不到要重置的设置项」，存储里不会多一个空值。
 */
fun applyReset(values: Map<String, String>, action: ConfigAction): Map<String, String> {
    val fallback = action.default ?: return values
    return values + (action.target to fallback)
}

/** 字符串 → 开关状态。插件存的是字符串，这里集中解释，避免各调用点各写一套。 */
fun switchOn(value: String): Boolean = when (value.trim().lowercase()) {
    "1", "true", "on", "yes", "enabled" -> true
    else -> false
}
