package dev.dsh.mobile.app.diag

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.app.DshApplication
import dev.dsh.mobile.core.common.diag.LkgPlanner
import dev.dsh.mobile.core.common.RestartReason
import dev.dsh.mobile.core.common.restartApp
import dev.dsh.mobile.core.data.DataModule
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.SectionCard
import dev.dsh.mobile.design.SectionHeading
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.qqPageBottomInset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页：崩溃物证 / 安全模式 / 配置回滚的唯一人工出口。
 *
 * 这里只做「读现状 + 发一个动作」，判定逻辑全在 core 层与 AppBootGuard；
 * 页面自己不留状态（除了一次性的刷新计数），重进即重新读盘。
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as? DshApplication)?.container
    val crashStore = remember { container?.crashReporter?.store }
    val guard = container?.bootGuard
    val settings = remember { DataModule.createSettings(context) }
    val lkgStore = remember { LkgStore(File(context.filesDir, "lkg")) }
    val scope = rememberCoroutineScope()
    val p = LocalQq.current

    var crashes by remember { mutableStateOf<List<Pair<String, CrashRecord>>>(emptyList()) }
    var safeActive by remember { mutableStateOf(false) }
    var safeReason by remember { mutableStateOf<String?>(null) }
    var uploadEnabled by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var lastRollback by remember { mutableStateOf<String?>(null) }
    var endpointEditing by remember { mutableStateOf(false) }
    var endpointDraft by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val pair = runCatching { settings.crashUpload() }.getOrDefault(false to "")
        uploadEnabled = pair.first
        endpoint = pair.second
        safeActive = guard?.safeModeController?.isActive() == true
        safeReason = guard?.safeModeController?.reason()
        lastRollback = guard?.let { g ->
            runCatching { g.readLastRollback() }.getOrNull()
        }
        crashes = if (crashStore == null) emptyList() else withContext(Dispatchers.IO) {
            crashStore.list().mapNotNull { f ->
                val rec = crashStore.read(f) ?: return@mapNotNull null
                f.name to rec
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        QqScreenTopBar(title = "诊断", isBack = true, onNavigationClick = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SectionSpacing.horizontal)
                .padding(bottom = qqPageBottomInset(false)),
        ) {
            Spacer(Modifier.height(QqSpace.s()))
            SectionHeading(
                title = "安全模式",
                subtitle = if (safeActive) "已开启 · " + (safeReason ?: "原因未知") else "未开启",
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                QqRow(
                    icon = if (safeActive) Icons.Filled.Warning else Icons.Filled.Check,
                    title = if (safeActive) "安全模式已开启" else "安全模式未开启",
                    subtitle = if (safeActive) {
                        "插件 / 自动连接 / 无障碍扩展已按安全模式收敛；状态粘性，只能从这里退出"
                    } else {
                        "连续启动失败未达阈值"
                    },
                    tint = if (safeActive) p.accent else p.t3,
                )
                if (safeActive) {
                    val flags = guard?.safeModeController?.flags()
                    if (flags != null) {
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Info,
                            title = "本次收敛的开关",
                            subtitle = listOfNotNull(
                                "插件".takeIf { flags.disablePlugins }?.let { "插件已禁用" },
                                "自动引擎".takeIf { flags.disableAutoEngine }?.let { "自动连接已禁用（仍可手动启动）" },
                                "无障碍".takeIf { flags.disableAccessibility }?.let { "无障碍扩展已禁用" },
                            ).joinToString(" · ").ifBlank { "无" },
                        )
                    }
                }
                if (!lastRollback.isNullOrBlank()) {
                    QqCardDivider()
                    QqRow(
                        icon = Icons.Filled.Refresh,
                        title = "最近一次配置回滚",
                        subtitle = lastRollback,
                    )
                }
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Refresh,
                    title = "退出安全模式并重启",
                    subtitle = if (busy) "执行中…" else "清掉安全模式标志后在进程内重建插件与引擎",
                    onClick = if (busy) null else {
                        {
                            guard?.safeModeController?.exit()
                            toast(context, "已退出安全模式，正在重建")
                            // 软重启：不杀进程。安全模式的标志位全在 BootGuardStore（同步写、读时现取），
                            // 进程内就能重建完，不需要（也不该）走杀进程那条路——Android 14+ 的 BAL
                            // 会把「杀完自己再回来」拦成 BAL_BLOCK，用户只会看到桌面。
                            restartApp(context, RestartReason.SafeModeExit)
                        }
                    },
                )
            }

            Spacer(Modifier.height(QqSpace.l()))
            SectionHeading(
                title = "崩溃记录",
                subtitle = "上传成功后文件会被删除，所以列在这里的都是尚未上传的",
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                if (crashes.isEmpty()) {
                    QqRow(
                        icon = Icons.Filled.Check,
                        title = "没有待处理的崩溃文件",
                        subtitle = if (crashStore == null) "崩溃目录不可用" else "崩溃目录为空",
                    )
                } else {
                    crashes.forEachIndexed { index, (name, rec) ->
                        if (index > 0) QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Warning,
                            title = rec.kind + " · " + formatTs(rec.ts),
                            subtitle = firstLine(rec.message).ifBlank { rec.throwable.ifBlank { name } },
                            tint = p.accent,
                            trailing = {
                                Text(
                                    text = "未上传",
                                    color = p.t3,
                                    fontSize = qqFontBody(11.5.sp),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(QqSpace.l()))
            SectionHeading(
                title = "维护动作",
                subtitle = if (uploadEnabled) {
                    "上传已开启 · " + endpoint.ifBlank { "端点为空" }
                } else {
                    "上传未开启（下面可以打开；关闭时全程不联网）"
                },
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                QqRow(
                    icon = Icons.Filled.Refresh,
                    title = "恢复上次配置（pre-rollback）",
                    subtitle = "把回滚前存下的那份设置写回去，重启后完全生效",
                    onClick = if (busy) null else {
                        {
                            busy = true
                            scope.launch {
                                runCatching {
                                    val snap = withContext(Dispatchers.IO) { lkgStore.preRollback() }
                                    if (snap == null) {
                                        toast(context, "没有可恢复的回滚前副本")
                                    } else {
                                        val current = withContext(Dispatchers.IO) { settings.snapshotEntries() }
                                        val plan = LkgPlanner.plan(snap, current)
                                        withContext(Dispatchers.IO) { settings.applyEntries(plan.restore) }
                                        toast(context, "已恢复 " + plan.restore.size + " 项设置，重启后完全生效")
                                    }
                                }.onFailure { toast(context, "恢复失败：" + it.message) }
                                busy = false
                                reload++
                            }
                        }
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Info,
                    title = "上传崩溃报告",
                    subtitle = if (uploadEnabled) "已开启 · 点一下关闭" else "已关闭 · 点一下打开（需要先填端点）",
                    tint = if (uploadEnabled) p.accent else p.t3,
                    onClick = {
                        if (uploadEnabled) {
                            scope.launch {
                                runCatching { settings.setCrashUpload(false, endpoint) }
                                toast(context, "已关闭上传")
                                reload++
                            }
                        } else if (endpoint.isBlank()) {
                            endpointDraft = ""
                            endpointEditing = true
                        } else {
                            scope.launch {
                                runCatching { settings.setCrashUpload(true, endpoint) }
                                toast(context, "已开启上传")
                                reload++
                            }
                        }
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Link,
                    title = "上传端点",
                    subtitle = endpoint.ifBlank { "未设置 · POST 一个 JSON 到这里" },
                    onClick = {
                        endpointDraft = endpoint
                        endpointEditing = true
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Delete,
                    title = "立即上传未上传文件",
                    subtitle = "单次 POST，10s 超时，不重试；2xx 才删文件",
                    onClick = if (busy || crashStore == null) null else {
                        {
                            busy = true
                            scope.launch {
                                val n = runCatching { CrashUploader(settings).uploadPending(crashStore) }
                                    .getOrElse { -1 }
                                toast(
                                    context,
                                    if (n < 0) "上传失败，文件保留" else "已上传 " + n + " 条",
                                )
                                busy = false
                                reload++
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(QqSpace.xl()))
            Text(
                text = "崩溃文件目录：files/crash · 配置快照目录：files/lkg（环形 3 份 + pre-rollback 审计副本）",
                color = p.t3,
                fontSize = qqFontBody(11.5.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        }
    }

    if (endpointEditing) {
        AlertDialog(
            onDismissRequest = { endpointEditing = false },
            title = { Text("崩溃上传端点") },
            text = {
                Column {
                    Text(
                        "POST 一个 JSON，2xx 才算成功；留空等于不启用。",
                        color = p.t3,
                        fontSize = qqFontBody(11.5.sp),
                    )
                    Spacer(Modifier.height(QqSpace.s()))
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = { endpointDraft = it.trim() },
                        singleLine = true,
                        placeholder = { Text("https://example.com/crash") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    endpointEditing = false
                    scope.launch {
                        runCatching { settings.setCrashUpload(endpointDraft.isNotBlank(), endpointDraft) }
                        toast(context, if (endpointDraft.isNotBlank()) "已保存并开启上传" else "已清空端点")
                        reload++
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { endpointEditing = false }) { Text("取消") } },
        )
    }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private val TS_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

private fun formatTs(ts: Long): String = TS_FORMAT.format(Date(ts))

/** 崩溃 message 的首行；多行 message 在列表里会把行高撑爆。 */
private fun firstLine(message: String): String =
    message.lineSequence().firstOrNull()?.trim()?.take(120) ?: ""
