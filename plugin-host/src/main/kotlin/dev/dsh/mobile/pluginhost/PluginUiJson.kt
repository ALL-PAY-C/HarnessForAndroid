package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginUiMenuEntry
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiButtonStyle
import dev.dsh.mobile.pluginapi.UiColorRole
import dev.dsh.mobile.pluginapi.UiTextEmphasis
import dev.dsh.mobile.pluginapi.WidthPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * plugin.json 里的声明式 UI 子树 → [PluginUiSpec]。
 *
 * 第三方插件大多是给别的桌面端 DSH 写的，它们的 plugin.json 里带的是自己的
 * HTML/小程序式描述。宿主读进来以后统一翻译成 [PluginUiNode]，再由
 * PluginUiHost 用宿主自己的主题、字号、圆角画出来 —— 这就是「装上即适配 UI」：
 * 插件不需要认识 Compose，也不需要打包任何资源。
 *
 * 支持的节点（`type` 字段，大小写不敏感）：
 * text / label / paragraph / heading、button / action、iconButton、toggle / switch、
 * slider / range、textField / input、row / hstack、column / vstack、card、section、
 * divider / hr、spacer / gap、badge / tag、menu / dropdown。
 * 未知节点按可读文本降级，绝不抛异常 —— 装一个写错的插件只应该少一个按钮。
 */
object PluginUiJson {

    fun spec(json: JSONObject?): PluginUiSpec? {
        if (json == null) return null
        val rootJson = json.optJSONObject("root") ?: json
        val root = node(rootJson) ?: return null
        return PluginUiSpec(
            root = root,
            widthPolicy = when (json.optString("width").lowercase()) {
                "fill" -> WidthPolicy.Fill
                "clamp" -> WidthPolicy.Clamp
                else -> WidthPolicy.Wrap
            },
            scrollable = json.optBoolean("scrollable", false),
            maxHeightDp = json.optInt("maxHeight", 0).takeIf { it > 0 },
        )
    }

