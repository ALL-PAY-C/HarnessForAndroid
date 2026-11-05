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

@Composable
internal fun PluginHubRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, drawerState: androidx.compose.material3.DrawerState, scope: kotlinx.coroutines.CoroutineScope) {
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
                            // 汉堡 → 抽屉：与会话/Agent/设置三个一级页同一行回调。
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            pages = container.pluginHost.slots.configPages + listOf(
                                PluginConfigPage(
                                    id = "app.plugins.market",
                                    label = stringResource(DesignR.string.nav_plugin_market_label),
                                    subtitle = stringResource(DesignR.string.nav_plugin_market_sub),
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
                                    label = stringResource(DesignR.string.nav_plugin_manage_label),
                                    subtitle = stringResource(DesignR.string.nav_plugin_manage_sub),
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
                                // 内置 Kotlin 插件的设置项（B3-0）：清单自带 settings，读的是同一份 config 存储。
                                native = dev.dsh.mobile.pluginhost.builtin.builtinPlugins.map { it.manifest },
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

@Composable
internal fun PluginPageRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, scope: kotlinx.coroutines.CoroutineScope, entry: androidx.navigation.NavBackStackEntry) {
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
                            DrawerPage(stringResource(DesignR.string.nav_plugin_page_title), onBack = { nav.popBackStack() }) {
                                Box(Modifier.padding(16.dp)) {
                                    Text(
                                        stringResource(DesignR.string.nav_plugin_page_missing, pageId),
                                        color = LocalQq.current.t3,
                                        fontSize = 13.sp,
                                    )
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

@Composable
internal fun PluginsRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, scope: kotlinx.coroutines.CoroutineScope) {
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
