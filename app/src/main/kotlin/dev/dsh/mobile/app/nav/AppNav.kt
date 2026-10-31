package dev.dsh.mobile.app.nav

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
import dev.dsh.mobile.app.RuntimeControlAdapter
import dev.dsh.mobile.app.phone.PhoneControlScreen
import dev.dsh.mobile.app.update.AutoUpdatePrompt
import dev.dsh.mobile.core.model.RuntimeState
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.QqTabBar
import dev.dsh.mobile.design.TabSpec
import dev.dsh.mobile.design.UiLayoutPrefs
import dev.dsh.mobile.design.UiLayoutSettingsCard
import dev.dsh.mobile.feature.plugins.PluginHubScreen
import dev.dsh.mobile.feature.plugins.pluginSettingsEntries
import dev.dsh.mobile.feature.plugins.SkillCenterScreen
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
import dev.dsh.mobile.feature.settings.ExtrasScreen
import dev.dsh.mobile.feature.settings.SettingsScreen
import dev.dsh.mobile.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private object Routes {
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val CHAT_SETTINGS = "chat/{sessionId}/settings"
    const val AGENTS = "agents"
    const val SETTINGS = "settings"
    const val PLUGINS = "plugins"
    const val PLUGIN_HUB = "plugins/hub"
    const val TERMINAL = "terminal"
    const val SEARCH = "search"
    const val EXTRAS = "settings/extras"
    const val PLUGIN_PAGE = "plugin-page/{pageId}"
    fun chat(id: String) = "chat/$id"
    fun chatSettings(id: String) = "chat/$id/settings"
    fun pluginPage(id: String) = "plugin-page/$id"
}

private val TABS = listOf(
    TabSpec("会话", Icons.Filled.ChatBubble) to Routes.SESSIONS,
    TabSpec("Agent", Icons.Filled.SmartToy) to Routes.AGENTS,
    TabSpec("插件", Icons.Filled.Extension) to Routes.PLUGIN_HUB,
    TabSpec("设置", Icons.Filled.Settings) to Routes.SETTINGS,
)

/**
 * Built-in tabs plus whatever plugins contributed through the BottomTab slot.
 * Plugin tabs get a synthetic `plugin/<id>` route so navigation stays uniform.
 */
