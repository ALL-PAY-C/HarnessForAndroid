package dev.dsh.mobile.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Agent 页每个区块的收纳卡片，视觉与设置页的 ExpandableSection 同一套：
 * 左侧 30dp 图标块 + 标题 / 副标题 + 右侧旋转箭头 + 整行可点 + 展开后顶部 1dp 分隔线。
 *
 * 卡片底色由 [SectionCard] 提供，调用方直接放内容（不要再套一层 SectionCard，
 * 否则白卡套白卡）。展开状态由调用方持有（页面级 rememberSaveable），这样
 * LazyColumn 回收 item、条件区块出现/消失都不会把别的区块的状态带错位。
 */
@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    tint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    val arrow by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow-" + title)
    SectionCard(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = SectionSpacing.horizontal, vertical = SectionSpacing.vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = p.t1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (count != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("$count", color = p.t3, fontSize = 12.sp)
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        color = p.t3,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (expanded) "收起" else "展开",
                tint = p.t3,
                modifier = Modifier.size(20.dp).rotate(arrow),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                content()
            }
        }
    }
}
