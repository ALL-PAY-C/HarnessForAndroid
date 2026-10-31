package dev.dsh.mobile.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar

/**
 * 设置 → 拓展。
 *
 * 目前只放一个开关：拓展插件 UI 配置。默认关闭，开了才有插件往宿主界面注入 UI 的余地；
 * 这里只负责开关本身，具体注入点由插件槽位消费。
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.card)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("拓展插件ui配置", color = p.t1, fontSize = 14.sp)
                            Text(
                                "允许插件往宿主界面注入 UI",
                                color = p.t3,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = state.settings.pluginUiExtend,
                            onCheckedChange = viewModel::setPluginUiExtend,
                        )
                    }
                }
            }
        }
    }
}