private fun tabsWithPlugins(container: AppContainer): List<Pair<TabSpec, String>> {
    val extra = container.pluginHost.slots.bottomTabs.map { tab ->
        TabSpec(tab.label, tab.icon ?: Icons.Filled.Extension) to "plugin/${tab.id}"
    }
    return TABS + extra
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val runtime: RuntimeState by container.runtime.collectAsStateWithLifecycle()
    // 界面级偏好（UI 修改模式 / 底部栏布局 / 三套字号）从 SharedPreferences 装一次，
    // 装完 UiEditMode 就已经是落盘时的值，首帧不会先空一帧再跳。
    val appContext = LocalContext.current
    LaunchedEffect(Unit) { UiLayoutPrefs.attach(appContext) }

    // 通知点击 → 直接进对应会话；消费一次就清空，返回键不会又被弹回来。
    val pendingSession by container.pendingSession.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSession) {
        val target = pendingSession ?: return@LaunchedEffect
        container.consumePendingSession()
        nav.navigate(Routes.chat(target)) { launchSingleTop = true }
    }

    // First frame is up: tell the crash guard this boot survived.
    LaunchedEffect(Unit) {
        delay(2_500)
        container.pluginHost.markBootOk()
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
            initializer { PresetDrawerViewModel(container.presetStore, container.settings) }
        },
    )
    val presetState by presetVm.uiState.collectAsStateWithLifecycle()
    // 拓展插件 UI 配置 → 全应用可编辑态。开关是全局设置，界面各处只读这一个布尔。
    val pluginUiExtend by container.settings.settings
        .map { it.pluginUiExtend }
        .collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(pluginUiExtend) { SlotEditState.enabled = pluginUiExtend }

    val pluginRevision by container.pluginHost.revision.collectAsStateWithLifecycle()
    val placementRevision = container.pluginHost.placements(drawerContext).revision
    val drawerEntries = remember(drawerContext, pluginRevision, placementRevision, presetState.defaultName) {
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
            label = "技能中心",
            subtitle = if (skillDirs.isEmpty()) "未装技能插件" else skillDirs.size.toString() + " 个技能插件",
            pluginId = "app",
            content = { SkillCenterScreen(engine = container.nodeEngine, pluginDirs = skillDirs) },
        )
        // 手机控制页（T1）：权限引导 + 动作列表 + 状态可视化。免 root/免 ADB，
        // 页面本身就是「无障碍服务开没开」的真实读数，所以固定摆在抽屉里。
        val phone = DrawerEntry(
            id = "phone",
            label = "手机控制",
            subtitle = if (container.phone.isEnabled()) "无障碍已开启" else "免 root 操控 · 待授权",
            pluginId = "app",
            icon = Icons.Filled.TouchApp,
            content = { PhoneControlScreen(container.phone) },
        )
        // Agent 预设（原设置页那个区块）：列表 / 设为默认 / 导入 / 删除都搬进抽屉面板。
        val presets = DrawerEntry(
            id = "presets",
            label = "Agent 预设",
            subtitle = "默认：" + presetState.defaultName,
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
                        val vm: SessionsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    SessionsViewModel(container.repo, container.settings, container.models) { container.cwd() }
                                }
                            },
                        )
                        // 会话列表槽：右下角「+」与列表末尾的已放置组件都落在这个槽位上。
                        val listContext = LocalContext.current
                        val listSlot = rememberSlotWiring(container, SlotId.SessionList)
                        SessionsScreen(
                            viewModel = vm,
                            onOpenSession = { nav.navigate(Routes.chat(it)) { launchSingleTop = true } },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            sessionRowExtras = container.pluginHost.slots.sessionRowExtras.toList(),
                            slotPlaced = listSlot.placed,
                            slotCandidates = listSlot.candidates,
                            onSlotAdd = listSlot.add,
                            onSlotRemove = listSlot.remove,
                            onSlotAction = { action ->
                                handleSlot(action, null, listContext, nav, scope)
                            },
                        )
                    }

                    composable(
                        route = Routes.CHAT,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("sessionId").orEmpty()
                        val vm: ChatViewModel = viewModel(
                            key = "chat-$id",
                            factory = viewModelFactory {
                                initializer {
                                    ChatViewModel(
                                        container.repo,
                                        container.settings,
                                        container.models,
                                        container.sendQueue,
                                        id,
                                        container.executionAgents,
                                    ) { container.cwd() }
                                }
                            },
                        )
                        val slotContext = LocalContext.current
                        val chatState by vm.uiState.collectAsStateWithLifecycle()
                        // 前台会话不打扰；离开页面立刻解绑，切到别的会话再提醒。
                        val notify = container.notificationCenter
                        DisposableEffect(id) {
                            notify.setForegroundSession(id)
                            onDispose { notify.setForegroundSession(null) }
                        }
                        // 完成 / 待确认提醒统一由 OngoingCenter 跟踪仓储流发出——离开聊天页、
                        // 甚至按 Home 之后也照发；这里只负责登记「用户正在看哪个会话」。
                        // 聊天路由订阅宿主 revision：live 装卸插件 / 摆槽位时，composer /
                        // overflow / preset 槽位必须跟着重组（对齐抽屉 134-136 的写法），
                        // 否则只对 placements.revision 有反应、对插件注册表变化无感。
                        val chatPluginRevision by container.pluginHost.revision.collectAsStateWithLifecycle()
                        val chatPlacementRevision = container.pluginHost.placements(slotContext).revision
                        val (composerSlots, overflowSlots, presetSlots) = remember(
                            slotContext,
                            chatPluginRevision,
                            chatPlacementRevision,
                        ) {
                            Triple(
                                container.pluginHost.visibleSlots(slotContext, SlotId.ComposerPlus),
                                container.pluginHost.visibleSlots(slotContext, SlotId.ChatOverflow),
                                container.pluginHost.visibleSlots(slotContext, SlotId.AgentPreset),
                            )
                        }
                        // 聊天底部槽：「+」与已放置组件都在输入框上方那一片。
                        val bottomSlot = rememberSlotWiring(container, SlotId.ChatBottom)
                        ChatScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenSettings = {
                                nav.navigate(Routes.chatSettings(id)) { launchSingleTop = true }
                            },
                            messageSlot = { msg -> container.pluginHost.MessageSlot(msg) },
                            composerExtras = container.pluginHost.slots.composerButtons
                                .map { it.label to it.transform },
                            // 统一走 visibleSlots：槽位管理页关掉的贡献在这里立即消失。
                            composerSlots = composerSlots,
                            overflowSlots = overflowSlots,
                            presetSlots = presetSlots,
                            onSlot = { slot ->
                                handleSlot(slot.action, vm, slotContext, nav, scope)
                            },
                            slotPlaced = bottomSlot.placed,
                            slotCandidates = bottomSlot.candidates,
                            onSlotAdd = bottomSlot.add,
                            onSlotRemove = bottomSlot.remove,
                            // 智能体预设：只写本会话的覆盖；页面负责「首条消息后锁定」的显示。
                            onPickPreset = { presetId ->
                                scope.launch { container.settings.setSessionPresetId(id, presetId) }
                            },
                        )
                    }

                    // 会话设置整页：原来是从右边推出来的面板，现在有独立路由。
                    // ViewModel 借聊天目的地的 Store、沿用同一个 key，改完返回聊天页
                    // 看到的就是同一份状态，不会出现「设置改了、聊天页没变」。
                    composable(
                        route = Routes.CHAT_SETTINGS,
                        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("sessionId").orEmpty()
                        val chatEntry = remember(id) { nav.getBackStackEntry(Routes.chat(id)) }
                        val vm: ChatViewModel = viewModel(
                            viewModelStoreOwner = chatEntry,
                            key = "chat-$id",
                            factory = viewModelFactory {
                                initializer {
                                    ChatViewModel(
                                        container.repo,
                                        container.settings,
                                        container.models,
                                        container.sendQueue,
                                        id,
                                        container.executionAgents,
                                    ) { container.cwd() }
                                }
                            },
                        )
                        val slotContext = LocalContext.current
                        val settingsPluginRevision by container.pluginHost.revision.collectAsStateWithLifecycle()
                        val settingsPlacementRevision = container.pluginHost.placements(slotContext).revision
                        val extras = remember(
                            slotContext,
                            settingsPluginRevision,
                            settingsPlacementRevision,
                        ) {
                            container.pluginHost.visibleSlots(slotContext, SlotId.ChatOverflow)
                        }
                        // 会话设置槽（整页中部空白）：摆放与移除都落在这个类自己的目标槽位上。
                        val settingsSlot = rememberSlotWiring(container, SlotId.ChatSettings)
                        ChatSettingsScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } },
                            extras = extras,
                            onSlot = { slot -> handleSlot(slot.action, vm, slotContext, nav, scope) },
                            presetViewModel = presetVm,
                            slotPlaced = settingsSlot.placed,
                            slotCandidates = settingsSlot.candidates,
                            onSlotAdd = settingsSlot.add,
                            onSlotRemove = settingsSlot.remove,
                        )
                    }

                    // 整页搜索：结果点开就回会话。会话还在栈里就直接回退，
                    // 不在栈里才压新目的地，免得来回点出一叠重复的 chat。
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            container = container,
                            onBack = { nav.popBackStack() },
                            onOpenSession = { hit ->
                                if (!nav.popBackStack(Routes.chat(hit), inclusive = false)) {
                                    nav.navigate(Routes.chat(hit)) { launchSingleTop = true }
                                }
                            },
                        )
                    }

                    composable(Routes.AGENTS) {
                        val vm: AgentsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AgentsViewModel(
                                        container.repo,
                                        container.settings,
                                        container.models,
                                        container.runtime,
                                        container.shell,
                                        // engine:// 端点的模型列表走宿主引擎通道，不经 HTTP。
                                        container::engineModelIds,
                                    )
                                }
                            },
                        )
                        AgentsScreen(
                            viewModel = vm,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenSession = { nav.navigate(Routes.chat(it)) { launchSingleTop = true } },
                            agentCardExtras = container.pluginHost.slots.agentCardExtras.toList(),
                            onReconnect = { container.connectActive(force = true) },
                        )
                    }

                    composable(Routes.PLUGIN_HUB) {
            // 订阅槽位版本：插件被停用后配置页要立刻消失，而不是等下次冷启动。
            container.pluginHost.revision.collectAsStateWithLifecycle()
                        val hubVm: PluginsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    PluginsViewModel(
                                        container.pluginHost,
                                        externalStore = container.externalPlugins,
                                        onReload = { container.reloadPlugins() },
                                    )
                                }
                            },
                        )
                        // 槽位热更新已在上面订阅 revision；这里把「只有 SlotContribution、
                        // 没有 Compose 代码」的那批插件页汇总进来。PluginPage 只在中心内部
                        // 可浏览，直接从注册表取（不参与摆放）；Drawer / Settings / SessionRow
                        // 这类外部槽位一律来自用户显式摆放（visibleSlots）。
                        val hubContext = LocalContext.current
                        val hubRegistry = container.pluginHost.slots
                        // 插件页槽：中心内部那一层的「+」与已放置组件。
                        val hubSlot = rememberSlotWiring(container, SlotId.PluginPage)
                        PluginHubScreen(
                            slotContributions = mergeHubSlots(
                                hubRegistry.inSlot(SlotId.PluginPage),
                                container.pluginHost.visibleSlots(hubContext, SlotId.Settings),
                                container.pluginHost.placements(hubContext).visible(SlotId.Drawer, hubRegistry.bridgedCandidates()),
                                container.pluginHost.visibleSlots(hubContext, SlotId.SessionRow),
                            ),
                            drawerResolver = hubRegistry.drawerEntryResolver(),
                            onAction = { action ->
                                handleSlot(action, null, hubContext, nav, scope)
                            },
                            slotPlaced = hubSlot.placed,
                            slotCandidates = hubSlot.candidates,
                            onSlotAdd = hubSlot.add,
                            onSlotRemove = hubSlot.remove,
                            pages = container.pluginHost.slots.configPages + listOf(
                                PluginConfigPage(
                                    id = "app.plugins.market",
                                    label = "插件安装",
                                    subtitle = "检索安装、备份与回档",
                                    icon = Icons.Filled.Extension,
                                    pluginId = "app",
                                    content = {
                                        PluginMarketScreen(
                                            installer = container.pluginInstaller,
                                            backup = container.backupManager,
                                            installedIds = container.installedPlugins.all().keys.toList(),
                                            // 装完就地热加载，新插件立刻出现在槽位里，不用重启。
                                            onChanged = { container.reloadPlugins() },
                                            externalStore = container.externalPlugins,
                                        )
                                    },
                                ),
                                PluginConfigPage(
                                    id = "app.plugins.manage",
                                    label = "插件管理",
                                    subtitle = "启用停用、存档保护",
                                    icon = Icons.Filled.Tune,
                                    pluginId = "app",
                                    content = {
                                        val probes by container.externalPlugins.probes.collectAsStateWithLifecycle()
                                        PluginsScreen(
                                            hubVm,
                                            onBack = {},
                                            embedded = true,
                                            probes = probes,
                                            onRescan = { container.reloadPlugins() },
                                            // 声明式面板里的按钮/开关/滑杆要靠这个回调才有反应；
                                            // 不传会落到默认空实现，面板看起来能点其实全是死的。
                                            onSlotAction = { action -> handleSlot(action, null, hubContext, nav, scope) },
                                        )
                                    },
                                ),
                            ),
                            pluginSettings = pluginSettingsEntries(
                                installed = container.installedPlugins.all().values.toList(),
                                pages = hubRegistry.inSlot(SlotId.PluginPage),
                                values = { id -> container.installedPlugins.config(id) },
                            ),
                            onConfigSave = { id, key, value ->
                                container.installedPlugins.setConfig(id, key, value)
                            },
                            // 配置可能改变插件行为，返回列表时重载一次，别等冷启动。
                            onSettingsClosed = { container.reloadPlugins() },
                            onOpenUrl = { url ->
                                hubContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        val settingsContext = LocalContext.current
                        val runtimeControl = RuntimeControlAdapter(container)
                        val vm: SettingsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    SettingsViewModel(
                                        settingsStore = container.settings,
                                        secretStore = container.secretStore,
                                        linuxActions = dev.dsh.mobile.app.LinuxSettingsController(settingsContext.applicationContext, container),
                                        endpoints = container.endpoints,
                                        runtime = runtimeControl,
                                        updater = dev.dsh.mobile.core.data.update.Updater(settingsContext),
                                        onReconnect = { container.connectActive(force = true) },
                                    )
                                }
                            },
                        )
                        // 界面修改一节放在设置页顶部：UI 修改模式开关、底部栏布局、三套字号。
                        // feature:settings 不在本次改动范围，所以用宿主这一层把设计模块的卡片叠上去。
                        Column(Modifier.fillMaxSize()) {
                            UiLayoutSettingsCard(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            Box(Modifier.weight(1f)) {
                        SettingsScreen(
                            viewModel = vm,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenPlugins = { nav.navigate(Routes.PLUGINS) { launchSingleTop = true } },
                            onOpenExtras = { nav.navigate(Routes.EXTRAS) { launchSingleTop = true } },
                            onOpenTerminal = { nav.navigate(Routes.TERMINAL) { launchSingleTop = true } },
                            extraSettings = container.pluginHost.slots.settingsItems.map { item ->
                                { item.content() }
                            },
                        )
                            }
                        }
                    }

                    drawerEntries.forEach { entry ->
                        composable("drawer/${entry.id}") {
                            DrawerPage(entry.label, onBack = { nav.popBackStack() }) { entry.content() }
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
                        val pageId = entry.arguments?.getString("pageId").orEmpty()
                        val pageContext = LocalContext.current
                        // 先按贡献 id 找；找不到再按贡献自己声明的 pageId 找 —— plugin.json 里
                        // id 与 pageId 通常不同名（如 id=page、pageId=dsh.demo.slots）。
                        val pages = container.pluginHost.slots.contributions.filter {
                            it.slot == SlotId.PluginPage
                        }
                        val page = pages.firstOrNull { it.id == pageId }
                            ?: pages.firstOrNull {
                                (it.action as? SlotAction.Page)?.pageId == pageId
                            }
                        if (page == null) {
                            DrawerPage("插件页面", onBack = { nav.popBackStack() }) {
                                Box(Modifier.padding(16.dp)) {
                                    Text("插件页面不存在：$pageId", color = LocalQq.current.t3, fontSize = 13.sp)
                                }
                            }
                        } else {
                            PluginContributionPage(
                                contribution = page,
                                onBack = { nav.popBackStack() },
                                onAction = { action -> handleSlot(action, null, pageContext, nav, scope) },
                            )
                        }
                    }

                    composable(Routes.TERMINAL) {
                        dev.dsh.mobile.feature.agents.TerminalScreen(
                            shell = container.shell,
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(Routes.EXTRAS) {
                        val extrasContext = LocalContext.current
                        val vm: SettingsViewModel = viewModel(
                            key = "settings-extras",
                            factory = viewModelFactory {
                                initializer {
                                    SettingsViewModel(
                                        settingsStore = container.settings,
                                        secretStore = container.secretStore,
                                        linuxActions = dev.dsh.mobile.app.LinuxSettingsController(extrasContext.applicationContext, container),
                                        endpoints = container.endpoints,
                                        runtime = RuntimeControlAdapter(container),
                                        updater = dev.dsh.mobile.core.data.update.Updater(extrasContext),
                                        onReconnect = { container.connectActive(force = true) },
                                    )
                                }
                            },
                        )
                        ExtrasScreen(viewModel = vm, onBack = { nav.popBackStack() })
                    }

                    composable(Routes.PLUGINS) {
                        val vm: PluginsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    PluginsViewModel(
                                        container.pluginHost,
                                        externalStore = container.externalPlugins,
                                        onReload = { container.reloadPlugins() },
                                    )
                                }
                            },
                        )
                        // 独立路由页也要看到加载诊断：和插件 Hub 内嵌版读同一份 probes。
                        val probes by container.externalPlugins.probes.collectAsStateWithLifecycle()
                        val pluginsContext = LocalContext.current
                        PluginsScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            probes = probes,
                            onRescan = { container.reloadPlugins() },
                            onSlotAction = { action -> handleSlot(action, null, pluginsContext, nav, scope) },
                        )
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

