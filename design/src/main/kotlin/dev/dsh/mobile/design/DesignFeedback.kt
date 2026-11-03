// 反馈态：空状态与运行状态胶囊/圆点。
package dev.dsh.mobile.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.SessionStatus

/** Runtime state pill: green ready / amber working / red failed / grey unconfigured. */
@Composable
fun StatusCapsule(state: RuntimeState, onClick: () -> Unit = {}) {
    val p = LocalQq.current
    val (label, color) = when (state) {
        is RuntimeState.Ready -> "就绪" to p.green
        is RuntimeState.Starting -> "启动中" to p.amber
        is RuntimeState.Reconnecting -> "重连 ${state.attempt}" to p.amber
        is RuntimeState.Failed -> "离线" to p.red
        RuntimeState.NotConfigured -> "未配置" to p.t3
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(QqShape.pill()).background(color))
        Text(label, color = color, fontSize = qqFontBody(10.5.sp), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 会话运行态圆点：蓝=运行中（呼吸）、橙=待选择、绿=做完、红=意外中断。
 * 列表和聊天头部共用，颜色语义只在这里定义一次。
 */
@Composable
fun SessionStatusDot(status: SessionStatus, size: Dp = 8.dp) {
    val p = LocalQq.current
    val color = when (status) {
        SessionStatus.Running -> p.accent
        SessionStatus.WaitingPermission -> p.warn
        SessionStatus.Failed -> p.red
        SessionStatus.Idle -> p.green
    }
    val alpha = if (status == SessionStatus.Running) {
        rememberInfiniteTransition(label = "statusDot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "statusDotAlpha",
        ).value
    } else {
        1f
    }
    // 用 Canvas 画而不是 Box+background：状态点只有 8dp，夹在文字行里时
    // 各种父布局的 clip / 圆角裁剪会把它整块裁掉（以前就是这么消失的）。
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawCircle(color = color, radius = this.size.minDimension / 2f, alpha = alpha)
    }
}

@Composable
fun RunPill(text: String) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(p.accent.copy(alpha = 0.1f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(QqShape.pill()).background(p.accent))
        Text(text, color = p.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 空状态：主副文案，可选图标与动作。
 *
 * 历史上是「无图标」和「带图标」两个同名重载：调用点一半用位置参数（EmptyState(a, b)），
 * 一半用命名参数（icon = ... / title = ...）。合并成一个时把 icon 排到 title/subtitle 之后，
 * 位置调用照旧解析、命名调用一个都不用改。icon 为 null 走无图标旧版式（32/56 内边距、
 * 15sp t2 标题），非 null 走带图标版式（28/48、18sp t1 标题）——两边像素与合并前一致。
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 42.dp,
) {
    val p = LocalQq.current
    if (icon == null) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = p.t2, fontSize = qqFontBody(15.sp), fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = p.t3, fontSize = qqFontBody(13.sp))
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = p.t3, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(14.dp))
        Text(title, color = p.t1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = p.t3, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel, color = p.accent) }
        }
    }
}
