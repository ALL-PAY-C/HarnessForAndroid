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
import dev.dsh.mobile.core.data.settings.ModelProvider
import dev.dsh.mobile.core.data.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.data.settings.contextWindowOf
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

internal fun AppContainer.buildEngineBundled(): Boolean {
        return run {
        runCatching {
            context.assets.open(NodeEngineHost.ASSET_ZIP).close()
            true
        }.getOrDefault(false)
        }
    }

internal fun AppContainer.buildNodeEngine(): NodeEngine {
        return run {
        RemoteNodeEngine(
            context = context,
            scope = scope,
            onUnexpectedDeath = { reason -> crashReporter.recordCaught(CrashKind.ENGINE, "binder", reason) },
        ).also { it.setHostCallHandler(PluginHostCalls(this, context.filesDir)) }
        }
    }

internal fun AppContainer.buildLinux(): LinuxEnvironment {
        return run {
        LinuxEnvironment(
            baseDir = File(context.filesDir, "linux"),
            abi = LinuxAbis.current() ?: "unsupported",
            downloader = LinuxDownload::http,
        )
        }
    }

internal fun AppContainer.createProotChannel(): ProotChannel? =
        linux.active()?.let { installation ->
            ProotChannel(
                installation = installation,
                nativeDir = File(context.applicationInfo.nativeLibraryDir),
                tmpDir = linux.tmpDir,
                workDir = File(context.filesDir, "linux/workspace"),
                network = linuxNetwork(),
            )
        }

internal fun AppContainer.buildShell(): ShellBridge {
        return run {
        ShellBridge(context).also { bridge ->
            // 第一次构造 proot 通道会做 DNS 探测（真 UDP），必须离开主线程；
            // 注册完 refresh 会推动状态流，界面照常更新。
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                createProotChannel()?.let { bridge.register(it) }
            }
            bridge.refresh()
            // 设置里选了固定通道就跟着走；默认 auto = shizuku/root → 应用内 → termux。
            scope.launch {
                settings.settings.collect { st ->
                    // 所有子进程（Termux / proot / 应用内）的代理来源：设置一变就同步。
                    ProxyEnv.set(st.proxyUrl)
                    bridge.preferred = st.shellChannel
                    bridge.refresh()
                    // T4.5：能力档位镜像给同步侧的 PhoneBridge（空串 = 全 Ask）。
                    capabilityTiersCache = st.capabilityTiers
                }
            }
        }
        }
    }

internal fun AppContainer.buildToolExecutors(): ToolExecutors {
        return run {
        ToolExecutors(
            files = FileOps.of(fileBridge),
            shell = { command, channel ->
                val r = shell.execChannel(command, channel, TOOL_SHELL_TIMEOUT_MS)
                // 和非通道版本同一套：失败必须抛出原因，否则 UI 上只剩「失败」+ 空响应。
                if (!r.ok) throw IllegalStateException(r.asToolOutput().ifBlank { "退出码 ${r.code}" })
                r.asToolOutput()
            },
            channels = { shell.channelListJson() },
            // 每次现取通道：rootfs 可能刚装好或被重装，缓存一个 ProotChannel 只会拿到旧的。
            // prootChannel 会做真 UDP 的 DNS 探测（主线程 StrictMode 会抛），所以只在 IO 上取。
            background = {
                withContext(Dispatchers.IO) { prootChannel }
                    ?.let { ProotBackgroundJobs(it, File(context.filesDir, "linux/bg")) }
            },
        )
        }
    }

internal fun AppContainer.buildSkillExec(): suspend (JsonObject) -> String = { args ->
        val name = (args["name"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.let { if (it.isString) it.content else null }
            .orEmpty()
            .trim()
        if (name.isEmpty()) {
            "缺少技能名 name"
        } else {
            try {
                val resp = withContext(Dispatchers.IO) {
                    nodeEngine.rpc("/skill-manager", "read", JSONObject().put("name", name))
                }
                (resp.opt("value") as? JSONObject)?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: "技能读取失败：引擎没有返回内容"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                "技能读取失败：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

internal fun AppContainer.kickOffToolchainImpl() {
        if (toolchainKicked) return
        toolchainKicked = true
        scope.launch(Dispatchers.IO) {
            // rootfs 没装就没有通道，直接退出；prootChannel 会做真 UDP 的 DNS 探测，只能在 IO 上拿。
            val channel = prootChannel ?: return@launch
            val line = runCatching {
                ToolchainProvisioner(
                    channel = channel,
                    jobsDir = File(context.filesDir, "linux/bg"),
                    logFile = File(context.filesDir, "linux/toolchain.log"),
                ).provision()
            }.getOrElse { "工具链预装失败：" + (it.message ?: it.toString()) }
            AppLog.i("toolchain 预装结果：" + line)
        }
    }
