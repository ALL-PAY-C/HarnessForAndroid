package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.PluginViewNode
import dev.dsh.mobile.pluginapi.PluginViewSpec
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.UiColorRole
import dev.dsh.mobile.pluginapi.UiTextEmphasis

/**
 * 声明式视图 → 宿主既有 [PluginUiNode] 树的映射。
 *
 * 之所以落成一次纯翻译、而不是另写一套 Compose 渲染：`PluginUiTranslator` 已经把
 * [PluginUiSpec] 画成了完整的界面（主题、宽度策略、滚动、动作回调），新协议只要把
 * 数据搬进同一棵树，就自动继承了所有既有行为与降级路径，零新增 Compose 代码。
 *
 * 映射表（**没有一项会执行插件给的东西**）：
 * | 视图节点 | 宿主节点 |
 * | --- | --- |
 * | Text | Text（emphasis / colorRole 原样带过去） |
 * | Chip | Badge（tone 原样） |
 * | Button | Button（actionId → [SlotAction.Custom]，走既有 custom 通道） |
 * | Image | Text 占位（宿主不加载远程图，只显示描述/来源） |
 * | Row / Column | Row / Column（gap 夹紧在 0..64） |
 * | List | Column（ordered 时每项前面加序号） |
 * | Divider | Divider |
 */
object PluginViewAdapter {

    /** 视图规格 → 既有渲染管线的规格。 */
    fun toUiSpec(view: PluginViewSpec): PluginUiSpec = PluginUiSpec(
        root = toUiNode(view.root),
        widthPolicy = view.widthPolicy,
        scrollable = view.scrollable,
        maxHeightDp = view.maxHeightDp,
    )

    fun toUiNode(node: PluginViewNode): PluginUiNode = when (node) {
        is PluginViewNode.Text -> PluginUiNode.Text(node.text, node.emphasis, node.colorRole)
        is PluginViewNode.Chip -> PluginUiNode.Badge(node.text, node.tone)
        is PluginViewNode.Button -> PluginUiNode.Button(
            label = node.label,
            // 动作只认 id：点击后由宿主 dispatch 成 SlotAction.Custom，插件在自己的
            // command 通道里接，不存在「视图里塞一段代码」的可能。
            action = node.actionId?.let { SlotAction.Custom(it) },
            style = node.style,
        )
        is PluginViewNode.Image -> PluginUiNode.Text(
            text = imageLabel(node),
            emphasis = UiTextEmphasis.Caption,
            colorRole = UiColorRole.OnSurfaceVariant,
        )
        is PluginViewNode.Row -> PluginUiNode.Row(node.children.map(::toUiNode), node.gap)
        is PluginViewNode.Column -> PluginUiNode.Column(node.children.map(::toUiNode), node.gap)
        is PluginViewNode.List -> PluginUiNode.Column(
            children = if (node.ordered) {
                node.items.mapIndexed { index, item ->
                    PluginUiNode.Row(
                        children = listOf(
                            PluginUiNode.Text(
                                text = (index + 1).toString() + ".",
                                emphasis = UiTextEmphasis.Label,
                                colorRole = UiColorRole.OnSurfaceVariant,
                            ),
                            toUiNode(item),
                        ),
                        gap = node.gap,
                    )
                }
            } else {
                node.items.map(::toUiNode)
            },
            gap = node.gap,
        )
        PluginViewNode.Divider -> PluginUiNode.Divider
    }

    private fun imageLabel(image: PluginViewNode.Image): String {
        val description = image.description.trim()
        if (description.isNotEmpty()) return "（图片：" + description + "）"
        val source = image.source.trim()
        return if (source.isEmpty()) "（图片）" else "（图片：" + source + "）"
    }
}
