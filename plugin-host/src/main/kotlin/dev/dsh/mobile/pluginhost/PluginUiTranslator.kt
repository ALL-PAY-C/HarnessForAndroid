package dev.dsh.mobile.pluginhost

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.pluginapi.HostThemeTokens
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiButtonStyle
import dev.dsh.mobile.pluginapi.UiColorRole
import dev.dsh.mobile.pluginapi.UiTextEmphasis
import dev.dsh.mobile.pluginapi.WidthPolicy
import dev.dsh.mobile.pluginapi.HtmlBlockRenderers
import dev.dsh.mobile.render.ChatContent
import dev.dsh.mobile.render.HtmlRenderer

/**
 * UI 转译层（需求 7）：把 [PluginUiSpec] 的节点树翻译成宿主主题下的 Compose 组件。
 *
 * 三条铁律：
 * 1. 插件只给语义角色，色值/圆角/字号全部来自 [HostThemeTokens]；
 * 2. 旧插件（content 直画）也进同一个容器，并通过 MaterialTheme 覆写拿到同一套
 *    colorScheme/typography/shapes —— 插件界面自动贴合 App 风格，不用改插件；
 * 3. 任何坏数据都降级：节点解析失败只丢那个节点，不炸宿主。
 */

/** 宿主容器：安全区内边距 + 圆角表面 + 内边距 + 可选滚动/最大高度。 */
@Composable
fun HostContainer(
    tokens: HostThemeTokens,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    maxHeightDp: Int? = null,
    /** 整屏使用时吃安全区；嵌在已有列表/菜单里的槽位行传 false，避免双重内边距。 */
    insets: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(tokens.cornerRadiusDp.dp)
    var m = modifier
        .let { if (insets) it.windowInsetsPadding(WindowInsets.safeDrawing) else it }
        .shadow(elevation = 1.dp, shape = shape)
        .clip(shape)
        .background(Color(tokens.surface))
        .padding(tokens.spacingDp.dp)
    if (maxHeightDp != null) m = m.heightIn(max = maxHeightDp.dp)
    if (scrollable) m = m.verticalScroll(rememberScrollState())
    Column(modifier = m, content = content)
}

/**
 * 槽位渲染入口。spec 非空走声明式翻译；否则把 legacy 包进同一个容器。
 * [legacy] 与 [spec] 都为空时什么也不画（空槽位由调用方决定怎么表达）。
 */
@Composable
fun PluginUiHost(
    spec: PluginUiSpec?,
    tokens: HostThemeTokens,
    onAction: (SlotAction) -> Unit,
    legacy: (@Composable () -> Unit)? = null,
    insets: Boolean = true,
) {
    if (spec == null && legacy == null) return
    ThemedSurface(tokens) {
        HostContainer(
            tokens = tokens,
            modifier = widthModifier(spec?.widthPolicy ?: WidthPolicy.Wrap),
            scrollable = spec?.scrollable ?: false,
            maxHeightDp = spec?.maxHeightDp,
            insets = insets,
        ) {
            when {
                spec != null -> NodeView(spec.root, tokens, onAction)
                legacy != null -> legacy()
            }
        }
    }
}

/**
 * 一个槽位贡献的统一渲染点：有声明式 ui 就用 ui，否则用插件自带的 content，
 * 再否则用 label 生成一行。ChatScreen 的 overflowSlots / AppNav 的槽位消费点
 * 都调这一个函数即可，不需要知道插件用的是哪条路。
 */
@Composable
fun PluginSlotItem(
    contribution: SlotContribution,
    tokens: HostThemeTokens,
    onAction: (SlotAction) -> Unit,
) {
    val legacy: (@Composable () -> Unit) = contribution.content ?: { Text(contribution.label) }
    PluginUiHost(contribution.ui, tokens, onAction, legacy = legacy, insets = false)
}

/** 从当前 MaterialTheme + 系统密度提取宿主主题变量；读不到就退回默认值。 */
@Composable
fun rememberHostThemeTokens(): HostThemeTokens {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    return remember(
        density.density,
        density.fontScale,
        scheme.primary,
        scheme.surface,
        scheme.onSurface,
        scheme.onSurfaceVariant,
        scheme.outline,
        scheme.error,
        shapes.medium,
    ) {
        HostThemeTokens(
            cornerRadiusDp = cornerRadiusDp(shapes.medium, density),
            fontScale = density.fontScale.coerceIn(0.7f, 1.6f),
            densityScale = density.density,
            primary = scheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
            surface = scheme.surface.toArgb().toLong() and 0xFFFFFFFFL,
            onSurface = scheme.onSurface.toArgb().toLong() and 0xFFFFFFFFL,
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
            outline = scheme.outline.toArgb().toLong() and 0xFFFFFFFFL,
            spacingDp = 8f,
            error = scheme.error.toArgb().toLong() and 0xFFFFFFFFL,
        )
    }
}

// ------------------------------------------------------------------ internals

