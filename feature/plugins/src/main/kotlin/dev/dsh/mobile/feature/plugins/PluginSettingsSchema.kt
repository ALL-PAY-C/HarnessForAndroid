package dev.dsh.mobile.feature.plugins

import kotlin.math.roundToInt
import dev.dsh.mobile.design.R
import dev.dsh.mobile.pluginhost.ConfigSpec

/**
 * 设置页的一行。插件只声明（plugin.json 的 config[]），宿主用自己的 design 组件画，
 * 所以插件不需要自带 HTML/Compose 也能有原生设置界面。
 *
 * 封闭集合：新增类型必须同时改 [settingsSchema] 与 PluginSettingsScreen 的渲染，
 * 否则老宿主遇到新类型会走进 [SettingItem.Info] 的降级行 —— 整页不会因此渲染失败。
 */
sealed interface SettingItem {
    val key: String

    /** 分组标题行：同 section 的字段归到它下面，视觉上是一段，不是一个卡片。 */
    data class Section(val title: String) : SettingItem {
        override val key: String get() = "section:" + title
    }

    /** 开关。 */
    data class Toggle(
        override val key: String,
        val label: String,
        val hint: String,
        val value: String,
    ) : SettingItem

    /** 文本 / 密码 / 数字：宿主只换输入法类型，不改行结构。 */
    data class Entry(
        override val key: String,
        val label: String,
        val hint: String,
        val value: String,
        val placeholder: String,
        val secret: Boolean = false,
        val numeric: Boolean = false,
        val multiline: Boolean = false,
    ) : SettingItem

    /** 下拉选择。 */
    data class Choice(
        override val key: String,
        val label: String,
        val hint: String,
        val value: String,
        val options: List<String>,
    ) : SettingItem

    /** 滑杆：min/max/step 缺失时按 0..100 连续兜底，值永远是能写回的十进制字符串。 */
    data class Slider(
        override val key: String,
        val label: String,
        val hint: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val steps: Int,
    ) : SettingItem

    /** 按钮行（type=action：把 target 那项写回默认值）。 */
    data class Action(
        override val key: String,
        val label: String,
        val target: String,
        val default: String?,
    ) : SettingItem

    /** 说明行：插件声明的纯文本，或未知类型的降级落点。 */
    data class Info(
        override val key: String,
        val label: String,
        val hint: String = "",
    ) : SettingItem
}

/** 解析结果 + 降级明细（供日志与「哪一条没生效」的可见提示用）。 */
data class SettingsSchema(
    val items: List<SettingItem>,
    val unsupported: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = items.none { it !is SettingItem.Section }
}

/** 一行要画成可点的控件还是只读说明。 */
internal val SettingItem.isReadOnly: Boolean
    get() = this is SettingItem.Info || this is SettingItem.Section || this is SettingItem.Action

/**
 * plugin.json 的 config[] → 宿主可渲染的设置页。
 *
 * 三条硬规则：
 * 1. 未知 type 降级成说明行并记进 [SettingsSchema.unsupported]，绝不静默丢弃、也绝不让整页失败；
 * 2. 空 key 的行直接跳过（写回去也没有落点）；
 * 3. section 只在它第一次出现的位置插一行标题，不重排字段顺序 —— 插件作者写的顺序就是界面顺序。
 */
fun settingsSchema(
    config: List<ConfigSpec>,
    /** 文案解析器：本文件刻意不碰 Context，分组兜底与降级提示都由调用方给已本地化的串。 */
    copy: (Int, List<Any>) -> String = { id, _ -> "#" + id },
): SettingsSchema {
    val items = mutableListOf<SettingItem>()
    val unsupported = mutableListOf<String>()
    var lastSection: String? = null
    config.forEach { spec ->
        val type = spec.type.trim().lowercase()
        val label = spec.label.ifBlank { spec.key.trim() }
        // 分组行：key 允许为空（它不存值），标题就是 label。老插件把分组写成
        // type=group 的独立行，新写法是用字段上的 section 属性，两条都要认。
        if (type == "group" || type == "section") {
            val title = label.ifBlank { copy(R.string.sessions_group_action, emptyList()) }
            if (title != lastSection) {
                items += SettingItem.Section(title)
                lastSection = title
            }
            return@forEach
        }
        val key = spec.key.trim()
        if (key.isEmpty()) return@forEach
        val section = spec.section.trim()
        if (section.isNotEmpty() && section != lastSection) {
            items += SettingItem.Section(section)
            lastSection = section
        }
        val hint = spec.hint
        val value = spec.default
        items += when (type) {
            "switch", "toggle", "bool", "boolean", "checkbox" -> SettingItem.Toggle(key, label, hint, value)
            "secret", "password" -> SettingItem.Entry(key, label, hint, value, spec.placeholder, secret = true)
            "number", "int", "integer", "float", "double" ->
                SettingItem.Entry(key, label, hint, value, spec.placeholder, numeric = true)
            "text", "string", "" ->
                SettingItem.Entry(key, label, hint, value, spec.placeholder, multiline = true)
            "textarea", "multiline" ->
                SettingItem.Entry(key, label, hint, value, spec.placeholder, multiline = true)
            "select", "enum", "choice", "radio" -> SettingItem.Choice(key, label, hint, value, spec.options)
            "slider", "range" -> sliderOf(key, label, hint, value, spec)
            "action", "button", "reset" -> SettingItem.Action(
                key = key,
                label = label,
                target = spec.target.trim(),
                default = spec.target.trim().takeIf { it.isNotBlank() }
                    ?.let { t -> config.firstOrNull { it.key == t }?.default },
            )
            "info", "note", "hint", "description", "help", "heading", "title" ->
                SettingItem.Info(key, label, hint)
            else -> {
                unsupported += key + ":" + spec.type
                SettingItem.Info(
                    key = key,
                    label = label,
                    hint = copy(R.string.plugins_schema_unsupported, listOf(spec.type)),
                )
            }
        }
    }
    return SettingsSchema(items, unsupported)
}

/** 滑杆边界：只认插件自己声明的 min/max；max<=min 时按 min+100 兜底，避免除零与零宽度滑杆。 */
private fun sliderOf(
    key: String,
    label: String,
    hint: String,
    raw: String,
    spec: ConfigSpec,
): SettingItem.Slider {
    val min = (spec.min ?: 0.0).toFloat()
    val max = (spec.max ?: 100.0).toFloat().takeIf { it > min } ?: (min + 100f)
    val value = raw.toFloatOrNull()?.coerceIn(min, max) ?: min
    val step = spec.step?.toFloat()?.takeIf { it > 0f }
    val steps = step?.let { (((max - min) / it).roundToInt() - 1).coerceAtLeast(0) } ?: 0
    return SettingItem.Slider(key, label, hint, value, min, max, steps)
}

/** 滑杆读数：整数不带小数点，避免「0.5000000001」这种脏显示。 */
fun sliderText(item: SettingItem.Slider, value: Float = item.value): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.2f", value)
