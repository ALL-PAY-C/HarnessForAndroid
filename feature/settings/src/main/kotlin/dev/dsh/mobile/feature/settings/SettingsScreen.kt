package dev.dsh.mobile.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dsh.mobile.core.data.settings.SendMode
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.core.model.BackMaterial
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SectionHeading
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.StatusCapsule
import dev.dsh.mobile.toolbridge.AppStorage
import dev.dsh.mobile.toolbridge.phone.PhoneBridge
import kotlinx.coroutines.launch

private val ACCENTS = listOf(
    0xFF0099FF, 0xFF3CB4FF, 0xFF22C55E, 0xFFF59E0B,
    0xFFFF3B30, 0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6,
)

private const val README_URL = "https://github.com/ALL-PAY-C/HarnessForAndroid#readme"

internal const val OPEN_DEFAULT = ""

/**
 * 设置页。东西越来越多之后平铺已经没法看了，所以改成两层折叠：
 * 一级是分类卡片（点标题展开/收起），二级是分类内部的子分组。
 * 展开状态按 id 存在 rememberSaveable 的 csv 里，旋转屏幕不丢。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenPlugins: () -> Unit,
    onOpenExtras: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    extraSettings: List<@Composable () -> Unit> = emptyList(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val linux by viewModel.linuxState.collectAsStateWithLifecycle()
    val exaConfigured by viewModel.exaConfigured.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EndpointDraft?>(null) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf(false) }
    var confirmingRuntimeReinstall by remember { mutableStateOf(false) }
    val p = LocalQq.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var openCsv by rememberSaveable { mutableStateOf(OPEN_DEFAULT) }
    val open = remember(openCsv) { openCsv.split(',').filter { it.isNotBlank() }.toSet() }
    fun toggle(id: String) {
        openCsv = if (id in open) (open - id).joinToString(",") else (open + id).joinToString(",")
    }


    val cwdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val path = treeUriToPath(uri)
            viewModel.setCwdTreeUri(uri.toString())
            if (path != null) {
                viewModel.setDefaultCwd(path)
            } else {
                scope.launch {
                    snackbar.showSnackbar("该目录无法映射成本机路径（网盘/云端），已保留授权但工作区未改变")
                }
            }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar("未授予通知权限，系统通知不会弹出") }
    }
    fun askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (confirmingRuntimeReinstall) {
        AlertDialog(
            onDismissRequest = { confirmingRuntimeReinstall = false },
            title = { Text("重装运行时") },
            text = { Text("会删除当前 Linux 运行时并重新下载覆盖，已安装的插件与技能不受影响") },
            confirmButton = {
                TextButton(enabled = !linux.busy, onClick = {
                    confirmingRuntimeReinstall = false
                    viewModel.reinstallRuntime()
                }) { Text("确认重装") }
            },
            dismissButton = { TextButton(onClick = { confirmingRuntimeReinstall = false }) { Text("取消") } },
        )
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqTopBar(leading = { IconBtn(Icons.Filled.Menu, "菜单", onOpenDrawer) }) {
                Text("设置", color = p.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(SectionSpacing.cardGap),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing.cardGap),
            ) {

                // ---------- 1. 应用与更新 ----------
                item {
                    ExpandableSection(
                        id = "basic",
                        title = "应用与更新",
                        subtitle = "版本 ${state.appVersion}",
                        icon = Icons.Filled.SystemUpdate,
                        tint = p.accent,
                        open = "basic" in open,
                        onToggle = { toggle("basic") },
                    ) {
                        QqRow(
                            icon = Icons.Filled.SystemUpdate,
                            title = "检查更新",
                            subtitle = "当前 ${state.appVersion}",
                            tint = p.accent,
                            onClick = { viewModel.checkUpdate() },
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Link,
                            title = "更新源",
                            subtitle = when {
                                state.updateSource.isBlank() -> "内置 · GitHub v0.01（点这里可自定义）"
                                state.updateSource in dev.dsh.mobile.core.data.update.Updater.DEFAULT_SOURCES ||
                                    state.updateSource == dev.dsh.mobile.core.data.update.Updater.DEFAULT_SOURCE ->
                                    "内置 · GitHub v0.01（点这里可自定义）"
                                else -> state.updateSource
                            },
                            tint = p.t2,
                            onClick = { editingSource = true },
                        )
                        if (state.updateStatus != null || state.updateInfo != null) {
                            QqCardDivider()
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                Text(state.updateStatus.orEmpty(), color = p.t2, fontSize = 12.5.sp)
                                state.updateInfo?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(notes, color = p.t3, fontSize = 12.sp)
                                }
                                if (state.updateProgress in 1..99) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { state.updateProgress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.updateInfo != null && !state.updateReady) {
                                        AccentChip("下载") { viewModel.downloadUpdate() }
                                    }
                                    if (state.updateReady) {
                                        AccentChip("安装") {
                                            viewModel.installIntent()?.let { intent ->
                                                runCatching { ctx.startActivity(intent) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Extension,
                            title = "插件",
                            subtitle = "主题 / 消息渲染器 / 网关",
                            tint = p.accent2,
                            onClick = onOpenPlugins,
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Widgets,
                            title = "拓展",
                            subtitle = "拓展插件 UI 配置",
                            tint = p.accent,
                            onClick = onOpenExtras,
                        )
                        QqCardDivider()
                        SubGroup(
                            title = "工作区",
                            subtitle = state.settings.defaultCwd.ifBlank { "应用私有目录" },
                            open = "workspace" in open,
                            onToggle = { toggle("workspace") },
                        ) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Folder, null, tint = p.accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("默认工作区", color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { cwdPicker.launch(null) }) { Text("选择", fontSize = 13.sp) }
                                    if (state.settings.defaultCwd.isNotBlank()) {
                                        TextButton(onClick = {
                                            viewModel.setDefaultCwd("")
                                            viewModel.setCwdTreeUri("")
                                        }) { Text("清除", color = p.t3, fontSize = 13.sp) }
                                    }
                                }
                                Text(
                                    middleEllipsis(state.settings.defaultCwd.ifBlank { "未设置 · 使用应用私有目录" }),
                                    color = p.t3,
                                    fontSize = 12.sp,
                                )
                                if (state.settings.cwdTreeUri.isNotBlank()) {
                                    Text(
                                        "已授权系统目录访问；运行时读写仍按上面的绝对路径",
                                        color = p.t3,
                                        fontSize = 11.sp,
                                    )
                                }
                                // 全文件访问：Android 11+ 才存在这个设置页，低版本整条不显示。
                                val fullAccessAction = fullFileAccessAction(Build.VERSION.SDK_INT)
                                if (fullAccessAction != null) {
                                    // 用户去系统设置授权后返回要变，所以跟着 onResume 重读一次。
                                    var fullAccess by remember { mutableStateOf(AppStorage.hasFullFileAccess()) }
                                    LifecycleResumeEffect(Unit) {
                                        fullAccess = AppStorage.hasFullFileAccess()
                                        onPauseOrDispose { }
                                    }
                                    QqCardDivider()
                                    QqRow(
                                        icon = Icons.Filled.Folder,
                                        title = "全文件访问",
                                        subtitle = fullFileAccessLabel(fullAccess),
                                        tint = p.accent,
                                        onClick = {
                                            // 少数定制 ROM 缺这个设置页，回落应用详情页，用户仍能手动翻权限。
                                            val opened = runCatching { ctx.startActivity(Intent(fullAccessAction)) }
                                            if (opened.isFailure) {
                                                ctx.startActivity(
                                                    Intent(
                                                        "android.settings.APPLICATION_DETAILS_SETTINGS",
                                                        Uri.parse("package:" + ctx.packageName),
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Info,
                            title = "说明文档",
                            subtitle = README_URL,
                            tint = p.t3,
                            onClick = {
                                val opened = runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(README_URL)))
                                }
                                if (opened.isFailure) {
                                    scope.launch { snackbar.showSnackbar("没有能打开网页的应用") }
                                }
                            },
                        )
                    }
                }

                // ---------- 2. 外观 ----------
                item {
                    ExpandableSection(
                        id = "theme",
                        title = "外观",
                        subtitle = "图标 / 壁纸 / 材质 / 细节",
                        icon = Icons.Filled.Palette,
                        tint = p.accent2,
                        open = "theme" in open,
                        onToggle = { toggle("theme") },
                    ) {
                        AppearanceSectionBody(state = state, vm = viewModel)
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Bolt,
                            title = "恢复默认外观",
                            subtitle = "重置图标、壁纸、颜色与间距",
                            tint = p.t3,
                            onClick = {
                                viewModel.setAppIconStyle("system")
                                viewModel.setCustomIconUri("")
                                viewModel.setBackgroundUri("")
                                viewModel.setAccent(ACCENTS[0])
                                viewModel.setRadius(18)
                                viewModel.setFontScale(1f)
                                viewModel.setDensity(1f)
                                // 材质也一起回默认：否则「恢复默认外观」后仍停在液态，用户会以为没生效。
                                viewModel.setBackMaterial(BackMaterial.DEFAULT.key)
                                viewModel.setGlassAlpha(0.82f)
                                viewModel.setGlassBlur(0)
                            },
                        )
                    }
                }

                // ---------- 3. 会话 ----------
                item {
                    ExpandableSection(
                        id = "chat",
                        title = "会话",
                        subtitle = "发送 / 显示 / 群组 / 模板",
                        icon = Icons.Filled.Bolt,
                        tint = p.accent,
                        open = "chat" in open,
                        onToggle = { toggle("chat") },
                    ) {
                        SubGroup(
                            title = "发送与显示",
                            subtitle = "发消息 / 滚动 / 思考",
                            open = "chatSend" in open,
                            onToggle = { toggle("chatSend") },
                        ) {
                            SegmentedRow(
                                label = "运行中发消息",
                                icon = Icons.Filled.Bolt,
                                options = listOf(
                                    SendMode.Queue to "排队",
                                    SendMode.Insert to "插入",
                                    SendMode.Steer to "打断",
                                ),
                                selected = state.settings.sendMode,
                                onSelect = { viewModel.setSendMode(it) },
                            )
                            Hint("排队＝等这轮跑完再发；插入＝不打断，下一轮请求带上；打断＝立刻中止当前回复。")
                            QqCardDivider()
                            SliderRow("排队上限", "${state.settings.queueLimit} 条") {
                                Slider(
                                    value = state.settings.queueLimit.toFloat(),
                                    onValueChange = { viewModel.setQueueLimit(it.toInt()) },
                                    valueRange = 1f..50f,
                                    steps = 48,
                                )
                            }
                            QqCardDivider()
                            SwitchRow("会话分组", "按项目折叠会话列表", state.settings.groupSessions) { viewModel.setGroup(it) }
                            QqCardDivider()
                            SwitchRow("自动滚到底", "新内容到达时贴着底部", state.settings.autoScroll) { viewModel.setAutoScroll(it) }
                            QqCardDivider()
                            SwitchRow("显示耗时", "气泡右下角显示本轮用时", state.settings.showDuration) { viewModel.setShowDuration(it) }
                            QqCardDivider()
                            SwitchRow("作者着色", "群聊里不同 Agent 用不同颜色", state.settings.colorizeAuthors) { viewModel.setColorizeAuthors(it) }
                            QqCardDivider()
                            SwitchRow("显示思考过程", "Agent 的 reasoning 片段", state.settings.showThoughts) { viewModel.setShowThoughts(it) }
                            QqCardDivider()
                            SwitchRow("思考默认展开", "新会话默认展开思考过程", state.settings.thoughtsExpanded) { viewModel.setThoughtsExpanded(it) }
                            QqCardDivider()
                            SwitchRow(
                                "默认渲染特殊格式",
                                "HTML / LaTeX / Markdown / 代码块；新会话按此初始化",
                                state.settings.renderSpecialFormats,
                            ) { viewModel.setRenderSpecialFormats(it) }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "上下文与工具",
                            subtitle = "状态 / 用量 / 上限",
                            open = "chatCtx" in open,
                            onToggle = { toggle("chatCtx") },
                        ) {
                            SwitchRow("显示 Agent 状态", "运行中/等待确认标记", state.settings.showAgentStatus) { viewModel.setShowAgentStatus(it) }
                            QqCardDivider()
                            SwitchRow("显示 token 用量", "标题栏用量胶囊", state.settings.showTokenUsage) { viewModel.setShowTokens(it) }
                            QqCardDivider()
                            SwitchRow("自动确认工具", "跳过执行前的确认弹窗", state.settings.autoApproveTools) { viewModel.setAutoApprove(it) }
                            QqCardDivider()
                            SliderRow("单轮输出上限", if (state.settings.maxOutputTokens == 0) "不限制" else "${state.settings.maxOutputTokens} tokens") {
                                Slider(
                                    value = state.settings.maxOutputTokens.toFloat(),
                                    onValueChange = { viewModel.setMaxOutputTokens(it.toInt()) },
                                    valueRange = 0f..32768f,
                                    steps = 31,
                                )
                            }
                            Hint("0 = 不限制。推理模型不设上限会写出几万字思维链，群组一轮能跑十几分钟。")
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "群组编排",
                            subtitle = "并行度 / 分工 / 上下文预算",
                            open = "chatGroup" in open,
                            onToggle = { toggle("chatGroup") },
                        ) {
                            SliderRow("最大并行", "${state.settings.groupMaxParallel} 个 Agent") {
                                Slider(
                                    value = state.settings.groupMaxParallel.toFloat(),
                                    onValueChange = { viewModel.setGroupMaxParallel(it.toInt()) },
                                    valueRange = 1f..8f,
                                    steps = 6,
                                )
                            }
                            Hint("同一 Agent 可以被拉进群多次，每个实例独立上下文、独立执行。")
                            QqCardDivider()
                            SegmentedRow(
                                label = "默认编排",
                                icon = Icons.Filled.Groups,
                                options = listOf(
                                    "auto" to "自动",
                                    "parallel" to "并行",
                                    "chain" to "串行",
                                    "automaton" to "验收",
                                ),
                                selected = state.settings.groupMode,
                                onSelect = { viewModel.setGroupMode(it) },
                            )
                            Hint("自动＝按成员数挑；并行＝同时开工；串行＝依次接力；验收＝执行者与验收者交替。")
                            QqCardDivider()
                            SwitchRow("办公室式自主分工", "群聊时让 Agent 自己认领任务、互相交接", state.settings.groupOfficePrompt) {
                                viewModel.setGroupOfficePrompt(it)
                            }
                            QqCardDivider()
                            SwitchRow("群聊允许工具", "关闭后群成员只回文字，不跑 shell", state.settings.groupAllowTools) { viewModel.setGroupAllowTools(it) }
                            QqCardDivider()
                            SwitchRow(
                                "同人多次拉入各自独立",
                                "同一个 Agent 进群多次时，每个实例各记各的上下文（关掉会退化成共享）",
                                state.settings.groupIsolateMembers,
                            ) { viewModel.setGroupIsolateMembers(it) }
                            QqCardDivider()
                            SliderRow("上下文预算", "${state.settings.groupContextBudget / 1000}k 字符") {
                                Slider(
                                    value = state.settings.groupContextBudget.toFloat(),
                                    onValueChange = { viewModel.setGroupContextBudget((it / 4000f).toInt() * 4000) },
                                    valueRange = 8000f..80000f,
                                )
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "快捷指令与模板",
                            subtitle = "斜杠命令 / 常用提问",
                            open = "quick" in open,
                            onToggle = { toggle("quick") },
                        ) {
                            Text("每行一条，聊天页显示为快捷按钮", color = p.t3, fontSize = 11.5.sp)
                            Spacer(Modifier.height(7.dp))
                            TextArea(
                                value = state.settings.quickCommands.joinToString("\n"),
                                placeholder = "继续\n总结一下\n运行测试",
                                onChange = viewModel::setQuickCommands,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Prompt 模板", color = p.t3, fontSize = 11.5.sp)
                            Spacer(Modifier.height(7.dp))
                            TextArea(
                                value = state.settings.promptTemplates.joinToString("\n"),
                                placeholder = "解释这段代码\n写单元测试",
                                onChange = viewModel::setTemplates,
                            )
                        }
                    }
                }

                // ---------- 4. 通知 ----------
                item {
                    ExpandableSection(
                        id = "notify",
                        title = "通知",
                        subtitle = "常驻面板 / 思维链 / 通知内选择",
                        icon = Icons.Filled.Notifications,
                        tint = p.red,
                        open = "notify" in open,
                        onToggle = { toggle("notify") },
                    ) {
                        SwitchRow("通知", "总开关，关掉后所有提醒静默", state.settings.notificationsEnabled) {
                            viewModel.setNotifications(it)
                            if (it) askNotifyPermission()
                        }
                        QqCardDivider()
                        SwitchRow("免打扰", "静音所有提醒", state.settings.dnd) { viewModel.setDnd(it) }
                        QqCardDivider()
                        SubGroup(
                            title = "常驻聚合通知",
                            subtitle = "n 个会话在跑时展示 n 行",
                            open = "notifyOngoing" in open,
                            onToggle = { toggle("notifyOngoing") },
                        ) {
                            SwitchRow("开启常驻通知", "进行中的会话常驻通知栏", state.settings.notifyOngoing) {
                                viewModel.setNotifyOngoing(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow("展示思维链", "每行附当前思考内容", state.settings.notifyShowThought) {
                                viewModel.setNotifyShowThought(it)
                            }
                            if (state.settings.notifyShowThought) {
                                SliderRow("思维链行数", "${state.settings.notifyThoughtLines} 行") {
                                    Slider(
                                        value = state.settings.notifyThoughtLines.toFloat(),
                                        onValueChange = { viewModel.setNotifyThoughtLines(it.toInt()) },
                                        valueRange = 1f..8f,
                                        steps = 6,
                                    )
                                }
                            }
                            QqCardDivider()
                            SwitchRow("隐藏当前会话", "正在看的会话不占通知行", state.settings.notifySuppressForeground) {
                                viewModel.setNotifySuppressForeground(it)
                            }
                            QqCardDivider()
                            SwitchRow("完成项保留", "跑完后在通知里留一会儿", state.settings.notifyKeepAfterDone) {
                                viewModel.setNotifyKeepAfterDone(it)
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "提醒与选择",
                            subtitle = "完成 / 待确认 / 震动",
                            open = "notifyAlert" in open,
                            onToggle = { toggle("notifyAlert") },
                        ) {
                            SwitchRow("完成通知", "对话跑完时提醒", state.settings.notifyOnComplete) {
                                viewModel.setNotifyOnComplete(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow("待确认通知", "Agent 等你确认时提醒", state.settings.notifyOnPermission) {
                                viewModel.setNotifyOnPermission(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow("通知内直接选择", "把选项挂成通知按钮，可直接点", state.settings.notifyInlineActions) {
                                viewModel.setNotifyInlineActions(it)
                            }
                            QqCardDivider()
                            SwitchRow("回复摘要", "完成通知里带一小段回复", state.settings.notifyReplySnippet) {
                                viewModel.setNotifyReplySnippet(it)
                            }
                            QqCardDivider()
                            SwitchRow("震动", "通知同时震动", state.settings.notifyVibrate) { viewModel.setNotifyVibrate(it) }
                            if (state.settings.notifyVibrate) {
                                SliderRow("震动力度", vibrateLabel(state.settings.notifyVibrateStrength)) {
                                    Slider(
                                        value = state.settings.notifyVibrateStrength.toFloat(),
                                        onValueChange = { viewModel.setNotifyVibrateStrength(it.toInt()) },
                                        valueRange = 0f..2f,
                                        steps = 1,
                                    )
                                }
                            }
                            QqCardDivider()
                            SwitchRow("横幅", "以悬浮横幅展示，关闭后只在通知栏静默提示", state.settings.notifyHeadsUp) {
                                viewModel.setNotifyHeadsUp(it)
                                if (it) askNotifyPermission()
                            }
                        }
                    }
                }

                // ---------- 5. 环境 ----------
                item {
                    ExpandableSection(
                        id = "env",
                        title = "环境",
                        subtitle = "运行时 / 连接 / 工具 / 网络 / Linux",
                        icon = Icons.Filled.Memory,
                        tint = p.accent,
                        open = "env" in open,
                        onToggle = { toggle("env") },
                    ) {
                        SubGroup(
                            title = "运行时",
                            subtitle = "${state.versions.firstOrNull() ?: "未内置"} · ${state.storageBytes / 1024 / 1024} MB",
                            open = "envRuntime" in open,
                            onToggle = { toggle("envRuntime") },
                        ) {
                            QqRow(
                                icon = Icons.Filled.Memory,
                                title = "本地运行时",
                                subtitle = "内置引擎，无需服务器",
                                tint = p.accent,
                                onClick = { },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusCapsule(state.runtime) { viewModel.reconnect() }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "重启",
                                            color = p.accent,
                                            fontSize = 12.5.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(p.accent.copy(alpha = 0.1f))
                                                .clickable { viewModel.reconnect() }
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                        )
                                    }
                                },
                            )
                            if (state.versions.isNotEmpty()) {
                                QqCardDivider()
                                Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                                    Text("运行时版本", color = p.t3, fontSize = 11.5.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        state.versions.forEach { v ->
                                            val on = state.settings.runtimeVersion == v
                                            Text(
                                                v,
                                                color = if (on) Color.White else p.t2,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(if (on) p.accent else p.hover)
                                                    .clickable { viewModel.setRuntimeVersion(v) }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                        Text(
                                            "清日志",
                                            color = p.t2,
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(p.hover)
                                                .clickable { viewModel.clearLogs() }
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "连接",
                            subtitle = "本地 / 局域网 / 远程",
                            open = "envEndpoint" in open,
                            onToggle = { toggle("envEndpoint") },
                        ) {
                            state.endpoints.forEachIndexed { i, ep ->
                                if (i > 0) QqCardDivider()
                                val active = ep.id == state.settings.activeEndpointId
                                QqRow(
                                    icon = if (ep.kind == TransportKind.InProcess) Icons.Filled.Memory else Icons.Filled.Wifi,
                                    title = ep.name,
                                    subtitle = if (ep.kind == TransportKind.InProcess) "进程内 · 本机" else ep.url,
                                    tint = if (active) p.accent else p.t3,
                                    onClick = { viewModel.activate(ep.id) },
                                    trailing = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (active) {
                                                Text(
                                                    "使用中",
                                                    color = p.accent,
                                                    fontSize = 11.5.sp,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(p.accent.copy(alpha = 0.1f))
                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            IconBtn(Icons.Filled.Delete, "删除") { viewModel.deleteEndpoint(ep.id) }
                                        }
                                    },
                                )
                            }
                            QqCardDivider()
                            QqRow(
                                icon = Icons.Filled.Add,
                                title = "添加连接地址",
                                subtitle = "局域网 / 自定义 endpoint",
                                tint = p.t3,
                                onClick = { editing = null; showEndpointDialog = true },
                            )
                            QqCardDivider()
                            SliderRow("心跳 · 仅远程/WebSocket 生效", "${state.settings.heartbeatMs / 1000}s") {
                                Slider(
                                    value = state.settings.heartbeatMs.toFloat(),
                                    onValueChange = { viewModel.setHeartbeat((it / 5000f).toLong() * 5000L) },
                                    valueRange = 5000f..60000f,
                                )
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "工具通道",
                            subtitle = "决定 shell 命令在哪跑",
                            open = "envShell" in open,
                            onToggle = { toggle("envShell") },
                        ) {
                            QqRow(
                                icon = Icons.Filled.Terminal,
                                title = "打开终端",
                                subtitle = "内置 PTY，多标签、字号可调",
                                tint = p.accent,
                                onClick = onOpenTerminal,
                            )
                            QqCardDivider()
                            var a11yEnabled by remember { mutableStateOf(PhoneBridge(ctx).isEnabled()) }
                            LifecycleResumeEffect(Unit) {
                                a11yEnabled = PhoneBridge(ctx).isEnabled()
                                onPauseOrDispose { }
                            }
                            QqRow(
                                icon = Icons.Filled.TouchApp,
                                title = "手机控制（无障碍）",
                                subtitle = if (a11yEnabled) "已开启" else "未开启 · 开启后 App 可直接点击/滑动/输入/读屏",
                                tint = p.accent,
                                onClick = { ctx.startActivity(PhoneBridge(ctx).accessibilitySettingsIntent()) },
                            )
                            QqCardDivider()
                            QqRow(
                                icon = Icons.Filled.TouchApp,
                                title = "悬浮状态",
                                subtitle = if (PhoneBridge(ctx).isOverlayEnabled()) "已授权 · 服务连接后显示前台应用" else "授权后显示服务与前台应用状态",
                                tint = p.accent,
                                onClick = { ctx.startActivity(PhoneBridge(ctx).overlaySettingsIntent()) },
                            )
                            Hint("支持动作：点击、滑动、输入、读屏；另支持返回、主页、最近任务、打开应用与截图。")
                            QqCardDivider()
                            SegmentedRow(
                                label = "执行通道",
                                icon = Icons.Filled.Terminal,
                                options = listOf(
                                    "auto" to "自动",
                                    "proot" to "Linux",
                                    "app" to "应用内",
                                    "shizuku" to "Shizuku",
                                    "root" to "Root",
                                    "termux" to "Termux",
                                ),
                                selected = state.settings.shellChannel,
                                onSelect = { viewModel.setShellChannel(it) },
                            )
                            Hint("自动优先 Linux（proot），再选择其它可用通道。Linux 未安装时仍可选，但通道不可用；请在 Linux 环境区完成安装。")
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "网络与下载",
                            subtitle = "搜索引擎、密钥与下载限制 · 代理：" + proxyLabel(state.settings.proxyUrl),
                            open = "envWeb" in open,
                            onToggle = { toggle("envWeb") },
                        ) {
                            SegmentedRow(
                                label = "搜索引擎", icon = Icons.Filled.Wifi,
                                options = listOf("auto" to "自动", "bing" to "Bing", "duckduckgo" to "DuckDuckGo", "searxng" to "SearXNG", "exa" to "Exa"),
                                selected = state.settings.webSearchEngine,
                                onSelect = viewModel::setWebSearchEngine,
                            )
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                var instance by remember(state.settings.searxngInstance) { mutableStateOf(state.settings.searxngInstance) }
                                OutlinedTextField(
                                    value = instance, onValueChange = { instance = it }, singleLine = true,
                                    label = { Text("SearXNG 实例 URL") }, modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setSearxngInstance(instance) }, enabled = !state.busy) { Text("保存实例 URL") }
                                // Never restore the stored secret into Compose state or saved instance state.
                                var exaKey by remember { mutableStateOf("") }
                                OutlinedTextField(
                                    value = exaKey, onValueChange = { exaKey = it }, singleLine = true,
                                    label = { Text(if (exaConfigured) "Exa API Key（已保存，留空可清除）" else "Exa API Key") },
                                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setExaApiKey(exaKey); exaKey = "" }, enabled = !state.busy) { Text("保存 / 清除密钥") }
                                var maxMb by remember(state.settings.maxDownloadBytes) {
                                    mutableStateOf((state.settings.maxDownloadBytes / (1024L * 1024)).toString())
                                }
                                OutlinedTextField(
                                    value = maxMb, onValueChange = { maxMb = it }, singleLine = true,
                                    label = { Text("单次下载上限 MB（1–4096）") },
                                    isError = downloadLimitBytes(maxMb) == null, modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setMaxDownloadMb(maxMb) }, enabled = !state.busy && downloadLimitBytes(maxMb) != null) { Text("保存下载上限") }
                                var proxy by remember(state.settings.proxyUrl) { mutableStateOf(state.settings.proxyUrl) }
                                OutlinedTextField(
                                    value = proxy, onValueChange = { proxy = it }, singleLine = true,
                                    label = { Text("代理地址 HTTP(S)_PROXY（留空 = 直连）") },
                                    placeholder = { Text("http://127.0.0.1:7890") },
                                    isError = proxyError(proxy) != null, modifier = Modifier.fillMaxWidth(),
                                )
                                proxyError(proxy)?.let { Text(it, color = Color(0xFFC00000), fontSize = 12.sp) }
                                TextButton(onClick = { viewModel.setProxyUrl(proxy) }, enabled = !state.busy && proxyError(proxy) == null) { Text("保存代理") }
                                Hint("保存后所有子进程（Termux / proot / 应用内 shell，含 pip 与 git）都带上它；清空即直连。")
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "权限与后台",
                            subtitle = "无障碍、通知使用权、电池优化、Shizuku",
                            open = "envPerm" in open,
                            onToggle = { toggle("envPerm") },
                        ) {
                            var permTick by remember { mutableStateOf(0) }
                            val permBridge = remember(permTick, ctx) { PhoneBridge(ctx) }
                            val a11y = remember(permTick) { permBridge.isEnabled() }
                            val notif = remember(permTick) { permBridge.isNotificationListenerEnabled() }
                            val battery = remember(permTick) { permBridge.isIgnoringBatteryOptimizations() }
                            val shizuku = remember(permTick) { permBridge.shizukuStatus() }
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 只给状态与入口，不代用户开系统权限：这类授权必须在系统界面里确认。
                                PermissionRow(
                                    title = "无障碍服务",
                                    granted = a11y,
                                    label = if (a11y) "已启用 · 手机控制可用" else "未启用 · 手机控制不可用",
                                    actionLabel = "去开启",
                                    onAction = { ctx.startActivity(permBridge.accessibilitySettingsIntent()) },
                                )
                                PermissionRow(
                                    title = "通知使用权",
                                    granted = notif,
                                    label = if (notif) "已授权 · 通知类工具可读通知栏" else "未授权 · 通知类工具拿不到通知",
                                    actionLabel = "去授权",
                                    onAction = { ctx.startActivity(permBridge.notificationListenerIntent()) },
                                )
                                PermissionRow(
                                    title = "电池优化白名单",
                                    granted = battery,
                                    label = if (battery) "已加入 · 后台不被系统限制" else "未加入 · 长时间后台可能被系统杀掉",
                                    actionLabel = "去设置",
                                    onAction = { runCatching { ctx.startActivity(permBridge.batteryOptimizationIntent()) } },
                                )
                                PermissionRow(
                                    title = "Shizuku",
                                    granted = shizuku.ready,
                                    label = shizuku.label + if (shizuku.detail.isBlank()) "" else " · " + shizuku.detail,
                                )
                                if (shizuku.hint().isNotBlank()) Hint(shizuku.hint())
                                TextButton(onClick = { permTick += 1 }) { Text("刷新权限状态") }
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = "Linux 环境",
                            subtitle = linux.rootfs,
                            open = "envLinux" in open,
                            onToggle = { toggle("envLinux") },
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("rootfs：" + linux.rootfs, color = p.t1)
                                Text("DSH 内核：" + linux.kernel, color = p.t1)
                                Text(linux.health, color = p.t2)
                                Text("磁盘占用：" + linux.usageBytes + " 字节", color = p.t2)
                                if (linux.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(linux.progress ?: "处理中", color = p.t2)
                                }
                                TextButton(onClick = viewModel::installLinux, enabled = !linux.busy) { Text("安装 / 修复 Linux 环境") }
                                Row {
                                    TextButton(onClick = viewModel::repairRuntime, enabled = !linux.busy) { Text("修复运行时") }
                                    TextButton(onClick = { confirmingRuntimeReinstall = true }, enabled = !linux.busy) { Text("重装运行时") }
                                }
                                linux.error?.let {
                                    Text(it.lineSequence().take(2).joinToString("\n"), color = Color(0xFFC00000), fontSize = 12.sp)
                                }
                                TextButton(onClick = viewModel::installKernel, enabled = !linux.busy) { Text("安装 / 修复 DSH 内核") }
                                TextButton(onClick = viewModel::runLinuxSelfCheck, enabled = !linux.busy) { Text("运行自检") }
                                TextButton(onClick = viewModel::refreshLinux, enabled = !linux.busy) { Text("刷新状态") }
                                TextButton(onClick = viewModel::bootstrapLinuxEnv, enabled = !linux.busy) { Text("一键补齐环境（Termux / proot / pip / git）") }
                                TextButton(onClick = viewModel::cleanupLinux, enabled = !linux.busy) { Text("清理非激活版本") }
                                linux.message?.let { Text(it, color = p.t2, fontSize = 12.sp) }
                                TextButton(onClick = viewModel::clearLinuxDiagnostics, enabled = !linux.busy) { Text("清空诊断日志") }
                                SelectionContainer {
                                    Text(
                                        linux.diagnostics.ifBlank { "暂无诊断日志" }, color = p.t2, fontSize = 12.sp,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                                    )
                                }
                                linux.checks.forEach { detail -> Text(detail, color = p.t2, fontSize = 12.sp) }

                            }
                        }
                    }
                }

                // ---------- 插件自带的设置项 ----------
                if (extraSettings.isNotEmpty()) {
                    item {
                        ExpandableSection(
                            id = "pluginSettings",
                            title = "插件设置",
                            subtitle = "${extraSettings.size} 个插件项",
                            icon = Icons.Filled.Extension,
                            tint = p.accent2,
                            open = "pluginSettings" in open,
                            onToggle = { toggle("pluginSettings") },
                        ) {
                            Column(Modifier.padding(4.dp)) {
                                extraSettings.forEach { slot -> slot() }
                            }
                        }
                    }
                }


                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (editingSource) {
        var draft by remember { mutableStateOf(state.updateSource) }
        AlertDialog(
            onDismissRequest = { editingSource = false },
            title = { Text("更新源") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("清单地址 https://…/update.json") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setUpdateSource(draft.trim())
                    editingSource = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingSource = false }) { Text("取消") } },
        )
    }

    if (showEndpointDialog) {
        EndpointDialog(
            draft = editing,
            onDismiss = { showEndpointDialog = false },
            onSave = { viewModel.saveEndpoint(it); showEndpointDialog = false },
        )
    }
}
/**
 * 一级折叠卡片：整张卡片的标题行可点，展开后才是内容。
 * 收起时只显示标题 + 副标题，所以一屏能看见全部分类。
 */
