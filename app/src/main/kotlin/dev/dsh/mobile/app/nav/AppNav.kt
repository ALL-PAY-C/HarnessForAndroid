package dev.dsh.mobile.app.nav

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.activity.compose.BackHandler
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.content.Intent
import android.net.Uri
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.app.diag.DiagnosticsScreen
import dev.dsh.mobile.app.diag.SafeModeBanner
import dev.dsh.mobile.app.phone.PhoneControlScreen
import dev.dsh.mobile.app.update.AutoUpdatePrompt
import dev.dsh.mobile.core.model.RuntimeState
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.QqTabBar
import dev.dsh.mobile.design.TabSpec
import dev.dsh.mobile.design.UiLayoutPrefs
import dev.dsh.mobile.feature.plugins.PluginHubScreen
import dev.dsh.mobile.feature.plugins.pluginSettingsEntries
import dev.dsh.mobile.feature.plugins.SkillCenterBarActions
import dev.dsh.mobile.feature.plugins.SkillCenterScreen
import dev.dsh.mobile.feature.plugins.rememberSkillCenterBarState
import java.io.File
import dev.dsh.mobile.feature.plugins.PluginMarketScreen
import dev.dsh.mobile.feature.plugins.PluginsScreen
import dev.dsh.mobile.feature.plugins.PluginsViewModel
import dev.dsh.mobile.pluginapi.DiagramRenderers
import dev.dsh.mobile.pluginapi.HtmlBlockRenderers
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.pluginapi.DrawerEntry
import dev.dsh.mobile.pluginapi.PluginConfigPage
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginhost.PluginHostHolder
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.feature.plugins.mergeHubSlots
import dev.dsh.mobile.feature.plugins.bridgedCandidates
import dev.dsh.mobile.feature.plugins.drawerEntryResolver
import dev.dsh.mobile.feature.agents.AgentsScreen
import dev.dsh.mobile.feature.agents.AgentsViewModel
import dev.dsh.mobile.feature.chat.ChatScreen
import dev.dsh.mobile.feature.chat.ChatSettingsScreen
import dev.dsh.mobile.feature.chat.ChatViewModel
import dev.dsh.mobile.feature.chat.PresetDrawerPanel
import dev.dsh.mobile.feature.chat.PresetDrawerViewModel
import dev.dsh.mobile.feature.sessions.SessionsScreen
import dev.dsh.mobile.feature.sessions.SessionsViewModel
import dev.dsh.mobile.feature.settings.AppAppearanceScreen
import dev.dsh.mobile.feature.settings.BackupRestoreScreen
import dev.dsh.mobile.feature.settings.AppCapabilityScreen
import dev.dsh.mobile.feature.settings.ExtrasScreen
import dev.dsh.mobile.feature.settings.McpServersScreen
import dev.dsh.mobile.feature.settings.SettingsScreen
import dev.dsh.mobile.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map