/**
 * Runs a plugin-declared slot action.
 *
 * 为什么 scope 从调用点传进来、而不是把本函数改成 @Composable：
 * onSlot 是 ChatScreen 的 `(SlotContribution) -> Unit` 回调，那里没有 composable
 * 上下文，@Composable 版本根本调不到；传 scope 让它保持普通函数，一处
 * rememberCoroutineScope() 就够。vm 允许为空：插件 Hub 没有「当前会话」，
 * 这时 Prompt 退化成一句可见提示，而不是静默什么都不做。
 */
private fun handleSlot(
    action: SlotAction?,
    vm: dev.dsh.mobile.feature.chat.ChatViewModel?,
    context: android.content.Context,
    nav: androidx.navigation.NavHostController,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (action) {
        is SlotAction.Prompt -> {
            val target = vm
            if (target != null) target.send(action.text)
            else toast(context, "没有打开的会话，插件动作「${action.text}」无处发送")
        }
        is SlotAction.Link -> runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(action.uri)),
            )
        }.onFailure { toast(context, "打不开链接：${it.message ?: it::class.java.simpleName}") }
        // pageId 优先映射到宿主自己的页面：插件写 "settings" / "sessions" 这类名字
        // 指的是内置页，不是合成出来的 plugin/<id> 路由（后者只有底部 tab 插件才有）。
        // 目标不存在时给一句 toast，绝不让 NavController 的 IllegalArgumentException 冒到顶。
        is SlotAction.Page -> {
            val route = when (action.pageId) {
                "settings" -> Routes.SETTINGS
                "sessions" -> Routes.SESSIONS
                "agents" -> Routes.AGENTS
                "plugins", "hub" -> Routes.PLUGIN_HUB
                else -> Routes.pluginPage(action.pageId)
            }
            runCatching { nav.navigate(route) { launchSingleTop = true } }
                .onFailure { toast(context, "插件指向的页面不存在：${action.pageId}") }
        }
        is SlotAction.Http -> runHttp(context, action, scope)
        is SlotAction.Custom -> runCustom(context, action)
        null -> Unit
    }
}