@Composable
private fun ExpandableSection(
    id: String,
    title: String,
    subtitle: String?,
    icon: ImageVector,
    tint: Color,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalQq.current
    val arrow by animateFloatAsState(if (open) 90f else 0f, label = "arrow-" + id)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = SectionSpacing.horizontal, vertical = SectionSpacing.vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(11.dp))
            SectionHeading(title, subtitle, Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (open) "收起" else "展开",
                tint = p.t3,
                modifier = Modifier.size(20.dp).rotate(arrow),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                content()
            }
        }
    }
}

/** 二级折叠：分类内部再细分（缩进 + 更小的标题，避免和一级抢视觉）。 */
@Composable
private fun SubGroup(
    title: String,
    subtitle: String?,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalQq.current
    val arrow by animateFloatAsState(if (open) 90f else 0f, label = "sub-" + title)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = SectionSpacing.horizontal * 2, end = SectionSpacing.horizontal, top = SectionSpacing.vertical, bottom = SectionSpacing.vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(title, subtitle, Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (open) "收起" else "展开",
                tint = p.t3,
                modifier = Modifier.size(17.dp).rotate(arrow),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp)) { content() }
        }
    }
}

@Composable
private fun AccentChip(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(p.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 单选分段控件：一行把「排队/插入/打断」这类互斥项摊开。 */
@Composable
private fun <T> SegmentedRow(
    label: String,
    icon: ImageVector,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.t3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
        options.forEach { (value, text) ->
            val on = value == selected
            Text(
                text,
                color = if (on) Color.White else p.t2,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) p.accent else p.hover)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    label: String,
    actionLabel: String = "",
    onAction: () -> Unit = {},
) {
    val p = LocalQq.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.t1, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (granted) "已授权" else "未授权", color = if (granted) Color(0xFF22C55E) else p.t2, fontSize = 12.sp)
        }
        Text(label, color = p.t2, fontSize = 12.sp)
        if (actionLabel.isNotBlank()) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun Hint(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t3,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp),
    )
}

