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

internal fun AppContainer.buildPluginInstaller(): PluginInstaller {
        return run {
        PluginInstaller(
            context = context,
            installed = installedPlugins,
            onBeforeInstall = { id -> withContext(Dispatchers.IO) { backupManager.snapshotBeforeInstall(id) } },
            // 插件不再往模型库自动塞条目（用户要求模型全部手填）：onModel 走默认空实现。
            // 插件 ctx.sessionId()：读取口，装载/重载的那一刻读前台会话。
            sessionId = { activePluginSession },
            // 插件 ctx.setSessionPersona()：写的也是那一刻的前台会话人设。
            onSessionPersona = { text -> writeSessionPersona(text) },
        )
        }
    }

internal fun AppContainer.buildPluginHost(): PluginHost {
        return run {
        pluginStore.beginBoot()
        PluginHost(
            pluginStore,
            configBridge = pluginConfigBridge,
            personaBridge = sessionPersonaBridge,
            onPluginCrash = { id, failure -> crashReporter.recordCaught(CrashKind.PLUGIN, "plugin", id + ": " + failure) },
            // 崩溃循环进安全模式后只放行内置插件：判定来自启动守卫，与插件自己数的失败取或。
            extraSafeMode = { bootGuard.safeModeController.isActive() },
            onUninstall = { id ->
            pluginInstaller.uninstall(id).getOrThrow()
            externalPlugins.remove(id)
            pluginHost.placements(context).removePlugin(id)
        }).apply {
            // 内置插件：HTML 渲染器（dsh.html / html.web，含 html 代码围栏的 WebView 渲染）。
            // 源码随 app 编译（app/build.gradle.kts 的 sourceSets），这里排在最前注册，
            // 于是用户额外装的同名插件会被 PluginHost 按 id 去重掉，不会重复注册渲染器。
            val builtinPlugins = appBuiltinPlugins(this)
            // 其余插件：插件页初始就是「无插件」，能力全部来自用户自己安装的插件。
            // 三条安装通路都要接上：
            //  · 独立 APK 插件（外部进程，DshPlugin 实现）→ externalPlugins.load()
            //  · Rhino/声明式数据包插件 → pluginInstaller.loadablePlugins()
            //  · Node 插件（node:/@deepseek-ai/ 依赖）→ pluginInstaller.loadableNodePlugins()，走 NodeEngineHost
            // Markdown/LaTeX 已内置在 :render（不算插件）；HTML 渲染由 dsh.html 插件提供。
            // 启动这一趟也要先拿磁盘对齐注册表：reloadPlugins 有 resync，boot 以前没有，
            // 于是「目录在、注册表缺条目」（装到一半、恢复了旧备份、清过 data）的插件
            // 启动时彻底不加载，界面上什么提示都没有 —— 这是「装上了却不起作用」的头号成因。
            runCatching { pluginInstaller.packages.resync() }
                .onFailure { AppLog.w("启动时对齐插件注册表失败：" + it.message, it) }
            val jsPlugins = externalPlugins.load() + pluginInstaller.loadablePlugins()
            val nodePlugins = pluginInstaller.loadableNodePlugins()
            loadAll(builtinPlugins + jsPlugins + nodePlugins)
            pluginStore.rememberIds(
                slots.bottomTabs.map { it.pluginId } +
                    (builtinPlugins + jsPlugins + nodePlugins).map { it.manifest.id }
            )
            // 槽位管理页的入口签名固定为 onBack，宿主走进程级兜底（CompositionLocal 优先）。
            PluginHostHolder.instance = this
            // 需要常驻本地服务的插件（自带本机网关那种）在这里自动拉起，
            // 用户不必先去插件页点一次「启动」。
            startBackgroundPlugins(context, scope)
            // 模型源必须在 loadAll 之后注册：这一步读的是已加载行的 manifest，
            // 顺序反了会一个模型都注册不上。传 this 而不是 pluginHost，避免重入自己的 lazy。
            syncPluginModelSources(this)
            // Node 插件（node:/@deepseek-ai/ 依赖）异步加载到 NodeEngineHost；失败只记该插件不吞别的。
            if (nodePlugins.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    NodeBackedPlugin.loadNodePlugins(host = this@apply, engine = nodeEngine, plugins = nodePlugins)
                }
            }
        }
        }
    }

internal fun AppContainer.appBuiltinPlugins(host: PluginHost): List<DshPlugin> =
        listOf(HtmlPlugin()) +
            dev.dsh.mobile.pluginhost.builtin.builtinPluginsWith { id, preferred ->
                host.loopbackPort(id, preferred)
            }

internal fun AppContainer.reloadPluginsImpl() {
        runCatching {
            // 先拿磁盘对齐注册表：重装、删包、只恢复了一半数据，都在这一步自愈。
            // 少这一趟，「行不出现 / 行不消失」只能靠用户去插件设置页兜一圈。
            runCatching { pluginInstaller.packages.resync() }
            val jsPlugins = externalPlugins.load() + pluginInstaller.loadablePlugins()
            val nodePlugins = pluginInstaller.loadableNodePlugins()
            val all = appBuiltinPlugins(pluginHost) + jsPlugins + nodePlugins
            pluginHost.loadAll(all, force = true)
            // load 只加不删，幽灵行（目录已删）要靠 keepOnly 收口。
            pluginHost.keepOnly(all.map { it.manifest.id })
            pluginHost.startBackgroundPlugins(context, scope)
            // 新装的包可能带模型源：重载后补注册一次（register 幂等，重复调用无副作用）。
            syncPluginModelSources(pluginHost)
            if (nodePlugins.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    NodeBackedPlugin.loadNodePlugins(pluginHost, nodeEngine, nodePlugins)
                }
            }
        }
    }

