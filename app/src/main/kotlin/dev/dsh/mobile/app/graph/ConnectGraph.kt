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

internal fun AppContainer.buildMcpRuntime(): McpRuntime {
        return run {
        McpRuntime(
            store = McpServerStore(context),
            scope = scope,
            imageDir = { File(context.filesDir, "workspace/mcp-images") },
        ).also { it.start() }
        }
    }

    /** Connect using the active endpoint stored in DataStore. Idempotent. */
internal fun AppContainer.connectActiveImpl(force: Boolean) {
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

internal suspend fun AppContainer.connectImpl(endpoint: Endpoint, runtimeVersion: String) {
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

    /** value.providers[*].models 与 value.models 两种形状都认（再容错裸数组 / 单个字符串）。 */
internal fun AppContainer.engineModelIdsOf(resp: JSONObject): List<String> {
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

internal suspend fun AppContainer.disconnectNow() {
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

internal suspend fun AppContainer.contextWindowFor(sessionId: String): Int {
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

internal suspend fun AppContainer.llmConfigOf(m: ModelProfile, capFallback: Int, sessionId: String): LlmConfig = LlmConfig(
        baseUrl = m.baseUrl,
        apiKey = m.apiKey,
        model = m.model,
        // 思考参数的发送形状由协议层按 providerId/baseUrl/model 推断，这两项是推断的输入。
        providerId = m.providerId,
        reasoningStyle = m.reasoningStyle,
        maxTokens = if (m.maxOutputTokens > 0) m.maxOutputTokens else capFallback,
        systemPrompt = presetSystemPrompt(sessionId),
        reasoningEffort = m.reasoningEffort,
        temperature = m.temperature,
        topP = m.topP,
        supportsTools = m.supportsTools,
        // M5：推理档位映射原样带到协议层；reasoningParams 里命中就按它发，未命中走内置逻辑。
        reasoningMap = m.reasoningMap,
        // 注入重试的第二跳：同 baseUrl 的其它可用模型（换模型才可能躲过整类拒答）。
        fallbackModels = models.models.first()
            .filter { it.model != m.model && it.baseUrl == m.baseUrl && it.usable() }
            .map { it.model }
            .distinct()
            .take(2),
    )

    /** 群组会话 → 每个席位一份配置（同一模型可以出现多次，各自并行）。 */
internal suspend fun AppContainer.groupPlan(sessionId: String): GroupPlan? {
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
