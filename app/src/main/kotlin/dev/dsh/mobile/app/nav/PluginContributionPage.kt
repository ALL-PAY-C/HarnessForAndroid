package dev.dsh.mobile.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens

/**
 * 插件声明的独立页面（plugin.page 槽位贡献）。
 *
 * 以前 SlotAction.Page 只会去 `plugin/<pageId>`，而那条路由只对注册了底部 tab 的插件
 * 存在，声明式插件（只有 plugin.json、没有代码）点它的页面就是一句「页面不存在」。
 * 这里按贡献 id 把页面画出来：有声明式 ui 走宿主翻译，没有就退化成插件自带的 content。
 */
@Composable
fun PluginContributionPage(
    contribution: SlotContribution,
    onBack: () -> Unit,
    onAction: (SlotAction) -> Unit,
) {
    val p = LocalQq.current
    val tokens = rememberHostThemeTokens()
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqTopBar(
                leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) },
            ) {
                Text(
                    contribution.label,
                    color = p.t1,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                PluginSlotItem(contribution = contribution, tokens = tokens, onAction = onAction)
            }
        }
    }
}
