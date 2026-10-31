package dev.dsh.mobile.design

import androidx.compose.foundation.background
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
    slot: UiEditMode.Slot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!UiEditMode.plusVisible(slot)) return
    val p = LocalQq.current
    SlotStrip(modifier = modifier.fillMaxWidth(), horizontal = true) {
        Text(
            slot.label,
            color = p.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 6.dp, top = 6.dp),
        )
        SlotPlusButton(label = "编辑" + slot.label, onClick = onClick)
    }
}

/**
 * 设置里的「界面修改」一节：UI 修改模式开关 + 正文/代码/终端三套字号。
 *
 * 三个字号取的是倍率，乘在主题既有字号上：正文影响 DshTheme 的全局字号，
 * 代码影响 JsonBlock 这类等宽块，终端由 [LocalFontRoles] 提供给终端页。
 */
@Composable
fun UiLayoutSettingsCard(modifier: Modifier = Modifier) {
    val p = LocalQq.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UI 修改模式", color = p.t1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "打开后会话列表、顶栏、输入框、底部栏旁出现「+」，可横向滑动挑选要放上去的组件",
                    color = p.t3,
                    fontSize = 11.5.sp,
                )
            }
            Switch(checked = UiLayoutPrefs.uiEditEnabled, onCheckedChange = { UiLayoutPrefs.enableUiEdit(it) })
        }
        Spacer(Modifier.height(6.dp))
        FontRoleSlider("正文字号", UiLayoutPrefs.fontBody) { UiLayoutPrefs.setBodyFont(it) }
        FontRoleSlider("代码字号", UiLayoutPrefs.fontCode) { UiLayoutPrefs.setCodeFont(it) }
        FontRoleSlider("终端字号", UiLayoutPrefs.fontTerminal) { UiLayoutPrefs.setTerminalFont(it) }
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
