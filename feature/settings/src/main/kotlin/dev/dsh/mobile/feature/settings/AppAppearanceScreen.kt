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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqPageBottomInset

/**
 * 外观子页：设置页一级列表里「外观」点进来的落地页。
 *
 * 内容原样复用 [AppearanceSectionBody]，它以前是塞在设置页某张卡里的手风琴正文；
 * 搬到独立页面之后，展开/收起任何一项都不会再顶动上方的其它设置项（O7 的误触根因）。
 * 卡片外壳走 SettingsScreen 的 SectionFrame，不复制第二份实现。
 */
@Composable
fun AppAppearanceScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val p = LocalQq.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(
                title = stringResource(DesignR.string.settings_appearance_title),
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
                            // 子页压掉了底部 tab 栏，所以这里要自己补底栏高度 + 导航条 inset，
                            // 否则滚到底最后一行会贴到屏幕导航条上（O5/O8）。
                            bottom = SectionSpacing.cardGap + qqPageBottomInset(tabBarVisible = false),
                        ),
                    ),
            ) {
                // 顶栏已经写了「外观」，卡片里不再重复一次标题。
                SectionFrame(title = null, icon = null, tint = p.accent2) {
                    AppearanceSectionBody(state, viewModel)
                    QqCardDivider()
                    QqRow(
                        icon = Icons.Filled.Bolt,
                        title = stringResource(DesignR.string.settings_appearance_reset),
                        subtitle = stringResource(DesignR.string.settings_appearance_reset_sub),
                        tint = p.t3,
                        onClick = { viewModel.resetAppearance() },
                    )
                }
            }
        }
    }
}