@Composable
private fun SliderRow(label: String, value: String, content: @Composable () -> Unit) {
    val p = LocalQq.current
    Column(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, color = p.t3, fontSize = 12.5.sp)
        }
        content()
    }
}

@Composable
private fun SwitchRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.t1, fontSize = 15.sp)
            Text(subtitle, color = p.t3, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextArea(value: String, placeholder: String, onChange: (String) -> Unit) {
    val p = LocalQq.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(p.bg)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        if (value.isBlank()) Text(placeholder, color = p.t3, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 13.5.sp, color = p.t1),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointDialog(draft: EndpointDraft?, onDismiss: () -> Unit, onSave: (EndpointDraft) -> Unit) {
    var name by remember { mutableStateOf(draft?.name ?: "") }
    var url by remember { mutableStateOf(draft?.url ?: "ws://192.168.1.10:8787/ws") }
    var token by remember { mutableStateOf(draft?.token ?: "") }
    var cwd by remember { mutableStateOf(draft?.cwd ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft == null) "添加连接" else "编辑连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Field("名称", name) { name = it }
                Field("地址", url) { url = it }
                Field("Token（可选）", token) { token = it }
                Field("工作目录（可选）", cwd) { cwd = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    EndpointDraft(
                        id = draft?.id ?: "",
                        name = name.ifBlank { "自定义连接" },
                        kind = TransportKind.Remote,
                        url = url.trim(),
                        token = token.trim(),
                        cwd = cwd.trim(),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val p = LocalQq.current
    Column {
        Text(label, color = p.t3, fontSize = 11.5.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun vibrateLabel(v: Int): String = when (v) {
    0 -> "轻"
    2 -> "重"
    else -> "标准"
}

/** 路径过长时中间省略，保留头尾便于辨认。 */
private fun middleEllipsis(path: String, max: Int = 40): String =
    if (path.length <= max) path else path.take(max / 2 - 1) + "…" + path.takeLast(max / 2 - 1)

/**
 * SAF tree uri → 本机绝对路径；映射不出来返回 null。
 *
 * 以前这里兜底拼一个 "/xxx (URI)" 的假路径塞进 defaultCwd，运行时会拿它当 cwd 启动，
 * 目录不存在 → 会话直接起不来。宁可返回 null 让调用方保留原工作区。
 */
private fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (docId.isNullOrBlank()) return null
    val volume = docId.substringBefore(':', "")
    val rel = docId.substringAfter(':', "")
    val root = when {
        volume == "primary" -> "/storage/emulated/0"
        volume.isNotBlank() -> "/storage/$volume"
        else -> return null
    }
    return if (rel.isBlank()) root else "$root/$rel"
}