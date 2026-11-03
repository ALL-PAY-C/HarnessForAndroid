package dev.dsh.mobile.pluginapi

/**
 * 声明式视图协议 v1（T6.3）：插件交数据，宿主画界面。
 *
 * 与 [PluginUiNode] 的关系：那一套是**进程内** Kotlin 插件用的渲染接口（插件能拿到
 * SlotAction、能写 Markdown/HTML 原文），这一套是**跨进程/第三方包**用的**数据协议**——
 * 清单里的一节 JSON，宿主解析成 [PluginViewNode] 再用宿主自己的 Compose 组件画。
 * 两条路并存，互不影响：老插件继续用 [PluginUiNode] / content 直画。
 *
 * 三条硬规则（宿主侧解析器 [PLUGIN_VIEW_VERSION] 同版本实现）：
 * 1. **不执行任何东西**：视图是纯数据，节点只有这里列出的 8 种，属性只认白名单键，
 *    没有表达式、没有脚本、没有反射入口；认不出的属性直接忽略。
 * 2. **未知节点降级不崩**：认不出的 `type` 渲染成一行灰占位文本
 *    （[UiTextEmphasis.Caption] + [UiColorRole.OnSurfaceVariant]），其余节点照画。
 * 3. **未知动作丢弃**：按钮只能写「动作 id」字符串；id 不在宿主给的已知集合里就把
 *    动作置空（按钮变灰不可点），并在降级清单里留一条，绝不猜一个动作去执行。
 *
 * 版本策略与 dsh-go 一致：协议版本只在**不兼容**时升；v1 内只加不改。宿主读到比自己新的
 * 版本时整体拒收（可读错误码 [PluginViewCode.VersionUnsupported]），而不是按 v1 硬解析 ——
 * 少画一块面板好过画错一块。
 */

/** 本端实现的视图协议版本。加节点类型/属性时同步 +1 并改 docs/PLUGINS.md。 */
const val PLUGIN_VIEW_VERSION: Int = 1

/**
 * 视图节点：封闭集合，8 种够用。
 *
 * 颜色只给语义角色（[UiColorRole]）、字号只给层级（[UiTextEmphasis]），真实色值/圆角由
 * 宿主按 [HostThemeTokens] 填 —— 插件拿不到具体像素，也就没法把界面做成另一套。
 *
 * 容器节点（[Row] / [Column] / [List]）的 `gap` 是宿主间距单位（dp），超范围由宿主夹紧。
 */
sealed interface PluginViewNode {

    /** 一行文字。 */
    data class Text(
        val text: String,
        val emphasis: UiTextEmphasis = UiTextEmphasis.Body,
        val colorRole: UiColorRole = UiColorRole.OnSurface,
    ) : PluginViewNode

    /** 小标签（状态、计数）。 */
    data class Chip(
        val text: String,
        val tone: UiBadgeTone = UiBadgeTone.Neutral,
    ) : PluginViewNode

    /**
     * 按钮。[actionId] 只是**动作 id**，不是回调也不是代码；点击后宿主走既有的
     * custom 动作通道（[SlotAction.Custom]）分发，与今天的 custom 插件同一个出口。
     * null = 不可点（动作没声明或 id 未知被丢弃）。
     */
    data class Button(
        val label: String,
        val actionId: String? = null,
        val style: UiButtonStyle = UiButtonStyle.Filled,
    ) : PluginViewNode

    /**
     * 图片。[source] 只作标识（包内相对路径或 http(s) URL），v1 宿主**不解码也不联网**，
     * 只按 [description] 画一个占位块 —— 避免插件靠一张图把宿主变成外网请求发起方。
     */
    data class Image(
        val source: String,
        val description: String = "",
    ) : PluginViewNode

    /** 横向排列。 */
    data class Row(
        val children: kotlin.collections.List<PluginViewNode> = emptyList(),
        val gap: Int = 8,
    ) : PluginViewNode

    /** 纵向排列（也是视图的默认根）。 */
    data class Column(
        val children: kotlin.collections.List<PluginViewNode> = emptyList(),
        val gap: Int = 8,
    ) : PluginViewNode

    /** 列表：[ordered] 为真时宿主在每项前加序号。 */
    data class List(
        val items: kotlin.collections.List<PluginViewNode> = emptyList(),
        val ordered: Boolean = false,
        val gap: Int = 4,
    ) : PluginViewNode

    /** 分隔线。 */
    data object Divider : PluginViewNode
}

/**
 * 一棵通过校验的视图树。
 *
 * [version] 是**插件写下的**协议版本（校验通过后必然 ≤ [PLUGIN_VIEW_VERSION]）；
 * 宿主渲染按本端版本走，插件不需要知道宿主版本。
 */
