package dev.dsh.mobile.app

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

/**
 * Manual DI container. Everything the app wires together lives here so modules stay
 * framework-free and testable; no annotation processing, no reflection.
 */
class AppContainer(
    internal val context: Context,
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

    /**
     * 会话身份文本：预设身份卡渲染结果优先，没有卡时回落到会话的旧自由文本 persona。
     *
     * 远端（把文本塞进首条 user 消息）与本地（拼 extraSystem）都从这一个入口取，
     * 两条路径看到的身份永远一致；卡是预设级的，换会话即换身份。
     */

    /**
     * 已启用插件要追加到系统提示词的那一段（前面带两空行）。
     *
     * 现算不缓存：插件停用、装新包、拨 `prompt.enabled` 开关，下一轮请求立刻反映。
     * 远端（首条 user 消息）与本地（extraSystem）两条路径取的是同一个字符串。
     */

    /**
     * 当前可被模型调用的技能目录（名字 + 一句描述）。
     *
     * 技能库落在 :node 引擎的 DSH_HOME 下，这边看不到文件，只能问 /skill-manager；
     * 没启引擎、没装技能、引擎报错都退成空表 —— 提示词少一段技能说明，会话照常能聊。
     */

    /**
     * 技能目录拼成系统提示词的一段。
     *
     * 只放名字和一句描述：全文有几十 KB，写在提示词里每轮都要付钱，让模型真要用时再调 skill 读。
     */

    /** 只给同进程的适配器（RuntimeControlAdapter）读 assets 与私有目录用。 */
    val appContext: Context get() = context.applicationContext

    /**
     * APK 里有没有引擎包。assets/runtime/engine.zip 是构建期从 engine/ 生成的唯一投放物；
     * 旧布局 assets/runtime/<version>/<abi>/node 已经不存在，这里就是「有没有内置引擎」的判据。
     */
    val engineBundled: Boolean by lazy { buildEngineBundled() }

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
    val nodeEngine: NodeEngine by lazy { buildNodeEngine() }

    /**
     * Linux 运行环境：proot + Alpine rootfs，装在 filesDir/linux。
     * 版本化目录 + sha256 校验 + 原子激活都在 :linux-runtime 里，这里只决定放哪。
     */
    val linux: LinuxEnvironment by lazy { buildLinux() }

    /** 只有 rootfs 已激活才有 proot 通道；null 时 AUTO_ORDER 自动落到下一个通道。 */
    val prootChannel: ProotChannel? get() = createProotChannel()

    /** DNS 探测是真 UDP 往返（StrictMode 在主线程直接抛），进程内只探一次。 */
    @Volatile
    internal var networkCache: dev.dsh.mobile.linux.LinuxNetwork? = null

    internal fun linuxNetwork(): dev.dsh.mobile.linux.LinuxNetwork =
        networkCache ?: dev.dsh.mobile.linux.LinuxNetwork.verified(context).also { networkCache = it }


    @Synchronized
    fun refreshProotChannel(): ProotChannel? {
        val channel = createProotChannel()
        if (channel == null) shell.unregister("proot")
        else shell.register(channel, replace = true)
        shell.refresh()
        return channel
    }

    val shell: ShellBridge by lazy { buildShell() }

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
    val toolExecutors: ToolExecutors by lazy { buildToolExecutors() }

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
    val skillExec: suspend (JsonObject) -> String = buildSkillExec()

    /** 工具链预装每进程只起一次；重复调用在主线程直接短路，真正的探测都在 IO 上。 */
    @Volatile
    internal var toolchainKicked = false

    /**
     * 后台预装 Linux 工具链（bash/jq/ffmpeg/...）。
     *
     * 启动路径绝不等它：provision 要下几十 MB 的 apk 包，前台跑会把冷启动拖到分钟级。
     * 脚本自身幂等（包齐了直接退出、不联网），所以每次冷启动都可以放心跑一遍。
     */
    fun kickOffToolchain() = kickOffToolchainImpl()

    /**
     * T4.5：六类能力的档位缓存。PhoneBridge 的判定是同步的（exec 前就要答案），
     * 不能在里面 await DataStore，所以由 [settingsMirror] 这个常驻 collector 推一份进来。
     */
    @Volatile
    internal var capabilityTiersCache: String = ""

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
    val pluginInstaller: PluginInstaller by lazy { buildPluginInstaller() }

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
    internal val _runtime = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    val runtime: StateFlow<RuntimeState> = _runtime.asStateFlow()

    init { initRuntimeState() }

    val pluginHost: PluginHost by lazy { buildPluginHost() }

    /**
     * 编译进 APK 的 Kotlin 插件（B3-0）：启动装配与 [reloadPlugins] 必须取同一份。
     *
     * 以前只有 HtmlPlugin()，热重载时 keepOnly 只认这一份名单 —— 内置后台网关插件（LocalGateway /
     * StreamGateway）不在名单里就会被当成「磁盘上已经没有的插件」连行带实例摘掉，表现为
     * 「进一次插件页，两个内置插件消失、模型源也一起空掉」。
     *
     * 端口由传入的 host 分配：[PluginHost.loopbackPort] 已分配即稳定，重载拿到的还是同一个端口。
     */

    /**
     * 装完插件就地热加载，不用重启 App。
     * [PluginHost.load] 对已存在的 id 默认 no-op，这里 force 重注册（装完更新包 / 失败恢复都走这）。
     */
    fun reloadPlugins() = reloadPluginsImpl()

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
    fun softRestart(reason: RestartReason): Boolean = softRestartImpl(reason)

    internal val _transport = MutableStateFlow<DshTransport?>(null)
    val transport: StateFlow<DshTransport?> = _transport.asStateFlow()

    /**
     * 执行池快照。只有纯 Kotlin 本地引擎有任务队列，网络端点（ACP / WebSocket）
     * 由对端调度，这里给空表；传输切换时用 flatMapLatest 换源，订阅者无感。
     */
    val executionAgents: StateFlow<List<AgentRuntimeState>> = buildExecutionAgents()

    /**
     * 前台会话 id：插件 ctx.sessionId() 的唯一来源。
     *
     * 导航层在进/出聊天页时写（AppNav 的 DisposableEffect），插件只读。用 @Volatile 而不是
     * StateFlow：插件在装配线程上同步读一次，没有任何 Compose 需要观察它。
     */
    @Volatile
    internal var activePluginSession: String? = null

    /**
     * 会话人设的内存副本。
     *
     * 插件槽位（[SlotAction.SetPersona]）在 UI 线程同步读一次人设来判定开关方向，这里不能为了
     * 一个开关去 Room 上阻塞：读一律命中内存，写先在内存落地再交给 IO 线程落库。
     * 会话切换时（[setActivePluginSession]）预热一次，冷启动首点也能拿到真实值。
     */
    internal val personaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 导航层告知「用户现在看着哪个会话」；null / 空串表示离开了会话页。 */
    fun setActivePluginSession(sessionId: String?) {
        activePluginSession = sessionId?.takeIf { it.isNotBlank() }
        activePluginSession?.let { refreshPersona(it) }
    }



    /**
     * 把插件声明的模型源注册进 [ModelProviders]，装完插件后 Agent 页的模板列表立刻多出这一项。
     *
     * 只加不删：[ModelProviders] 是进程级注册表且 register 幂等，插件停用后已注册的 provider
     * 仍留在列表里（选中它只会连接失败）。为此在 core/data 开一个 unregister 口子的代价大于收益 ——
     * 那是共享模型层，不是插件层。
     */

    /**
     * 插件 config 读写口（SlotAction.SetConfig）：写的是 installedPlugins 里那份存储，
     * 写完走 [reloadPlugins] 重建插件实例 —— 与设置页改完配置后的处理完全一致。
     */
    val pluginConfigBridge: PluginConfigBridge by lazy { buildPluginConfigBridge() }

    /**
     * 「提示词注入」一键开关的落库口（[SlotAction.SetPersona] 与插件 ctx.setSessionPersona 共用）。
     *
     * 写的会话永远是导航层此刻报上来的那个，过期会话 id 一律不写 —— 在会话列表点插件开关时
     * 应该什么都不发生，而不是悄悄改上一个会话。人设空串 = 清除，注入包装整段消失。
     */

    /**
     * 会话人设读写口（[SlotAction.SetPersona]）：与 [pluginConfigBridge] 分开，
     * 一份写插件自己的 config，一份写宿主会话表。
     */
    val sessionPersonaBridge: SessionPersonaBridge by lazy { buildSessionPersonaBridge() }

    /** 通知点击带过来的会话 id：导航层消费一次后清空，避免旋转屏幕重复跳转。 */
    internal val _pendingSession = MutableStateFlow<String?>(null)
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

    internal var connectJob: Job? = null
    internal var stateJob: Job? = null
    internal var lastConnectedKey: ConnectionKey? = null
    internal val connectionCoordinator = ConnectionCoordinator()

    /** Embedded runtime works inside the app sandbox; keep sessions in one stable dir. */
    fun workspaceDir(): File = File(context.filesDir, "workspace").apply { mkdirs() }

    /**
     * Where new sessions start. A user-set path wins; the app-private directory
     * stays as the fallback so a blank setting never yields an invalid cwd.
     */
    @Volatile
    internal var configuredCwd: String = ""

    /**
     * Kept warm by [init] so [cwd] never has to block on DataStore.
     *
     * 用户设的目录可能被删、SD 卡被拔、网盘目录被回收：能建就建，建不出来回落私有目录，
     * 否则运行时会拿着一个不存在的 cwd 起会话（进程直接起不来）。
     */
    fun cwd(): String = cwdImpl()


    fun connectActive(force: Boolean = false) = connectActiveImpl(force)

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
    val mcpRuntime: McpRuntime by lazy { buildMcpRuntime() }

    fun defaultEndpoint(): Endpoint =
        Endpoint(id = "embedded", name = "本地运行时", kind = TransportKind.InProcess, cwd = cwd())

    suspend fun connect(endpoint: Endpoint, runtimeVersion: String = "bundled") = connectImpl(endpoint, runtimeVersion)

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



    /**
     * 会话绑定的模型 → 本地引擎配置；没绑定时回落到默认模型。
     * 没有可用 Key 时返回 null，运行时状态会提示去配置。
     */
    /** engine:// 走本地引擎，密钥由插件自己管，空 Key 也算可用；其余仍要求填 Key。 */
    internal fun ModelProfile.usable(): Boolean =
        model.isNotBlank() && (apiKey.isNotBlank() || baseUrl.trim().startsWith("engine://"))

    /**
     * 会话绑定的模型 → 上下文窗口（tokens）。解析顺序与 [activeLlmConfig] 一致：
     * session.agentId 命中 → isDefault → 首个；都查不到就用 32k 兜底。
     * 顶栏百分比、装配预算、压缩预算（窗口 * 0.8）全从这里取，避免再出现两套口径。
     */

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


}