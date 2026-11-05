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
import dev.dsh.mobile.core.model.settings.ModelProfile
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

internal suspend fun AppContainer.presetSystemPrompt(sessionId: String): String {
        val s = settings.settings.first()
        val base = dev.dsh.mobile.core.data.preset.CapabilityGuide.augment(LlmConfig.DEFAULT_SYSTEM) +
            pluginPromptSuffix()
        // 技能目录按库现取而不是按会话存：装了/关了技能，下一轮就生效，不用重建会话。
        val skills = skillsBlock(skillsCatalog())
        val preset = presetStore.get(s.presetFor(sessionId)) ?: return base + skills
        val on = dev.dsh.mobile.core.model.preset.AgentPresets.switchOn(preset, s.switchOnFor(sessionId))
        return dev.dsh.mobile.core.model.preset.AgentPresets.compose(preset, base, cwd(), on) + skills
    }

internal suspend fun AppContainer.identityText(sessionId: String): String {
        val presetId = settings.settings.first().presetFor(sessionId)
        val card = runCatching { presetStore.cardOf(presetId) }.getOrNull()
        val identity = if (card != null && card.hasContent()) {
            card.render()
        } else {
            db.sessionDao().byId(sessionId)?.persona.orEmpty()
        }
        // 插件追加段只在本来就有身份时接上：身份为空说明这个会话没设人设，
        // 这时候把插件文本塞进身份位，远端路径会把它包成「用户设定的人设」，语义就错了。
        return if (identity.isBlank()) identity else identity + pluginPromptSuffix()
    }

internal fun AppContainer.pluginPromptSuffix(): String {
        val text = pluginHost.promptAdditions().joinToString("\n\n")
        return if (text.isBlank()) "" else "\n\n" + text
    }

internal suspend fun AppContainer.skillsCatalog(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = nodeEngine.rpc("/skill-manager", "list", JSONObject())
            val entries = (resp.opt("value") as? JSONObject)?.optJSONArray("entries")
            buildList {
                for (i in 0 until (entries?.length() ?: 0)) {
                    val e = entries?.optJSONObject(i) ?: continue
                    // 关掉的技能不进目录：列出来模型就会去读，然后拿一个「未知技能」回来。
                    if (!e.optBoolean("effectiveEnabled")) continue
                    if (!e.optBoolean("modelInvocable", true)) continue
                    val name = e.optString("name")
                    if (name.isBlank()) continue
                    add(name to e.optString("description"))
                }
            }
        }.getOrDefault(emptyList())
    }

internal fun AppContainer.skillsBlock(items: List<Pair<String, String>>): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("\n\n## 可用技能\n")
            append("下面这些技能能直接用。任务与某条描述对得上时，先调 skill 工具按名字读它的完整说明，再照着做。\n")
            for ((name, desc) in items) {
                append("- ").append(name)
                if (desc.isNotBlank()) append("：").append(desc)
                append('\n')
            }
        }
    }
