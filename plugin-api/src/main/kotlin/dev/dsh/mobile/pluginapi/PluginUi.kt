package dev.dsh.mobile.pluginapi

/**
 * 声明式 UI 节点树（需求 7）。
 *
 * 插件跑在 App 进程里，直接写 Compose 就能硬编码颜色、把界面做得和 App 毫无关系。
 * 这里把「能画什么」收敛成封闭的节点集合：插件只描述意图，颜色只给语义角色
 * [UiColorRole]，真实色值/圆角/字号由宿主按当前主题 tokens 填。
 * 旧插件继续用 SlotContribution.content 直接画，两条路并存。
 */

/** 语义颜色角色。插件永远拿不到具体色值，杜绝颜色穿透。 */
enum class UiColorRole { Primary, OnSurface, OnSurfaceVariant, Outline, Error }

/** 文字层级，映射到宿主 typography。 */
enum class UiTextEmphasis { Display, Title, Subtitle, Body, Label, Caption }

enum class UiButtonStyle { Filled, Tonal, Text, Outlined }

enum class UiBadgeTone { Neutral, Info, Success, Warning, Danger }

/** 宿主宽度策略：Wrap 按内容、Fill 撑满、Clamp 撑满但封顶。 */
enum class WidthPolicy { Wrap, Fill, Clamp }

sealed interface PluginUiNode {

    data class Text(
        val text: String,
        val emphasis: UiTextEmphasis = UiTextEmphasis.Body,
        val colorRole: UiColorRole = UiColorRole.OnSurface,
    ) : PluginUiNode

    data class Button(
        val label: String,
        val action: SlotAction? = null,
        val style: UiButtonStyle = UiButtonStyle.Filled,
    ) : PluginUiNode

    data class IconButton(
        val icon: String,
        val label: String,
        val action: SlotAction? = null,
    ) : PluginUiNode

    data class Toggle(
        val label: String,
        val checked: Boolean,
        val action: SlotAction? = null,
    ) : PluginUiNode

    data class Slider(
        val label: String,
        val value: Float,
        val range: ClosedFloatingPointRange<Float> = 0f..1f,
        val action: SlotAction? = null,
    ) : PluginUiNode

    data class TextField(
        val label: String,
        val value: String,
        val placeholder: String = "",
        val action: SlotAction? = null,
    ) : PluginUiNode

    data class Row(val children: List<PluginUiNode> = emptyList(), val gap: Int = 8) : PluginUiNode

    data class Column(val children: List<PluginUiNode> = emptyList(), val gap: Int = 8) : PluginUiNode

    data class Card(val title: String = "", val children: List<PluginUiNode> = emptyList()) : PluginUiNode

    data class Section(
        val title: String,
        val subtitle: String = "",
        val children: List<PluginUiNode> = emptyList(),
    ) : PluginUiNode

    data object Divider : PluginUiNode

    data class Spacer(val size: Int = 8) : PluginUiNode

    data class Badge(val text: String, val tone: UiBadgeTone = UiBadgeTone.Neutral) : PluginUiNode

    data class Menu(val items: List<PluginUiMenuEntry> = emptyList()) : PluginUiNode

    /**
     * 富文本：桌面插件的 `markdown` 节点。
     *
     * 节点里只存原文，解析/排版全交给宿主（:render 的 Markdown），插件拿不到样式，
     * 也就没法把界面做成另一套。没有 :render 时由渲染层降级成纯文本。
     */
    data class Markdown(val text: String) : PluginUiNode

    /**
     * 原样 HTML。宿主装了 dsh.html 插件就交给 WebView 真渲染，
     * 没装则退回源码/纯文本视图 —— 与 ```html 围栏走同一条 [HtmlBlockRenderers] 通道。
     */
    data class Html(val html: String) : PluginUiNode

    /** 等宽代码块。不做语法高亮：[lang] 只用于将来加高亮时挑词法，当前仅作标识。 */
    data class Code(val text: String, val lang: String = "") : PluginUiNode
}

/** 菜单项。单独取名，避免和 material3 的 MenuItem 撞名。 */
data class PluginUiMenuEntry(val label: String, val action: SlotAction? = null)

data class PluginUiSpec(
    val root: PluginUiNode,
    val widthPolicy: WidthPolicy = WidthPolicy.Wrap,
    val scrollable: Boolean = false,
    val maxHeightDp: Int? = null,
)

/**
 * 宿主主题变量快照。颜色一律 ARGB Long —— plugin-api 不依赖 Compose，
 * 插件拿不到 Color/CompositionLocal，也就没法绕过主题。
 */
data class HostThemeTokens(
    val cornerRadiusDp: Float,
    val fontScale: Float,
    val densityScale: Float,
    val primary: Long,
    val surface: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val spacingDp: Float,
    /** 语义 Error 色；material3 的 error 不在上面 9 个槽位里，单独带一个默认值。 */
    val error: Long = 0xFFB3261E,
)
