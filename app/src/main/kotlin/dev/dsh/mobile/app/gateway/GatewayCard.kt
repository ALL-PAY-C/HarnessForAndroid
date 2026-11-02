package dev.dsh.mobile.app.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.app.AppContainer
import kotlinx.coroutines.launch

/**
 * 设置页顶部的内置网关卡片：状态 + 启动/停止 + 客户端地址 + 凭据导入。
 * 放在宿主 app 模块而不是 feature/settings，是因为它直接持有 GatewayManager（有 Context）。
 */
@Composable
fun GatewayCard(container: AppContainer) {
    val gateway = container.gateway
    val state by gateway.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var authTick by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    // authTick 只用来在导入后重读文件列表
    val authNames = remember(authTick) { gateway.authNames() }

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
            Text(
                if (authNames.isEmpty()) "凭据：未导入（没有凭据出不了模型）"
                else "凭据：${authNames.size} 份 · ${authNames.joinToString()}",
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
                OutlinedButton(onClick = { importing = true }) { Text("导入凭据") }
            }
        }
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("导入网关凭据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("粘贴 OAuth JSON（含 refresh_token）。落盘到 ${gateway.authPath()}，重启网关生效。", fontSize = 12.sp)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; err = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        label = { Text("JSON") },
                        isError = err != null,
                    )
                    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { gateway.importAuth(text.trim(), "auth.json") }
                        .onSuccess {
                            importing = false
                            authTick++
                            gateway.stop()
                            GatewayService.start(ctx)
                            scope.launch { gateway.start() }
                        }
                        .onFailure { err = it.message ?: "导入失败" }
                }) { Text("导入并重启") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } },
        )
    }
}
