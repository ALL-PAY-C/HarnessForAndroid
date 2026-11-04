package dev.dsh.mobile.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dsh.mobile.core.data.preset.AgentPresetStore
import dev.dsh.mobile.core.data.settings.SendMode
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.SectionHeading
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.StatusCapsule
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.qqPageBottomInset
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.toolbridge.AppStorage
import dev.dsh.mobile.toolbridge.ShizukuChannel
import dev.dsh.mobile.toolbridge.phone.PhoneAction
import dev.dsh.mobile.toolbridge.phone.PhoneBridge
import dev.dsh.mobile.toolbridge.phone.ShizukuState
import dev.dsh.mobile.toolbridge.phone.ShizukuStatus
import dev.dsh.mobile.toolbridge.privilege.PrivilegedExecutor
import dev.dsh.mobile.toolbridge.privilege.RootProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val README_URL = "https://github.com/ALL-PAY-C/HarnessForAndroid#readme"

/**
 * 一级列表的默认展开集合。设置页已经**没有**折叠态（一级列表全部跳子页、二级分组固定展开），
 * 这个常量留着的意义只剩一条不变式：默认没有任何分区处于展开状态。测试锁的就是它。
 */
internal const val OPEN_DEFAULT = ""

/**
 * 设置页。一级列表只有一种交互：**点一行进一个子页**。
 *
 * 以前这里是两级手风琴（一级卡点标题原地展开，二级子分组再展开一次），问题不是审美：
 * 展开会让下面所有行整体下移，用户记住的位置第二次点击落到别的卡上（实机连点 3 次误触）。
 * 现在折叠态一律不存在——列表行的右箭头永远是「进入」，页内没有原地展开，也就没有重排。
 *
 * 同一个组件同时承担「根列表」和「分类子页」两种形态，靠 [section] 切换：
 * 传 null 是根列表（全部跳子页），传分类 id 就只渲染那一块内容；页面顶栏跟着换成返回箭头。
 */

