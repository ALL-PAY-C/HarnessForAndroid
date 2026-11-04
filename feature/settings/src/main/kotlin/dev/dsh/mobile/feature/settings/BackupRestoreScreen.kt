package dev.dsh.mobile.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.common.TimeFmt
import dev.dsh.mobile.core.common.RestartReason
import dev.dsh.mobile.core.common.restartApp
import dev.dsh.mobile.core.data.backup.BackupManager
import dev.dsh.mobile.core.data.backup.BackupManifest
import dev.dsh.mobile.core.data.backup.BackupSelection
import dev.dsh.mobile.core.data.backup.RestoreEngine
import dev.dsh.mobile.core.data.backup.isLegacyPackage
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.qqPageBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 → 备份与恢复（T9.13 P3，设计见 research/T913-backup.md §2）。
 *
 * 三块，自上而下对应三个真实诉求：
 *  1. 立即备份：勾要带的节，产一个 zip 落到应用私有 backups 目录；
 *  2. 已有备份：列出现有包（名字 / 时间 / 大小 / 包含哪些节），可恢复、可删；
 *  3. 从文件恢复：SAF 选包 → 只读 manifest 预览 → 合并 / 整机还原 → 含凭证则强制口令。
 *
 * 所有磁盘与 zip 操作都在 [Dispatchers.IO]：写包能跑几十秒，主线程上就是 ANR。
 * 写包与恢复共用一把 busy 锁，任何一个在跑另一个按钮就禁掉，避免并发写同一批文件。
 */
