package dev.dsh.mobile.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.ExtrasUiEditCard
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar

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
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            QqTopBar(leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) }) {
                Text("拓展", color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                ExtrasUiEditCard(
                    enabled = state.settings.pluginUiExtend,
                    onEnabledChange = viewModel::setPluginUiExtend,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
