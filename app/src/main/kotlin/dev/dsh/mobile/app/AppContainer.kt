package dev.dsh.mobile.app

import android.content.Context
import dev.dsh.mobile.app.diag.AppBootGuard
import dev.dsh.mobile.app.diag.CrashReporter
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.RestartReason
import dev.dsh.mobile.core.common.ProxyEnv
import dev.dsh.mobile.core.common.AppScope
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.common.runCatchingDsh
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
import dev.dsh.mobile.core.protocol.context.WarmMessage
import dev.dsh.mobile.core.data.backup.BackupScheduler
import dev.dsh.mobile.plugin.html.HtmlPlugin
import dev.dsh.mobile.pluginapi.PluginModelSource
import dev.dsh.mobile.pluginhost.InstalledPlugins
import dev.dsh.mobile.pluginhost.PluginInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.mobile.pluginhost.PluginHost
import dev.dsh.mobile.pluginhost.PluginHostHolder
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

/**
 * Manual DI container. Everything the app wires together lives here so modules stay
 * framework-free and testable; no annotation processing, no reflection.
 */
class AppContainer(
    private val context: Context,
    /** 崩溃落盘器：DshApplication 在 AppContainer 之前装好的同一实例（构造期崩溃也要有记录）。 */
    val crashReporter: CrashReporter,
    /** 启动守卫：首帧/看门狗/回滚判定都从这里进。 */
    val bootGuard: AppBootGuard,
) {

    val scope: CoroutineScope = AppScope.scope

    val db by lazy { DataModule.createDatabase(context) }
    val settings: SettingsStore by lazy { DataModule.createSettings(context) }
    /**
     * 会话投影（P0-6）：LocalEngineTransport 写、ChatRepository 读、UI 只读订阅。
     * 必须是同一个实例 —— 分成两份就会出现「工具改了待办但界面不动」。
     */
    val projections by lazy { dev.dsh.mobile.core.protocol.SessionProjections() }
    /** 压缩摘要持久化（P0-1）：SharedPreferences，不新建 Room 表。 */
    val summaryStore by lazy {
        dev.dsh.mobile.core.data.repo.SharedPrefsSummaryStore.from(context)
    }
    /** 温层持久化（T9.8-S2）：直接用 S1 的 warm_layers 表，不另建存储。 */
    val warmStore by lazy { RoomWarmLayerStore(db.warmLayerDao()) }
    /** 大工具输出落盘根目录（P0-4）：app 私有目录下的 tools-output/。 */
    val toolOutputSpill by lazy {
        dev.dsh.mobile.core.protocol.ToolOutputSpill(rootProvider = { File(context.filesDir, "tools-output") })
    }
    val repo: ChatRepository by lazy { DataModule.createChatRepository(db, scope, projections) }

    /** 发送队列：活过页面，关掉会话再回来排队中的消息还在。 */
    val sendQueue: SendQueue by lazy { SendQueue(repo, scope) }
    /** endpoint token 独立存储（noBackupFilesDir），不随备份导出；只存密钥，不存其它状态。 */
    val secretStore: EndpointSecretStore by lazy { EndpointSecretStore(context) }
    val endpoints: EndpointRepository by lazy { EndpointRepository(db, settings, secretStore) }
    val models by lazy { ModelStore(context) }

    val presetStore by lazy { dev.dsh.mobile.core.data.preset.AgentPresetStore(context) }

    /** 群聊规划书（T9.7a）：一个会话一本，DataStore 单条 JSON，不建表不迁移。 */
    val groupLedger by lazy { dev.dsh.mobile.core.data.group.GroupLedgerStore(context) }

    /**
     * 本会话实际生效的系统提示词：预设按会话取（会话内切过就用会话的，否则跟随全局默认），
     * 开关也按会话取（没动过的会话按预设自带的默认），所以下一轮请求自动用新选择。
     */
    private suspend fun presetSystemPrompt(sessionId: String): String {
        val s = settings.settings.first()
        val base = dev.dsh.mobile.core.data.preset.CapabilityGuide.augment(LlmConfig.DEFAULT_SYSTEM) +
            pluginPromptSuffix()
        // 技能目录按库现取而不是按会话存：装了/关了技能，下一轮就生效，不用重建会话。
        val skills = skillsBlock(skillsCatalog())
        val preset = presetStore.get(s.presetFor(sessionId)) ?: return base + skills
        val on = dev.dsh.mobile.core.data.preset.AgentPresets.switchOn(preset, s.switchOnFor(sessionId))
        return dev.dsh.mobile.core.data.preset.AgentPresets.compose(preset, base, cwd(), on) + skills
    }

    /**
     * 会话身份文本：预设身份卡渲染结果优先，没有卡时回落到会话的旧自由文本 persona。
     *
     * 远端（把文本塞进首条 user 消息）与本地（拼 extraSystem）都从这一个入口取，
     * 两条路径看到的身份永远一致；卡是预设级的，换会话即换身份。
     */
    private suspend fun identityText(sessionId: String): String {
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

    /**
     * 已启用插件要追加到系统提示词的那一段（前面带两空行）。
     *
     * 现算不缓存：插件停用、装新包、拨 `prompt.enabled` 开关，下一轮请求立刻反映。
     * 远端（首条 user 消息）与本地（extraSystem）两条路径取的是同一个字符串。
     */
    private fun pluginPromptSuffix(): String {
        val text = pluginHost.promptAdditions().joinToString("\n\n")
        return if (text.isBlank()) "" else "\n\n" + text
    }

    /**
     * 当前可被模型调用的技能目录（名字 + 一句描述）。
     *
     * 技能库落在 :node 引擎的 DSH_HOME 下，这边看不到文件，只能问 /skill-manager；
     * 没启引擎、没装技能、引擎报错都退成空表 —— 提示词少一段技能说明，会话照常能聊。
     */
    private suspend fun skillsCatalog(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
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

    /**
     * 技能目录拼成系统提示词的一段。
     *
     * 只放名字和一句描述：全文有几十 KB，写在提示词里每轮都要付钱，让模型真要用时再调 skill 读。
     */
    private fun skillsBlock(items: List<Pair<String, String>>): String {
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

    /** 只给同进程的适配器（RuntimeControlAdapter）读 assets 与私有目录用。 */
    val appContext: Context get() = context.applicationContext

    /**
     * APK 里有没有引擎包。assets/runtime/engine.zip 是构建期从 engine/ 生成的唯一投放物；
     * 旧布局 assets/runtime/<version>/<abi>/node 已经不存在，这里就是「有没有内置引擎」的判据。
     */
    val engineBundled: Boolean by lazy {
        runCatching {
            context.assets.open(NodeEngineHost.ASSET_ZIP).close()
            true
        }.getOrDefault(false)
    }

    /**
     * 设置页「运行时」面板的数据源（单例）：state 是 stateIn 出来的热流，
     * 每次重组新建一份会白白多一条协程，所以这里只建一次。
     */
    val runtimeControl: RuntimeControl by lazy { RuntimeControlAdapter(this) }

    /**
     * Node 引擎（libnode + engine bundle）：真正跑在 `:node` 子进程里，这里只是 AIDL 代理。
     *
     * 反向请求处理器必须在启动前挂好：插件第一次 ctx.llm.chat 就要用到它。
     * 代理的意义：引擎 process.exit / 崩溃只死 `:node`，主进程不受影响；stop() 能真正杀掉
     * 那个进程，于是 libnode「一个进程只能启动一次」的限制不再挡住重启。
     */
    val nodeEngine: NodeEngine by lazy {
        RemoteNodeEngine(
            context = context,
            scope = scope,
            onUnexpectedDeath = { reason -> crashReporter.recordCaught(CrashKind.ENGINE, "binder", reason) },
        ).also { it.setHostCallHandler(PluginHostCalls(this, context.filesDir)) }
    }

    /**
     * Linux 运行环境：proot + Alpine rootfs，装在 filesDir/linux。
     * 版本化目录 + sha256 校验 + 原子激活都在 :linux-runtime 里，这里只决定放哪。
     */
    val linux: LinuxEnvironment by lazy {
        LinuxEnvironment(
            baseDir = File(context.filesDir, "linux"),
            abi = LinuxAbis.current() ?: "unsupported",
            downloader = LinuxDownload::http,
        )
    }

    /** 只有 rootfs 已激活才有 proot 通道；null 时 AUTO_ORDER 自动落到下一个通道。 */
    val prootChannel: ProotChannel? get() = createProotChannel()

    /** DNS 探测是真 UDP 往返（StrictMode 在主线程直接抛），进程内只探一次。 */
    @Volatile
    private var networkCache: dev.dsh.mobile.linux.LinuxNetwork? = null

    private fun linuxNetwork(): dev.dsh.mobile.linux.LinuxNetwork =
        networkCache ?: dev.dsh.mobile.linux.LinuxNetwork.verified(context).also { networkCache = it }

    private fun createProotChannel(): ProotChannel? =
        linux.active()?.let { installation ->
            ProotChannel(
                installation = installation,
                nativeDir = File(context.applicationInfo.nativeLibraryDir),
                tmpDir = linux.tmpDir,
                workDir = File(context.filesDir, "linux/workspace"),
                network = linuxNetwork(),
            )
        }

    @Synchronized
    fun refreshProotChannel(): ProotChannel? {
        val channel = createProotChannel()
        if (channel == null) shell.unregister("proot")
        else shell.register(channel, replace = true)
        shell.refresh()
        return channel
    }

    val shell: ShellBridge by lazy {
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

    /**
     * 文件工具（fs_*）的执行面。额外把应用私有目录加进白名单：模型要能读写自己刚生成的文件。
     */
    val fileBridge: FileBridge by lazy {
        AppStorage.fileBridge(listOf(context.filesDir.absolutePath, context.cacheDir.absolutePath))
    }

    /**
     * 工具执行器：把 app 侧能力拼成 LocalEngineTransport 需要的四个 lambda。
     * 这里只做参数映射与 JSON 透传，白名单/错误码/超时都是 toolbridge 与 linux-runtime 的事。
     */
    val toolExecutors: ToolExecutors by lazy {
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

    /** 给 LocalEngineTransport 的四个 lambda（D3 接线用，见 .mock/agent-env/task-app-shell/REPORT.md）。 */
    val fsExec: suspend (String, JsonObject) -> String get() = toolExecutors::fsExec
    val shellExecChannel: suspend (String, String?) -> String get() = toolExecutors::shellExecChannel
    val channelListExec: suspend () -> String get() = toolExecutors::channelListExec
    val bgExec: suspend (String, JsonObject) -> String get() = toolExecutors::bgExec

    /**
     * skill 工具：只把技能名转给引擎，路径一律由引擎侧按扫描结果解析，这边不拼路径。
     *
     * 失败转成可读文本而不是抛异常：工具结果要能告诉模型「为什么没读到」，
     * 抛出去只剩一个类名，模型下一轮只会照着猜。
     */
    val skillExec: suspend (JsonObject) -> String = { args ->
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

    /** 工具链预装每进程只起一次；重复调用在主线程直接短路，真正的探测都在 IO 上。 */
    @Volatile
    private var toolchainKicked = false

    /**
     * 后台预装 Linux 工具链（bash/jq/ffmpeg/...）。
     *
     * 启动路径绝不等它：provision 要下几十 MB 的 apk 包，前台跑会把冷启动拖到分钟级。
     * 脚本自身幂等（包齐了直接退出、不联网），所以每次冷启动都可以放心跑一遍。
     */
    fun kickOffToolchain() {
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

    /**
     * T4.5：六类能力的档位缓存。PhoneBridge 的判定是同步的（exec 前就要答案），
     * 不能在里面 await DataStore，所以由 [settingsMirror] 这个常驻 collector 推一份进来。
     */
    @Volatile
    private var capabilityTiersCache: String = ""

    // 能力档位从 SettingsStore 注入（不在工具层读全局），空串 = 全 Ask。
    val phone: PhoneBridge by lazy { PhoneBridge(context) { capabilityTiersCache } }

    val pluginStore: PluginStore by lazy { PluginStore(context) }

    /** Installed plugin packages (data-only, declarative). */
    val installedPlugins: InstalledPlugins by lazy { InstalledPlugins(context) }

    val backupManager: BackupManager by lazy { BackupManager(context) }

    /**
     * Installing a package snapshots state first, once per batch — see
     * [BackupManager.snapshotBeforeInstall].
     */
    val pluginInstaller: PluginInstaller by lazy {
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

    /**
     * 外部插件仓库：宿主不内置任何插件（空白安装包），插件由用户自己装。
     * 正常分发是 zip 包 → 交给 [pluginInstaller] 解包成目录（package.json 与入口都留在里面）；
     * 老 .apk 整包仍走 PathClassLoader(apk, hostClassLoader)，与宿主共享 plugin-api 类型。
     */
    val externalPlugins: ExternalPluginStore by lazy { ExternalPluginStore(context, pluginInstaller) }

    /**
     * 必须声明在 init 之前：Kotlin 按声明顺序初始化属性，而 init 里那个
     * models.models.collect 会在构造过程中就收到第一次发射（DataStore 立刻吐空表）。
     * 声明在后面时它读到的是 null → 冷启动 NPE（实测 AppContainer.kt:464，DefaultDispatcher-worker）。
     */
    private val _runtime = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    val runtime: StateFlow<RuntimeState> = _runtime.asStateFlow()

    init {
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

    val pluginHost: PluginHost by lazy {
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
            val builtinPlugins = listOf(HtmlPlugin())
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

    /**
     * 装完插件就地热加载，不用重启 App。
     * [PluginHost.load] 对已存在的 id 默认 no-op，这里 force 重注册（装完更新包 / 失败恢复都走这）。
     */
    fun reloadPlugins() {
        runCatching {
            // 先拿磁盘对齐注册表：重装、删包、只恢复了一半数据，都在这一步自愈。
            // 少这一趟，「行不出现 / 行不消失」只能靠用户去插件设置页兜一圈。
            runCatching { pluginInstaller.packages.resync() }
            val jsPlugins = externalPlugins.load() + pluginInstaller.loadablePlugins()
            val nodePlugins = pluginInstaller.loadableNodePlugins()
            val all = listOf(HtmlPlugin()) + jsPlugins + nodePlugins
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

    /**
     * 进程内软重启：不杀进程，把「只在启动时读一次磁盘」的可变状态按当前磁盘重建一遍。
     *
     * 为什么主路径不再杀进程：Android 14+ 的 BAL 硬化会拦掉 AlarmManager PendingIntent 的启动
     * （实测 BAL_BLOCK，callingUidProcState: FOREGROUND_SERVICE），而 killProcess 之后 AMS 已经把任务
     * 移除（Force removing ActivityRecord ... app died, no saved state），粘性服务把进程拉回来也不会
     * 把 Activity 带回前台。杀进程这条路在当前系统上根本回不来，只能做进程内重建。
     *
     * 为什么不重建 AppContainer 本身，逐条：
     *  · scope 是 AppScope 的进程级单例（AppContainer.kt:111），新容器会多出一整套后台循环
     *    （models 自动重连、备份补跑），旧容器的循环还活着，两套一起写同一份磁盘；
     *  · PluginHostHolder.instance 是进程级槽位，换实例会让已经上屏的插件 UI 指向死宿主；
     *  · db / settings 都是按文件单例（DataModule），再建一份只是多一条连接，读不到「更干净」的数据。
     * 真正需要重建的只有三样：插件注册表、引擎连接、界面（界面由 RestartController 调 recreate）。
     *
     * @return true = 已在进程内重建完；false = 这条场景必须换进程，调用方走硬重启。
     */
    fun softRestart(reason: RestartReason): Boolean {
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

    private val _transport = MutableStateFlow<DshTransport?>(null)
    val transport: StateFlow<DshTransport?> = _transport.asStateFlow()

    /**
     * 执行池快照。只有纯 Kotlin 本地引擎有任务队列，网络端点（ACP / WebSocket）
     * 由对端调度，这里给空表；传输切换时用 flatMapLatest 换源，订阅者无感。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val executionAgents: StateFlow<List<AgentRuntimeState>> = transport
        .flatMapLatest { t -> (t as? LocalEngineTransport)?.agentStates ?: flowOf(emptyMap()) }
        .map { states -> states.values.sortedBy { it.since } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 前台会话 id：插件 ctx.sessionId() 的唯一来源。
     *
     * 导航层在进/出聊天页时写（AppNav 的 DisposableEffect），插件只读。用 @Volatile 而不是
     * StateFlow：插件在装配线程上同步读一次，没有任何 Compose 需要观察它。
     */
    @Volatile
    private var activePluginSession: String? = null

    /**
     * 会话人设的内存副本。
     *
     * 插件槽位（[SlotAction.SetPersona]）在 UI 线程同步读一次人设来判定开关方向，这里不能为了
     * 一个开关去 Room 上阻塞：读一律命中内存，写先在内存落地再交给 IO 线程落库。
     * 会话切换时（[setActivePluginSession]）预热一次，冷启动首点也能拿到真实值。
     */
    private val personaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 导航层告知「用户现在看着哪个会话」；null / 空串表示离开了会话页。 */
    fun setActivePluginSession(sessionId: String?) {
        activePluginSession = sessionId?.takeIf { it.isNotBlank() }
        activePluginSession?.let { refreshPersona(it) }
    }

    /** 后台把人设读进内存副本；读失败只记日志，不让插件拿到「读取中」这种状态。 */
    private fun refreshPersona(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { db.sessionDao().byId(sessionId)?.persona.orEmpty() }
                .onSuccess { personaCache[sessionId] = it }
                .onFailure { AppLog.w("读会话人设失败 " + sessionId + "：" + it.message) }
        }
    }

    /** 后台落库人设；失败照常记日志（插件侧已拿到受理结果，不能把异常咽掉）。 */
    private fun persistPersona(sessionId: String, text: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { repo.setPersona(sessionId, text) }
                .onFailure { AppLog.w("写会话人设失败 " + sessionId + "：" + it.message) }
        }
    }

    /**
     * 把插件声明的模型源注册进 [ModelProviders]，装完插件后 Agent 页的模板列表立刻多出这一项。
     *
     * 只加不删：[ModelProviders] 是进程级注册表且 register 幂等，插件停用后已注册的 provider
     * 仍留在列表里（选中它只会连接失败）。为此在 core/data 开一个 unregister 口子的代价大于收益 ——
     * 那是共享模型层，不是插件层。
     */
    private fun syncPluginModelSources(host: PluginHost) {
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

    /**
     * 插件 config 读写口（SlotAction.SetConfig）：写的是 installedPlugins 里那份存储，
     * 写完走 [reloadPlugins] 重建插件实例 —— 与设置页改完配置后的处理完全一致。
     */
    private val pluginConfigBridge: PluginConfigBridge by lazy {
        object : PluginConfigBridge {
            override fun read(pluginId: String): Map<String, String> = installedPlugins.config(pluginId)

            override fun write(pluginId: String, key: String, value: String) {
                installedPlugins.setConfig(pluginId, key, value)
            }

            override fun reload() = reloadPlugins()
        }
    }

    /**
     * 「破甲注入」一键开关的落库口（[SlotAction.SetPersona] 与插件 ctx.setSessionPersona 共用）。
     *
     * 写的会话永远是导航层此刻报上来的那个，过期会话 id 一律不写 —— 在会话列表点插件开关时
     * 应该什么都不发生，而不是悄悄改上一个会话。人设空串 = 清除，注入包装整段消失。
     */
    private fun writeSessionPersona(text: String): Boolean {
        val sessionId = activePluginSession ?: return false
        val trimmed = text.trim()
        // 先在内存副本上落地，UI 线程立刻返回；落库交给 IO 线程，失败进日志。
        personaCache[sessionId] = trimmed
        persistPersona(sessionId, trimmed)
        return true
    }

    /**
     * 会话人设读写口（[SlotAction.SetPersona]）：与 [pluginConfigBridge] 分开，
     * 一份写插件自己的 config，一份写宿主会话表。
     */
    private val sessionPersonaBridge: SessionPersonaBridge by lazy {
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

    /** 通知点击带过来的会话 id：导航层消费一次后清空，避免旋转屏幕重复跳转。 */
    private val _pendingSession = MutableStateFlow<String?>(null)
    val pendingSession: StateFlow<String?> = _pendingSession.asStateFlow()

    fun openSessionFromNotification(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) _pendingSession.value = sessionId
    }

    fun consumePendingSession() {
        _pendingSession.value = null
    }

    /** 完成 / 待确认的系统通知出口；开关与免打扰在 [NotificationCenter] 内部判定。 */
    val notificationCenter: NotificationCenter by lazy {
        NotificationCenter(context, scope, settings.settings)
    }

    /**
     * 常驻聚合通知：n 个会话在跑就展示 n 行 + 各自思维链，等待确认时把选项挂成通知按钮。
     * 与 [notificationCenter] 分工：那边负责「一次性提醒」，这边负责「现在在跑什么」。
     */
    val ongoingCenter: OngoingCenter by lazy {
        OngoingCenter(context, scope, settings.settings, repo) { notificationCenter }
    }

    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var lastConnectedKey: ConnectionKey? = null
    private val connectionCoordinator = ConnectionCoordinator()

    /** Embedded runtime works inside the app sandbox; keep sessions in one stable dir. */
    fun workspaceDir(): File = File(context.filesDir, "workspace").apply { mkdirs() }

    /**
     * Where new sessions start. A user-set path wins; the app-private directory
     * stays as the fallback so a blank setting never yields an invalid cwd.
     */
    @Volatile
    private var configuredCwd: String = ""

    /**
     * Kept warm by [init] so [cwd] never has to block on DataStore.
     *
     * 用户设的目录可能被删、SD 卡被拔、网盘目录被回收：能建就建，建不出来回落私有目录，
     * 否则运行时会拿着一个不存在的 cwd 起会话（进程直接起不来）。
     */
    fun cwd(): String {
        val configured = configuredCwd.trim()
        if (configured.isEmpty()) return workspaceDir().absolutePath
        val dir = File(configured)
        if (dir.isDirectory) return configured
        val created = runCatching { dir.mkdirs() }.getOrDefault(false)
        return if (created || dir.isDirectory) configured else workspaceDir().absolutePath
    }

    private fun watchWorkspace() {
        scope.launch {
            settings.settings.collect { configuredCwd = it.defaultCwd }
        }
    }

    /** Connect using the active endpoint stored in DataStore. Idempotent. */
    fun connectActive(force: Boolean = false) {
        when (connectGate(connectJob?.isActive == true, force)) {
            ConnectGate.Skip -> return
            // 强制重连不能绕过同一连接生命周期互斥：先取消在跑的 connect
            // （它会在 runCatchingDsh 处上抛 CancellationException 并释放 connectionCoordinator 的互斥），
            // 新 connect 再经 connectionCoordinator 串行执行，旧尝试不会覆盖新状态。
            ConnectGate.CancelThenProceed -> connectJob?.cancel()
            ConnectGate.Proceed -> Unit
        }
        connectJob = scope.launch {
            val settingsValue = settings.settings.first()
            val list = endpoints.all()
            val active = list.firstOrNull { it.id == settingsValue.activeEndpointId }
                ?: defaultEndpoint()
            val key = connectionKey(
                endpoint = active,
                runtimeVersion = settingsValue.runtimeVersion,
                // 引擎只有一个内置包，版本切换没有第二个包可切，只报「有没有」。
                bundledNode = engineBundled,
                workspaceCwd = cwd(),
            )
            // H6：相同端点/运行时配置且已 Ready 时，重进不应掐掉正在跑的一轮；
            // force 或端点内容/运行时配置实际变化才重连。
            if (!shouldReconnect(lastConnectedKey, key, _runtime.value, force)) return@launch
            connect(active, settingsValue.runtimeVersion)
            lastConnectedKey = key
        }
    }

    /**
     * 默认永远是"手机本地运行"：进程内的 Kotlin 引擎（LocalEngineTransport），
     * 需要 Node 的调用由 engine:// 端点转给 `:node` 子进程。
     * 只有在设置里显式切换到某个 loopback / remote endpoint 时才走网络。
     *
     * kind 仍是 InProcess：这个 transport 确实是进程内的，不是网络端点；语义与
     * LocalEngineTransport 一致。旧的「内置 node 可执行文件」进程内通道已经删了。
     */
    /**
     * T9.10-P3：MCP 工具源。设置里 enabled 的服务器把 tools/list 摊成
     * `mcp__<server>__<tool>` 进工具表；某台连不上只让它自己的工具缺席，不打断对话。
     * 预热在后台（2s 后），首轮请求不等 MCP。
     */
    private val mcpRuntime: McpRuntime by lazy {
        McpRuntime(
            store = McpServerStore(context),
            scope = scope,
            imageDir = { File(context.filesDir, "workspace/mcp-images") },
        ).also { it.start() }
    }

    fun defaultEndpoint(): Endpoint =
        Endpoint(id = "embedded", name = "本地运行时", kind = TransportKind.InProcess, cwd = cwd())

    suspend fun connect(endpoint: Endpoint, runtimeVersion: String = "bundled") {
        // 同一连接生命周期互斥：force 重连也不能并发改 _runtime/_transport/repo/stateJob。
        connectionCoordinator.replace(
            teardown = { disconnectNow() },
            start = {
                _runtime.value = RuntimeState.Starting("准备运行时")
                val t: DshTransport = when (endpoint.kind) {
                    // 本地端点走纯 Kotlin 引擎（LocalEngineTransport）：同一套 DshTransport，UI 无感。
                    // 需要 Node 的调用由 engineChat / engineRpc 转给 `:node` 子进程；旧的
                    // 「assets/runtime/<version>/<abi>/node 解出可执行文件在进程内跑」分支已删。
                    TransportKind.InProcess -> LocalEngineTransport(
                        scope = scope,
                        web = WebToolkit(
                            workspace = File(context.filesDir, "workspace").apply {
                                check(isDirectory || mkdirs()) { "创建 Web 工作区失败：$absolutePath" }
                            },
                            settings = { settings.settings.first().toWebSettings(secretStore) },
                        ),
                        configProvider = { sessionId -> activeLlmConfig(sessionId) },
                        groupProvider = { sessionId -> groupPlan(sessionId) },
                        historyProvider = { id -> db.messageDao().bySession(id).map { it.toModel() } },
                        shellExec = { cmd ->
                            val r = shell.exec(cmd)
                            // 失败必须抛出原因：以前直接返回 asToolOutput()（空串），
                            // UI 就只剩一个「失败」+ 空的响应 JSON。
                            if (!r.ok) throw IllegalStateException(r.asToolOutput().ifBlank { "退出码 ${r.code}" })
                            r.asToolOutput()
                        },
                        toolHelp = { shell.describeForModel() + "\n\n" + phone.describeForModel() },
                        phoneExec = { args ->
                            val r = phone.exec(args.toString())
                            if (!r.ok) throw IllegalStateException(r.error ?: "phone 工具失败")
                            r.output
                        },
                        personaProvider = { id -> identityText(id) },
                        autoApprove = { settings.settings.first().autoApproveTools },
                        // 工具桥（原生文件/通道/后台任务）与单聊上下文预算，见 .mock/agent-env/task-app-shell 与 task-proto-wiring 报告。
                        fsExec = fsExec,
                        shellExecChannel = shellExecChannel,
                        channelListExec = channelListExec,
                        bgExec = bgExec,
                        skillExec = skillExec,
                        // 压缩预算 = 会话模型窗口 * 0.8，和顶栏百分比同一口径（以前取 groupContextBudget 字符数，
                        // 24000 字符 /4 = 6000 tokens，跟顶栏 32k 完全两套，于是 /compact 永远回「不需要压缩」）。
                        contextBudgetProvider = { sid -> ContextBudget.budgetChars(contextWindowFor(sid)) },
                        contextWindowProvider = { sid -> contextWindowFor(sid) },
                        // 引擎插件：baseUrl = engine://<模型 id> 时走 :node 的 /llm，不碰网络。
                        // nodeEngine 是 lazy，这里只是传闭包，真正碰引擎发生在发消息那一刻。
                        engineChat = { payload -> nodeEngine.rpc("/llm", "chat", payload) },
                        engineRpc = { path, endpoint, payload -> nodeEngine.rpc(path, endpoint, payload) },
                        // P0-1/P0-4/P0-6：摘要持久化、大输出落盘、会话投影，都走同一实例给到仓库与 UI。
                        spill = toolOutputSpill,
                        projections = projections,
                        summaryStore = summaryStore,
                        // T9.8-S2：温层读写 + 带 rowid 的历史。温层只读 messages 的 rowid/role/text 三列，
                        // 不参与装配（S3 才接 prompt），刷失败也只是少一份备忘，不影响对话。
                        warmStore = warmStore,
                        warmHistoryProvider = { sid ->
                            db.messageDao().warmRows(sid).map { WarmMessage(it.rowId, it.role, it.text) }
                        },
                        // T9.8-S4 冷层回捞：只读 messages 的 rowid/role/text 三列，命中块进下一轮提示词，
                        // 绝不写回消息表（UI 条数不变）。不注入这两条通道 = 回捞整段关闭。
                        recallSearch = { sid, like, limit ->
                            db.warmLayerDao().searchRecall(sid, like, limit)
                                .map { RecallHit(it.rowId, it.role, it.text) }
                        },
                        recallRows = { sid, from, to, limit ->
                            db.warmLayerDao().recallInRange(sid, from, to, limit)
                                .map { RecallHit(it.rowId, it.role, it.text) }
                        },
                        // T9.10-P3：MCP 服务器注入的工具（每轮从 source 快照取，配置改动即生效）。
                        extraTools = mcpRuntime.source,
                    ).also { t ->
                        // 子代理嵌套深度跟着设置走（默认 2）：设置页改完下一次派发即生效，不用重连运行时。
                        scope.launch { settings.settings.collect { t.setSubagentMaxDepth(it.subagentMaxDepth) } }
                        // T4.5：六类能力的档位同理跟着设置走，改完下一次工具调用即生效。
                        scope.launch { settings.settings.collect { t.setCapabilityTiers(it.capabilityTiers) } }
                    }
                    // Loopback / Remote 只差 defaultCwd：同一个 ACP transport，载体都是 WebSocket。
                    // 载体只管搬帧（LineCarrier 负责 open/读行/断链信号），握手·重连·配对在 DshMux 里；
                    // 这里不再手动 .also{ it.open() }——open 由 carrier.connect() 统一发起（旧代码开过两次）。
                    TransportKind.Loopback, TransportKind.Remote -> AcpTransport(
                        carrier = LineCarrier(scope) {
                            WebSocketChannel(endpoint.url, endpoint.token, endpoint.heartbeatMs)
                        },
                        kind = endpoint.kind,
                        scope = scope,
                        defaultCwd = {
                            if (endpoint.kind == TransportKind.Remote) {
                                endpoint.cwd.ifBlank { cwd() }
                            } else {
                                cwd()
                            }
                        },
                        personaProvider = { id -> identityText(id) },
                        // T9.10-P4：启用的 MCP 服务器随 session/new 下发给引擎，引擎自己连自己执行，
                        // 工具调用走既有 toolCall 事件回流。每次建会话现读设置：改完配置对新会话生效，
                        // 旧会话沿用创建时的集合（session/resume 不接受 mcpServers）。
                        mcpServers = {
                            McpServerStore(context).load().map { it.toProtocolConfig() }
                        },
                    )
                }
                _transport.value = t
                repo.attach(t, endpoint.id)
                stateJob?.cancel()
                stateJob = scope.launch { t.state.collect { _runtime.value = it } }
                // 取消必须传播：被 force 重连取消的旧 connect 不能把「正常取消」写成 Failed。
                when (val r = runCatchingDsh { t.connect() }) {
                    is DshResult.Ok -> Unit
                    is DshResult.Err -> {
                        AppLog.w("connect failed: ${r.message}")
                        _runtime.value = RuntimeState.Failed(r.message, retryable = true)
                    }
                }
            },
        )
    }

    suspend fun disconnect() {
        connectionCoordinator.teardown { disconnectNow() }
    }

    /**
     * 引擎插件上报的模型 id（/llm models）——对话框在 engine:// 端点下点「拉取模型列表」走这里。
     *
     * 引擎没起来、插件没装、宿主没有 Node 运行时，都只是「没有模型」：探测是可选能力，
     * 异常吞掉返回空表，别把 AIDL 层的堆栈甩到对话框上。
     */
    suspend fun engineModelIds(): List<String> = runCatching {
        engineModelIdsOf(nodeEngine.rpc("/llm", "models", JSONObject()))
    }.getOrDefault(emptyList())

    /** value.providers[*].models 与 value.models 两种形状都认（再容错裸数组 / 单个字符串）。 */
    private fun engineModelIdsOf(resp: JSONObject): List<String> {
        if (!resp.optBoolean("ok", true)) return emptyList()
        val value = resp.opt("value") ?: return emptyList()
        val out = LinkedHashSet<String>()
        fun collect(from: JSONArray?) {
            if (from == null) return
            for (i in 0 until from.length()) {
                when (val item = from.opt(i)) {
                    is String -> if (item.isNotBlank()) out += item
                    is JSONObject -> item.optString("id")
                        .ifBlank { item.optString("model") }
                        .ifBlank { item.optString("name") }
                        .takeIf { it.isNotBlank() }
                        ?.let { out += it }
                }
            }
        }
        when (value) {
            is JSONObject -> {
                val providers = value.optJSONArray("providers")
                for (i in 0 until (providers?.length() ?: 0)) {
                    collect(providers?.optJSONObject(i)?.optJSONArray("models"))
                }
                collect(value.optJSONArray("models"))
                collect(value.optJSONArray("data"))
                value.optString("model").takeIf { it.isNotBlank() }?.let { out += it }
            }

            is JSONArray -> collect(value)
            is String -> if (value.isNotBlank()) out += value
        }
        return out.toList()
    }

    private suspend fun disconnectNow() {
        val old = _transport.value ?: return
        _transport.value = null
        repo.attach(null)
        stateJob?.cancel()
        _runtime.value = RuntimeState.NotConfigured
        // B3：替换连接前，旧 transport + 旧 runtime 必须 await 停完才能 start 新的；
        // fire-and-forget 会让迟到的 stop() 关掉刚 start 的 current。
        teardownConnection(
            disconnectTransport = { runCatching { old.disconnect() } },
            stopRuntime = { runCatching { nodeEngine.stop() } },
        )
    }

    /**
     * 会话绑定的模型 → 本地引擎配置；没绑定时回落到默认模型。
     * 没有可用 Key 时返回 null，运行时状态会提示去配置。
     */
    /** engine:// 走本地引擎，密钥由插件自己管，空 Key 也算可用；其余仍要求填 Key。 */
    private fun ModelProfile.usable(): Boolean =
        model.isNotBlank() && (apiKey.isNotBlank() || baseUrl.trim().startsWith("engine://"))

    /**
     * 会话绑定的模型 → 上下文窗口（tokens）。解析顺序与 [activeLlmConfig] 一致：
     * session.agentId 命中 → isDefault → 首个；都查不到就用 32k 兜底。
     * 顶栏百分比、装配预算、压缩预算（窗口 * 0.8）全从这里取，避免再出现两套口径。
     */
    private suspend fun contextWindowFor(sessionId: String): Int {
        val window = runCatching {
            val list = models.models.first()
            val bound = if (sessionId.isBlank()) null else repo.session(sessionId)?.agentId
            val m = list.firstOrNull { it.id == bound }
                ?: list.firstOrNull { it.isDefault }
                ?: list.firstOrNull()
            if (m == null) 0 else contextWindowOf(m.model, m.contextWindow)
        }.getOrDefault(0)
        return if (window > 0) window else ContextBudget.DEFAULT_WINDOW_TOKENS
    }

    internal suspend fun activeLlmConfig(sessionId: String): LlmConfig? {
        val list = models.models.first()
        val bound = if (sessionId.isBlank()) null else repo.session(sessionId)?.agentId
        val m = list.firstOrNull { it.id == bound }
            ?: list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
            ?: return null
        if (!m.usable()) return null
        return llmConfigOf(m, settings.settings.first().maxOutputTokens, sessionId)
    }

    /**
     * 模型条目 → 协议层请求配置：思考强度、采样参数、工具开关都来自模型配置，
     * 不再在协议层写死（Agent 页可分别设置）。
     */
    private suspend fun llmConfigOf(m: ModelProfile, capFallback: Int, sessionId: String): LlmConfig = LlmConfig(
        baseUrl = m.baseUrl,
        apiKey = m.apiKey,
        model = m.model,
        maxTokens = if (m.maxOutputTokens > 0) m.maxOutputTokens else capFallback,
        systemPrompt = presetSystemPrompt(sessionId),
        reasoningEffort = m.reasoningEffort,
        temperature = m.temperature,
        topP = m.topP,
        supportsTools = m.supportsTools,
        // 破甲重试的第二跳：同 baseUrl 的其它可用模型（换模型才可能躲过整类拒答）。
        fallbackModels = models.models.first()
            .filter { it.model != m.model && it.baseUrl == m.baseUrl && it.usable() }
            .map { it.model }
            .distinct()
            .take(2),
    )

    /** 群组会话 → 每个席位一份配置（同一模型可以出现多次，各自并行）。 */
    private suspend fun groupPlan(sessionId: String): GroupPlan? {
        val session = repo.session(sessionId) ?: return null
        if (!session.isGroup || session.participants.isEmpty()) return null
        val profiles = models.models.first()
        val cap = settings.settings.first().maxOutputTokens
        val isolate = settings.settings.first().groupIsolateMembers
        val members = session.participants.mapNotNull { p ->
            val m = profiles.firstOrNull { it.id == p.modelId } ?: return@mapNotNull null
            if (!m.usable()) return@mapNotNull null
            // 角色席位（T9.7b）：没选角色就是 null，这个席位不参与角色编排，退回老轮转。
            // 选了就把该角色的身份卡（用户在设置里改过的覆盖层优先）拼在 persona 前面注入。
            val role = dev.dsh.mobile.core.model.GroupRole.of(p.role).takeIf { p.role.isNotBlank() }
            val roleCard = role?.let { presetStore.roleCard(it).render().takeIf(String::isNotBlank) }
            GroupMember(
                id = p.id,
                name = p.name,
                persona = p.persona,
                cfg = llmConfigOf(m, cap, sessionId),
                card = listOfNotNull(roleCard, p.persona.takeIf { it.isNotBlank() })
                    .joinToString("\n\n"),
                role = role,
                // 「同人多次拉入各自独立」开关真正生效的地方：默认每个席位一条线程，
                // 关掉时按模型归并，多个实例共用一份记忆（对照用）。
                thread = if (isolate) p.id else p.modelId,
            )
        }
        if (members.isEmpty()) return null
        val s = settings.settings.first()
        // 「办公室式自主分工」开关在自动档下直接接管：3 人以上才值得开会（经理+至少两个干活的），
        // 两人群仍按关键词分诊，否则每句话都要多付一次拆解 + 一次汇总的钱。
        val autoMode = when {
            s.groupMode != "auto" -> s.groupMode
            s.groupOfficePrompt && members.size >= 3 -> "office"
            else -> "auto"
        }
        return GroupPlan(
            members = members,
            mode = autoMode,
            allowTools = s.groupAllowTools,
            maxParallel = s.groupMaxParallel,
            contextBudget = s.groupContextBudget,
            scheduler = s.groupScheduler,
            moderatorModel = s.groupModeratorModel,
            // 角色编排循环的账本：席位选了角色时，B/D/C/A 的条目状态写进这本规划书。
            roleLedger = groupLedger,
        )
    }

}