// 内置 tab 的文字走设计模块资源（DesignR）：这里是非 Composable 顶层 val，
// 只能存资源 id，取值在 QqTabBar 里做（T9.11 §3.2）。
/**
 * Built-in tabs plus whatever plugins contributed through the BottomTab slot.
 * Plugin tabs get a synthetic `plugin/<id>` route so navigation stays uniform.
 */

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val runtime: RuntimeState by container.runtime.collectAsStateWithLifecycle()
    // 界面级偏好（三套字号）从 SharedPreferences 装一次。
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        UiLayoutPrefs.attach(appContext)
    }

    // 通知点击 → 直接进对应会话；消费一次就清空，返回键不会又被弹回来。
    val pendingSession by container.pendingSession.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSession) {
        val target = pendingSession ?: return@LaunchedEffect
        container.consumePendingSession()
        nav.navigate(Routes.chat(target)) { launchSingleTop = true }
    }

    // First frame is up: tell the crash guard this boot survived.
    // bootGuard.onFirstFrame() 内部仍调 pluginHost.markBootOk()（插件层的安全模式语义不变），
    // 另外把 SafeMode 的 boot.attempt 归零并停掉 8s 看门狗。
    LaunchedEffect(Unit) {
        delay(2_500)
        container.bootGuard.onFirstFrame()
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = tabsWithPlugins(container)
    val tabRoutes = tabs.map { it.second }
    val tabIndex = tabs.indexOfFirst { it.second == route }

    // 二级页（抽屉页 / 插件列表 / 槽位管理 / 终端）自己 popBackStack。
    // 不接管的话 NavHost 按 tab 的 popUpTo 语义退栈：用户从「设置 → 插件」按返回
    // 会直接落到会话主页。popBackStack 失败时兜底回会话 tab，绝不把返回吞掉。
    val back = backTarget(route, tabRoutes)
    BackHandler(enabled = back == BackTarget.PopRoute) {
        if (!nav.popBackStack()) nav.navigate(Routes.SESSIONS) { launchSingleTop = true }
    }

    // 抽屉条目 = 用户显式摆进「左侧抽屉」槽位的贡献 + 宿主内置入口（技能中心）。
    // PluginPage 只在插件中心内部可浏览，绝不自动插进抽屉。原生 DrawerEntry 与声明式
    // 槽位贡献都必须由用户在「插件槽位」里显式摆放后才出现；原生项渲染走其 content+icon 直画。
    val drawerContext = LocalContext.current
    // Agent 预设面板的数据源：和设置页同一套 Store/Importer，只是换成抽屉里的一份 VM，
    // 这样抽屉路由与聊天页拿到的是同一个实例（默认预设切换后抽屉标题立刻跟着变）。
    val presetVm: PresetDrawerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PresetDrawerViewModel(
                    container.presetStore,
                    container.settings,
                    container.presetImporter,
                    // 旧自由文本人设还在会话实体上：没有卡时用它合成只读卡展示。
                    personaOf = { id -> container.db.sessionDao().byId(id)?.persona.orEmpty() },
                )
            }
        },
    )
    val presetState by presetVm.uiState.collectAsStateWithLifecycle()
    // 拓展插件 UI 配置 → 全应用可编辑态。开关是全局设置，界面各处只读这一个布尔。
    val pluginUiExtend by container.settings.settings
        .map { it.pluginUiExtend }
        .collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(pluginUiExtend) { SlotEditState.enabled = pluginUiExtend }

    // 技能中心的安装/刷新按钮画在宿主顶栏（DrawerPage 的 actions），页面本身不再自绘顶栏；
    // 所以这份状态提到 AppNav 作用域，抽屉条目 content 与 drawer/* 路由共用同一个实例。
    val skillBar = rememberSkillCenterBarState()
    val pluginRevision by container.pluginHost.revision.collectAsStateWithLifecycle()
    val placementRevision = container.pluginHost.placements(drawerContext).revision
    // 抽屉条目的标题/副标题要在 remember 里拼（非 @Composable 上下文），所以统一走 Context.getString；
    // 语言切换会换 Configuration，把它并入 key 让条目重建。
    val localeConfig = LocalConfiguration.current
    val drawerEntries = remember(
        drawerContext,
        pluginRevision,
        placementRevision,
        presetState.defaultName,
        localeConfig,
    ) {
        val registry = container.pluginHost.slots
        val resolver = registry.drawerEntryResolver()
        val placed = container.pluginHost.placements(drawerContext)
            .visible(SlotId.Drawer, registry.bridgedCandidates())
            .map { contribution ->
                val native = resolver.resolve(contribution)
                if (native != null) {
                    // 原生 DrawerEntry：placement 只决定是否显示，渲染走原 content+icon 直画。
                    DrawerEntry(
                        id = "slot-" + native.id,
                        label = native.label,
                        subtitle = native.subtitle,
                        icon = native.icon,
                        pluginId = native.pluginId,
                        content = native.content,
                    )
                } else {
                    DrawerEntry(
                        id = "slot-" + contribution.id,
                        label = contribution.label,
                        subtitle = contribution.subtitle,
                        icon = null,
                        pluginId = contribution.pluginId,
                        content = {
                            Box(Modifier.fillMaxSize().padding(12.dp)) {
                                PluginSlotItem(
                                    contribution = contribution,
                                    tokens = rememberHostThemeTokens(),
                                    onAction = { action -> handleSlot(action, null, drawerContext, nav, scope) },
                                )
                            }
                        },
                    )
                }
            }
        // 技能中心是宿主内置入口（桌面 DSH 左侧栏那一项）。数据来自 Node 引擎里的
        // dsh-skills-manager 插件；插件没装时页面自己给「未装技能插件」的提示。
        val skillDirs = container.pluginInstaller.installedIds()
            .map { container.pluginInstaller.dirOf(it) }
            .filter { File(it, "package.json").isFile }
        val skills = DrawerEntry(
            id = "skills",
            label = drawerContext.getString(DesignR.string.nav_drawer_skills),
            subtitle = if (skillDirs.isEmpty()) {
                drawerContext.getString(DesignR.string.nav_drawer_skills_none)
            } else {
                drawerContext.getString(DesignR.string.nav_drawer_skills_count, skillDirs.size)
            },
            pluginId = "app",
            content = { SkillCenterScreen(engine = container.nodeEngine, pluginDirs = skillDirs, bar = skillBar) },
        )
        // 手机控制页（T1）：权限引导 + 动作列表 + 状态可视化。免 root/免 ADB，
        // 页面本身就是「无障碍服务开没开」的真实读数，所以固定摆在抽屉里。
        val phone = DrawerEntry(
            id = "phone",
            label = drawerContext.getString(DesignR.string.nav_drawer_phone),
            subtitle = if (container.phone.isEnabled()) {
                drawerContext.getString(DesignR.string.nav_drawer_phone_on)
            } else {
                drawerContext.getString(DesignR.string.nav_drawer_phone_off)
            },
            pluginId = "app",
            icon = Icons.Filled.TouchApp,
            content = { PhoneControlScreen(container.phone) },
        )
        // Agent 预设（原设置页那个区块）：列表 / 设为默认 / 导入 / 删除都搬进抽屉面板。
        val presets = DrawerEntry(
            id = "presets",
            label = drawerContext.getString(DesignR.string.nav_drawer_presets),
            subtitle = drawerContext.getString(DesignR.string.nav_drawer_presets_default, presetState.defaultName),
            pluginId = "app",
            icon = Icons.Filled.Tune,
            content = { PresetDrawerPanel(presetVm) },
        )
        listOf(presets, phone, skills) + placed
    }

    // 抽屉从左侧滑出（Material3 的起始边），和 QQ 的手机端一致：左上角/列表页左滑出会话与聊天记录。

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // 抽屉槽：「常用」与「最近会话」之间那个「+」，以及已摆进抽屉的组件。
                val drawerSlot = rememberSlotWiring(container, SlotId.Drawer)
                AppDrawer(
                    runtime = runtime,
                    container = container,
                    onOpenSession = { id ->
                        scope.launch { drawerState.close() }
                        nav.navigate(Routes.chat(id)) { launchSingleTop = true }
                    },
                    entries = drawerEntries,
                    onEntry = { entry ->
                        scope.launch { drawerState.close() }
                        nav.navigate("drawer/${entry.id}") { launchSingleTop = true }
                    },
                    onPlugins = {
                        scope.launch { drawerState.close() }
                        nav.navigate(Routes.PLUGINS) { launchSingleTop = true }
                    },
                    slotPlaced = drawerSlot.placed,
                    slotCandidates = drawerSlot.candidates,
                    onSlotAdd = drawerSlot.add,
                    onSlotRemove = drawerSlot.remove,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 安全模式是粘性状态，只能从诊断页退出：黄条常驻在最上面，点一下就到诊断页。
            // 状态在本次进程内不会变（进入/退出都伴随重启），所以只读一次。
            // 资源在这里取好再进 remember：remember 的 lambda 不是 Composable，不能直接 stringResource。
            val bootFailed = stringResource(DesignR.string.nav_safe_mode_boot_failed)
            val safeModeReason = remember(bootFailed) { container.bootGuard.safeModeController.let { c ->
                if (c.isActive()) c.reason() ?: bootFailed else null
            } }
            if (safeModeReason != null) {
                SafeModeBanner(
                    reason = safeModeReason,
                    onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                )
            }
            Box(Modifier.weight(1f)) {
                // 四个转场显式关掉：导航库默认给整页 fade in/out（700ms），一旦动画在
                // 应用切后台/丢帧时被挂起，新目的地会停在不透明度的第 0 帧——整页空白，
                // 而底部 tab（在 NavHost 外面）照常显示，就是「退出会话后偶发空白」。
                // 页面切换瞬切即可，本应用的分屏切换本来也没有方向性观感收益。
                NavHost(
                    navController = nav,
                    startDestination = Routes.SESSIONS,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {

                    composable(Routes.SESSIONS) {
                        SessionsRoute(container, nav, drawerState, scope)
                    }

                    composable(
                        route = Routes.CHAT,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                    ) { entry ->
                        ChatRoute(container, nav, drawerState, scope, appContext, route, entry)
                    }

                    // 会话设置整页：原来是从右边推出来的面板，现在有独立路由。
                    // ViewModel 借聊天目的地的 Store、沿用同一个 key，改完返回聊天页
                    // 看到的就是同一份状态，不会出现「设置改了、聊天页没变」。
                    composable(
                        route = Routes.CHAT_SETTINGS,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                    ) { entry ->
                        ChatSettingsRoute(container, nav, scope, appContext, route, presetVm, entry)
                    }

                    // 整页搜索：结果点开就回会话。会话还在栈里就直接回退，
                    // 不在栈里才压新目的地，免得来回点出一叠重复的 chat。
                    composable(Routes.SEARCH) {
                        SearchRoute(container, nav)
                    }

                    composable(Routes.AGENTS) {
                        AgentsRoute(container, nav, drawerState, scope, runtime)
                    }

                    composable(Routes.PLUGIN_HUB) {
            PluginHubRoute(container, nav, drawerState, scope)
                    }

                    composable(Routes.SETTINGS) {
                        SettingsRoute(container, nav, drawerState, scope, runtime)
                    }

                    // 分类子页：同一个 SettingsScreen 只渲染 section 那一块，顶栏换成返回箭头。
                    composable(
                        Routes.SETTINGS_SECTION,
                        arguments = listOf(navArgument("section") { type = NavType.StringType }),
                    ) { entry ->
                        SettingsSectionRoute(container, nav, entry)
                    }

                    composable(Routes.SETTINGS_APPEARANCE) {
                        AppearanceRoute(container, nav)
                    }

                    // T4.5 手机能力授权：三档开关 + 运行时权限申请（弹窗只能在 app 模块发起）。
                    composable(Routes.SETTINGS_CAPABILITY) {
                        CapabilityRoute(container, nav)
                    }

                    // T9.10 P1 MCP 服务器：列表 + 增删改 + 启停 + 测试连接。
                    composable(Routes.SETTINGS_MCP) {
                        McpServersRoute(container, nav)
                    }

                    // T9.12 步骤3 诊断：崩溃记录 / 安全模式状态 / 退出安全模式 / 撤销回滚。
                    composable(Routes.DIAGNOSTICS) {
                        DiagnosticsRoute(nav)
                    }

                    // T9.13 P3 备份与恢复：立即备份 / 已有备份 / SAF 选包恢复。
                    composable(Routes.SETTINGS_BACKUP) {
                        BackupRoute(container, nav)
                    }

                    drawerEntries.forEach { entry ->
                        composable("drawer/${entry.id}") {
                            DrawerEntryRoute(entry, nav, skillBar)
                        }
                    }

                    container.pluginHost.slots.bottomTabs.forEach { tab ->
                        composable("plugin/${tab.id}") { tab.content() }
                    }

                    // 插件声明的独立页面（plugin.page 贡献）。声明式插件没有底部 tab，
                    // 走 plugin/<id> 只会撞上不存在的路由，所以单开一条按贡献 id 找的通道。
                    composable(
                        Routes.PLUGIN_PAGE,
                        arguments = listOf(navArgument("pageId") { type = NavType.StringType }),
                    ) { entry ->
                        PluginPageRoute(container, nav, scope, entry)
                    }

                    composable(Routes.TERMINAL) {
                        TerminalRoute(container, nav)
                    }

                    composable(Routes.EXTRAS) {
                        ExtrasRoute(container, nav, runtime)
                    }

                    composable(Routes.PLUGINS) {
                        PluginsRoute(container, nav, scope)
                    }
                }
            }
            if (tabIndex >= 0) {
                QqTabBar(
                    tabs = tabs.map { it.first },
                    selected = tabIndex,
                    // 底部栏的「+」：底部 tab 是插件声明式贡献，装载在插件页，
                    // 这里直接把人送到那儿，不在底部栏里另造一套picker。
                    // ponytail: 只做导航，没有在底部栏就地挑选组件。
                    onEditPlus = { nav.navigate(Routes.PLUGIN_HUB) { launchSingleTop = true } },
                    onSelect = { i ->
                        nav.navigate(tabs[i].second) {
                            popUpTo(Routes.SESSIONS) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }

    // 启动时的静默更新检查：命中新版本弹一次对话框，网络失败只记日志，不打扰首屏。
    AutoUpdatePrompt()
}