/** 分类 id → 子页标题。根列表与子页共用这张表，标题不会两处写两遍。 */
private val SECTION_TITLES: Map<String, Int> = mapOf(
    "basic" to DesignR.string.settings_section_basic,
    "chat" to DesignR.string.settings_section_chat,
    "notify" to DesignR.string.settings_section_notify,
    "env" to DesignR.string.settings_section_env,
    "pluginSettings" to DesignR.string.settings_section_plugins,
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenExtras: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    extraSettings: List<@Composable () -> Unit> = emptyList(),
    gatewayCard: (@Composable () -> Unit)? = null,
    /** 内置网关行的入口；为 null 时该行不出现（没有网关就没有这一项）。 */
    onOpenGateway: (() -> Unit)? = null,
    /** 网关行的副标题（状态摘要），由宿主算好传进来，设置页不持有网关状态。 */
    gatewaySummary: String? = null,
    /** 外观有独立页面（AppAppearanceScreen），走自己的路由。 */
    onOpenAppearance: () -> Unit = {},
    /** 手机能力授权（T4.5）也有独立页面（AppCapabilityScreen），走自己的路由。 */
    onOpenCapability: () -> Unit = {},
    /** MCP 服务器列表（T9.10 P1）同样有独立页面（McpServersScreen）。 */
    onOpenMcp: () -> Unit = {},
    /** 备份与恢复（T9.13 P3）同样有独立页面（BackupRestoreScreen）。 */
    onOpenBackup: () -> Unit = {},
    /** 诊断与崩溃报告（T9.12 步骤3）也有独立页面（DiagnosticsScreen）。 */
    onOpenDiagnostics: () -> Unit = {},
    /** null = 根列表；否则是当前子页的分类 id。 */
    section: String? = null,
    /** 群聊角色卡仓库（T9.7a）；null 时整个「群聊角色卡」分组不渲染。 */
    roleCardStore: AgentPresetStore? = null,
    /** 宿主是否已经为底部 tab 栏单独留过位置（根页 true，子页 false）。 */
    tabBarVisible: Boolean = true,
    onOpenSection: (String) -> Unit = {},
    onBack: () -> Unit = {},
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
                    snackbar.showSnackbar(ctx.getString(DesignR.string.settings_cwd_unmappable))
                }
            }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar(ctx.getString(DesignR.string.settings_notify_perm_denied)) }
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
            title = { Text(stringResource(DesignR.string.settings_reinstall_runtime)) },
            text = { Text(stringResource(DesignR.string.settings_reinstall_runtime_body)) },
            confirmButton = {
                TextButton(enabled = !linux.busy, onClick = {
                    confirmingRuntimeReinstall = false
                    viewModel.reinstallRuntime()
                }) { Text(stringResource(DesignR.string.settings_reinstall_runtime_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingRuntimeReinstall = false }) { Text(stringResource(DesignR.string.common_cancel)) } },
        )
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(
                title = if (section == null) {
                    stringResource(DesignR.string.settings_title)
                } else {
                    SECTION_TITLES[section]?.let { stringResource(it) } ?: stringResource(DesignR.string.settings_title)
                },
                isBack = section != null,
                onNavigationClick = if (section == null) onOpenDrawer else onBack,
            )
            // 底部留白只在这里算一次：tab 栏在场时它自己占位（补 0），子页/键盘弹起时补齐。
            val bottomInset = qqPageBottomInset(tabBarVisible)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = SectionSpacing.cardGap,
                    top = SectionSpacing.cardGap,
                    end = SectionSpacing.cardGap,
                    bottom = SectionSpacing.cardGap + bottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing.cardGap),
            ) {

                // ---------- 1. 应用与更新 ----------
                // 子页里只有命中的那个分类该进列表：未命中的槽位是零高 item，但 spacedBy(cardGap)
                // 照样给它留一个间距，首卡会随分类序号逐级下移（环境页因此在会话页基础上再多吃 2×12dp）。
                if (section == null || section == "basic") item {
                    SectionSlot(
                        id = "basic",
                        section = section,
                        onOpen = onOpenSection,
                        title = stringResource(DesignR.string.settings_section_basic),
                        subtitle = stringResource(DesignR.string.settings_version_sub, state.appVersion),
                        icon = Icons.Filled.SystemUpdate,
                        tint = p.accent,
                    ) {
                        QqRow(
                            icon = Icons.Filled.SystemUpdate,
                            title = stringResource(DesignR.string.settings_check_update),
                            subtitle = stringResource(DesignR.string.settings_current_version, state.appVersion),
                            tint = p.accent,
                            onClick = { viewModel.checkUpdate() },
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Link,
                            title = stringResource(DesignR.string.settings_update_source),
                            subtitle = when {
                                state.updateSource.isBlank() -> stringResource(DesignR.string.settings_update_source_builtin)
                                state.updateSource in dev.dsh.mobile.core.data.update.Updater.DEFAULT_SOURCES ||
                                    state.updateSource == dev.dsh.mobile.core.data.update.Updater.DEFAULT_SOURCE ->
                                    stringResource(DesignR.string.settings_update_source_builtin)
                                else -> state.updateSource
                            },
                            tint = p.t2,
                            onClick = { editingSource = true },
                        )
                        if (state.updateStatus != null || state.updateInfo != null) {
                            QqCardDivider()
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                Text(state.updateStatus.orEmpty(), color = p.t2, fontSize = qqFontBody(12.5.sp))
                                // 新增：本轮实际用了哪个源、花了多久（回退时写明是内存/磁盘副本）
                                state.updateSourceLine?.let { line ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(line, color = p.t3, fontSize = qqFontBody(11.5.sp))
                                }
                                state.updateInfo?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                    Spacer(Modifier.height(QqSpace.xs()))
                                    Text(notes, color = p.t3, fontSize = qqFontBody(12.sp))
                                }
                                if (state.updateProgress in 1..99) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LinearProgressIndicator(
                                            progress = { state.updateProgress / 100f },
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        // 新增：百分比数字，进度条之外再给一个能读的读数
                                        Text(
                                            "${state.updateProgress}%",
                                            color = p.t2,
                                            fontSize = qqFontBody(11.5.sp),
                                        )
                                    }
                                }
                                // 新增：sha256 校验结论。不通过时红字，且下面的「安装」不出现（updateReady 恒 false）
                                state.updateDigest?.let { digest ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        digest,
                                        color = when (state.updateDigestOk) {
                                            true -> p.green
                                            false -> p.red
                                            null -> p.t3
                                        },
                                        fontSize = qqFontBody(11.5.sp),
                                    )
                                }
                                Spacer(Modifier.height(QqSpace.s()))
                                Row(horizontalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                                    if (state.updateInfo != null && !state.updateReady) {
                                        AccentChip(stringResource(DesignR.string.settings_download)) { viewModel.downloadUpdate() }
                                    }
                                    // 校验不通过时即便 updateReady 被误置也不给安装入口
                                    if (state.updateReady && state.updateDigestOk != false) {
                                        AccentChip(stringResource(DesignR.string.settings_install)) {
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
                            icon = Icons.Filled.Widgets,
                            title = stringResource(DesignR.string.settings_extras_title),
                            subtitle = stringResource(DesignR.string.settings_extras_sub),
                            tint = p.accent,
                            onClick = onOpenExtras,
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Widgets,
                            title = stringResource(DesignR.string.settings_mcp),
                            subtitle = stringResource(DesignR.string.settings_mcp_sub),
                            tint = p.accent,
                            onClick = onOpenMcp,
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Backup,
                            title = stringResource(DesignR.string.settings_backup),
                            subtitle = stringResource(DesignR.string.settings_backup_sub),
                            tint = p.accent2,
                            onClick = onOpenBackup,
                        )
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(DesignR.string.settings_diagnostics),
                            subtitle = stringResource(DesignR.string.settings_diagnostics_sub),
                            tint = p.accent2,
                            onClick = onOpenDiagnostics,
                        )
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_workspace),
                            subtitle = state.settings.defaultCwd.ifBlank { stringResource(DesignR.string.settings_app_private_dir) },
                        ) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Folder, null, tint = p.accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(DesignR.string.settings_default_workspace), color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { cwdPicker.launch(null) }) { Text(stringResource(DesignR.string.common_select), fontSize = qqFontBody(13.sp)) }
                                    if (state.settings.defaultCwd.isNotBlank()) {
                                        TextButton(onClick = {
                                            viewModel.setDefaultCwd("")
                                            viewModel.setCwdTreeUri("")
                                        }) { Text(stringResource(DesignR.string.common_clear), color = p.t3, fontSize = qqFontBody(13.sp)) }
                                    }
                                }
                                Text(
                                    middleEllipsis(state.settings.defaultCwd.ifBlank { stringResource(DesignR.string.settings_workspace_unset) }),
                                    color = p.t3,
                                    fontSize = qqFontBody(12.sp),
                                )
                                if (state.settings.cwdTreeUri.isNotBlank()) {
                                    Text(
                                        stringResource(DesignR.string.settings_workspace_granted),
                                        color = p.t3,
                                        fontSize = qqFontBody(11.sp),
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
                                        title = stringResource(DesignR.string.settings_full_file_access),
                                        subtitle = stringResource(
                                            if (fullAccess) DesignR.string.settings_ffa_granted else DesignR.string.settings_ffa_denied,
                                        ),
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
                            title = stringResource(DesignR.string.settings_readme),
                            subtitle = README_URL,
                            tint = p.t3,
                            onClick = {
                                val opened = runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(README_URL)))
                                }
                                if (opened.isFailure) {
                                    scope.launch { snackbar.showSnackbar(ctx.getString(DesignR.string.settings_no_browser)) }
                                }
                            },
                        )
                    }
                }

                // ---------- 2. 外观（内容在 AppAppearanceScreen，这里只留入口） ----------
                // 只有根列表才有这一行；子页里再出现一次入口就成了套娃。
                if (section == null) {
                    item {
                        SectionNavRow(
                            title = stringResource(DesignR.string.settings_appearance_title),
                            subtitle = stringResource(DesignR.string.settings_appearance_sub),
                            icon = Icons.Filled.Palette,
                            tint = p.accent2,
                            onClick = onOpenAppearance,
                        )
                    }
                }

                // 手机能力授权：六类系统能力的三档开关，同样只在根列表留入口。
                if (section == null) {
                    item {
                        SectionNavRow(
                            title = stringResource(DesignR.string.settings_phone_caps),
                            subtitle = stringResource(DesignR.string.settings_phone_caps_sub),
                            icon = Icons.Filled.Lock,
                            tint = p.accent,
                            onClick = onOpenCapability,
                        )
                    }
                }

                // ---------- 3. 会话 ----------
                if (section == null || section == "chat") item {
                    SectionSlot(
                        id = "chat",
                        section = section,
                        onOpen = onOpenSection,
                        title = stringResource(DesignR.string.settings_section_chat),
                        subtitle = stringResource(DesignR.string.settings_chat_sub),
                        icon = Icons.Filled.Bolt,
                        tint = p.accent,
                    ) {
                        SubGroup(
                            title = stringResource(DesignR.string.settings_send_display),
                            subtitle = stringResource(DesignR.string.settings_send_display_sub),
                        ) {
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_send_mode),
                                icon = Icons.Filled.Bolt,
                                options = listOf(
                                    SendMode.Queue to stringResource(DesignR.string.settings_send_queue),
                                    SendMode.Insert to stringResource(DesignR.string.settings_send_insert),
                                    SendMode.Steer to stringResource(DesignR.string.settings_send_steer),
                                ),
                                selected = state.settings.sendMode,
                                onSelect = { viewModel.setSendMode(it) },
                            )
                            Hint(stringResource(DesignR.string.settings_send_mode_hint))
                            QqCardDivider()
                            SliderRow(stringResource(DesignR.string.settings_queue_limit), stringResource(DesignR.string.settings_items_count, state.settings.queueLimit)) {
                                Slider(
                                    value = state.settings.queueLimit.toFloat(),
                                    onValueChange = { viewModel.setQueueLimit(it.toInt()) },
                                    valueRange = 1f..50f,
                                    steps = 48,
                                )
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_group_sessions), stringResource(DesignR.string.settings_group_sessions_hint), state.settings.groupSessions) { viewModel.setGroup(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_auto_scroll), stringResource(DesignR.string.settings_auto_scroll_hint), state.settings.autoScroll) { viewModel.setAutoScroll(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_show_duration), stringResource(DesignR.string.settings_show_duration_hint), state.settings.showDuration) { viewModel.setShowDuration(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_colorize_authors), stringResource(DesignR.string.settings_colorize_authors_hint), state.settings.colorizeAuthors) { viewModel.setColorizeAuthors(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_show_thoughts), stringResource(DesignR.string.settings_show_thoughts_hint), state.settings.showThoughts) { viewModel.setShowThoughts(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_thoughts_expanded), stringResource(DesignR.string.settings_thoughts_expanded_hint), state.settings.thoughtsExpanded) { viewModel.setThoughtsExpanded(it) }
                            QqCardDivider()
                            SwitchRow(
                                stringResource(DesignR.string.settings_render_special),
                                stringResource(DesignR.string.settings_render_special_hint),
                                state.settings.renderSpecialFormats,
                            ) { viewModel.setRenderSpecialFormats(it) }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_context_tools),
                            subtitle = stringResource(DesignR.string.settings_context_tools_sub),
                        ) {
                            SwitchRow(stringResource(DesignR.string.settings_show_agent_status), stringResource(DesignR.string.settings_show_agent_status_hint), state.settings.showAgentStatus) { viewModel.setShowAgentStatus(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_show_token_usage), stringResource(DesignR.string.settings_show_token_usage_hint), state.settings.showTokenUsage) { viewModel.setShowTokens(it) }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_auto_approve), stringResource(DesignR.string.settings_auto_approve_hint), state.settings.autoApproveTools) { viewModel.setAutoApprove(it) }
                            QqCardDivider()
                            SliderRow(stringResource(DesignR.string.settings_max_output), if (state.settings.maxOutputTokens == 0) stringResource(DesignR.string.settings_unlimited) else "${state.settings.maxOutputTokens} tokens") {
                                Slider(
                                    value = state.settings.maxOutputTokens.toFloat(),
                                    onValueChange = { viewModel.setMaxOutputTokens(it.toInt()) },
                                    valueRange = 0f..32768f,
                                    steps = 31,
                                )
                            }
                            Hint(stringResource(DesignR.string.settings_max_output_hint))
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_group_orchestration),
                            subtitle = stringResource(DesignR.string.settings_group_orchestration_sub),
                        ) {
                            SliderRow(stringResource(DesignR.string.settings_group_max_parallel), stringResource(DesignR.string.settings_group_agents, state.settings.groupMaxParallel)) {
                                Slider(
                                    value = state.settings.groupMaxParallel.toFloat(),
                                    onValueChange = { viewModel.setGroupMaxParallel(it.toInt()) },
                                    valueRange = 1f..8f,
                                    steps = 6,
                                )
                            }
                            Hint(stringResource(DesignR.string.settings_group_multi_hint))
                            QqCardDivider()
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_group_strategy),
                                icon = Icons.Filled.Groups,
                                options = listOf(
                                    "auto" to stringResource(DesignR.string.settings_auto),
                                    "parallel" to stringResource(DesignR.string.settings_parallel),
                                    "chain" to stringResource(DesignR.string.settings_chain),
                                    "automaton" to stringResource(DesignR.string.settings_automaton),
                                ),
                                selected = state.settings.groupMode,
                                onSelect = { viewModel.setGroupMode(it) },
                            )
                            Hint(stringResource(DesignR.string.settings_group_strategy_hint))
                            QqCardDivider()
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_room_dispatch),
                                icon = Icons.Filled.Forum,
                                options = listOf(
                                    "legacy" to stringResource(DesignR.string.settings_room_round_robin),
                                    "brief-moderated" to stringResource(DesignR.string.settings_room_moderated),
                                ),
                                selected = state.settings.groupScheduler,
                                onSelect = { viewModel.setGroupScheduler(it) },
                            )
                            Hint(stringResource(DesignR.string.settings_room_dispatch_hint))
                            QqCardDivider()
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_subagent_depth),
                                icon = Icons.Filled.AccountTree,
                                options = listOf(
                                    "1" to stringResource(DesignR.string.settings_depth_one),
                                    "2" to stringResource(DesignR.string.settings_depth_two),
                                    "3" to stringResource(DesignR.string.settings_depth_three),
                                ),
                                selected = state.settings.subagentMaxDepth.coerceIn(1, 3).toString(),
                                onSelect = { viewModel.setSubagentMaxDepth(it.toInt()) },
                            )
                            Hint(stringResource(DesignR.string.settings_depth_hint))
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_office_mode), stringResource(DesignR.string.settings_office_mode_hint), state.settings.groupOfficePrompt) {
                                viewModel.setGroupOfficePrompt(it)
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_group_tools), stringResource(DesignR.string.settings_group_tools_hint), state.settings.groupAllowTools) { viewModel.setGroupAllowTools(it) }
                            QqCardDivider()
                            SwitchRow(
                                stringResource(DesignR.string.settings_group_isolated),
                                stringResource(DesignR.string.settings_group_isolated_hint),
                                state.settings.groupIsolateMembers,
                            ) { viewModel.setGroupIsolateMembers(it) }
                            QqCardDivider()
                            SliderRow(stringResource(DesignR.string.settings_context_budget), stringResource(DesignR.string.settings_chars_k, state.settings.groupContextBudget / 1000)) {
                                Slider(
                                    value = state.settings.groupContextBudget.toFloat(),
                                    onValueChange = { viewModel.setGroupContextBudget((it / 4000f).toInt() * 4000) },
                                    valueRange = 8000f..80000f,
                                )
                            }
                        }
                        if (roleCardStore != null) {
                            QqCardDivider()
                            SubGroup(
                                title = stringResource(DesignR.string.settings_role_cards),
                                subtitle = stringResource(DesignR.string.settings_role_cards_sub),
                            ) {
                                Text(
                                    stringResource(DesignR.string.settings_role_cards_hint),
                                    color = p.t3,
                                    fontSize = qqFontBody(11.5.sp),
                                )
                                Spacer(Modifier.height(7.dp))
                                GroupRoleCardsSection(roleCardStore)
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_quick_commands),
                            subtitle = stringResource(DesignR.string.settings_quick_commands_sub),
                        ) {
                            Text(stringResource(DesignR.string.settings_quick_commands_hint), color = p.t3, fontSize = qqFontBody(11.5.sp))
                            Spacer(Modifier.height(7.dp))
                            TextArea(
                                value = state.settings.quickCommands.joinToString("\n"),
                                placeholder = stringResource(DesignR.string.settings_quick_commands_placeholder),
                                onChange = viewModel::setQuickCommands,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(DesignR.string.settings_prompt_templates), color = p.t3, fontSize = qqFontBody(11.5.sp))
                            Spacer(Modifier.height(7.dp))
                            TextArea(
                                value = state.settings.promptTemplates.joinToString("\n"),
                                placeholder = stringResource(DesignR.string.settings_prompt_templates_placeholder),
                                onChange = viewModel::setTemplates,
                            )
                        }
                    }
                }

                // ---------- 4. 通知 ----------
                if (section == null || section == "notify") item {
                    SectionSlot(
                        id = "notify",
                        section = section,
                        onOpen = onOpenSection,
                        title = stringResource(DesignR.string.settings_section_notify),
                        subtitle = stringResource(DesignR.string.settings_notify_sub),
                        icon = Icons.Filled.Notifications,
                        tint = p.red,
                    ) {
                        // 不叫「通知」：subsection 页级标题已经占了这个词，开关标签同名会让 dump 里出现两个精确相等的节点。
                        SwitchRow(stringResource(DesignR.string.settings_notify_enabled), stringResource(DesignR.string.settings_notify_enabled_hint), state.settings.notificationsEnabled) {
                            viewModel.setNotifications(it)
                            if (it) askNotifyPermission()
                        }
                        QqCardDivider()
                        SwitchRow(stringResource(DesignR.string.settings_dnd), stringResource(DesignR.string.settings_dnd_hint), state.settings.dnd) { viewModel.setDnd(it) }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_ongoing),
                            subtitle = stringResource(DesignR.string.settings_ongoing_hint),
                        ) {
                            SwitchRow(stringResource(DesignR.string.settings_ongoing_enabled), stringResource(DesignR.string.settings_ongoing_enabled_hint), state.settings.notifyOngoing) {
                                viewModel.setNotifyOngoing(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_thought), stringResource(DesignR.string.settings_notify_thought_hint), state.settings.notifyShowThought) {
                                viewModel.setNotifyShowThought(it)
                            }
                            if (state.settings.notifyShowThought) {
                                SliderRow(stringResource(DesignR.string.settings_notify_thought_lines), stringResource(DesignR.string.settings_lines, state.settings.notifyThoughtLines)) {
                                    Slider(
                                        value = state.settings.notifyThoughtLines.toFloat(),
                                        onValueChange = { viewModel.setNotifyThoughtLines(it.toInt()) },
                                        valueRange = 1f..8f,
                                        steps = 6,
                                    )
                                }
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_hide_current), stringResource(DesignR.string.settings_notify_hide_current_hint), state.settings.notifySuppressForeground) {
                                viewModel.setNotifySuppressForeground(it)
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_keep_done), stringResource(DesignR.string.settings_notify_keep_done_hint), state.settings.notifyKeepAfterDone) {
                                viewModel.setNotifyKeepAfterDone(it)
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_notify_alerts),
                            subtitle = stringResource(DesignR.string.settings_notify_alerts_sub),
                        ) {
                            SwitchRow(stringResource(DesignR.string.settings_notify_complete), stringResource(DesignR.string.settings_notify_complete_hint), state.settings.notifyOnComplete) {
                                viewModel.setNotifyOnComplete(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_permission), stringResource(DesignR.string.settings_notify_permission_hint), state.settings.notifyOnPermission) {
                                viewModel.setNotifyOnPermission(it)
                                if (it) askNotifyPermission()
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_inline), stringResource(DesignR.string.settings_notify_inline_hint), state.settings.notifyInlineActions) {
                                viewModel.setNotifyInlineActions(it)
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_snippet), stringResource(DesignR.string.settings_notify_snippet_hint), state.settings.notifyReplySnippet) {
                                viewModel.setNotifyReplySnippet(it)
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_vibrate), stringResource(DesignR.string.settings_notify_vibrate_hint), state.settings.notifyVibrate) { viewModel.setNotifyVibrate(it) }
                            if (state.settings.notifyVibrate) {
                                SliderRow(stringResource(DesignR.string.settings_vibrate_strength), vibrateLabel(state.settings.notifyVibrateStrength)) {
                                    Slider(
                                        value = state.settings.notifyVibrateStrength.toFloat(),
                                        onValueChange = { viewModel.setNotifyVibrateStrength(it.toInt()) },
                                        valueRange = 0f..2f,
                                        steps = 1,
                                    )
                                }
                            }
                            QqCardDivider()
                            SwitchRow(stringResource(DesignR.string.settings_notify_heads_up), stringResource(DesignR.string.settings_notify_heads_up_hint), state.settings.notifyHeadsUp) {
                                viewModel.setNotifyHeadsUp(it)
                                if (it) askNotifyPermission()
                            }
                        }
                    }
                }

                // ---------- 5. 环境 ----------
                if (section == null || section == "env") item {
                    SectionSlot(
                        id = "env",
                        section = section,
                        onOpen = onOpenSection,
                        title = stringResource(DesignR.string.settings_section_env),
                        subtitle = stringResource(DesignR.string.settings_env_sub),
                        icon = Icons.Filled.Memory,
                        tint = p.accent,
                    ) {
                        SubGroup(
                            title = stringResource(DesignR.string.settings_runtime),
                            subtitle = "${state.versions.firstOrNull() ?: "未内置"} · ${state.storageBytes / 1024 / 1024} MB",
                        ) {
                            QqRow(
                                icon = Icons.Filled.Memory,
                                title = stringResource(DesignR.string.settings_runtime_local),
                                subtitle = stringResource(DesignR.string.settings_runtime_local_sub),
                                tint = p.accent,
                                onClick = { },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusCapsule(state.runtime) { viewModel.reconnect() }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(DesignR.string.settings_restart),
                                            color = p.accent,
                                            fontSize = qqFontBody(12.5.sp),
                                            modifier = Modifier
                                                .clip(QqShape.chip())
                                                .background(p.accent.copy(alpha = 0.1f))
                                                .clickable { viewModel.reconnect() }
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                        )
                                    }
                                },
                            )
                            if (state.versions.isNotEmpty()) {
                                QqCardDivider()
                                Column(Modifier.padding(horizontal = 13.dp, vertical = QqSpace.s())) {
                                    Text(stringResource(DesignR.string.settings_runtime_version), color = p.t3, fontSize = qqFontBody(11.5.sp))
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        state.versions.forEach { v ->
                                            val on = state.settings.runtimeVersion == v
                                            Text(
                                                v,
                                                color = if (on) Color.White else p.t2,
                                                fontSize = qqFontBody(12.sp),
                                                modifier = Modifier
                                                    .clip(QqShape.chip())
                                                    .background(if (on) p.accent else p.hover)
                                                    .clickable { viewModel.setRuntimeVersion(v) }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                        Text(
                                            stringResource(DesignR.string.settings_clear_logs),
                                            color = p.t2,
                                            fontSize = qqFontBody(12.sp),
                                            modifier = Modifier
                                                .clip(QqShape.chip())
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
                            title = stringResource(DesignR.string.settings_connections),
                            subtitle = stringResource(DesignR.string.settings_connections_sub),
                        ) {
                            state.endpoints.forEachIndexed { i, ep ->
                                if (i > 0) QqCardDivider()
                                val active = ep.id == state.settings.activeEndpointId
                                QqRow(
                                    icon = if (ep.kind == TransportKind.InProcess) Icons.Filled.Memory else Icons.Filled.Wifi,
                                    title = ep.name,
                                    subtitle = if (ep.kind == TransportKind.InProcess) stringResource(DesignR.string.settings_conn_inprocess) else ep.url,
                                    tint = if (active) p.accent else p.t3,
                                    onClick = { viewModel.activate(ep.id) },
                                    trailing = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (active) {
                                                Text(
                                                    stringResource(DesignR.string.settings_conn_active),
                                                    color = p.accent,
                                                    fontSize = qqFontBody(11.5.sp),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(p.accent.copy(alpha = 0.1f))
                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            IconBtn(Icons.Filled.Delete, stringResource(DesignR.string.common_delete)) { viewModel.deleteEndpoint(ep.id) }
                                        }
                                    },
                                )
                            }
                            QqCardDivider()
                            QqRow(
                                icon = Icons.Filled.Add,
                                title = stringResource(DesignR.string.settings_conn_add),
                                subtitle = stringResource(DesignR.string.settings_conn_add_sub),
                                tint = p.t3,
                                onClick = { editing = null; showEndpointDialog = true },
                            )
                            QqCardDivider()
                            SliderRow(stringResource(DesignR.string.settings_heartbeat), "${state.settings.heartbeatMs / 1000}s") {
                                Slider(
                                    value = state.settings.heartbeatMs.toFloat(),
                                    onValueChange = { viewModel.setHeartbeat((it / 5000f).toLong() * 5000L) },
                                    valueRange = 5000f..60000f,
                                )
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_tool_channels),
                            subtitle = stringResource(DesignR.string.settings_tool_channels_sub),
                        ) {
                            QqRow(
                                icon = Icons.Filled.Terminal,
                                title = stringResource(DesignR.string.settings_open_terminal),
                                subtitle = stringResource(DesignR.string.settings_open_terminal_sub),
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
                                title = stringResource(DesignR.string.settings_phone_control),
                                subtitle = if (a11yEnabled) stringResource(DesignR.string.settings_on) else stringResource(DesignR.string.settings_phone_control_off),
                                tint = p.accent,
                                onClick = { ctx.startActivity(PhoneBridge(ctx).accessibilitySettingsIntent()) },
                            )
                            QqCardDivider()
                            QqRow(
                                icon = Icons.Filled.TouchApp,
                                title = stringResource(DesignR.string.settings_overlay),
                                subtitle = if (PhoneBridge(ctx).isOverlayEnabled()) stringResource(DesignR.string.settings_overlay_on) else stringResource(DesignR.string.settings_overlay_off),
                                tint = p.accent,
                                onClick = { ctx.startActivity(PhoneBridge(ctx).overlaySettingsIntent()) },
                            )
                            // 文案由动作目录派生：以前手写 9 项，实际早就 24 项，加动作没人记得改文案。
                            Hint(
                                stringResource(
                                    DesignR.string.settings_phone_actions,
                                    PhoneAction.entries.joinToString(stringResource(DesignR.string.common_list_separator)) { it.label },
                                ),
                            )
                            QqCardDivider()
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_exec_channel),
                                icon = Icons.Filled.Terminal,
                                options = listOf(
                                    "auto" to stringResource(DesignR.string.settings_auto),
                                    "proot" to "Linux",
                                    "app" to stringResource(DesignR.string.settings_exec_in_app),
                                    "shizuku" to "Shizuku",
                                    "root" to "Root",
                                    "termux" to "Termux",
                                ),
                                selected = state.settings.shellChannel,
                                onSelect = { viewModel.setShellChannel(it) },
                            )
                            Hint(stringResource(DesignR.string.settings_exec_channel_hint))
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_network),
                            subtitle = stringResource(
                                DesignR.string.settings_network_sub,
                                proxyLabel(state.settings.proxyUrl) ?: stringResource(DesignR.string.settings_direct),
                            ),
                        ) {
                            SegmentedRow(
                                label = stringResource(DesignR.string.settings_search_engine), icon = Icons.Filled.Wifi,
                                options = listOf("auto" to stringResource(DesignR.string.settings_auto), "bing" to "Bing", "duckduckgo" to "DuckDuckGo", "searxng" to "SearXNG", "exa" to "Exa"),
                                selected = state.settings.webSearchEngine,
                                onSelect = viewModel::setWebSearchEngine,
                            )
                            Column(Modifier.padding(QqSpace.l()), verticalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                                var instance by remember(state.settings.searxngInstance) { mutableStateOf(state.settings.searxngInstance) }
                                OutlinedTextField(
                                    value = instance, onValueChange = { instance = it }, singleLine = true,
                                    label = { Text(stringResource(DesignR.string.settings_searxng_url)) }, modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setSearxngInstance(instance) }, enabled = !state.busy) { Text(stringResource(DesignR.string.settings_save_searxng)) }
                                // Never restore the stored secret into Compose state or saved instance state.
                                var exaKey by remember { mutableStateOf("") }
                                OutlinedTextField(
                                    value = exaKey, onValueChange = { exaKey = it }, singleLine = true,
                                    label = { Text(if (exaConfigured) stringResource(DesignR.string.settings_exa_key_saved) else "Exa API Key") },
                                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setExaApiKey(exaKey); exaKey = "" }, enabled = !state.busy) { Text(stringResource(DesignR.string.settings_save_exa_key)) }
                                var maxMb by remember(state.settings.maxDownloadBytes) {
                                    mutableStateOf((state.settings.maxDownloadBytes / (1024L * 1024)).toString())
                                }
                                OutlinedTextField(
                                    value = maxMb, onValueChange = { maxMb = it }, singleLine = true,
                                    label = { Text(stringResource(DesignR.string.settings_max_download_mb)) },
                                    isError = downloadLimitBytes(maxMb) == null, modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { viewModel.setMaxDownloadMb(maxMb) }, enabled = !state.busy && downloadLimitBytes(maxMb) != null) { Text(stringResource(DesignR.string.settings_save_max_download)) }
                                var proxy by remember(state.settings.proxyUrl) { mutableStateOf(state.settings.proxyUrl) }
                                OutlinedTextField(
                                    value = proxy, onValueChange = { proxy = it }, singleLine = true,
                                    label = { Text(stringResource(DesignR.string.settings_proxy_field)) },
                                    placeholder = { Text("http://127.0.0.1:7890") },
                                    isError = proxyInvalid(proxy), modifier = Modifier.fillMaxWidth(),
                                )
                                if (proxyInvalid(proxy)) {

                                Text(stringResource(DesignR.string.settings_proxy_invalid), color = p.red, fontSize = qqFontBody(12.sp))

                            }
                                TextButton(onClick = { viewModel.setProxyUrl(proxy) }, enabled = !state.busy && !proxyInvalid(proxy)) { Text(stringResource(DesignR.string.settings_save_proxy)) }
                                Hint(stringResource(DesignR.string.settings_proxy_hint))
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_permissions),
                            subtitle = stringResource(DesignR.string.settings_permissions_sub),
                        ) {
                            var permTick by remember { mutableStateOf(0) }
                            val permBridge = remember(permTick, ctx) { PhoneBridge(ctx) }
                            val a11y = remember(permTick) { permBridge.isEnabled() }
                            val notif = remember(permTick) { permBridge.isNotificationListenerEnabled() }
                            val battery = remember(permTick) { permBridge.isIgnoringBatteryOptimizations() }
                            // 通道实例要跨重组保留：授权回调注册在它身上，每次重组重建就永远收不到结果。
                            val shizukuChannel = remember { ShizukuChannel(ctx) }
                            val shizuku = remember(permTick) { shizukuChannel.status() }
                            DisposableEffect(shizukuChannel) {
                                shizukuChannel.registerListener { permTick += 1 }
                                onDispose { shizukuChannel.unregisterListener() }
                            }
                            // root 探测起真 su（最坏 2×4s），不能压在组合线程上。
                            var rootProbe by remember(permTick) { mutableStateOf<RootProbe?>(null) }
                            LaunchedEffect(permTick) {
                                rootProbe = withContext(Dispatchers.IO) {
                                    runCatching { PrivilegedExecutor.probe() }.getOrNull()
                                }
                            }
                            Column(Modifier.padding(QqSpace.l()), verticalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                                // 只给状态与入口，不代用户开系统权限：这类授权必须在系统界面里确认。
                                PermissionRow(
                                    title = stringResource(DesignR.string.settings_a11y_service),
                                    granted = a11y,
                                    label = if (a11y) stringResource(DesignR.string.settings_a11y_on) else stringResource(DesignR.string.settings_a11y_off),
                                    actionLabel = stringResource(DesignR.string.settings_go_enable),
                                    onAction = { ctx.startActivity(permBridge.accessibilitySettingsIntent()) },
                                )
                                PermissionRow(
                                    title = stringResource(DesignR.string.settings_notif_access),
                                    granted = notif,
                                    label = if (notif) stringResource(DesignR.string.settings_notif_access_on) else stringResource(DesignR.string.settings_notif_access_off),
                                    actionLabel = stringResource(DesignR.string.settings_authorize),
                                    onAction = { ctx.startActivity(permBridge.notificationListenerIntent()) },
                                )
                                PermissionRow(
                                    title = stringResource(DesignR.string.settings_battery_whitelist),
                                    granted = battery,
                                    label = if (battery) stringResource(DesignR.string.settings_battery_on) else stringResource(DesignR.string.settings_battery_off),
                                    actionLabel = stringResource(DesignR.string.settings_go_settings),
                                    onAction = { runCatching { ctx.startActivity(permBridge.batteryOptimizationIntent()) } },
                                )
                                PermissionRow(
                                    title = "Shizuku",
                                    granted = shizuku.ready,
                                    label = shizuku.label + if (shizuku.detail.isBlank()) "" else " · " + shizuku.detail,
                                    actionLabel = when (shizuku.state) {
                                        ShizukuState.NotInstalled -> stringResource(DesignR.string.settings_shizuku_not_installed)
                                        ShizukuState.NotRunning -> stringResource(DesignR.string.settings_shizuku_open)
                                        ShizukuState.NoPermission -> stringResource(DesignR.string.settings_authorize)
                                        ShizukuState.Ready -> stringResource(DesignR.string.settings_refresh)
                                    },
                                    // 没装时没有可执行的动作：按钮灰掉，原因写在 label 与 hint 里。
                                    enabled = shizuku.state != ShizukuState.NotInstalled,
                                    onAction = {
                                        when (shizuku.state) {
                                            ShizukuState.NotInstalled -> Unit
                                            ShizukuState.NotRunning -> openShizukuApp(ctx)
                                            ShizukuState.NoPermission -> shizukuChannel.requestPermission()
                                            ShizukuState.Ready -> Unit
                                        }
                                        permTick += 1
                                    },
                                )
                                if (shizuku.hint().isNotBlank()) Hint(shizuku.hint())
                                PermissionRow(
                                    title = "Root",
                                    granted = rootProbe?.form?.available == true,
                                    label = rootProbe?.detail ?: stringResource(DesignR.string.settings_probing),
                                    actionLabel = if (rootProbe == null) "" else stringResource(DesignR.string.settings_reprobe),
                                    enabled = rootProbe != null,
                                    onAction = {
                                        PrivilegedExecutor.invalidate()
                                        permTick += 1
                                    },
                                )
                                TextButton(onClick = { permTick += 1 }) { Text(stringResource(DesignR.string.settings_refresh_perms)) }
                            }
                        }
                        QqCardDivider()
                        SubGroup(
                            title = stringResource(DesignR.string.settings_linux),
                            subtitle = linux.rootfs,
                        ) {
                            Column(Modifier.padding(QqSpace.l()), verticalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                                Text(stringResource(DesignR.string.settings_linux_rootfs, linux.rootfs), color = p.t1)
                                Text(stringResource(DesignR.string.settings_linux_kernel, linux.kernel), color = p.t1)
                                Text(linux.health, color = p.t2)
                                Text(stringResource(DesignR.string.settings_linux_usage, linux.usageBytes), color = p.t2)
                                if (linux.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(linux.progress ?: stringResource(DesignR.string.settings_processing), color = p.t2)
                                }
                                TextButton(onClick = viewModel::installLinux, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_linux_install)) }
                                Row {
                                    TextButton(onClick = viewModel::repairRuntime, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_repair_runtime)) }
                                    TextButton(onClick = { confirmingRuntimeReinstall = true }, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_reinstall_runtime)) }
                                }
                                linux.error?.let {
                                    Text(it.lineSequence().take(2).joinToString("\n"), color = p.red, fontSize = qqFontBody(12.sp))
                                }
                                TextButton(onClick = viewModel::installKernel, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_kernel_install)) }
                                TextButton(onClick = viewModel::runLinuxSelfCheck, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_self_check)) }
                                TextButton(onClick = viewModel::refreshLinux, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_refresh_state)) }
                                TextButton(onClick = viewModel::bootstrapLinuxEnv, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_bootstrap_env)) }
                                TextButton(onClick = viewModel::cleanupLinux, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_cleanup_versions)) }
                                linux.message?.let { Text(it, color = p.t2, fontSize = qqFontBody(12.sp)) }
                                TextButton(onClick = viewModel::clearLinuxDiagnostics, enabled = !linux.busy) { Text(stringResource(DesignR.string.settings_clear_diagnostics)) }
                                SelectionContainer {
                                    Text(
                                        linux.diagnostics.ifBlank { stringResource(DesignR.string.settings_no_diagnostics) }, color = p.t2, fontSize = qqFontBody(12.sp),
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                                    )
                                }
                                linux.checks.forEach { detail -> Text(detail, color = p.t2, fontSize = qqFontBody(12.sp)) }

                            }
                        }
                    }
                }

                // ---------- 宿主内置网关：靠下、默认收起（没有就不占位） ----------
                // 以前这里直接贴一张 Material Card，裸文本 + 蓝色文字链接浮在页面底色上（O2）。
                // 现在它和别的分类一样是一行：同样的圆角/内边距/字号，点进去才是详情。
                if (section == null && onOpenGateway != null && (gatewayCard != null || gatewaySummary != null)) {
                    item {
                        SectionNavRow(
                            title = stringResource(DesignR.string.settings_gateway),
                            subtitle = gatewaySummary ?: stringResource(DesignR.string.settings_gateway_sub),
                            icon = Icons.Filled.Link,
                            tint = p.accent,
                            onClick = { onOpenGateway?.invoke() },
                        )
                    }
                }

                // ---------- 插件自带的设置项 ----------
                if (extraSettings.isNotEmpty() && (section == null || section == "pluginSettings")) {
                    item {
                        SectionSlot(
                            id = "pluginSettings",
                            section = section,
                            onOpen = onOpenSection,
                            title = stringResource(DesignR.string.settings_section_plugins),
                            subtitle = stringResource(DesignR.string.settings_plugin_items, extraSettings.size),
                            icon = Icons.Filled.Extension,
                            tint = p.accent2,
                        ) {
                            Column(Modifier.padding(QqSpace.xs())) {
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
            title = { Text(stringResource(DesignR.string.settings_update_source)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(DesignR.string.settings_manifest_url)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setUpdateSource(draft.trim())
                    editingSource = false
                }) { Text(stringResource(DesignR.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { editingSource = false }) { Text(stringResource(DesignR.string.common_cancel)) } },
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
 * 一级列表行：设置页唯一的范式——点一下进子页，右箭头永远朝右，没有任何原地展开。
 * 形状 / 间距 / 字号与原来的折叠卡头完全一致，所以列表看起来没变，变的是点击结果可预判。
 */
@Composable
private fun SectionNavRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(QqShape.card())
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = SectionSpacing.horizontal, vertical = SectionSpacing.vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(QqShape.chip())
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        SectionHeading(title, subtitle, Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(DesignR.string.settings_enter),
            tint = p.t3,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 子页里承载某个分类内容的卡：圆角 / 分隔线 / 内边距与列表行同一套，但**不可折叠**——
 * 这一页只有这一个分类，折叠没有意义，只会把「点完位置就变」的问题再引一遍。
 */
@Composable
internal fun SectionFrame(
    title: String?,
    icon: ImageVector?,
    tint: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalQq.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(QqShape.card())
            .background(p.card),
    ) {
        // 顶栏已经写了分类名时不重复报一遍：传 null 就只留卡片本体。
        if (title != null && icon != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SectionSpacing.horizontal, vertical = SectionSpacing.vertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(QqShape.chip())
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(11.dp))
                SectionHeading(title, null, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
        content()
    }
}

/**
 * 一个分类槽位。[section] 为 null（根列表）时渲染成一行入口；等于自身 [id] 时渲染成内容卡。
 * 根列表与子页因此是同一个组件、同一套行样式，不会出现「两套实现各走各的」。
 */
@Composable
private fun SectionSlot(
    id: String,
    title: String,
    subtitle: String?,
    icon: ImageVector,
    tint: Color,
    section: String?,
    onOpen: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    when {
        section == null -> SectionNavRow(title, subtitle, icon, tint) { onOpen(id) }
        // 子页顶栏（:222 的 QqScreenTopBar 用 SECTION_TITLES[section]）已经写了分类名，
        // 这里再传 title 就是同一页两处页级标题；标题行原本撑出的上间距由卡片内首行
        // 自带的内边距（QqRow/SwitchRow 11dp、SubGroup 12dp）承担，首行不会贴到卡片顶边。
        section == id -> SectionFrame(title = null, icon = icon, tint = tint, content = content)
    }
}

/** 二级分组标题：**固定展开**。以前这里也能折叠，展开后整列下移，同一坐标第二次点击落到别处。 */
@Composable
private fun SubGroup(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SectionSpacing.horizontal * 2,
                    end = SectionSpacing.horizontal,
                    top = SectionSpacing.vertical,
                    bottom = SectionSpacing.vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(title, subtitle, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().padding(start = QqSpace.m())) { content() }
    }
}

@Composable
private fun AccentChip(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Text(
        label,
        color = Color.White,
        fontSize = qqFontBody(13.sp),
        modifier = Modifier
            .clip(QqShape.chip())
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
                fontSize = qqFontBody(12.sp),
                modifier = Modifier
                    .padding(start = 5.dp)
                    .clip(QqShape.chip())
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
    enabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    val p = LocalQq.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.t1, fontSize = qqFontBody(14.sp), modifier = Modifier.weight(1f))
            Text(if (granted) stringResource(DesignR.string.settings_granted) else stringResource(DesignR.string.settings_ungranted), color = if (granted) p.green else p.t2, fontSize = qqFontBody(12.sp))
        }
        Text(label, color = p.t2, fontSize = qqFontBody(12.sp))
        if (actionLabel.isNotBlank()) TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
    }
}

/** 拉起 Shizuku 本体；没装或没有启动入口就静默返回，设置页不因此崩。 */
private fun openShizukuApp(ctx: Context) {
    runCatching {
        ctx.packageManager.getLaunchIntentForPackage(ShizukuStatus.PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { ctx.startActivity(it) }
    }
}

@Composable
private fun Hint(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t3,
        fontSize = qqFontBody(11.sp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = QqSpace.xs()),
    )
}

@Composable
private fun SliderRow(label: String, value: String, content: @Composable () -> Unit) {
    val p = LocalQq.current
    Column(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, color = p.t3, fontSize = qqFontBody(12.5.sp))
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
            Text(subtitle, color = p.t3, fontSize = qqFontBody(11.5.sp))
        }
        Spacer(Modifier.width(QqSpace.s()))
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
            .clip(QqShape.field())
            .background(p.bg)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        if (value.isBlank()) Text(placeholder, color = p.t3, fontSize = qqFontBody(13.sp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = qqFontBody(13.5.sp), color = p.t1),
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
    // onClick 里不能调 composable，标题文案先在组合期取出来。
    val customConnectionLabel = stringResource(DesignR.string.settings_custom_connection)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft == null) stringResource(DesignR.string.settings_conn_add_title) else stringResource(DesignR.string.settings_conn_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Field(stringResource(DesignR.string.settings_field_name), name) { name = it }
                Field(stringResource(DesignR.string.settings_field_url), url) { url = it }
                Field(stringResource(DesignR.string.settings_field_token), token) { token = it }
                Field(stringResource(DesignR.string.settings_field_cwd), cwd) { cwd = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    EndpointDraft(
                        id = draft?.id ?: "",
                        name = name.ifBlank { customConnectionLabel },
                        kind = TransportKind.Remote,
                        url = url.trim(),
                        token = token.trim(),
                        cwd = cwd.trim(),
                    ),
                )
            }) { Text(stringResource(DesignR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel)) } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val p = LocalQq.current
    Column {
        Text(label, color = p.t3, fontSize = qqFontBody(11.5.sp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(QqShape.chip())
                .background(p.bg)
                .padding(horizontal = 10.dp, vertical = QqSpace.s()),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = qqFontBody(14.sp), color = p.t1),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun vibrateLabel(v: Int): String = stringResource(
    when (v) {
        0 -> DesignR.string.settings_vibrate_light
        2 -> DesignR.string.settings_vibrate_heavy
        else -> DesignR.string.settings_vibrate_normal
    },
)

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