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
internal fun ChatRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, drawerState: androidx.compose.material3.DrawerState, scope: kotlinx.coroutines.CoroutineScope, appContext: android.content.Context, route: String?, entry: androidx.navigation.NavBackStackEntry) {
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
                                        container.groupLedger,
                                        container.executionAgents,
                                        container.appContext,
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
                            // 插件 ctx.sessionId() 的唯一来源：进/出会话页时改写。
                            container.setActivePluginSession(id)
                            onDispose {
                                notify.setForegroundSession(null)
                                container.setActivePluginSession(null)
                            }
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

@Composable
internal fun ChatSettingsRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, scope: kotlinx.coroutines.CoroutineScope, appContext: android.content.Context, route: String?, presetVm: dev.dsh.mobile.feature.chat.PresetDrawerViewModel, entry: androidx.navigation.NavBackStackEntry) {
                        val id = entry.arguments?.getString("sessionId").orEmpty()
                        // 会话设置是独立路由：这一页上的插件开关也要能定位到本会话。
                        LaunchedEffect(id) { container.setActivePluginSession(id) }
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
                                        container.groupLedger,
                                        container.executionAgents,
                                        container.appContext,
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
                            // 点插件项时再同步落一次会话 id，别依赖上面的 effect 已经跑过。
                            onSlot = { slot ->
                                container.setActivePluginSession(id)
                                handleSlot(slot.action, vm, slotContext, nav, scope)
                            },
                            presetViewModel = presetVm,
                            slotPlaced = settingsSlot.placed,
                            slotCandidates = settingsSlot.candidates,
                            onSlotAdd = settingsSlot.add,
                            onSlotRemove = settingsSlot.remove,
                        )
}
