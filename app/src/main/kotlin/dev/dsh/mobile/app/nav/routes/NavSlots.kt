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

/**
 * Runs a plugin-declared slot action.
 *
 * 为什么 scope 从调用点传进来、而不是把本函数改成 @Composable：
 * onSlot 是 ChatScreen 的 `(SlotContribution) -> Unit` 回调，那里没有 composable
 * 上下文，@Composable 版本根本调不到；传 scope 让它保持普通函数，一处
 * rememberCoroutineScope() 就够。vm 允许为空：插件 Hub 没有「当前会话」，
 * 这时 Prompt 退化成一句可见提示，而不是静默什么都不做。
 */
internal fun handleSlot(
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
            else toast(context, context.getString(DesignR.string.nav_slot_no_session, action.text))
        }
        // 只切状态、不发消息：插件点一下改自己的 config 并重建界面，会话里不会多一条消息。
        // 走进程级 holder 而不是多加一路参数：9 个调用点都没有「插件实例」在手，只有宿主有。
        is SlotAction.SetConfig -> {
            val ok = PluginHostHolder.instance?.applySlotConfig(action) == true
            if (!ok) toast(context, context.getString(DesignR.string.nav_slot_config_failed, action.key))
        }
        // 提示词注入一键开关：一次点击直接写会话 persona（再点清空），不发消息、不再过确认框。
        is SlotAction.SetPersona -> {
            val ok = PluginHostHolder.instance?.applySlotPersona(action) == true
            toast(
                context,
                when {
                    ok -> context.getString(DesignR.string.nav_persona_updated)
                    // 清除类动作（text 为空）在人设本来就是空的时候什么也没改，不该报「没写成」。
                    action.text.isBlank() -> context.getString(DesignR.string.nav_persona_was_empty)
                    else -> context.getString(DesignR.string.nav_persona_no_session)
                },
            )
        }
        is SlotAction.Link -> runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(action.uri)),
            )
        }.onFailure {
            toast(context, context.getString(DesignR.string.nav_link_failed, it.message ?: it::class.java.simpleName))
        }
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
                .onFailure {
                    toast(context, context.getString(DesignR.string.nav_slot_page_missing, action.pageId))
                }
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
internal val HOST_CUSTOM_HANDLERS: Map<String, (android.content.Context) -> String> = mapOf(
    "html.render" to { ctx ->
        if (HtmlBlockRenderers.available) ctx.getString(DesignR.string.nav_custom_html_ready)
        else ctx.getString(DesignR.string.nav_custom_html_missing)
    },
    "diagram.render" to { ctx ->
        if (DiagramRenderers.available) ctx.getString(DesignR.string.nav_custom_diagram_ready)
        else ctx.getString(DesignR.string.nav_custom_diagram_missing)
    },
)

internal fun runCustom(context: android.content.Context, action: SlotAction.Custom) {
    val handler = HOST_CUSTOM_HANDLERS[action.id]
    toast(context, handler?.invoke(context) ?: context.getString(DesignR.string.nav_custom_handled, action.id))
}

/**
 * 真发一次 HTTP 并回报结果：IO 在宿主线程外，连接/读取各 10s，
 * 2xx 显示响应体前 200 字，非 2xx 显示 "HTTP ${code}"，异常显示原因。
 */
internal fun runHttp(
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
                    text.take(200).ifBlank { app.getString(DesignR.string.nav_http_empty, code) }
                } else {
                    "HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            app.getString(DesignR.string.nav_http_failed, e.message ?: e::class.java.simpleName)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            toast(app, message)
        }
    }
}

internal fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
