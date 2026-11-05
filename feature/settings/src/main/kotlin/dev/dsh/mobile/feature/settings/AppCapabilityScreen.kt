package dev.dsh.mobile.feature.settings

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.platform.device.CapabilityTier
import dev.dsh.mobile.core.platform.device.CapabilityTiers
import dev.dsh.mobile.core.platform.device.DeviceCapability
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.hint
import dev.dsh.mobile.design.label
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.title
import dev.dsh.mobile.design.qqPageBottomInset

/**
 * 设置 → 手机能力授权（T4.5）。
 *
 * 六类系统能力各一行三档：常驻允许 / 本会话 / 每次询问。系统权限没给时行尾出现「去授权」，
 * 点了由宿主弹系统运行时权限框（设置模块没有 activity-compose，弹窗只能在 app 模块发起）。
 * 判定逻辑全在 [CapabilityTiers] 与 ToolPolicy 里，这一页只负责把选择写回去。
 */
@Composable
fun AppCapabilityScreen(
    viewModel: SettingsViewModel,
    grantedOf: (DeviceCapability) -> Boolean,
    onRequest: (DeviceCapability) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalQq.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tiers = state.settings.capabilityTiers
    val parsed = remember(tiers) { CapabilityTiers.parse(tiers) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(
                title = stringResource(DesignR.string.settings_capability_title),
                isBack = true,
                onNavigationClick = onBack,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            start = SectionSpacing.cardGap,
                            top = SectionSpacing.cardGap,
                            end = SectionSpacing.cardGap,
                            bottom = SectionSpacing.cardGap + qqPageBottomInset(tabBarVisible = false),
                        ),
                    ),
            ) {
                SectionFrame(
                    title = stringResource(DesignR.string.settings_capability_section),
                    icon = Icons.Filled.Lock,
                    tint = p.accent2,
                ) {
                    DeviceCapability.entries.forEachIndexed { index, capability ->
                        if (index > 0) QqCardDivider()
                        CapabilityRow(
                            capability = capability,
                            tier = parsed[capability] ?: CapabilityTier.Ask,
                            granted = grantedOf(capability),
                            onTierChange = viewModel::setCapabilityTier,
                            onRequest = onRequest,
                        )
                    }
                }
                Spacer(Modifier.height(SectionSpacing.cardGap))
                HintLine(stringResource(DesignR.string.settings_capability_hint))
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    capability: DeviceCapability,
    tier: CapabilityTier,
    granted: Boolean,
    onTierChange: (DeviceCapability, CapabilityTier) -> Unit,
    onRequest: (DeviceCapability) -> Unit,
) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(capability.title(), color = p.t1, fontSize = 15.sp)
                Text(capability.hint(), color = p.t3, fontSize = qqFontBody(11.5.sp))
            }
            if (!granted) {
                Text(
                    stringResource(DesignR.string.settings_capability_grant),
                    color = Color.White,
                    fontSize = qqFontBody(12.sp),
                    modifier = Modifier
                        .clip(QqShape.chip())
                        .background(p.accent)
                        .clickable { onRequest(capability) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    if (granted) DesignR.string.settings_capability_granted else DesignR.string.settings_capability_ungranted,
                ),
                color = if (granted) p.green else p.t3,
                fontSize = qqFontBody(12.sp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Row {
            CapabilityTier.entries.forEach { option ->
                val on = option == tier
                Text(
                    option.label(),
                    color = if (on) Color.White else p.t2,
                    fontSize = qqFontBody(12.sp),
                    modifier = Modifier
                        .padding(end = 5.dp)
                        .clip(QqShape.chip())
                        .background(if (on) p.accent else p.hover)
                        .clickable { onTierChange(capability, option) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t3,
        fontSize = qqFontBody(11.sp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
    )
}
