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
internal fun SettingsRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, drawerState: androidx.compose.material3.DrawerState, scope: kotlinx.coroutines.CoroutineScope, runtime: dev.dsh.mobile.core.model.RuntimeState) {
                        val settingsContext = LocalContext.current
                        val runtimeControl = container.runtimeControl
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
                        SettingsScreen(
                            viewModel = vm,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenExtras = { nav.navigate(Routes.EXTRAS) { launchSingleTop = true } },
                            onOpenTerminal = { nav.navigate(Routes.TERMINAL) { launchSingleTop = true } },
                            extraSettings = container.pluginHost.slots.settingsItems.map { item ->
                                { item.content() }
                            },
                            // 根列表只有两种点击结果：进子页。外观、能力各有自己的路由。
                            onOpenAppearance = { nav.navigate(Routes.SETTINGS_APPEARANCE) { launchSingleTop = true } },
                            onOpenCapability = { nav.navigate(Routes.SETTINGS_CAPABILITY) { launchSingleTop = true } },
                            onOpenMcp = { nav.navigate(Routes.SETTINGS_MCP) { launchSingleTop = true } },
                            onOpenBackup = { nav.navigate(Routes.SETTINGS_BACKUP) { launchSingleTop = true } },
                            onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                            onOpenSection = { id -> nav.navigate(Routes.settingsSection(id)) { launchSingleTop = true } },
                            roleCardStore = container.presetStore,
                            tabBarVisible = true,
                        )
}

@Composable
internal fun SettingsSectionRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, entry: androidx.navigation.NavBackStackEntry) {
                        val section = entry.arguments?.getString("section").orEmpty()
                        SettingsScreen(
                            viewModel = settingsSubViewModel(container, "settings-section-" + section),
                            onOpenExtras = { nav.navigate(Routes.EXTRAS) { launchSingleTop = true } },
                            onOpenTerminal = { nav.navigate(Routes.TERMINAL) { launchSingleTop = true } },
                            extraSettings = container.pluginHost.slots.settingsItems.map { item ->
                                { item.content() }
                            },
                            section = section,
                            onOpenMcp = { nav.navigate(Routes.SETTINGS_MCP) { launchSingleTop = true } },
                            onOpenBackup = { nav.navigate(Routes.SETTINGS_BACKUP) { launchSingleTop = true } },
                            onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                            roleCardStore = container.presetStore,
                            tabBarVisible = false,
                            onBack = { nav.popBackStack() },
                        )
}

@Composable
internal fun AppearanceRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController) {
                        AppAppearanceScreen(
                            viewModel = settingsSubViewModel(container, "settings-appearance"),
                            onBack = { nav.popBackStack() },
                        )
}

@Composable
internal fun CapabilityRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController) {
                        val caps = dev.dsh.mobile.app.phone.rememberCapabilityPermissionsUi()
                        AppCapabilityScreen(
                            viewModel = settingsSubViewModel(container, "settings-capability"),
                            grantedOf = { capability -> caps.granted(capability) },
                            onRequest = { capability -> caps.requestMissing(capability) },
                            onBack = { nav.popBackStack() },
                        )
}

@Composable
internal fun McpServersRoute(nav: androidx.navigation.NavHostController) {
                        McpServersScreen(onBack = { nav.popBackStack() })
}

@Composable
internal fun DiagnosticsRoute(nav: androidx.navigation.NavHostController) {
                        DiagnosticsScreen(onBack = { nav.popBackStack() })
}

@Composable
internal fun BackupRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController) {
                        BackupRestoreScreen(
                            onBack = { nav.popBackStack() },
                            manager = container.backupManager,
                        )
}

@Composable
internal fun ExtrasRoute(container: dev.dsh.mobile.app.AppContainer, nav: androidx.navigation.NavHostController, runtime: dev.dsh.mobile.core.model.RuntimeState) {
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
                                        runtime = container.runtimeControl,
                                        updater = dev.dsh.mobile.core.data.update.Updater(extrasContext),
                                        onReconnect = { container.connectActive(force = true) },
                                    )
                                }
                            },
                        )
                        ExtrasScreen(viewModel = vm, onBack = { nav.popBackStack() })
}
