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
import androidx.compose.ui.Modifier
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqPageBottomInset

/**
 * 设置 → 内置模型网关。
 *
 * 卡片本体由宿主注入（它握着 AppContainer 与网关状态，设置模块不反向依赖 app 模块）。
 * 设置页根列表只留一行入口——原来那张裸文本 + 蓝色链接直接铺在页面底色上，
 * 既不属于卡片体系、也让底部看着像没做完（O2）。
 */
@Composable
fun AppGatewayScreen(
    onBack: () -> Unit,
    gatewayCard: @Composable () -> Unit,
) {
    val p = LocalQq.current

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(
                title = stringResource(DesignR.string.settings_gateway_title),
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
                gatewayCard()
            }
        }
    }
}