/** 用 tokens 覆写 MaterialTheme：旧插件直画的界面也跟着变。 */
@Composable
private fun ThemedSurface(tokens: HostThemeTokens, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            primary = Color(tokens.primary),
            surface = Color(tokens.surface),
            onSurface = Color(tokens.onSurface),
            onSurfaceVariant = Color(tokens.onSurfaceVariant),
            outline = Color(tokens.outline),
            error = Color(tokens.error),
        ),
        typography = scaledTypography(MaterialTheme.typography, tokens.fontScale),
        shapes = hostShapes(tokens.cornerRadiusDp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(tokens.onSurface)) {
            content()
        }
    }
}

private fun hostShapes(radiusDp: Float): Shapes {
    val r = radiusDp.dp
    return Shapes(
        extraSmall = RoundedCornerShape(r / 2),
        small = RoundedCornerShape(r),
        medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r * 1.5f),
        extraLarge = RoundedCornerShape(r * 2),
    )
}

private fun scaledTypography(base: Typography, fontScale: Float): Typography {
    if (fontScale <= 0f || fontScale == 1f) return base
    fun TextStyle.scaled(): TextStyle = copy(fontSize = fontSize * fontScale)
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

private fun cornerRadiusDp(shape: Shape, density: Density): Float {
    val based = shape as? CornerBasedShape ?: return 16f
    val px = runCatching { based.topStart.toPx(Size.Zero, density) }.getOrDefault(0f)
    val dp = with(density) { px.toDp().value }
    return if (dp > 0f) dp else 16f
}

private fun widthModifier(policy: WidthPolicy): Modifier = when (policy) {
    WidthPolicy.Wrap -> Modifier.wrapContentWidth()
    WidthPolicy.Fill -> Modifier.fillMaxWidth()
    WidthPolicy.Clamp -> Modifier.fillMaxWidth().widthIn(max = 420.dp)
}

@Composable
private fun styleFor(emphasis: UiTextEmphasis): TextStyle {
    val t = MaterialTheme.typography
    return when (emphasis) {
        UiTextEmphasis.Display -> t.headlineMedium
        UiTextEmphasis.Title -> t.titleMedium
        UiTextEmphasis.Subtitle -> t.titleSmall
        UiTextEmphasis.Body -> t.bodyMedium
        UiTextEmphasis.Label -> t.labelLarge
        UiTextEmphasis.Caption -> t.labelSmall
    }
}

private fun colorFor(role: UiColorRole, tokens: HostThemeTokens): Color = when (role) {
    UiColorRole.Primary -> Color(tokens.primary)
    UiColorRole.OnSurface -> Color(tokens.onSurface)
    UiColorRole.OnSurfaceVariant -> Color(tokens.onSurfaceVariant)
    UiColorRole.Outline -> Color(tokens.outline)
    UiColorRole.Error -> Color(tokens.error)
}

private fun toneColor(tone: UiBadgeTone, tokens: HostThemeTokens): Color = when (tone) {
    UiBadgeTone.Neutral -> Color(tokens.onSurfaceVariant)
    UiBadgeTone.Info -> Color(tokens.primary)
    UiBadgeTone.Success -> Color(0xFF2E7D32)
    UiBadgeTone.Warning -> Color(0xFFB26A00)
    UiBadgeTone.Danger -> Color(tokens.error)
}

private fun iconOf(name: String): ImageVector = when (name.lowercase()) {
    "add", "plus" -> Icons.Filled.Add
    "check", "done", "ok" -> Icons.Filled.Check
    "close", "cancel" -> Icons.Filled.Close
    "delete", "trash" -> Icons.Filled.Delete
    "edit", "pencil" -> Icons.Filled.Edit
    "search" -> Icons.Filled.Search
    "settings", "gear" -> Icons.Filled.Settings
    "star" -> Icons.Filled.Star
    "share" -> Icons.Filled.Share
    "send" -> Icons.Filled.Send
    "info" -> Icons.Filled.Info
    "list" -> Icons.AutoMirrored.Filled.List
    "home" -> Icons.Filled.Home
    "favorite", "heart" -> Icons.Filled.Favorite
    "warning" -> Icons.Filled.Warning
    "lock" -> Icons.Filled.Lock
    "person", "user" -> Icons.Filled.Person
    "play" -> Icons.Filled.PlayArrow
    "refresh", "reload" -> Icons.Filled.Refresh
    "mail", "email" -> Icons.Filled.MailOutline
    "more" -> Icons.Filled.MoreVert
    "menu" -> Icons.Filled.Menu
    "bell", "notifications" -> Icons.Filled.Notifications
    "back" -> Icons.AutoMirrored.Filled.ArrowBack
    "next", "chevron" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    else -> Icons.Filled.Info
}

private fun fire(action: SlotAction?, onAction: (SlotAction) -> Unit) {
    if (action != null) runCatching { onAction(action) }
}

/** 节点翻译。递归深度由插件决定，坏节点只丢自己。 */
@Composable
private fun NodeView(node: PluginUiNode, tokens: HostThemeTokens, onAction: (SlotAction) -> Unit) {
    when (node) {
        is PluginUiNode.Text -> Text(
            text = node.text,
            style = styleFor(node.emphasis),
            color = colorFor(node.colorRole, tokens),
        )

        is PluginUiNode.Button -> {
            val enabled = node.action != null
            val onClick = { fire(node.action, onAction) }
            when (node.style) {
                UiButtonStyle.Filled -> Button(onClick = onClick, enabled = enabled) { Text(node.label) }
                UiButtonStyle.Tonal -> FilledTonalButton(onClick = onClick, enabled = enabled) { Text(node.label) }
                UiButtonStyle.Text -> TextButton(onClick = onClick, enabled = enabled) { Text(node.label) }
                UiButtonStyle.Outlined -> OutlinedButton(onClick = onClick, enabled = enabled) { Text(node.label) }
            }
        }

        is PluginUiNode.IconButton -> IconButton(
            onClick = { fire(node.action, onAction) },
            enabled = node.action != null,
        ) {
            Icon(iconOf(node.icon), contentDescription = node.label)
        }

        is PluginUiNode.Toggle -> {
            // 状态归插件：这里只做乐观显示，点完回调动作，由插件重发新的 spec。
            var checked by remember(node.label, node.checked) { mutableStateOf(node.checked) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(tokens.onSurface),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        fire(node.action, onAction)
                    },
                )
            }
        }

        is PluginUiNode.Slider -> {
            var value by remember(node.label, node.value) { mutableStateOf(node.value) }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(tokens.onSurfaceVariant),
                )
                Slider(
                    value = value.coerceIn(node.range.start, node.range.endInclusive),
                    onValueChange = { value = it },
                    valueRange = node.range,
                    onValueChangeFinished = { fire(node.action, onAction) },
                )
            }
        }

        is PluginUiNode.TextField -> {
            var value by remember(node.label, node.value) { mutableStateOf(node.value) }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(node.label) },
                placeholder = if (node.placeholder.isBlank()) {
                    null
                } else {
                    { Text(node.placeholder) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { fire(node.action, onAction) }),
            )
        }

        is PluginUiNode.Row -> Row(
            horizontalArrangement = Arrangement.spacedBy(node.gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { NodeView(it, tokens, onAction) }
        }

        is PluginUiNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(node.gap.dp)) {
            node.children.forEach { NodeView(it, tokens, onAction) }
        }

        is PluginUiNode.Card -> Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(tokens.cornerRadiusDp.dp),
            colors = CardDefaults.cardColors(containerColor = Color(tokens.surface)),
        ) {
            Column(
                Modifier.padding(tokens.spacingDp.dp),
                verticalArrangement = Arrangement.spacedBy(tokens.spacingDp.dp),
            ) {
                if (node.title.isNotBlank()) {
                    Text(
                        node.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(tokens.onSurface),
                    )
                }
                node.children.forEach { NodeView(it, tokens, onAction) }
            }
        }

        is PluginUiNode.Section -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokens.spacingDp.dp / 2),
        ) {
            Text(
                node.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color(tokens.onSurface),
            )
            if (node.subtitle.isNotBlank()) {
                Text(
                    node.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(tokens.onSurfaceVariant),
                )
            }
            node.children.forEach { NodeView(it, tokens, onAction) }
        }

        PluginUiNode.Divider -> HorizontalDivider(color = Color(tokens.outline).copy(alpha = 0.4f))

        is PluginUiNode.Spacer -> Spacer(Modifier.size(node.size.dp))

        is PluginUiNode.Badge -> {
            val tone = toneColor(node.tone, tokens)
            Surface(
                shape = RoundedCornerShape(tokens.cornerRadiusDp.dp / 2),
                color = tone.copy(alpha = 0.14f),
                contentColor = tone,
            ) {
                Text(
                    node.text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // 富内容三种：都只吃原文，样式一律由宿主 style/tokens 决定。
        // markdown 走 :render 的块级渲染（表格/代码/数学/列表与聊天区完全一致，
        // html 围栏还能顺带拿到 HtmlBlockRenderers 的 WebView 通道）。
        is PluginUiNode.Markdown -> ChatContent(
            text = node.text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )

        // html 与 html 代码围栏共用一条通道：装了 dsh.html 就 WebView 真渲染，
        // 没装就退回 HtmlRenderer 的清洗文本，绝不把裸 HTML 当 Compose 处理。
        is PluginUiNode.Html -> {
            @Suppress("UNUSED_EXPRESSION") HtmlBlockRenderers.generation
            val web = HtmlBlockRenderers.render
            if (web != null) {
                web(node.html)
            } else {
                Text(
                    text = HtmlRenderer.toAnnotated(node.html, MaterialTheme.typography.bodyMedium),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 等宽纯文本，不做高亮（lang 只留作标识）。
        is PluginUiNode.Code -> Surface(
            shape = RoundedCornerShape(tokens.cornerRadiusDp.dp / 2),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = node.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(tokens.onSurface),
                modifier = Modifier.padding(10.dp),
            )
        }

        is PluginUiNode.Menu -> Column(Modifier.fillMaxWidth()) {
            node.items.forEachIndexed { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.action != null) { fire(item.action, onAction) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(tokens.onSurface),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index != node.items.lastIndex) {
                    HorizontalDivider(color = Color(tokens.outline).copy(alpha = 0.25f))
                }
            }
        }
    }
}
