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

internal object Routes {
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
    const val SETTINGS_SECTION = "settings/section/{section}"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_CAPABILITY = "settings/capability"
    const val SETTINGS_MCP = "settings/mcp"
    const val SETTINGS_BACKUP = "settings/backup"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val PLUGIN_PAGE = "plugin-page/{pageId}"
    /** 插件自带 WebUI：全屏 WebView 加载网关 http 地址，参数只带插件 id。 */
    const val PLUGIN_UI = "plugin-ui/{pluginId}"
    fun chat(id: String) = "chat/$id"
    fun chatSettings(id: String) = "chat/$id/settings"
    fun pluginPage(id: String) = "plugin-page/$id"
    fun pluginUi(id: String) = "plugin-ui/$id"
    fun settingsSection(id: String) = "settings/section/$id"
}

internal val TABS = listOf(
    TabSpec(null, Icons.Filled.ChatBubble, DesignR.string.nav_tab_sessions) to Routes.SESSIONS,
    TabSpec("Agent", Icons.Filled.SmartToy) to Routes.AGENTS,
    TabSpec(null, Icons.Filled.Extension, DesignR.string.nav_tab_plugins) to Routes.PLUGIN_HUB,
    TabSpec(null, Icons.Filled.Settings, DesignR.string.nav_tab_settings) to Routes.SETTINGS,
)


internal fun tabsWithPlugins(container: AppContainer): List<Pair<TabSpec, String>> {
    val extra = container.pluginHost.slots.bottomTabs.map { tab ->
        TabSpec(tab.label, tab.icon ?: Icons.Filled.Extension) to "plugin/${tab.id}"
    }
    return TABS + extra
}

/**
 * 抽屉面板的统一外壳：页面底色 + 顶栏 + 返回键。
 *
 * 三个面板（Agent 预设 / 手机控制 / 技能中心）自己只画内容，以前直接把内容贴到壁纸上，
 * 没有底色也没有顶栏，进去就退不出来、字还压在花纹上。
 */
/**
 * 设置域的子页各要一份 [SettingsViewModel]。key 隔开实例，工厂参数只写一次。
 */
@Composable
internal fun settingsSubViewModel(container: AppContainer, key: String): SettingsViewModel {
    val ctx = LocalContext.current
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsStore = container.settings,
                    secretStore = container.secretStore,
                    linuxActions = dev.dsh.mobile.app.LinuxSettingsController(ctx.applicationContext, container),
                    endpoints = container.endpoints,
                    runtime = container.runtimeControl,
                    updater = container.updater,
                    onReconnect = { container.connectActive(force = true) },
                )
            }
        },
    )
}
