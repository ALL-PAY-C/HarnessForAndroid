package dev.dsh.mobile.feature.settings

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.ExtrasUiEditCard
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqPageBottomInset

/**
 * 设置 → 拓展。
 *
 * 界面可编辑的唯一入口就在这里：一张卡片同时给总开关（拓展插件 UI 配置）和三档字号。
 * 设置页顶部原来另有一张「UI 修改模式」卡片，跟这张是同一件事的两套 UI，
 * 已经并成这一张、状态也并成一个 key（settings.pluginUiExtend）。
 */
@Composable
fun ExtrasScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val p = LocalQq.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏骨架与设置页/会话页一致：返回箭头在左、标题居中、无额外右侧槽位。
            QqScreenTopBar(
                title = stringResource(DesignR.string.settings_extras_title),
                isBack = true,
                onNavigationClick = onBack,
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // 子页没有底部 tab 栏，缺口必须自己补：底栏高度 + 导航条 inset + 键盘 inset。
                    .padding(
                        PaddingValues(
                            start = SectionSpacing.cardGap,
                            top = SectionSpacing.cardGap,
                            end = SectionSpacing.cardGap,
                            bottom = SectionSpacing.cardGap + qqPageBottomInset(tabBarVisible = false),
                        ),
                    ),
            ) {
                ExtrasUiEditCard(
                    enabled = state.settings.pluginUiExtend,
                    onEnabledChange = viewModel::setPluginUiExtend,
                )
            }
        }
    }
}
