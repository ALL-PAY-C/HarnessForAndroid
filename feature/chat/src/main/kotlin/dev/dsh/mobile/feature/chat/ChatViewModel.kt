package dev.dsh.mobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.repo.SendQueue
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.data.preset.PresetSwitch
import dev.dsh.mobile.core.data.repo.TurnState
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.data.settings.contextWindowOf
import dev.dsh.mobile.core.data.settings.SendMode
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionKind
import dev.dsh.mobile.core.model.TokenUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val sessionId: String,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val permissions: List<PermissionRequest> = emptyList(),
    val plan: List<String> = emptyList(),
    val settings: AppSettings = AppSettings.DEFAULT,
    val runtime: RuntimeState = RuntimeState.NotConfigured,
    val error: String? = null,
    val kind: SessionKind = SessionKind.Single,
    val participants: List<Participant> = emptyList(),
    val agentId: String? = null,
    val models: List<ModelProfile> = emptyList(),
    val usage: TokenUsage = TokenUsage.EMPTY,
    val contextWindow: Int = 32_000,
    val group: String = "",
    val groups: List<String> = emptyList(),
    val archived: Boolean = false,
    val cwd: String = "",
    /** 会话人设 / 预设（普通会话生效）。 */
    val persona: String = "",
    /** Tokens per second observed on the last usage update (0 when unknown). */
    val tokensPerSecond: Double = 0.0,
    /** 本会话是否富渲染特殊格式（HTML / LaTeX / Markdown / 代码块）；由会话覆盖或全局默认决定。 */
    val renderSpecial: Boolean = true,
    /** 排队等待发送的消息数。 */
    val queued: Int = 0,
    val queue: List<SendQueue.Item> = emptyList(),
    /** 执行池快照（只读）；空 = 拿不到或全空闲，消息区顶部状态条不渲染。 */
    val agents: List<AgentRuntimeState> = emptyList(),
    /** 本会话生效的预设 id（会话切过就是会话的，否则全局默认）。 */
    val presetId: String = AgentPresets.defaultPresetId,
    /** 该预设暴露的开关；内置预设以外为空，「+」面板据此决定要不要渲染开关区。 */
    val presetSwitches: List<PresetSwitch> = emptyList(),
    /** 已打开的开关 id；没动过的会话等于预设自带的 defaultOn。 */
    val presetSwitchOn: Set<String> = emptySet(),
) {
    val permission: PermissionRequest? get() = permissions.lastOrNull()
    /** 正在执行 / 等确认的 Agent；为空表示全空闲。 */
    val busyAgents: List<AgentRuntimeState>
        get() = agents.filter {
            it.state == AgentRunState.Thinking ||
                it.state == AgentRunState.ToolRunning ||
                it.state == AgentRunState.WaitingPermission
        }

    val sessionTotalTokens: Long get() = usage.sessionTotal
    val sessionTotalText: String get() = compact(sessionTotalTokens)
    val contextPercent: Long get() = if (contextWindow > 0) usage.lastPrompt * 100 / contextWindow else 0
    val contextUsageText: String
        get() = "${exactTokens(usage.lastPrompt)} / ${exactTokens(contextWindow.toLong())}（$contextPercent%）"

    /** 累计消耗放右侧，标题下只显示最近请求的实际上下文。 */
    val tokenText: String
        get() {
            val used = usage.lastPrompt
            val pct = contextPercent
            val speed = if (tokensPerSecond > 0.05) " · ${"%.1f".format(tokensPerSecond)} tok/s" else ""
            val cache = if (usage.cached > 0) " · 缓存 ${usage.cachePercent}%" else ""
            return "上下文 $pct% · ${exactTokens(used)} / ${exactTokens(contextWindow.toLong())}$speed$cache"
        }
    val isGroup: Boolean get() = kind == SessionKind.Group
    /** Name of the agent bound to a single chat (falls back to the default model). */
    val agentName: String
        get() = models.firstOrNull { it.id == agentId }?.name
            ?: models.firstOrNull { it.isDefault }?.name
            ?: models.firstOrNull()?.name
            ?: "未选择 Agent"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repo: ChatRepository,
    private val settingsStore: SettingsStore,
    private val modelStore: ModelStore,
    /** 发送队列（活过页面销毁，见 [SendQueue]）。 */
    private val sendQueue: SendQueue,
    private val sessionId: String,
    /** 执行池的 Agent 快照（core:protocol ExecutionPool.agents）；拿不到就传 null。 */
    executionAgents: StateFlow<List<AgentRuntimeState>>? = null,
    // 放在最后：调用方（app/AppNav）用尾随 lambda 传 cwd，尾随 lambda 只认最后一个参数。
    private val cwdProvider: () -> String,
) : ViewModel() {


    private val error = MutableStateFlow<String?>(null)

    init {
        // 预设抽屉是另一个 VM，靠这个共享 id 判断「切的是本会话」还是「改全局默认」。
        ActiveChatSession.id.value = sessionId
        // 排队/插队/直发失败都只经 SendQueue.sendError 出口（VM 可能已销毁，写回它的 error 没意义）；
        // 这里把本会话的发送错误镜像进 error，复用标题下可读横幅。
        // 来源追踪：只清「上一帧是自己写的」那一条，null 不覆盖 cancel/answerPermission 刚写入的错误。
        // 收在 viewModelScope：VM 销毁即取消 collector，不残留对旧会话队列错误的订阅。
        viewModelScope.launch {
            var mirrored: String? = null
            sendQueue.sendError(sessionId).collect { err ->
                if (err != null) {
                    mirrored = err
                    error.value = err
                } else if (mirrored != null && error.value == mirrored) {
                    mirrored = null
                    error.value = null
                }
            }
        }
    }

    /** 排队区快照：只取本会话那一段（队列实体活在 [SendQueue] 里，页面销毁也不丢）。 */
    private val queueFlow: Flow<List<SendQueue.Item>> = sendQueue.items.map { it[sessionId] ?: emptyList() }

    private val runtime: StateFlow<RuntimeState> = repo.transport
        .flatMapLatest { t -> t?.state ?: flowOf(RuntimeState.NotConfigured) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RuntimeState.NotConfigured)

    private val models: StateFlow<List<ModelProfile>> = modelStore.models
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val sessionFlow: StateFlow<Session?> = repo.observeSessions()
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val groupsFlow = repo.observeSessions()
        .map { list -> list.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }

    private data class Meta(
        val session: Session?,
        val error: String?,
        val models: List<ModelProfile>,
        val groups: List<String>,
        val queue: List<SendQueue.Item> = emptyList(),
    )

    /** 没有执行池就发一个空流，顶部状态条整条不渲染。 */
    private val agentsFlow: Flow<List<AgentRuntimeState>> = executionAgents ?: flowOf(emptyList())

    val uiState: StateFlow<ChatUiState> = combine(
        repo.observeMessages(sessionId),
        repo.turns.map { it[sessionId] ?: TurnState() },
        settingsStore.settings,
        runtime,
        combine(sessionFlow, error, models, groupsFlow, queueFlow) { s, e, m, g, q -> Meta(s, e, m, g, q) },
    ) { persisted, turn, settings, rt, meta ->
        // 实时气泡只保留「还没落库」的部分：一旦落库就立刻从实时列表移除，
        // 否则同一条回复会同时以实时气泡和落库消息出现两次（重复气泡的第二来源）。
        // 判重键 = turnId（用这一轮开始时间代表）+ authorId + 内容 hash。
        val turnId = turn.startedAt.toString()
        val settledKeys = persisted
            .filter { it.role == ChatMessage.Role.ASSISTANT }
            .map { contentKey(it.authorId, it.text) }
            .toSet()
        // 流式阶段 ThoughtChunk 先到、且不带 authorName，实时气泡会退回 id 前 6 位（一串 UUID），
        // 看着像陌生机器人发言。这里用会话成员表把 authorId 还原成名字。
        val participantNames = meta.session?.participants.orEmpty()
            .flatMap { p -> listOf(p.id to p.name, p.modelId to p.name) }
            .toMap()
        val live = buildList {
            addAll(persisted)
            turn.parts
                .filterNot { part ->
                    part.done ||
                        part.key in turn.persisted ||
                        contentKey(part.id, part.text) in settledKeys
                }
                .distinctBy { it.id }
                .forEach { part ->
                    add(
                        ChatMessage(
                            // key 稳定：turnId + authorId；LazyColumn 直接拿 msg.id 当 key。
                            id = "live-$turnId-${part.key}",
                            sessionId = sessionId,
                            role = ChatMessage.Role.ASSISTANT,
                            text = part.text,
                            thought = part.thought,
                            toolCalls = part.tools,
                            streaming = !part.done,
                            authorId = part.id,
                            authorName = participantNames[part.id] ?: part.name,
                            // 固定成这一轮的开始时间，避免每次重组都换时间戳。
                            at = turn.startedAt,
                            inputTokens = part.inputTokens,
                            outputTokens = part.outputTokens,
                        ),
                    )
                }
            // 思维链先到、正文还没来时也要显示：以前这里只看 text/tools，
            // 结果整段推理过程被丢掉，用户只看到「深度思索中…」后面一片空白。
            val showLegacy = turn.running &&
                (turn.text.isNotBlank() || turn.thought.isNotBlank() ||
                    (turn.tools.isNotEmpty() && turn.parts.isEmpty())) &&
                contentKey(null, turn.text) !in settledKeys
            if (showLegacy) {
                add(
                    ChatMessage(
                        id = "live-$turnId-legacy",
                        sessionId = sessionId,
                        role = ChatMessage.Role.ASSISTANT,
                        text = turn.text,
                        thought = turn.thought,
                        toolCalls = turn.tools,
                        streaming = turn.running,
                        at = turn.startedAt,
                        inputTokens = turn.inputTokens,
                        outputTokens = turn.outputTokens,
                    ),
                )
            }
            // 发出去之后到第一个 token 到达之前，屏幕上什么都不会动：
            // 用户会以为没发出去，于是再点一次发送 / 消息乱序。这里先挂一个空的「正在思考」气泡。
            if (showLegacy.not() && none { it.streaming } && turn.running) {
                add(
                    ChatMessage(
                        id = "live-$turnId-pending",
                        sessionId = sessionId,
                        role = ChatMessage.Role.ASSISTANT,
                        text = "",
                        // 单聊的思维链落在 turn.thought（没有 parts），占位气泡必须接上它，
                        // 否则「深度思索中」下面永远是空框。
                        thought = if (turn.parts.isEmpty()) turn.thought else "",
                        toolCalls = if (turn.parts.isEmpty()) turn.tools else emptyList(),
                        streaming = true,
                        authorId = meta.session?.agentId,
                        authorName = participantNames[meta.session?.agentId] ?: "",
                        at = turn.startedAt,
                        inputTokens = turn.inputTokens,
                        outputTokens = turn.outputTokens,
                    ),
                )
            }
        }
        val session = meta.session
        val speed = measureSpeed(session?.usage ?: TokenUsage.EMPTY)
        // 预设按会话取：面板上勾的开关要跟下一轮请求实际用的提示词一致。
        val preset = AgentPresets.builtinOf(settings.presetFor(sessionId)) ?: AgentPresets.standard
        val presetOn = AgentPresets.switchOn(preset, settings.switchOnFor(sessionId))
        val bound = meta.models.firstOrNull { it.id == session?.agentId }
            ?: meta.models.firstOrNull { it.isDefault }
            ?: meta.models.firstOrNull()
        ChatUiState(
            sessionId = sessionId,
            title = session?.title ?: "",
            messages = dedupeAdjacent(live),
            streaming = turn.running,
            permissions = turn.permissions,
            plan = turn.plan,
            settings = settings,
            runtime = rt,
            error = meta.error,
            kind = session?.kind ?: SessionKind.Single,
            participants = session?.participants ?: emptyList(),
            agentId = session?.agentId,
            models = meta.models,
            usage = session?.usage ?: TokenUsage.EMPTY,
            contextWindow = contextWindowOf(bound?.model ?: "", bound?.contextWindow ?: 0),
            group = session?.group.orEmpty(),
            queued = meta.queue.size,
            queue = meta.queue,
            groups = meta.groups,
            archived = session?.archived ?: false,
            cwd = session?.cwd.orEmpty(),
            persona = session?.persona.orEmpty(),
            tokensPerSecond = speed,
            renderSpecial = settings.renderSpecialFor(sessionId),
            presetId = preset.id,
            presetSwitches = preset.switches,
            presetSwitchOn = presetOn,
        )
    }.combine(agentsFlow) { state, agents ->
        if (agents.isEmpty()) state else state.copy(agents = agents)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(sessionId))

    private var lastUsage: TokenUsage = TokenUsage.EMPTY
    private var lastUsageAt: Long = 0L
    private var lastSpeed: Double = 0.0

    /**
     * Output tokens per second between two usage snapshots.
     *
     * The engine only reports cumulative totals, so speed has to be derived by
     * differencing. Sub-250ms gaps are ignored: they would divide by ~0 and show
     * meaningless spikes right after a burst.
     */
    private fun measureSpeed(usage: TokenUsage): Double {
        val now = android.os.SystemClock.elapsedRealtime()
        if (usage.output == lastUsage.output) return lastSpeed
        val deltaTokens = usage.output - lastUsage.output
        val deltaMs = if (lastUsageAt == 0L) 0L else now - lastUsageAt
        lastUsage = usage
        lastUsageAt = now
        if (deltaMs < 250L || deltaTokens <= 0L) return lastSpeed
        lastSpeed = deltaTokens * 1000.0 / deltaMs
        return lastSpeed
    }

    /**
     * 发消息。正在跑的时候：
     * - [SendMode.Queue]（默认）：排队，等这一轮结束再发；
     * - [SendMode.Steer]：先打断当前这轮，立刻发（等价 DSH 的 steer）；
     * - [SendMode.Insert]：不打断，插进当前这轮的下一轮请求里（等价 DSH 的 insert）。
     *
     * T9：本轮不在 VM 作用域里跑 —— [SendQueue.submit] 把它交给应用作用域，页面销毁只解除
     * uiState 订阅。这里只做状态选择（Queue / Steer / Insert）与提交，不等待、不持有本轮。
     */
    fun send(text: String, mode: SendMode = SendMode.Queue, images: List<String> = emptyList()) {
        if (text.isBlank() && images.isEmpty()) return
        // 上一帧还在跑（streaming 尚未回写）时，第二下发送按排队处理，不能直接再发一轮。
        if (uiState.value.streaming) {
            when (mode) {
                SendMode.Steer -> {
                    // 打断 + 立刻发：cancelAndSend 成功时在 finally 里 drain、失败时 skip drain 保队列
                    // （core/data 已处理，见 SendQueueEngine.cancelAndSend）。这里不再额外 drain，
                    // 否则 cancel 失败时会把保留的排队项发到仍在跑的旧 remote，破坏保队列。
                    sendQueue.submit(sessionId, exclusive = false) { deliver(text, images, interrupt = true) }
                    return
                }
                SendMode.Insert -> {
                    // 不打断当前生成：消息落库后直接交给运行时，下一轮请求里生效。
                    sendQueue.submit(sessionId, exclusive = false) { repo.interject(sessionId, text) }
                    return
                }
                SendMode.Queue -> {
                    val ok = sendQueue.enqueue(sessionId, text, images, uiState.value.settings.queueLimit)
                    if (!ok) error.value = "排队已达上限 " + uiState.value.settings.queueLimit + " 条"
                    return
                }
            }
        }
        // 直发闸门在 SendQueue 里（sending 的替代）：同一会话已有直发在飞时转排队，不并发发第二轮。
        if (!sendQueue.submit(sessionId) { deliver(text, images) }) {
            sendQueue.enqueue(sessionId, text, images, uiState.value.settings.queueLimit)
        }
    }

    /** 直发一次，返回结果给 [SendQueue.submit]；Err 由它写进 sendError（不回写可能已销毁的 VM）。 */
    private suspend fun deliver(
        text: String,
        images: List<String> = emptyList(),
        interrupt: Boolean = false,
    ): DshResult<Unit> = if (interrupt) sendQueue.cancelAndSend(sessionId, text, images)
    else sendQueue.sendText(sessionId, text, images)

    /** Reuse the send gate and queue for a fresh answer to this assistant turn's prompt. */
    fun regenerate(messageId: String) {
        val messages = uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId && it.role == ChatMessage.Role.ASSISTANT }
        if (index < 0 || uiState.value.streaming) return
        val prompt = messages.take(index).lastOrNull { it.role == ChatMessage.Role.USER } ?: return
        send(prompt.text, images = prompt.attachments)
    }

    fun clearQueue() = sendQueue.clear(sessionId)

    /** 把队列里某一条撤下来（用户改主意了）。 */
    fun dropQueued(id: String) = sendQueue.drop(sessionId, id)

    /** 把某一条插到最前面（下一轮立刻发）。 */
    fun promoteQueued(id: String) = sendQueue.promote(sessionId, id)

    /** 立即发送：打断当前这轮，把这一条先发出去，其余仍留在队列里。 */
    fun sendNow(id: String) {
        if (sendQueue.of(sessionId).none { it.id == id }) return
        // 打断 + 立刻发：cancel 与 send 在 SendQueue 的会话锁里原子完成，不靠 delay。
        viewModelScope.launch { sendQueue.sendNow(sessionId, id) }
    }

    fun cancel() = viewModelScope.launch {
        // 取消也走 error 通道：远端取消失败（ACP 15s 超时）时，本地 finalize/Idle 由 core:data 兜底，
        // 这里把失败原因写进 error，让标题下的可读横幅呈现出来，而不是静默吞掉。
        when (val r = repo.cancel(sessionId)) {
            is DshResult.Err -> error.value = r.message
            is DshResult.Ok -> error.value = null
        }
    }

    /**
     * 回确认。群聊里只回给发起这次提问的 Agent：卡片带着 ownerAgentId 时，
     * 传入的 [ownerAgentId] 与之不符就丢弃（旧调用点不传，行为不变）。
     */
    fun answerPermission(
        requestId: String,
        optionId: String?,
        ownerAgentId: String? = null,
    ) = viewModelScope.launch {
        when (val r = repo.answerPermission(sessionId, requestId, optionId, ownerAgentId)) {
            is DshResult.Err -> error.value = r.message
            is DshResult.Ok -> error.value = null
        }
    }

    fun clear() = viewModelScope.launch {
        // 先清队列再清消息：队列 clear 会推进代际，让 drain 已经摘出但还没发出的条目失效，
        // 否则点「清空」后，一条正在排队/刚被摘出的消息还会在下一轮被发出去（N1）。
        sendQueue.clear(sessionId)
        repo.clearMessages(sessionId)
    }

    /** 普通会话：换绑 agent。 */
    fun setAgent(modelId: String) = viewModelScope.launch { repo.setAgent(sessionId, modelId) }

    /** 群聊：右上角继续拉入一个 agent（同一 agent 可重复拉，用于并行）。 */
    /** "wb-intl/deepseek-v4.1-flash" → "DeepSeek V4.1 Flash"。 */
    private fun prettyModelName(model: String): String {
        val tail = model.substringAfterLast('/').ifBlank { model }
        val cleaned = tail.replace(Regex("(?i)^deepseek-"), "DeepSeek ")
            .replace(Regex("(?i)^glm-"), "GLM ")
            .replace(Regex("(?i)^kimi-"), "Kimi ")
            .replace(Regex("(?i)^minimax-"), "MiniMax ")
            .replace(Regex("(?i)^qwen-"), "Qwen ")
            .replace('-', ' ')
        return cleaned.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { w ->
                if (w.any { it.isDigit() } || w.length <= 3) w else w.replaceFirstChar { it.uppercase() }
            }
            .trim()
    }

    fun addParticipant(modelId: String) = viewModelScope.launch {
        val session = repo.session(sessionId) ?: return@launch
        val profile = models.value.firstOrNull { it.id == modelId } ?: return@launch
        val existing = session.participants.count { it.modelId == modelId }
        // 成员名用模型自己的可读名，而不是 provider 标签（"Alpha-DS4.1f-GJ" 那种）。
        val base = prettyModelName(profile.model).ifBlank { profile.name }
        val participant = Participant(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            name = if (existing > 0) "$base #${existing + 1}" else base,
            // T8：slot 是席位号，必须唯一。以前取 participants.size，删掉中间一个再加人就会
            // 与现存 slot 撞号（两个成员同一个席位），下游按 slot 派活时就成了「一个 Agent 干两人的活」。
            slot = session.participants.nextFreeSlot(),
        )
        repo.setParticipants(sessionId, session.participants + participant)
    }

    /**
     * 群成员改名。
     *
     * 返回 null 表示成功，否则是给用户看的原因。重名一律拒绝：群内发言靠名字
     * 区分谁在说话（历史消息里也存的是名字），两个同名成员会让 @ 提及和回溯
     * 都指向不明，所以宁可不改。
     */
    suspend fun renameParticipant(participantId: String, newName: String): String? {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return "名字不能为空"
        val session = repo.session(sessionId) ?: return "会话不存在"
        val clash = session.participants.any {
            it.id != participantId && it.name.trim().equals(trimmed, ignoreCase = true)
        }
        if (clash) return "已经有成员叫「$trimmed」了"
        val updated = session.participants.map {
            if (it.id == participantId) it.copy(name = trimmed) else it
        }
        repo.setParticipants(sessionId, updated)
        return null
    }

    fun removeParticipant(participantId: String) = viewModelScope.launch {
        val session = repo.session(sessionId) ?: return@launch
        repo.setParticipants(sessionId, session.participants.filterNot { it.id == participantId })
    }

    fun rename(title: String) = viewModelScope.launch { repo.rename(sessionId, title) }

    fun setGroup(group: String) = viewModelScope.launch { repo.setGroup(sessionId, group) }

    /** Where new artifacts land. Blank falls back to the runtime default. */
    fun setWorkspace(cwd: String) = viewModelScope.launch { repo.setCwd(sessionId, cwd) }

    /** 保存会话人设 / 预设；空串表示清空，回到模型自带行为。 */
    fun setPersona(text: String) = viewModelScope.launch { repo.setPersona(sessionId, text.trim()) }

    fun setArchived(archived: Boolean) = viewModelScope.launch { repo.setArchived(sessionId, archived) }

    /** 右侧边栏的「特殊格式渲染」滑块：只写本会话的覆盖，全局默认不动。 */
    fun setRenderSpecial(on: Boolean) = viewModelScope.launch {
        settingsStore.setRenderSpecialOverride(sessionId, on)
    }

    /** 「+」面板里的预设开关：只写本会话的覆盖，全局默认不动。 */
    fun setPresetSwitch(id: String, on: Boolean) = viewModelScope.launch {
        val s = settingsStore.settings.first()
        val preset = AgentPresets.builtinOf(s.presetFor(sessionId)) ?: return@launch
        val next = AgentPresets.switchOn(preset, s.switchOnFor(sessionId)).toMutableSet()
        if (on) next.add(id) else next.remove(id)
        settingsStore.setSessionPresetSwitches(sessionId, next)
    }

    fun dismissError() {
        error.value = null
        // 同步清掉队列错误源：否则 SendQueue 里还留着旧错误，StateFlow 重放会让横幅重现。
        sendQueue.clearSendError(sessionId)
    }

    override fun onCleared() {
        // 只在还是自己的时候清：新会话的 VM 可能已经先登记过了。
        if (ActiveChatSession.id.value == sessionId) ActiveChatSession.id.value = ""
        super.onCleared()
    }
}