internal fun AppContainer.softRestartImpl(reason: RestartReason): Boolean {
        AppLog.i("SOFT_RESTART begin reason=" + reason.label)
        if (reason == RestartReason.BackupRestore) {
            // 恢复改写的是 files/datastore/*.preferences_pb 原始文件（RestoreEngine.restoreSettings 直接
            // writeBytes，绕过 DataStore API），而 DataStore 实例是 SettingsStore.kt:215 的
            // "private val Context.settingsStore by preferencesDataStore(...)" 文件级委托：
            // 一个进程只有一个，没有「作废重读」的公开 API，core/data 又是只读边界。
            // 继续用旧实例 = 界面还是恢复前的值，而且下一次 edit() 会把整份旧缓存写回文件，
            // 把刚恢复的设置覆盖掉。所以恢复场景只能换进程。
            AppLog.w("SOFT_RESTART rejected: DataStore 缓存无法作废 reason=" + reason.label)
            return false
        }
        // 1) 标志位：调用方（SafeModeController.exit）在进来之前已经同步写完 BootGuardStore，
        //    这里不再写第二份，免得出现「标志清了但账本还写着安全模式」。
        // 2) 插件：安全模式期间被拦掉的插件要按磁盘重新注册。PluginHost.load 对已存在 id 是 no-op，
        //    reloadPlugins 走 force=true，并 keepOnly 收掉幽灵行。
        runCatching { reloadPlugins() }
            .onFailure { AppLog.e("SOFT_RESTART reloadPlugins failed", it) }
        // 3) 引擎：安全模式下 connectActive 压根没跑过。force=true 让「同端点且已 Ready」的短路放行，
        //    正常退出时也能真重连一次。
        if (!bootGuard.safeModeController.flags().disableAutoEngine) {
            runCatching { connectActive(force = true) }
                .onFailure { AppLog.e("SOFT_RESTART connectActive failed", it) }
        } else {
            AppLog.i("SOFT_RESTART engine skipped: disableAutoEngine")
        }
        // 4) 常驻通知：RuntimeService 的 collector 还活着，但文案可能停在安全模式那一版。
        runCatching { ongoingCenter.refresh() }
            .onFailure { AppLog.w("SOFT_RESTART ongoingCenter refresh failed", it) }
        AppLog.i("SOFT_RESTART done reason=" + reason.label)
        return true
    }

    /** 后台把人设读进内存副本；读失败只记日志，不让插件拿到「读取中」这种状态。 */
internal fun AppContainer.refreshPersona(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { db.sessionDao().byId(sessionId)?.persona.orEmpty() }
                .onSuccess { personaCache[sessionId] = it }
                .onFailure { AppLog.w("读会话人设失败 " + sessionId + "：" + it.message) }
        }
    }

    /** 后台落库人设；失败照常记日志（插件侧已拿到受理结果，不能把异常咽掉）。 */
internal fun AppContainer.persistPersona(sessionId: String, text: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { repo.setPersona(sessionId, text) }
                .onFailure { AppLog.w("写会话人设失败 " + sessionId + "：" + it.message) }
        }
    }

internal fun AppContainer.syncPluginModelSources(host: PluginHost) {
        val sources = host.modelSources()
        AppLog.i("MODEL_SOURCES " + sources.joinToString(",") { it.id + "=" + it.baseUrl })
        sources.forEach { src ->
            ModelProviders.register(object : ModelProvider {
                override val id: String = src.id
                override val label: String = src.label
                override val defaultBaseUrl: String = src.baseUrl
                override val defaultModel: String = src.defaultModel
                // 提示里带 baseUrl：同名模型源有好几个时，用户靠地址区分。
                override val hint: String = src.label + " · " + src.baseUrl
                // 插件包里放不了真凭据（明文 zip），Key 永远由用户在设置页填。
                override val defaultApiKey: String = ""
            })
        }
    }

internal fun AppContainer.buildPluginConfigBridge(): PluginConfigBridge {
        return run {
        object : PluginConfigBridge {
            override fun read(pluginId: String): Map<String, String> = installedPlugins.config(pluginId)

            override fun write(pluginId: String, key: String, value: String) {
                installedPlugins.setConfig(pluginId, key, value)
            }

            override fun reload() = reloadPlugins()
        }
        }
    }

internal fun AppContainer.writeSessionPersona(text: String): Boolean {
        val sessionId = activePluginSession ?: return false
        val trimmed = text.trim()
        // 先在内存副本上落地，UI 线程立刻返回；落库交给 IO 线程，失败进日志。
        personaCache[sessionId] = trimmed
        persistPersona(sessionId, trimmed)
        return true
    }

internal fun AppContainer.buildSessionPersonaBridge(): SessionPersonaBridge {
        return run {
        object : SessionPersonaBridge {
            override fun sessionId(): String? = activePluginSession

            override fun readPersona(sessionId: String): String {
                // 命中内存副本直接返回；冷缓存只补一次后台读，本次如实返回空串。
                return personaCache[sessionId] ?: run {
                    refreshPersona(sessionId)
                    ""
                }
            }

            override fun writePersona(sessionId: String, text: String): Boolean {
                val trimmed = text.trim()
                val before = personaCache[sessionId]
                if (before == trimmed) return true
                if (before == null) refreshPersona(sessionId)
                personaCache[sessionId] = trimmed
                persistPersona(sessionId, trimmed)
                return true
            }
        }
        }
    }
