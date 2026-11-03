package dev.dsh.mobile.app.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.SectionCard
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqFontBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页靠下部的内置网关卡片（原来钉在设置页最上面，太打扰）。
 * 默认收成一行摘要，展开才看地址/凭据/状态细节与按钮；收起态绝不渲染 API Key，
 * 展开态 key 也默认打码，点「显示 Key」才明文。
 */
@Composable
fun GatewayCard(container: AppContainer) {
    val gateway = container.gateway
    val state by gateway.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var authTick by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    // authTick 只用来在导入后重读文件列表
    val authNames = remember(authTick, state) { gateway.authNames() }

    // 状态保活：Starting 超时转 Failed，端口已在监听却还是 Starting 就自愈成 Running。
    // 收起态也要跑，所以放在卡片顶层而不是展开区里。
    LaunchedEffect(Unit) {
        while (true) {
            gateway.selfHeal()
            delay(1_000)
        }
    }

    val credentialMissing = authNames.isEmpty()
    val status = when (state) {
        is GatewayManager.State.Running -> "运行中"
        GatewayManager.State.Stopped -> "已停止"
        else -> "未就绪"
    }
    val key = gateway.apiKey
    val shownKey = if (revealed) key else "••••" + key.takeLast(4)

    val p = LocalQq.current
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = SectionSpacing.horizontal, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                buildString {
                    append("内置模型网关 · ")
                    append(status)
                    if (credentialMissing) append(" · 凭据未导入")
                },
                modifier = Modifier.weight(1f),
                color = p.t1,
                fontSize = qqFontBody(15.sp),
                fontWeight = FontWeight.Medium,
            )
            if (credentialMissing) {
                GatewayChip("导入凭据", primary = true) { importing = true }
            }
            Text(
                if (expanded) "收起 ▴" else "展开 ▾",
                color = p.t3,
                fontSize = qqFontBody(12.sp),
            )
        }

        if (expanded) {
            QqCardDivider()
            Column(
                Modifier.padding(
                    start = SectionSpacing.horizontal,
                    end = SectionSpacing.horizontal,
                    top = 10.dp,
                    bottom = SectionSpacing.horizontal,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    when (val s = state) {
                        is GatewayManager.State.Running ->
                            "运行中 · ${GatewayManager.HOST}:${GatewayManager.PORT} · ${s.models} 个模型"
                        is GatewayManager.State.Failed -> "启动失败：${s.message}"
                        GatewayManager.State.Starting ->
                            "启动中…（超过 ${GatewayManager.START_TIMEOUT_MS / 1000}s 判失败）"
                        GatewayManager.State.Stopped -> "未运行"
                    },
                    color = p.t2,
                    fontSize = qqFontBody(12.sp),
                    lineHeight = 18.sp,
                )
                Text(
                    if (credentialMissing) "凭据：未导入（没有凭据出不了模型）"
                    else "凭据：${authNames.size} 份 · ${authNames.joinToString()}",
                    color = p.t2,
                    fontSize = qqFontBody(12.sp),
                    lineHeight = 18.sp,
                )
                SelectionContainer {
                    Text(
                        "${gateway.baseUrl}\nkey ${shownKey}",
                        color = p.t1,
                        fontSize = qqFontBody(12.sp),
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(QqShape.field())
                            .background(p.bg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Text(
                    if (revealed) "隐藏 Key" else "显示 Key",
                    color = p.accent,
                    fontSize = qqFontBody(13.sp),
                    modifier = Modifier
                        .clip(QqShape.chip())
                        .clickable { revealed = !revealed }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GatewayChip("启动网关", primary = true) {
                        GatewayService.start(ctx)
                        scope.launch { gateway.start() }
                    }
                    GatewayChip("停止网关", primary = false) {
                        gateway.stop()
                        GatewayService.stop(ctx)
                    }
                    GatewayChip("导入凭据", primary = false) { importing = true }
                }
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
                    Text(
                        "粘贴 OAuth JSON（含 refresh_token）。落盘到 ${gateway.authPath()}，重启网关生效。",
                        fontSize = 12.sp,
                    )
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

/**
 * 卡片里的动作按钮。design 没有公开的 button 组件，样式按 SettingsScreen.kt:1148 的
 * AccentChip 复刻：主按钮 accent 填充 + 白字，次按钮 p.card 底 + p.line 描边 + t1 字，
 * 两者都是 QqShape.chip() + 14/7dp 内边距 + 13sp，与全项目一致。
 */
@Composable
private fun GatewayChip(label: String, primary: Boolean, onClick: () -> Unit) {
    val p = LocalQq.current
    Text(
        label,
        color = if (primary) Color.White else p.t1,
        fontSize = qqFontBody(13.sp),
        modifier = Modifier
            .clip(QqShape.chip())
            .background(if (primary) p.accent else p.card)
            // 次按钮：1dp p.line 描边，同 Components.kt:1695 的 chip 描边写法（qqBorder() 默认 0dp，画不出边）。
            .then(if (primary) Modifier else Modifier.border(1.dp, p.line, QqShape.chip()))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
