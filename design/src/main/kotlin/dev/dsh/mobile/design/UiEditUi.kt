package dev.dsh.mobile.design

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 某个可编辑位置旁的「+」：位置名 + 虚线加号，外面套 [SlotStrip] 所以整行可横向滑动，
 * 位置多了往右划过就行，不会把界面挤成固定几列。
 */
@Composable
fun UiEditSlotPlusRow(
    slot: SlotEditState.Slot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!SlotEditState.plusVisible(slot)) return
    val p = LocalQq.current
    SlotStrip(modifier = modifier.fillMaxWidth(), horizontal = true) {
        Text(
            stringResource(slot.labelRes),
            color = p.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 6.dp, top = 6.dp),
        )
        SlotPlusButton(label = stringResource(R.string.common_edit) + stringResource(slot.labelRes), onClick = onClick)
    }
}

/**
 * 「设置 → 拓展」页里唯一的入口卡片：插件 UI 拓展总开关 + 正文/代码/终端三套字号。
 *
 * 开关打开后各界面槽位旁出现「+」（唯一判定入口是 [SlotEditState.plusVisible]）；
 * 三个字号取的是倍率，乘在主题既有字号上：正文影响 DshTheme 的全局字号，
 * 代码影响 JsonBlock 这类等宽块，终端由 [LocalFontRoles] 提供给终端页。
 *
 * 开关状态不从这里存：落盘的是 settings.pluginUiExtend（AppNav 读它写 [SlotEditState]），
 * 卡片只画 enabled / onEnabledChange，避免再出现第二份可编辑态。
 */
@Composable
fun ExtrasUiEditCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalQq.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.design_ui_extend_title), color = p.t1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.design_ui_extend_hint),
                    color = p.t3,
                    fontSize = 11.5.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(6.dp))
        FontRoleSlider(stringResource(R.string.design_ui_font_body), UiLayoutPrefs.fontBody) { UiLayoutPrefs.setBodyFont(it) }
        FontRoleSlider(stringResource(R.string.design_ui_font_code), UiLayoutPrefs.fontCode) { UiLayoutPrefs.setCodeFont(it) }
        FontRoleSlider(stringResource(R.string.design_ui_font_terminal), UiLayoutPrefs.fontTerminal) { UiLayoutPrefs.setTerminalFont(it) }
    }
}

@Composable
private fun FontRoleSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val p = LocalQq.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.t2, fontSize = 12.5.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.8f..1.4f,
            steps = 11,
            modifier = Modifier.weight(1f),
        )
        Text(
            String.format("%.2f", value) + "×",
            color = p.t3,
            fontSize = 11.5.sp,
            modifier = Modifier.width(46.dp).padding(start = 6.dp),
        )
    }
}