@Composable
fun BackupRestoreScreen(onBack: () -> Unit, manager: BackupManager) {
    val p = LocalQq.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 区块一：立即备份 ----
    var incSessions by remember { mutableStateOf(true) }
    var incSettings by remember { mutableStateOf(true) }
    var incPlugins by remember { mutableStateOf(true) }
    var incAttachments by remember { mutableStateOf(true) }
    var incSecrets by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf("") }
    var exportNote by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var lastBackupAt by remember { mutableStateOf(0L) }

    // ---- 区块二：已有备份 ----
    var entries by remember { mutableStateOf<List<BackupManager.Entry>>(emptyList()) }
    var manifests by remember { mutableStateOf<Map<String, BackupManifest>>(emptyMap()) }

    // preview 失败但结构是历史三前缀包的：允许整机还原，别当坏包禁掉。
    var legacyNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<BackupManager.Entry?>(null) }

    // ---- 区块三：从文件恢复 ----
    var picked by remember { mutableStateOf<PickedBackup?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }

    // ---- 恢复确认 / 结果 ----
    var target by remember { mutableStateOf<PickedBackup?>(null) }
    var mode by remember { mutableStateOf(RestoreEngine.Mode.Merge) }
    var restorePass by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<RestoreEngine.Report?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val list = manager.list()
                val map = list.mapNotNull { entry ->
                    manager.previewPackage(entry.file).getOrNull()?.let { entry.file.name to it }
                }.toMap()
                // 只有 preview 失败的包才做旧格式结构判定：正常包不进这条路径。
                val legacy = list.map { it.file }
                    .filter { it.name !in map }
                    .filter { isLegacyPackage(it) }
                    .map { it.name }
                    .toSet()
                LoadedBackups(list, map, legacy, manager.lastBackupAt())
            }
            entries = loaded.entries
            manifests = loaded.manifests
            legacyNames = loaded.legacyNames
            lastBackupAt = loaded.lastBackupAt
        }
    }

    LaunchedEffect(Unit) {
        reload()
        withContext(Dispatchers.IO) { purgeStaleImports(ctx) }
    }

    // SAF 落的当前包：换包时删上一份，离开页面删当前这份。口令输错重试期间不动它。
    val importFile = remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            importFile.value?.delete()
            importFile.value = null
        }
    }

    fun startExport() {
        if (busy) return
        busy = true
        exportNote = null
        exportError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 插件目录列表本身就是一次磁盘扫描，放同一个 IO 块里。
                val plugins = if (incPlugins) installedPluginIds(ctx) else emptySet()
                manager.export(
                    BackupSelection(
                        sessions = if (incSessions) null else emptySet(),
                        settings = incSettings,
                        plugins = plugins,
                        secrets = incSecrets,
                        attachments = incAttachments,
                        workspace = false,
                        passphrase = if (incSecrets) exportPass else "",
                    ),
                )
            }
            busy = false
            result.onSuccess { pair ->
                val file = pair.first
                exportNote = file.name + " · " + formatBytes(file.length()) + " · " + countsSummary(pair.second)
                exportPass = ""
                reload()
            }.onFailure { e -> exportError = e.message ?: "备份失败" }
        }
    }

    fun startRestore(source: PickedBackup, restoreMode: RestoreEngine.Mode, passphrase: String) {
        if (busy) return
        busy = true
        restoreError = null
        report = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                manager.restorePackage(source.file, restoreMode, passphrase)
            }
            busy = false
            outcome.onSuccess { r ->
                report = r
                target = null
                restorePass = ""
                picked = null
                pickError = null
                // 恢复成功，缓存里那份导出的临时包没用了。
                importFile.value?.delete()
                importFile.value = null
                reload()
            }.onFailure { e ->
                // WrongPassphraseException 的 message 已经是「口令错误：…」，其余走引擎的中文文案。
                target = null
                restoreError = e.message ?: "恢复失败"
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickError = null
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { readPicked(ctx, manager, uri, importFile.value) }
                }
                busy = false
                result.onSuccess { source ->
                    importFile.value = source.file
                    picked = source
                    target = source
                    mode = if (source.legacy) RestoreEngine.Mode.Replace else RestoreEngine.Mode.Merge
                    restorePass = ""
                }.onFailure { e -> pickError = e.message ?: "读不出这个备份包" }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(title = "备份与恢复", isBack = true, onNavigationClick = onBack)
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
                verticalArrangement = Arrangement.spacedBy(SectionSpacing.cardGap),
            ) {
                // ---------- 1. 立即备份 ----------
                SectionFrame(title = "立即备份", icon = Icons.Filled.Backup, tint = p.accent) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "备份包是标准 zip，落在应用私有目录 backups/ 里。含凭证的包必须设口令（至少 " +
                                BackupSelection.MIN_PASSPHRASE_LENGTH + " 位），口令丢了包内凭证就解不回来。",
                            color = p.t3,
                            fontSize = qqFontBody(11.5.sp),
                        )
                        Text(
                            "上次备份：" + if (lastBackupAt > 0L) TimeFmt.absolute(lastBackupAt) else "从未备份",
                            color = p.t3,
                            fontSize = qqFontBody(11.sp),
                        )
                    }
                    QqCardDivider()
                    CheckRow("会话与消息", "全部会话与消息；不勾只留表结构", incSessions) { incSessions = it }
                    QqCardDivider()
                    CheckRow("设置", "界面 / 会话 / 通知 / 群组 / 模板（设备路径键恢复时保留本机值）", incSettings) {
                        incSettings = it
                    }
                    QqCardDivider()
                    CheckRow("插件", "已安装插件的目录与配置（不含 apk 与旧版本缓存）", incPlugins) { incPlugins = it }
                    QqCardDivider()
                    CheckRow("附件文件", "聊天附件（库里存的是相对路径，必须一起带走）", incAttachments) {
                        incAttachments = it
                    }
                    QqCardDivider()
                    CheckRow("凭证", "端点 token / 模型 apiKey / MCP 请求头 / 插件凭证，口令加密", incSecrets) {
                        incSecrets = it
                        if (!it) exportPass = ""
                    }
                    if (incSecrets) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            OutlinedTextField(
                                value = exportPass,
                                onValueChange = { exportPass = it },
                                label = {
                                    Text("导出凭证的口令（至少 " + BackupSelection.MIN_PASSPHRASE_LENGTH + " 位）")
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        QqCardDivider()
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { startExport() },
                            enabled = !busy && (!incSecrets || exportPass.length >= BackupSelection.MIN_PASSPHRASE_LENGTH),
                        ) { Text(if (busy) "处理中…" else "创建备份") }
                        Spacer(Modifier.width(8.dp))
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在写入备份包，先别退出", color = p.t3, fontSize = qqFontBody(11.5.sp))
                        }
                    }
                    exportNote?.let { InfoLine("备份完成：" + it, p.green) }
                    exportError?.let { InfoLine(it, p.red) }
                }

                // ---------- 2. 已有备份 ----------
                SectionFrame(title = "已有备份", icon = Icons.Filled.Restore, tint = p.accent2) {
                    if (entries.isEmpty()) {
                        Text(
                            "还没有备份。上面勾好内容点「创建备份」，文件就会出现在这里。",
                            color = p.t3,
                            fontSize = qqFontBody(12.sp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) QqCardDivider()
                        Column(Modifier.fillMaxWidth()) {
                            QqRow(
                                icon = Icons.Filled.Backup,
                                title = entry.file.name,
                                subtitle = listOfNotNull(
                                    TimeFmt.absolute(entry.createdAt).takeIf { it.isNotBlank() },
                                    formatBytes(entry.file.length()),
                                    manifests[entry.file.name]?.let { sectionsSummary(it) },
                                    if (entry.file.name in legacyNames) "旧格式包 · 仅整机还原（无分节校验）" else null,
                                ).joinToString(" · "),
                                tint = p.accent2,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        val manifest = manifests[entry.file.name]
                                        val legacy = entry.file.name in legacyNames
                                        when {
                                            manifest != null -> {
                                                target = PickedBackup(entry.file, entry.file.name, manifest, false)
                                                mode = RestoreEngine.Mode.Merge
                                                restorePass = ""
                                            }

                                            legacy -> {
                                                // 老包没有分节信息，也没有合并语义：只给整机覆盖。
                                                target = PickedBackup(entry.file, entry.file.name, null, true)
                                                mode = RestoreEngine.Mode.Replace
                                                restorePass = ""
                                            }

                                            else -> pickError = "这个备份包已损坏：既没有 manifest.json，也不是旧格式结构"
                                        }
                                    },
                                ) { Text("恢复") }
                                TextButton(enabled = !busy, onClick = { pendingDelete = entry }) {
                                    Text("删除", color = p.red)
                                }
                            }
                        }
                    }
                }

                // ---------- 3. 从文件恢复 ----------
                SectionFrame(title = "从文件恢复", icon = Icons.Filled.Folder, tint = p.accent) {
                    QqRow(
                        icon = Icons.Filled.Folder,
                        title = "选择备份包",
                        subtitle = "系统文件选择器 · .hfabak / .zip",
                        tint = p.accent,
                        onClick = { if (!busy) openDoc.launch(arrayOf("application/zip", "*/*")) },
                    )
                    picked?.let { source ->
                        QqCardDivider()
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(source.displayName, color = p.t1, fontSize = qqFontBody(13.sp))
                            val m = source.manifest
                            if (m == null) {
                                Text(
                                    "旧格式包 · 仅整机还原（无分节校验）",
                                    color = p.warn,
                                    fontSize = qqFontBody(11.5.sp),
                                )
                                Text(
                                    formatBytes(source.file.length()) +
                                        " · 包内没有 manifest.json，按旧版结构整机覆盖设置 / 数据库 / 插件",
                                    color = p.t3,
                                    fontSize = qqFontBody(11.sp),
                                )
                            } else {
                                Text(countsSummary(m), color = p.t3, fontSize = qqFontBody(11.5.sp))
                                Text(
                                    "包含：" + sectionsSummary(m) +
                                        " · 产包版本 " + m.appVersionName +
                                        " · 库版本 " + m.dbVersion,
                                    color = p.t3,
                                    fontSize = qqFontBody(11.sp),
                                )
                                m.warnings.firstOrNull()?.let {
                                    Text(it, color = p.warn, fontSize = qqFontBody(11.sp))
                                }
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    target = source
                                    mode = if (source.legacy) RestoreEngine.Mode.Replace else RestoreEngine.Mode.Merge
                                    restorePass = ""
                                },
                            ) { Text("开始恢复") }
                        }
                    }
                    pickError?.let {
                        QqCardDivider()
                        InfoLine(it, p.red)
                    }
                }

                // ---------- 结果 ----------
                report?.let { r ->
                    SectionFrame(title = "恢复完成", icon = Icons.Filled.Restore, tint = p.green) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(reportSummary(r), color = p.t1, fontSize = qqFontBody(12.5.sp))
                            r.preRestore?.let { snapshot ->
                                Text(
                                    "已自动创建恢复前快照：" + snapshot.name,
                                    color = p.t3,
                                    fontSize = qqFontBody(11.5.sp),
                                )
                            }
                            r.warnings.forEach { Text(it, color = p.warn, fontSize = qqFontBody(11.sp)) }
                            Text(
                                "重启 App 生效：设置与插件在下次启动时重新加载。",
                                color = p.t3,
                                fontSize = qqFontBody(11.sp),
                            )
                            // 恢复只改磁盘，DataStore 与插件都还是启动时读进内存的那一份：
                            // 不重启的话界面看上去「没恢复」，用户会以为备份坏了。
                            // 这条必须是硬重启：RestoreEngine 直接 writeBytes 覆盖 .preferences_pb，
                            // 而 DataStore 的实例是文件级委托单例，没有作废重读的 API
                            // （详见 AppContainer.softRestart 的注释）。
                            TextButton(onClick = { restartApp(ctx, RestartReason.BackupRestore) }) {
                                Text("立即重启")
                            }
                        }
                    }
                }
                restoreError?.let {
                    SectionFrame(title = "恢复失败", icon = Icons.Filled.Lock, tint = p.red) {
                        InfoLine(it, p.red)
                    }
                }
            }
        }
    }

    // ---------- 删除确认（不可撤销，必须弹一次）----------
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个备份") },
            text = { Text(entry.file.name + " · " + formatBytes(entry.file.length()) + "，删掉找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { manager.delete(entry.file) }
                        reload()
                    }
                }) { Text("删除", color = p.red) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // ---------- 恢复确认 ----------
    target?.let { source ->
        val m = source.manifest
        val needsPass = m?.secrets != null
        val legacyOnly = source.legacy
        AlertDialog(
            onDismissRequest = { if (!busy) target = null },
            title = { Text(if (legacyOnly) "整机还原旧格式包" else "恢复备份") },
            text = {
                Column {
                    Text(source.displayName, color = p.t1, fontSize = qqFontBody(13.sp))
                    if (m != null) {
                        Text(countsSummary(m), color = p.t3, fontSize = qqFontBody(11.5.sp))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (legacyOnly) {
                        // 旧包文案必须说清代价：没有分节可选，整机覆盖。
                        Text(
                            "旧格式包不含 manifest 校验，无法选择恢复内容，会整机覆盖设置 / 数据库 / 插件。",
                            color = p.warn,
                            fontSize = qqFontBody(11.5.sp),
                        )
                        Text(
                            "旧包没有分节信息，也不会自动拍恢复前快照。",
                            color = p.t3,
                            fontSize = qqFontBody(11.sp),
                        )
                    } else {
                        ModeRow(
                            selected = mode == RestoreEngine.Mode.Merge,
                            title = "合并（推荐）",
                            subtitle = "同 id 覆盖、新 id 新增；同名不同 id 的会话加「（导入）」后缀",
                        ) { mode = RestoreEngine.Mode.Merge }
                        ModeRow(
                            selected = mode == RestoreEngine.Mode.Replace,
                            title = "整机还原",
                            subtitle = "先清空会话 / 消息 / 设置 / 插件再整包落地，会自动先拍一个恢复前快照",
                        ) { mode = RestoreEngine.Mode.Replace }
                        if (needsPass) {
                            Spacer(Modifier.height(4.dp))
                            Text("这个包含凭证，必须输入导出时的口令", color = p.warn, fontSize = qqFontBody(11.5.sp))
                            OutlinedTextField(
                                value = restorePass,
                                onValueChange = { restorePass = it },
                                label = { Text("口令") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (!needsPass || restorePass.isNotEmpty()),
                    onClick = {
                        startRestore(
                            source,
                            if (legacyOnly) RestoreEngine.Mode.Replace else mode,
                            if (needsPass) restorePass else "",
                        )
                    },
                ) { Text(if (busy) "恢复中…" else "开始") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { target = null }) { Text("取消") } },
        )
    }
}

/**
 * SAF 选中并落到缓存的一个包：本地文件 + 展示名 + manifest。
 *
 * [manifest] 为 null 且 [legacy] 为 true 表示历史三前缀包（没有 manifest.json）：
 * 只能走整机覆盖，见 [BackupManager.restorePackage] 的 legacy 分支。
 */
private data class PickedBackup(
    val file: File,
    val displayName: String,
    val manifest: BackupManifest?,
    val legacy: Boolean,
)

/** 一次 reload 的四个结果（没有四元组，单独给个类型比 Triple 套 Pair 好读）。 */
private data class LoadedBackups(
    val entries: List<BackupManager.Entry>,
    val manifests: Map<String, BackupManifest>,
    val legacyNames: Set<String>,
    val lastBackupAt: Long,
)

/**
 * 把 SAF 的 uri 读成本地缓存文件再 preview。
 *
 * RestoreEngine 收 File（内部要反复读同一份字节做 sha256 校验），而 content uri 的输入流
 * 不保证能重复打开，所以先落一份到 cacheDir。[previous] 是上一份缓存包，换包时先删掉。
 *
 * 预览失败不再一律报错：结构与历史三前缀包一致（无 manifest.json）的放行成 legacy，
 * 真损坏的仍然抛出去，别把坏包当旧格式放过。
 */
private fun readPicked(ctx: Context, manager: BackupManager, uri: Uri, previous: File?): PickedBackup {
    val displayName = displayNameOf(ctx, uri) ?: "imported-backup.hfabak"
    previous?.delete()
    val local = File(ctx.cacheDir, IMPORT_FILE_PREFIX + System.currentTimeMillis() + ".hfabak")
    try {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("打不开这个文件")
        input.use { source -> local.outputStream().use { sink -> source.copyTo(sink) } }
        val preview = manager.previewPackage(local)
        return when (classifyBackup(preview.isFailure, isLegacyPackage(local))) {
            BackupKind.Current -> PickedBackup(local, displayName, preview.getOrThrow(), false)
            BackupKind.Legacy -> PickedBackup(local, displayName, null, true)
            BackupKind.Broken ->
                throw preview.exceptionOrNull() ?: IllegalStateException("读不出这个备份包")
        }
    } catch (e: Throwable) {
        local.delete()
        throw e
    }
}

/** 只清缓存里的 import-*.hfabak（超过 [IMPORT_TTL_MILLIS] 的）：别的缓存不归这个页面管。 */
private fun purgeStaleImports(ctx: Context) {
    val now = System.currentTimeMillis()
    ctx.cacheDir.listFiles()?.forEach { file ->
        if (isStaleImport(file.name, file.lastModified(), now)) file.delete()
    }
}

private fun displayNameOf(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** 已安装插件的目录名集合（与 BackupManager.snapshot 的口径一致）。 */
private fun installedPluginIds(ctx: Context): Set<String> =
    File(ctx.filesDir, "plugins").listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?.toSet()
        .orEmpty()

@Composable
private fun CheckRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 8.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Column(Modifier.weight(1f)) {
            Text(label, color = p.t1, fontSize = qqFontBody(14.sp))
            Text(subtitle, color = p.t3, fontSize = qqFontBody(11.sp))
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, color = p.t1, fontSize = qqFontBody(13.sp))
            Text(subtitle, color = p.t3, fontSize = qqFontBody(11.sp))
        }
    }
}

@Composable
private fun InfoLine(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = qqFontBody(11.5.sp),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
