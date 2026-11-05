package dev.dsh.mobile.app

// O4 纯物理拆分：AppContainer 的成员体搬到这里，仍是同一实例、同一初始化时机。

import android.content.Context
import dev.dsh.mobile.app.diag.AppBootGuard
import dev.dsh.mobile.app.diag.CrashReporter
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.RestartReason
import dev.dsh.mobile.core.platform.ProxyEnv
import dev.dsh.mobile.core.platform.AppScope
import dev.dsh.mobile.core.platform.DshResult
import dev.dsh.mobile.core.platform.runCatchingDsh
import dev.dsh.mobile.core.data.DataModule
import dev.dsh.mobile.core.data.EndpointRepository
import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.repo.SendQueue
import dev.dsh.mobile.core.data.repo.toModel
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.model.settings.ModelProvider
import dev.dsh.mobile.core.model.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.model.settings.contextWindowOf
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.data.settings.toWebSettings
import dev.dsh.mobile.core.protocol.web.WebToolkit
import dev.dsh.mobile.core.model.Endpoint
import dev.dsh.mobile.core.model.ConnectionKey
import dev.dsh.mobile.core.model.ConnectGate
import dev.dsh.mobile.core.model.ConnectionCoordinator
import dev.dsh.mobile.core.model.connectGate
import dev.dsh.mobile.core.model.connectionKey
import dev.dsh.mobile.core.model.shouldReconnect
import dev.dsh.mobile.core.model.teardownConnection
import dev.dsh.mobile.core.model.RuntimeControl
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.diag.CrashKind
import dev.dsh.mobile.core.protocol.AcpTransport
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.GroupMember
import dev.dsh.mobile.core.protocol.ContextBudget
import dev.dsh.mobile.core.protocol.GroupPlan
import dev.dsh.mobile.core.protocol.LocalEngineTransport
import dev.dsh.mobile.core.protocol.DshTransport
import dev.dsh.mobile.core.protocol.WebSocketChannel
import dev.dsh.mobile.core.protocol.transport.LineCarrier
import dev.dsh.mobile.core.data.backup.BackupManager
import dev.dsh.mobile.core.data.mcp.McpRuntime
import dev.dsh.mobile.core.data.mcp.McpServerStore
import dev.dsh.mobile.core.data.mcp.toProtocolConfig
import dev.dsh.mobile.core.data.context.RoomWarmLayerStore
import dev.dsh.mobile.core.protocol.context.RecallHit
import dev.dsh.mobile.core.model.context.WarmMessage
import dev.dsh.mobile.core.data.backup.BackupScheduler
import dev.dsh.mobile.plugin.html.HtmlPlugin
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginhost.InstalledPlugins
import dev.dsh.mobile.pluginhost.PluginInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.mobile.pluginhost.PluginHost
import dev.dsh.mobile.pluginhost.PluginHostHolder
import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginhost.PluginConfigBridge
import dev.dsh.mobile.pluginhost.SessionPersonaBridge
import dev.dsh.mobile.pluginhost.NodeBackedPlugin
import dev.dsh.mobile.pluginhost.PluginStore
import dev.dsh.mobile.pluginhost.ExternalPluginStore
import dev.dsh.mobile.app.notify.NotificationCenter
import dev.dsh.mobile.app.notify.OngoingCenter
import dev.dsh.mobile.runtimehost.NodeEngine
import dev.dsh.mobile.app.node.RuntimeControlAdapter
import dev.dsh.mobile.runtimehost.NodeEngineHost
import dev.dsh.mobile.app.node.RemoteNodeEngine
import dev.dsh.mobile.linux.LinuxAbis
import dev.dsh.mobile.linux.LinuxDownload
import dev.dsh.mobile.linux.LinuxEnvironment
import dev.dsh.mobile.linux.ProotChannel
import dev.dsh.mobile.toolbridge.AppStorage
import dev.dsh.mobile.toolbridge.FileBridge
import dev.dsh.mobile.toolbridge.ShellBridge
import dev.dsh.mobile.toolbridge.phone.PhoneBridge
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

internal fun AppContainer.initRuntimeState() {
        watchWorkspace()
        // endpoint token 迁移先行：把 Room 遗留明文 token 搬进 noBackupFilesDir 并清空 Room 字段，
        // 早于任何备份/连接；BackupManager.snapshot 另有同步屏障兜底冷启动 alarm 路径。
        scope.launch(Dispatchers.IO) { runCatching { endpoints.migrateLegacyTokens() } }
        // 冷启动自愈：上次进程被杀时残留的 Running 会让会话列表一直转圈、通知一直算它「运行中」。
        scope.launch(Dispatchers.IO) { runCatching { repo.resetStaleStatuses() } }
        // 模型自愈：老版本插件自动注册的 plugin.* 条目升级后清掉，列表里只剩用户自己填的。
        scope.launch(Dispatchers.IO) { runCatching { models.purgeAutoRegistered() } }
        // The app is not a daemon: a missed 05:00 backup is caught up here.
        runCatching { BackupScheduler.schedule(context, backupManager) }
        scope.launch(Dispatchers.IO) {
            runCatching { backupManager.catchUpIfDue() }
                .onFailure { AppLog.w("backup catch-up failed: ${it.message}") }
        }
        // 模型配置变了（新增/切换默认/填上 Key），运行时若卡在「未接入模型」就自动重连，
        // 用户不用再手动去 Agent 页点一次重试。
        scope.launch {
            models.models.collect { list ->
                val usable = list.any { it.apiKey.isNotBlank() && it.model.isNotBlank() }
                val state = _runtime.value
                val stuck = state is RuntimeState.Failed || state is RuntimeState.NotConfigured
                if (usable && stuck) connectActive(force = true)
            }
        }
    }

internal fun AppContainer.cwdImpl(): String {
        val configured = configuredCwd.trim()
        if (configured.isEmpty()) return workspaceDir().absolutePath
        val dir = File(configured)
        if (dir.isDirectory) return configured
        val created = runCatching { dir.mkdirs() }.getOrDefault(false)
        return if (created || dir.isDirectory) configured else workspaceDir().absolutePath
    }

internal fun AppContainer.watchWorkspace() {
        scope.launch {
            settings.settings.collect { configuredCwd = it.defaultCwd }
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
internal fun AppContainer.buildExecutionAgents(): StateFlow<List<AgentRuntimeState>> = transport
        .flatMapLatest { t -> (t as? LocalEngineTransport)?.agentStates ?: flowOf(emptyMap()) }
        .map { states -> states.values.sortedBy { it.since } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
