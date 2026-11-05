package dev.dsh.mobile.app.diag

import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
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
import dev.dsh.mobile.core.download.diag.LkgPlanner
import dev.dsh.mobile.core.platform.RestartReason
import dev.dsh.mobile.core.platform.restartApp
import dev.dsh.mobile.core.model.port.SettingsApi
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
    // 设置只能从组合根取：自建实例会开出第二个 DataStore，绕过 AppContainer（上一轮消灭的同类问题）。
    // 没有组合根（预览/测试宿主）时本页无意义，直接不渲染。
    if (container == null) return
    val crashStore = remember { container.crashReporter.store }
    val guard = container.bootGuard
    val settings: SettingsApi = container.settings
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
        QqScreenTopBar(title = stringResource(DesignR.string.diag_title), isBack = true, onNavigationClick = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SectionSpacing.horizontal)
                .padding(bottom = qqPageBottomInset(false)),
        ) {
            Spacer(Modifier.height(QqSpace.s()))
            SectionHeading(
                title = stringResource(DesignR.string.diag_safe_mode_title),
                subtitle = if (safeActive) stringResource(DesignR.string.diag_safe_on_prefix) + (safeReason ?: stringResource(DesignR.string.diag_safe_reason_unknown)) else stringResource(DesignR.string.diag_safe_off),
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                QqRow(
                    icon = if (safeActive) Icons.Filled.Warning else Icons.Filled.Check,
                    title = if (safeActive) stringResource(DesignR.string.diag_safe_active_title) else stringResource(DesignR.string.diag_safe_inactive_title),
                    subtitle = if (safeActive) {
                        stringResource(DesignR.string.diag_safe_active_hint)
                    } else {
                        stringResource(DesignR.string.diag_safe_inactive_hint)
                    },
                    tint = if (safeActive) p.accent else p.t3,
                )
                if (safeActive) {
                    val flags = guard?.safeModeController?.flags()
                    if (flags != null) {
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(DesignR.string.diag_safe_flags_title),
                            subtitle = listOfNotNull(
                                stringResource(DesignR.string.diag_flag_plugins_name).takeIf { flags.disablePlugins }?.let { stringResource(DesignR.string.diag_flag_plugins) },
                                stringResource(DesignR.string.diag_flag_engine_name).takeIf { flags.disableAutoEngine }?.let { stringResource(DesignR.string.diag_flag_engine) },
                                stringResource(DesignR.string.diag_flag_accessibility_name).takeIf { flags.disableAccessibility }?.let { stringResource(DesignR.string.diag_flag_accessibility) },
                            ).joinToString(" · ").ifBlank { stringResource(DesignR.string.diag_none) },
                        )
                    }
                }
                if (!lastRollback.isNullOrBlank()) {
                    QqCardDivider()
                    QqRow(
                        icon = Icons.Filled.Refresh,
                        title = stringResource(DesignR.string.diag_rollback_title),
                        subtitle = lastRollback,
                    )
                }
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(DesignR.string.diag_safe_exit),
                    subtitle = if (busy) stringResource(DesignR.string.diag_safe_exit_busy) else stringResource(DesignR.string.diag_safe_exit_hint),
                    onClick = if (busy) null else {
                        {
                            guard?.safeModeController?.exit()
                            toast(context, context.getString(DesignR.string.diag_toast_safe_exited))
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
                title = stringResource(DesignR.string.diag_crashes_title),
                subtitle = stringResource(DesignR.string.diag_crashes_hint),
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                if (crashes.isEmpty()) {
                    QqRow(
                        icon = Icons.Filled.Check,
                        title = stringResource(DesignR.string.diag_crashes_empty),
                        subtitle = if (crashStore == null) stringResource(DesignR.string.diag_crashes_dir_unavailable) else stringResource(DesignR.string.diag_crashes_dir_empty),
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
                                    text = stringResource(DesignR.string.diag_not_uploaded),
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
                title = stringResource(DesignR.string.diag_maintenance),
                subtitle = if (uploadEnabled) {
                    stringResource(DesignR.string.diag_upload_on_prefix) + endpoint.ifBlank { stringResource(DesignR.string.diag_endpoint_empty) }
                } else {
                    stringResource(DesignR.string.diag_upload_off_hint)
                },
            )
            Spacer(Modifier.height(QqSpace.s()))
            SectionCard {
                QqRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(DesignR.string.diag_restore_title),
                    subtitle = stringResource(DesignR.string.diag_restore_hint),
                    onClick = if (busy) null else {
                        {
                            busy = true
                            scope.launch {
                                runCatching {
                                    val snap = withContext(Dispatchers.IO) { lkgStore.preRollback() }
                                    if (snap == null) {
                                        toast(context, context.getString(DesignR.string.diag_toast_no_restore))
                                    } else {
                                        val current = withContext(Dispatchers.IO) { settings.snapshotEntries() }
                                        val plan = LkgPlanner.plan(snap, current)
                                        withContext(Dispatchers.IO) { settings.applyEntries(plan.restore) }
                                        toast(context, context.getString(DesignR.string.diag_toast_restored, plan.restore.size))
                                    }
                                }.onFailure { toast(context, context.getString(DesignR.string.diag_toast_restore_failed, it.message ?: "")) }
                                busy = false
                                reload++
                            }
                        }
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(DesignR.string.diag_upload_title),
                    subtitle = if (uploadEnabled) stringResource(DesignR.string.diag_upload_on_hint) else stringResource(DesignR.string.diag_upload_off_toggle),
                    tint = if (uploadEnabled) p.accent else p.t3,
                    onClick = {
                        if (uploadEnabled) {
                            scope.launch {
                                runCatching { settings.setCrashUpload(false, endpoint) }
                                toast(context, context.getString(DesignR.string.diag_toast_upload_off))
                                reload++
                            }
                        } else if (endpoint.isBlank()) {
                            endpointDraft = ""
                            endpointEditing = true
                        } else {
                            scope.launch {
                                runCatching { settings.setCrashUpload(true, endpoint) }
                                toast(context, context.getString(DesignR.string.diag_toast_upload_on))
                                reload++
                            }
                        }
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Link,
                    title = stringResource(DesignR.string.diag_endpoint_title),
                    subtitle = endpoint.ifBlank { stringResource(DesignR.string.diag_endpoint_unset) },
                    onClick = {
                        endpointDraft = endpoint
                        endpointEditing = true
                    },
                )
                QqCardDivider()
                QqRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(DesignR.string.diag_upload_now),
                    subtitle = stringResource(DesignR.string.diag_upload_now_hint),
                    onClick = if (busy || crashStore == null) null else {
                        {
                            busy = true
                            scope.launch {
                                val n = runCatching { CrashUploader(settings).uploadPending(crashStore) }
                                    .getOrElse { -1 }
                                toast(
                                    context,
                                    if (n < 0) context.getString(DesignR.string.diag_upload_failed_keep) else context.getString(DesignR.string.diag_uploaded_count, n),
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
                text = stringResource(DesignR.string.diag_paths_hint),
                color = p.t3,
                fontSize = qqFontBody(11.5.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        }
    }

    if (endpointEditing) {
        AlertDialog(
            onDismissRequest = { endpointEditing = false },
            title = { Text(stringResource(DesignR.string.diag_endpoint_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(DesignR.string.diag_endpoint_dialog_hint),
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
                        toast(context, if (endpointDraft.isNotBlank()) context.getString(DesignR.string.diag_toast_endpoint_saved) else context.getString(DesignR.string.diag_toast_endpoint_cleared))
                        reload++
                    }
                }) { Text(stringResource(DesignR.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { endpointEditing = false }) { Text(stringResource(DesignR.string.common_cancel)) } },
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
