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
internal fun DrawerEntryRoute(entry: dev.dsh.mobile.pluginapi.DrawerEntry, nav: androidx.navigation.NavHostController, skillBar: dev.dsh.mobile.feature.plugins.SkillCenterBarState) {
                            // 面板自带动作（技能中心）交给宿主顶栏画，面板自己不画第二条栏。
                            val actions: (@Composable () -> Unit)? =
                                if (entry.id == "skills") {
                                    { SkillCenterBarActions(skillBar) }
                                } else {
                                    null
                                }
                            DrawerPage(entry.label, onBack = { nav.popBackStack() }, actions = actions) {
                                entry.content()
                            }
}

/**
 * 抽屉面板的宿主页：顶栏只有这一处。
 *
 * 面板（手机控制 / 技能中心 / Agent 预设 / 插件声明的抽屉页）只画内容，自己再画一条顶栏
 * 就会叠成两条标题——手机控制页曾经就是这样。面板要放右上角动作，走 [actions] 槽，
 * 不许为了留按钮另起一条栏。
 */
@Composable
internal fun DrawerPage(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(title = title, isBack = true, onNavigationClick = onBack, actions = actions)
            content()
        }
    }
}
