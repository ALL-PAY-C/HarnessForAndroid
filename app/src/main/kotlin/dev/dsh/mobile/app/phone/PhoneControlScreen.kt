package dev.dsh.mobile.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqPalette
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SectionCard
import dev.dsh.mobile.design.SectionTitle
import dev.dsh.mobile.toolbridge.phone.PhoneAccessibilityService
import dev.dsh.mobile.toolbridge.phone.PhoneAction
import dev.dsh.mobile.toolbridge.phone.PhoneActionLog
import dev.dsh.mobile.toolbridge.phone.PhoneActionRecord
import dev.dsh.mobile.toolbridge.phone.PhoneBridge
import dev.dsh.mobile.toolbridge.phone.PhonePermissionState
import dev.dsh.mobile.toolbridge.phone.PhonePermissionStep
import dev.dsh.mobile.toolbridge.phone.PhoneStepStatus
import kotlinx.coroutines.launch

/**
 * T1 手机控制页：权限引导 + 动作列表 + 状态可视化。
 * 全部走 AccessibilityService / 悬浮窗，不碰 root、不碰 ADB；
 * 页面上每个状态位都来自 PhoneBridge 的真实读取，不做「假装已授权」。
 */
@Composable
fun PhoneControlScreen(phone: PhoneBridge) {
    val p = LocalQq.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(phone.permissionState()) }
    var probe by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val records by PhoneActionLog.records.collectAsStateWithLifecycle()

    fun refresh() {
        state = phone.permissionState()
        PhoneAccessibilityService.current()?.refreshOverlay()
    }

    // 用户去系统设置里授权完再回来，状态必须立刻反映真实值。
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        QqTopBar {
            Text("手机控制", color = p.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeadCard(state = state, onRefresh = ::refresh)
            SectionTitle("权限引导", PhonePermissionStep.entries.size)
            SectionCard {
                PhonePermissionStep.entries.forEachIndexed { index, step ->
                    if (index > 0) QqCardDivider()
                    val status = state.status(step)
                    QqRow(
                        icon = Icons.Filled.TouchApp,
                        title = step.title,
                        subtitle = if (status == PhoneStepStatus.Granted) "已授权" else step.body,
                        tint = statusColor(p, status),
                        onClick = {
                            val intent = when (step) {
                                PhonePermissionStep.Accessibility -> phone.accessibilitySettingsIntent()
                                PhonePermissionStep.Overlay -> phone.overlaySettingsIntent()
                            }
                            runCatching { ctx.startActivity(intent) }
                        },
                        trailing = {
                            Text(
                                when (status) {
                                    PhoneStepStatus.Granted -> "已授权"
                                    PhoneStepStatus.Pending -> "待连接"
                                    PhoneStepStatus.Off -> step.actionLabel
                                },
                                color = statusColor(p, status),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                    )
                }
            }
            SectionTitle("动作列表", PhoneAction.entries.size)
            SectionCard {
                PhoneAction.entries.forEachIndexed { index, action ->
                    if (index > 0) QqCardDivider()
                    val last = records.firstOrNull { it.action == action.id }
                    QqRow(
                        icon = Icons.Filled.TouchApp,
                        title = action.label,
                        subtitle = action.hint + " · " + action.id,
                        tint = p.accent,
                        trailing = { LastOutcome(last) },
                    )
                }
            }
            SectionCard {
                QqRow(
                    icon = Icons.Filled.Refresh,
                    title = if (busy) "读屏自检中…" else "读屏自检",
                    subtitle = "执行一次 readscreen，验证服务真的能下发动作",
                    tint = p.accent,
                    onClick = {
                        if (busy) return@QqRow
                        busy = true
                        scope.launch {
                            val r = phone.exec("{\"action\":\"readscreen\",\"maxItems\":30}")
                            probe = if (r.ok) r.output else (r.error ?: r.output)
                            busy = false
                            state = phone.permissionState()
                        }
                    },
                )
                probe?.let {
                    QqCardDivider()
                    Text(
                        it.take(1200),
                        color = p.t2,
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            SectionTitle("最近动作", records.size)
            SectionCard {
                if (records.isEmpty()) {
                    Text(
                        "还没有动作记录。模型调用 phone 工具或点上面的「读屏自检」后会出现在这里。",
                        color = p.t3,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                } else {
                    records.forEachIndexed { index, record ->
                        if (index > 0) QqCardDivider()
                        QqRow(
                            icon = if (record.ok) Icons.Filled.Check else Icons.Filled.Close,
                            title = record.label,
                            subtitle = record.message.ifBlank { record.action },
                            tint = if (record.ok) p.green else p.red,
                        )
                    }
                }
            }
            Text(
                "全部动作在本机执行，不需要 root、不需要 ADB、不需要电脑。服务未开启时按钮只会把你送到系统设置页。",
                color = p.t3,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun HeadCard(state: PhonePermissionState, onRefresh: () -> Unit) {
    val p = LocalQq.current
    val color = if (state.canControl) p.green else p.amber
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                state.headline(),
                color = p.t1,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("刷新", color = p.accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onRefresh))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("无障碍服务", state.status(PhonePermissionStep.Accessibility))
            StatusChip("服务连接", if (state.serviceBound) PhoneStepStatus.Granted else PhoneStepStatus.Off)
            StatusChip("悬浮窗", state.status(PhonePermissionStep.Overlay))
        }
        if (!state.canControl) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.missingHint(),
                color = p.t2,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, status: PhoneStepStatus) {
    val p = LocalQq.current
    val color = statusColor(p, status)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, color = p.t1, fontSize = 10.5.sp)
    }
}

@Composable
private fun LastOutcome(record: PhoneActionRecord?) {
    val p = LocalQq.current
    val color = when {
        record == null -> p.t3
        record.ok -> p.green
        else -> p.red
    }
    Text(
        when {
            record == null -> "未执行"
            record.ok -> "✓ 成功"
            else -> "✗ 失败"
        },
        color = color,
        fontSize = 11.5.sp,
    )
}

/** 状态色走调色板，深色模式下不用另调一套。 */
private fun statusColor(p: QqPalette, status: PhoneStepStatus): Color = when (status) {
    PhoneStepStatus.Granted -> p.green
    PhoneStepStatus.Pending -> p.amber
    PhoneStepStatus.Off -> p.red
}
