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
internal fun SessionsRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, drawerState: androidx.compose.material3.DrawerState, scope: kotlinx.coroutines.CoroutineScope) {
                        // 新会话/新群组的默认标题按当前语言取资源；LocalContext 已被 UiLocale 包过。
                        val vmCtx = LocalContext.current
                        val vm: SessionsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    SessionsViewModel(
                                        container.repo,
                                        container.settings,
                                        container.models,
                                        { container.cwd() },
                                        defaultChatTitle = vmCtx.getString(DesignR.string.sessions_default_title),
                                        defaultGroupTitle = vmCtx.getString(DesignR.string.sessions_default_group_title),
                                    )
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

@Composable
internal fun SearchRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController) {
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

@Composable
internal fun AgentsRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, drawerState: androidx.compose.material3.DrawerState, scope: kotlinx.coroutines.CoroutineScope, runtime: dev.dsh.mobile.core.model.RuntimeState) {
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

@Composable
internal fun TerminalRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController) {
                        dev.dsh.mobile.feature.agents.TerminalScreen(
                            shell = container.shell,
                            onBack = { nav.popBackStack() },
                        )
}
