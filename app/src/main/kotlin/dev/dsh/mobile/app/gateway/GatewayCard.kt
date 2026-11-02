package dev.dsh.mobile.app.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.app.AppContainer
import kotlinx.coroutines.launch

/**
 * 设置页顶部的内置网关卡片：状态 + 启动/停止 + 客户端地址。
 * 放在宿主 app 模块而不是 feature/settings，是因为它直接持有 GatewayManager（有 Context）。
 */
@Composable
fun GatewayCard(container: AppContainer) {
    val gateway = container.gateway
    val state by gateway.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("内置模型网关", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                when (val s = state) {
                    is GatewayManager.State.Running -> "运行中 · ${GatewayManager.HOST}:${GatewayManager.PORT} · ${s.models} 个模型"
                    is GatewayManager.State.Failed -> "启动失败：${s.message}"
                    GatewayManager.State.Starting -> "启动中…"
                    GatewayManager.State.Stopped -> "未运行"
                },
                fontSize = 12.sp,
            )
            SelectionContainer {
                Text(
                    "${gateway.baseUrl}\nkey ${gateway.apiKey}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    GatewayService.start(ctx)
                    scope.launch { gateway.start() }
                }) { Text("启动网关") }
                OutlinedButton(onClick = {
                    gateway.stop()
                    GatewayService.stop(ctx)
                }) { Text("停止网关") }
            }
        }
    }
}