    fun node(json: JSONObject?): PluginUiNode? {
        if (json == null) return null
        val type = typeKey(json.optString("type"))
        val label = json.optString("label").ifBlank { json.optString("text") }
        return when (type) {
            // 富内容：桌面端插件把 markdown / html / code 当成叶子节点。只存原文，
            // 排版与安全边界都留在宿主的渲染层（见 PluginUiTranslator 的对应分支）。
            "markdown", "md", "richtext" -> PluginUiNode.Markdown(
                text = json.optString("text").ifBlank { json.optString("markdown") },
            )
            "html", "htm", "rawhtml" -> PluginUiNode.Html(
                html = json.optString("html").ifBlank { json.optString("text") },
            )
            "code", "pre", "codeblock", "snippet" -> PluginUiNode.Code(
                text = json.optString("text").ifBlank { json.optString("code") },
                lang = json.optString("lang").ifBlank { json.optString("language") },
            )
            "text", "label", "paragraph", "heading", "title", "caption", "" ->
                PluginUiNode.Text(
                    text = label,
                    emphasis = when (type) {
                        "heading", "title" -> UiTextEmphasis.Title
                        "caption" -> UiTextEmphasis.Caption
                        else -> emphasisOf(json.optString("emphasis"))
                    },
                    colorRole = colorOf(json.optString("color")) ?: UiColorRole.OnSurface,
                )
            "button", "action" -> PluginUiNode.Button(
                label = label.ifBlank { "执行" },
                action = action(json.optJSONObject("action")) ?: SlotAction.Custom(json.optString("id")),
                style = when (json.optString("style").lowercase()) {
                    "tonal" -> UiButtonStyle.Tonal
                    "text", "plain" -> UiButtonStyle.Text
                    "outlined", "outline" -> UiButtonStyle.Outlined
                    else -> UiButtonStyle.Filled
                },
            )
            "iconbutton", "icon" -> PluginUiNode.IconButton(
                icon = json.optString("icon").ifBlank { "star" },
                label = label,
                action = action(json.optJSONObject("action")) ?: SlotAction.Custom(json.optString("id")),
            )
            "toggle", "switch", "checkbox" -> PluginUiNode.Toggle(
                label = label,
                checked = json.optBoolean("checked", json.optBoolean("value", false)),
                action = action(json.optJSONObject("action")) ?: SlotAction.Custom(json.optString("id")),
            )
            "slider", "range" -> {
                val min = json.optDouble("min", 0.0).toFloat()
                val max = json.optDouble("max", 1.0).toFloat().takeIf { it > min } ?: (min + 1f)
                PluginUiNode.Slider(
                    label = label,
                    value = json.optDouble("value", min.toDouble()).toFloat().coerceIn(min, max),
                    range = min..max,
                    action = action(json.optJSONObject("action")) ?: SlotAction.Custom(json.optString("id")),
                )
            }
            "textfield", "input", "editor" -> PluginUiNode.TextField(
                label = label,
                value = json.optString("value"),
                placeholder = json.optString("placeholder"),
                action = action(json.optJSONObject("action")) ?: SlotAction.Custom(json.optString("id")),
            )
            "row", "hstack", "horizontal" -> PluginUiNode.Row(
                children = children(json.optJSONArray("children") ?: json.optJSONArray("items")),
                gap = json.optInt("gap", 8),
            )
            // 桌面端还会用 panel / view / container / stack 当纵向容器：语义等同 column。
            "column", "vstack", "vertical", "panel", "view", "container", "stack", "box", "div" ->
                PluginUiNode.Column(
                children = children(json.optJSONArray("children") ?: json.optJSONArray("items")),
                gap = json.optInt("gap", 8),
            )
            "card" -> PluginUiNode.Card(
                title = json.optString("title").ifBlank { label },
                children = children(json.optJSONArray("children") ?: json.optJSONArray("items")),
            )
            "section", "group" -> PluginUiNode.Section(
                title = json.optString("title").ifBlank { label },
                subtitle = json.optString("subtitle"),
                children = children(json.optJSONArray("children") ?: json.optJSONArray("items")),
            )
            "divider", "hr", "line" -> PluginUiNode.Divider
            "spacer", "gap", "space" -> PluginUiNode.Spacer(size = json.optInt("size", 8))
            "badge", "tag", "chip" -> PluginUiNode.Badge(
                text = label,
                tone = when (json.optString("tone").lowercase()) {
                    "info" -> UiBadgeTone.Info
                    "success", "ok" -> UiBadgeTone.Success
                    "warning", "warn" -> UiBadgeTone.Warning
                    "danger", "error" -> UiBadgeTone.Danger
                    else -> UiBadgeTone.Neutral
                },
            )
            "menu", "dropdown", "list" -> PluginUiNode.Menu(
                items = (json.optJSONArray("items") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val it2 = arr.optJSONObject(i) ?: return@mapNotNull null
                        PluginUiMenuEntry(
                            label = it2.optString("label").ifBlank { it2.optString("text") },
                            action = action(it2.optJSONObject("action"))
                                ?: SlotAction.Custom(it2.optString("id")),
                        )
                    }
                },
            )
            else -> {
                // 认不出的类型（桌面端最常见的 "reactComponent"）：React 组件树没法逐节点
                // 翻译成宿主节点 —— 它的 props/样式树在本端没有对应物。此时只做两种降级：
                // ① 若它还带子节点，保留子节点（别把整块界面丢掉）；② 否则给一行可读文本。
                val kids = children(json.optJSONArray("children") ?: json.optJSONArray("items"))
                when {
                    kids.isNotEmpty() -> PluginUiNode.Column(kids)
                    label.isNotBlank() -> PluginUiNode.Text(label)
                    else -> PluginUiNode.Text(
                        text = "（不支持的界面节点：$type）",
                        emphasis = UiTextEmphasis.Caption,
                        colorRole = UiColorRole.OnSurfaceVariant,
                    )
                }
            }
        }
    }

    /**
     * 桌面端写法归一：`IconButton` / `icon-button` / `icon_button` / ` Icon Button ` 全等价。
     *
     * 只做「大小写 + 分隔符」这一层归一，不做同义词猜测 —— 认不出的词仍旧走下面的
     * 降级分支，插件写错了也不会把宿主炸掉。
     */
    internal fun typeKey(raw: String): String = raw.lowercase().filter { it != '-' && it != '_' && !it.isWhitespace() }

    private fun children(arr: JSONArray?): List<PluginUiNode> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> node(arr.optJSONObject(i)) }
    }

    private fun emphasisOf(raw: String): UiTextEmphasis = when (raw.lowercase()) {
        "display" -> UiTextEmphasis.Display
        "title" -> UiTextEmphasis.Title
        "subtitle" -> UiTextEmphasis.Subtitle
        "label" -> UiTextEmphasis.Label
        "caption" -> UiTextEmphasis.Caption
        else -> UiTextEmphasis.Body
    }

    private fun colorOf(raw: String): UiColorRole? = when (raw.lowercase()) {
        "primary", "accent" -> UiColorRole.Primary
        "error", "danger" -> UiColorRole.Error
        "variant", "secondary", "muted" -> UiColorRole.OnSurfaceVariant
        "outline", "line" -> UiColorRole.Outline
        "on-surface", "text" -> UiColorRole.OnSurface
        else -> null
    }

    fun action(json: JSONObject?): SlotAction? {
        if (json == null) return null
        val kind = json.optString("kind").ifBlank { json.optString("type") }.lowercase()
        return when (kind) {
            "prompt", "send" -> SlotAction.Prompt(
                text = json.optString("text").ifBlank { json.optString("value") },
                submit = json.optBoolean("submit", true),
            )
            "http", "request" -> SlotAction.Http(
                method = json.optString("method").ifBlank { "GET" },
                url = json.optString("url"),
                body = json.optString("body"),
            )
            "link", "uri", "open" -> SlotAction.Link(json.optString("uri").ifBlank { json.optString("url") })
            "page", "navigate" -> SlotAction.Page(json.optString("pageId").ifBlank { json.optString("id") })
            "custom" -> SlotAction.Custom(json.optString("custom").ifBlank { json.optString("id") })
            else -> null
        }
    }
}