/**
 * 下一个可用席位号（T8）。删人不重排 slot（历史消息/任务里存过这些号），所以新成员取
 * 「最大席位 + 1」，不取 size —— 删掉中间一个后 size 会与留下的空洞撞号，两个成员同席位。
 * ponytail: 只保证唯一与单调，不复用空洞；要复用需先让所有读 slot 的地方容忍复用。
 */
internal fun List<Participant>.nextFreeSlot(): Int = (maxOfOrNull { it.slot } ?: -1) + 1

internal fun exactTokens(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

/** 1.2k / 64k 这种紧凑写法。 */
internal fun compact(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

/** 归一化文本 hash：空白折叠 + 去首尾，换行不同不算两条。 */
private fun contentKey(authorId: String?, text: String): String =
    (authorId ?: "") + "|" + text.trim().replace(WHITESPACE, " ").hashCode()

private val WHITESPACE = Regex("\\s+")

/** 相邻重复气泡的时间窗：超过它就不算重复，避免误杀正常重复发言。 */
private const val DUP_WINDOW_MS = 3_000L

/**
 * 落库 + 实时两路拼完之后再扫一遍：同角色、同作者、归一化文本相同，
 * 且相邻两条时间差 < 3 秒才合并。
 */
private fun dedupeAdjacent(list: List<ChatMessage>): List<ChatMessage> {
    if (list.size < 2) return list
    val out = ArrayList<ChatMessage>(list.size)
    list.forEach { msg ->
        val prev = out.lastOrNull()
        val dup = prev != null &&
            msg.text.isNotBlank() &&
            prev.role == msg.role &&
            prev.authorId == msg.authorId &&
            contentKey(prev.authorId, prev.text) == contentKey(msg.authorId, msg.text) &&
            kotlin.math.abs(msg.at - prev.at) < DUP_WINDOW_MS
        if (!dup) out.add(msg)
    }
    return out
}