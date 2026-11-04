// 行式组件：会话行、设置行、标签栏与滑动操作行。
package dev.dsh.mobile.design

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

/** One conversation row inside a grouped card. */
@Composable
fun ConvRow(
    session: Session,
    onClick: () -> Unit,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    timeText: String = "",
    showDivider: Boolean = true,
    extras: List<@Composable (Session) -> Unit> = emptyList(),
) {
    val p = LocalQq.current
    val tokens = LocalThemeTokens.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) p.accent.copy(alpha = 0.07f) else p.card)
                .clickable(onClick = onClick)
                // 「列表密度」滑块（tokens.density）以前只在设置里改数值、没有任何地方读它。
                .padding(
                    horizontal = if (tokens.compact) 10.dp else 13.dp,
                    vertical = (if (tokens.compact) 8.dp else 10.dp) * tokens.density,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(QqShape.pill())
                        .background(if (selected) p.accent else Color.Transparent)
                        .then(if (selected) Modifier else Modifier.background(p.card)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (selected) "✓" else "", color = Color.White, fontSize = qqFontBody(13.sp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Ava(
                seed = session.id,
                size = if (tokens.compact) 40.dp else 46.dp,
                label = session.title,
                online = session.status == SessionStatus.Running,
            )
            Spacer(Modifier.width(QqSpace.m()))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 四色状态点跟着标题走：蓝=运行中 / 橙=待选择 / 绿=做完 / 红=意外中断。
                    // 放在预览行末尾时会被右下角挤成一个孤立的小圆点，看不出属于哪一行。
                    SessionStatusDot(session.status, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        session.title.ifBlank { stringResource(R.string.common_untitled_session) },
                        color = p.t1,
                        fontSize = qqFontBody(15.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (session.isGroup) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(stringResource(R.string.design_group_members, session.memberCount), color = p.accent, fontSize = qqFontBody(9.5.sp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (session.pinned) {
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.common_pinned), color = p.t3, fontSize = qqFontBody(10.sp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (timeText.isNotBlank()) {
                        Text(timeText, color = p.t3, fontSize = qqFontBody(11.5.sp))
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.preview.ifBlank { stringResource(R.string.sessions_start_chat) },

                        color = p.t2,
                        fontSize = qqFontBody(13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        extras.forEach { it(session) }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().padding(start = 71.dp).height(1.dp).background(p.line))
        }
    }
}

/** Compatibility alias: older screens pass a trailing slot. */
@Composable
fun SessionRow(
    session: Session,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    ConvRow(session = session, onClick = onClick, extras = if (trailing == null) emptyList() else listOf { trailing() })
}

/** Bottom tab bar: icon + label, accent when active. */
data class TabSpec(
    val label: String?,
    val icon: ImageVector,
    @StringRes val labelRes: Int? = null,
) {
    /** 底部栏文字：内置 tab 走资源，插件 tab 只有运行期来的纯文本。 */
    @Composable
    fun title(): String = labelRes?.let { stringResource(it) } ?: label.orEmpty()
}

@Composable
fun QqTabBar(
    tabs: List<TabSpec>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onEditPlus: (() -> Unit)? = null,
) {
    val p = LocalQq.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    // UI 修改模式：底部栏上方多一条可横滑的位置条 + 「+」，挑组件放到底部栏。
    if (onEditPlus != null && SlotEditState.plusVisible(SlotEditState.Slot.BottomBar)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.card)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(SlotEditState.Slot.BottomBar.labelRes),
                color = p.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 6.dp),
            )
            SlotPlusButton(
                label = stringResource(R.string.common_edit) + stringResource(SlotEditState.Slot.BottomBar.labelRes),
                onClick = onEditPlus,
            )
        }
    }
    Column(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding()) {
        // 4 个 tab 单行等宽（插件 tab 多了就一起挤压，不再分第二行）。
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            tabs.forEachIndexed { index, tab -> TabItem(tab, index == selected) { onSelect(index) } }
        }
    }
}

/** 底部栏一格：图标 + 文字，宽度由外层 Row 的 weight(1f) 决定。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(tab: TabSpec, active: Boolean, onClick: () -> Unit) {
    val p = LocalQq.current
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(top = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            tab.icon,
            tab.title(),
            tint = if (active) p.accent else p.t3,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.title(),
            color = if (active) p.accent else p.t3,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Settings/agent row: 30dp tinted icon square + title/subtitle + trailing slot. */
@Composable
fun QqRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalQq.current
    // 行级不画描边：QqRow 通常嵌在 SectionCard 里，行框会落在卡片描边内侧变成双层框。
    // 描边由 SectionCard 这类卡片级容器统一消费 LocalSurfaceStyle。
    val tokens = LocalThemeTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            // 行高走 min：大字号下不裁字；纵向内边距跟「列表密度」滑块。
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp * tokens.density),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint ?: p.t2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(QqSpace.m()))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.t1, fontSize = qqFontBody(15.sp), fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = p.t3, fontSize = qqFontBody(11.5.sp))
            }
        }
        trailing?.invoke()
    }
}

/** One action revealed by swiping a row to the left. */
data class SwipeAction(
    val label: String,
    val color: Color,
    val onAction: () -> Unit,
)

/**
 * QQ-style swipe row: drag left to reveal [actions] behind the content.
 * Horizontal drags are consumed only past the touch slop, so vertical list scrolling keeps working.
 */
@Composable
fun SwipeableRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 66.dp,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    val density = LocalDensity.current
    val maxPx = with(density) { (actionWidth * actions.size).toPx() }
    var dragTarget by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offsetX by animateFloatAsState(
        targetValue = dragTarget,
        animationSpec = if (dragging) snap() else tween(200),
        label = "swipeOffset",
    )

    // 只有真的左滑时才把动作条画出来：卡片底色在玻璃 / 壁纸下是半透明的，
    // 常驻绘制会让动作色块和标签从卡片底下透出来，整屏看着像一堆彩色条带。
    val revealed = offsetX < -0.5f
    Box(modifier.fillMaxWidth()) {
        if (revealed) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            actions.forEach { action ->
                Box(
                    Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.color)
                        .clickable {
                            dragging = false
                            dragTarget = 0f
                            action.onAction()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            dragging = true
                            dragTarget = (dragTarget + dragAmount).coerceIn(-maxPx, 0f)
                        },
                        onDragEnd = {
                            dragging = false
                            dragTarget = if (dragTarget < -maxPx / 2f) -maxPx else 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragTarget = 0f
                        },
                    )
                },
        ) {
            Box(Modifier.background(p.card)) { content() }
        }
    }
}