data class PluginViewSpec(
    val root: PluginViewNode,
    val version: Int = PLUGIN_VIEW_VERSION,
    val widthPolicy: WidthPolicy = WidthPolicy.Wrap,
    val scrollable: Boolean = false,
    val maxHeightDp: Int? = null,
)

/**
 * 视图硬配额。超限一律**整体拒收**（可读错误码），不做「截断后勉强画」——
 * 截断出来的界面和插件以为的样子不一致，比不画更糟。
 *
 * 默认值参照 dsh-go 的 `≤16 KiB` 序列化上限，其余三项按手机一屏的容量定：
 * 256 个节点 ≈ 一屏列表的 10 倍，12 层嵌套已经远超任何正常面板，512 字够一整段说明。
 */
data class PluginViewLimits(
    /** 视图 JSON 原文的 UTF-8 字节上限。 */
    val maxBytes: Int = 16 * 1024,
    /** 节点总数上限（含容器）。 */
    val maxNodes: Int = 256,
    /** 嵌套深度上限（根 = 1）。 */
    val maxDepth: Int = 12,
    /** 单个容器的直接子节点上限。 */
    val maxChildren: Int = 64,
    /** 单个字符串属性（文案/标签/地址）的字符上限。 */
    val maxStringLength: Int = 512,
)

/** 视图校验的错误码。全部是稳定字符串，便于插件作者与日志对齐。 */
object PluginViewCode {
    /** 视图不是一个 JSON 对象。 */
    const val NotObject: String = "view.not_object"

    /** 视图里没有可渲染的根节点。 */
    const val Empty: String = "view.empty"

    /** 原文超过 [PluginViewLimits.maxBytes]。 */
    const val TooLarge: String = "view.too_large"

    /** 节点总数超过 [PluginViewLimits.maxNodes]。 */
    const val TooManyNodes: String = "view.too_many_nodes"

    /** 嵌套深度超过 [PluginViewLimits.maxDepth]。 */
    const val TooDeep: String = "view.too_deep"

    /** 单个容器子节点超过 [PluginViewLimits.maxChildren]。 */
    const val TooManyChildren: String = "view.too_many_children"

    /** 字符串属性超过 [PluginViewLimits.maxStringLength]。 */
    const val StringTooLong: String = "view.string_too_long"

    /** 声明的协议版本高于本端。 */
    const val VersionUnsupported: String = "view.version_unsupported"

    /** 版本字段不是正整数。 */
    const val VersionInvalid: String = "view.version_invalid"

    /** 降级（不是拒收）：认不出的节点类型。 */
    const val UnknownNode: String = "view.unknown_node"

    /** 降级（不是拒收）：认不出/未声明的动作 id，动作被丢弃。 */
    const val UnknownAction: String = "view.unknown_action"
}

/**
 * 一条降级记录：视图整体还能画，但这一处按规则退化处理了。
 *
 * [path] 是节点在树里的位置（`/root/children/2`），插件作者照着就能定位。
 */
data class PluginViewIssue(
    val code: String,
    val path: String,
    val detail: String,
) {
    /** 给人看的一行（插件详情页/日志直接显示这句）。 */
    fun message(): String {
        val head = when (code) {
            PluginViewCode.UnknownNode -> "视图节点类型未知，已画成灰占位：" + detail
            PluginViewCode.UnknownAction -> "视图动作未声明，已丢弃：" + detail
            else -> detail
        }
        return head + "（" + path + "）"
    }
}

/** 一次整体拒收：视图不画，原因给用户看。 */
data class PluginViewRejection(
    val code: String,
    val path: String,
    val detail: String,
) {
    /** 给人看的一行，带错误码。 */
    fun message(): String {
        val head = when (code) {
            PluginViewCode.TooLarge -> "视图过大：" + detail
            PluginViewCode.TooManyNodes -> "视图节点过多：" + detail
            PluginViewCode.TooDeep -> "视图层级过深：" + detail
            PluginViewCode.TooManyChildren -> "单个容器的子节点过多：" + detail
            PluginViewCode.StringTooLong -> "视图文本过长：" + detail
            PluginViewCode.VersionUnsupported -> "视图协议版本高于本端：" + detail
            PluginViewCode.VersionInvalid -> "视图协议版本非法：" + detail
            PluginViewCode.NotObject -> "视图不是 JSON 对象：" + detail
            PluginViewCode.Empty -> "视图为空：" + detail
            else -> detail
        }
        return head + "（" + path + "）"
    }
}