/**
 * 宿主认识的 custom id。不在这张表里的 id 一律回一句「由插件处理」，绝不静默吞掉。
 *
 * APK 插件可以用 content 直画替代 custom，所以这里只列宿主自己确实拥有的通道：
 * html.render 就是 plugin-api 的 HtmlBlockRenderers（消息里 ```html 围栏的渲染槽）。
 * 它是 @Composable 槽位，不能在这里「执行」，只能如实回报当前接管状态。
 */
private val HOST_CUSTOM_HANDLERS: Map<String, (android.content.Context) -> String> = mapOf(
    "html.render" to { _ ->
        if (HtmlBlockRenderers.available) "HTML 渲染已就绪：消息里的 html 代码块会走 WebView"
        else "HTML 渲染插件没注册，代码块仍是源码视图"
    },
    "diagram.render" to { _ ->
        if (DiagramRenderers.available) "图形渲染已就绪：mermaid / dot / echarts / svg 等代码块会画成图"
        else "图形渲染插件没注册，代码块仍是源码视图"
    },
)

private fun runCustom(context: android.content.Context, action: SlotAction.Custom) {
    val handler = HOST_CUSTOM_HANDLERS[action.id]
    toast(context, handler?.invoke(context) ?: "由插件处理：${action.id}")
}

/**
 * 真发一次 HTTP 并回报结果：IO 在宿主线程外，连接/读取各 10s，
 * 2xx 显示响应体前 200 字，非 2xx 显示 "HTTP ${code}"，异常显示原因。
 */
private fun runHttp(
    context: android.content.Context,
    action: SlotAction.Http,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val app = context.applicationContext
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val message = try {
            val conn = java.net.URL(action.url).openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = action.method.ifBlank { "GET" }.uppercase()
                val body = action.body
                if (!body.isNullOrEmpty()) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val text = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    text.take(200).ifBlank { "HTTP $code（空响应）" }
                } else {
                    "HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            "请求失败：${e.message ?: e::class.java.simpleName}"
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            toast(app, message)
        }
    }
}

/**
 * 抽屉面板的统一外壳：页面底色 + 顶栏 + 返回键。
 *
 * 三个面板（Agent 预设 / 手机控制 / 技能中心）自己只画内容，以前直接把内容贴到壁纸上，
 * 没有底色也没有顶栏，进去就退不出来、字还压在花纹上。
 */
@Composable
private fun DrawerPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalQq.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqTopBar(
                leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) },
            ) {
                Text(title, color = p.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
