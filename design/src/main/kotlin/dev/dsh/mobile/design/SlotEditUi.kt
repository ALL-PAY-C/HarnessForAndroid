package dev.dsh.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 拓展插件 UI 配置打开时的可编辑态。
 *
 * 只做插件组件这一层：内置 UI 一律不可动，各界面只多出「+」（挑插件组件放这里）
 * 和已放置项的「−」（拿掉）。AppNav 按 settings.pluginUiExtend 把它写进
 * [SlotEditState]；关着的时候所有位置渲染跟平时完全一样。
 */
object SlotEditState {
    var enabled by mutableStateOf(false)
}

/** 一个可摆放的插件组件。 */
data class SlotEditItem(
    val pluginId: String,
    val contributionId: String,
    val label: String,
    val subtitle: String = "",
)

/**
 * 「+」占位块。虚线边、圆角，尺寸跟周围卡片一个量级：
 * 放在列表里不会把已有布局挤变形，点开进候选列表。
 */
@Composable
fun SlotPlusButton(
    modifier: Modifier = Modifier,
    label: String = "添加插件组件",
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .background(p.accent.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, tint = p.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = p.accent, fontSize = 12.5.sp)
    }
}

/** 已放置项的「−」。 */
@Composable
fun SlotRemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalQq.current
    // 整个圆都要可点：只给里面 14dp 的图标挂 clickable 会让命中区只剩图标本身。
    // 外层 48dp 只负责命中区与居中，圆点视觉仍是 22dp。
    Box(
        modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(p.t3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Remove,
                "移除",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 包住一个已摆放的插件组件：编辑态右上角叠「−」，否则原样输出。
 * 不改变内容自身的尺寸，删的时候也不会跳版。
 */
@Composable
fun SlotEditFrame(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 「−」摆在内容右侧而不是叠在内容右上角：叠上去会压掉 chip 最后几个字，
    // 也把点击区域盖住（点按钮本体变成点删除）。右侧并排只占 22 + 6 dp。
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        if (SlotEditState.enabled) SlotRemoveBadge(onClick = onRemove)
    }
}

/**
 * 候选列表。空列表也要能看：给一句说明为什么空（通常是没装插件）。
 */
@Composable
fun SlotPickerDialog(
    title: String,
    items: List<SlotEditItem>,
    emptyHint: String,
    onPick: (SlotEditItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text(title, color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) {
                Text(emptyHint, color = p.t3, fontSize = 12.5.sp)
            } else {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { onPick(item) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        ) {
                            Text(item.label, color = p.t1, fontSize = 14.sp)
                            if (item.subtitle.isNotBlank()) {
                                Text(item.subtitle, color = p.t3, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "取消",
                color = p.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 放置区容器。东西多到装不下时按方向滚，不压扁、不挤出屏幕：
 * [horizontal] = true 走横向一行，false 走竖向。
 */
@Composable
fun SlotStrip(
    horizontal: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .then(
                if (horizontal) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
