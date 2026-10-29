package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.LoopbackGateway
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.PermissionOption
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.TaskState
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.model.ToolCall.ToolStatus
import dev.dsh.mobile.core.protocol.web.WebToolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一个可接入的模型后端描述（协议层只认这些字段，不依赖数据层）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = DEFAULT_SYSTEM,
    /** 单轮 max_tokens；0 表示不限制（推理模型会写出几万字思维链，群组一轮能跑十分钟）。 */
    val maxTokens: Int = 0,
    /** 思考强度 off|low|medium|high；只有推理后端（DeepSeek 系）才写进请求。 */
    val reasoningEffort: String = "high",
    /** 采样温度，<0 表示不发送该参数（由上游默认）。 */
    val temperature: Float = -1f,
    /** top_p，<0 表示不发送该参数。 */
    val topP: Float = -1f,
    /** 模型是否支持 function calling；false 时不发 tools，避免 400。 */
    val supportsTools: Boolean = true,
) {
    companion object {
        val DEFAULT_SYSTEM = """
            你是运行在用户 Android 手机上的 DSH Agent。
            - 需要操作手机时调用 shell 工具（命令在手机本机执行，返回 stdout）。
            - 常用能力：am/pm/settings/dumpsys、文件读写、curl、python（若装有 Termux）。
            - 不要臆造命令输出；命令失败时根据 stderr 调整。
            - 回答使用中文，简洁直接。
            - 需要用户在几个方案里挑一个时，正文照常写；末尾单独追加一段选项块，格式：
              [[OPTIONS]]
              A: 选项一
              B: 选项二
              [[/OPTIONS]]
              客户端会把它渲染成可以点的按钮，点了就等于用户选了那一条。
        """.trimIndent()
    }
}

/** 群组里的一个 Agent 席位：同一模型可以被拉进来多次，各自并行干活。 */
data class GroupMember(
    val id: String,
    val name: String,
    val persona: String,
    val cfg: LlmConfig,
    /**
     * 这个席位自己的上下文线程键。
     *
     * 默认等于 [id]，也就是同一个 Agent 被拉进群几次就有几份互相独立的记忆。
     * 设置里关掉「同人多次拉入各自独立」时，[dev.dsh.mobile.app.AppContainer] 会把它
     * 改成 modelId：几个实例共享一条线程，用来对照「共享 vs 独立」的差别。
     */
    val thread: String = id,
)

enum class GroupRouting {
    /** 每轮换人：按用户轮次轮着叫。 */
    RoundRobin,

    /**
     * 默认档：不再全员广播（那是 token 浪费的根源）。
     * 有 @ 只叫被 @ 的人；没 @ 是追问就叫上一个发言人，否则叫第一个成员。
     */
    All,
}

data class GroupPlan(
    val members: List<GroupMember>,
    /**
     * 默认全员应答：群里每个成员都该说话，除非用户明确点名。
     * RoundRobin 只在显式指定时使用（省 token 的场合）。
     */
    val routing: GroupRouting = GroupRouting.All,
    /** 用户设置里的编排档：auto / chain / parallel / automaton / office。auto 表示按句子分诊。 */
    val mode: String = "auto",
    /** 群成员能不能开 shell。默认关：N 个成员各跑一遍同样的命令既慢又危险。 */
    val allowTools: Boolean = false,
    /** 同时最多几个成员在跑。 */
    val maxParallel: Int = 3,
    /** 群上下文预算（字符）。群成员共享的对话摘要在超过这个量时压缩得更狠。 */
    val contextBudget: Int = 24_000,
)

/**
 * 群聊的三种编排形态（参考开源多 Agent 工作流插件的做法，只保留真用得上的三种）：
 *
 * - [Parallel]：多人各干一部分，同时开跑，互不等待；
 * - [Chain]：接力，前一个人说完、摘要进公共历史，下一个人只补没说到的；
 * - [Automaton]：一棒做、一棒验收，验收不过打回重做，直到验收者说【完成】或撞上轮次上限。
 *
 * 以前只有一种（串行链），所以「你俩同时干」是排队的、「你俩互相监督」只能各说一轮。
 */
internal enum class GroupMode {
    Parallel,
    Chain,
    Automaton,

    /**
     * 办公室模式：先让一个「经理」把活拆成若干条，一人一条分下去同时干，
     * 干完再让经理汇总成人话。
     *
     * 和 [Parallel] 的区别：Parallel 是把同一句话广播给所有人各自理解「我要做哪部分」，
     * 于是每个人都把整件事做一遍；Office 是**先有一个真实的分工计划**，每人只拿到自己那一条。
     */
    Office,
}

/** 自动机一棒之后的结论。 */
internal enum class Verdict { More, Rework, Done }

private fun GroupPlan?.isNullOrEmpty(): Boolean = this == null || members.isEmpty()

/**
 * 纯 Kotlin 的本地 Agent 引擎：不依赖 Node，直接走 OpenAI 兼容的 /chat/completions，
 * 并把自己注册的 shell 工具交给模型调用。
 *
 * 这是"手机上跑 DSH"的兜底实现：当 APK 内没有内置 Node 运行时时，
 * [dev.dsh.mobile.app.AppContainer] 会选它，UI 完全无感（同一套 DshTransport）。
 */
class LocalEngineTransport(
    private val scope: CoroutineScope,
    private val configProvider: suspend (String) -> LlmConfig?,
    private val groupProvider: suspend (String) -> GroupPlan? = { null },
    private val historyProvider: suspend (String) -> List<ChatMessage>,
    private val shellExec: suspend (String) -> String,
    private val toolHelp: () -> String = { "shell 工具：在手机上执行 shell 命令并返回输出。" },
    /** phone 工具：通过无障碍服务模拟点击/滑动/输入/读屏/开应用/截屏（参数为 JSON 对象）。 */
    private val phoneExec: suspend (JsonObject) -> String = { "phone 工具未接入" },
    /** 会话人设 / 预设（普通会话生效；群聊各自带 persona）。 */
    private val personaProvider: suspend (String) -> String = { "" },
    /** 设置里的「自动确认工具」：为真时不再弹确认卡，直接放行。 */
    private val autoApprove: suspend () -> Boolean = { false },
    /** 网络工具（web_search / web_fetch / download）；网络层与工作区都可注入，默认不含工作区。 */
    private val web: WebToolkit = WebToolkit(),
    /** 原生文件工具：保留 JSON 参数和结果，避免 shell 转义损失。 */
    private val fsExec: suspend (String, JsonObject) -> String = { _, _ -> "文件工具未接入" },
    /** 通道缺省时保留旧 shell 接线。 */
    private val shellExecChannel: suspend (String, String?) -> String = { cmd, _ -> shellExec(cmd) },
    private val channelListExec: suspend () -> String = { "[]" },
    private val bgExec: suspend (String, JsonObject) -> String = { _, _ -> "后台任务未接入" },
    /** 与群聊相同，预算直接以字符计，默认复用群聊预算。 */
    private val contextBudgetProvider: suspend (String) -> Int = { GroupPlan(emptyList()).contextBudget },
    /**
     * 引擎路由：baseUrl 以 engine:// 开头时，把请求交给 :node 引擎里的 /llm chat，
     * 不再走 HTTP。null 表示宿主没有可用的引擎桥（例如 APK 内没有 Node 运行时）。
     */
    private val engineChat: (suspend (JSONObject) -> JSONObject)? = null,
    /** 引擎通用 RPC（path, endpoint, payload）；用于 engineModels 探测，不与 engineChat 合并。 */
    private val engineRpc: (suspend (String, String, JSONObject) -> JSONObject)? = null,
) : DshTransport {

    private val _events = MutableSharedFlow<DshEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<DshEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val sessions = mutableMapOf<String, Session>()
    private val jobs = mutableMapOf<String, Job>()

    /** 每会话起轮/取消锁：prompt 的「建档 + 挂 job」与 cancel 严格串行，取消不会误杀新轮。 */
    private val turnLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    // ---- 工具防抖 / 执行器 / 每 Agent 线程 / 执行池（需求 A、B、D、E） ----
    private val loopGuard = ToolLoopGuard()
    private val toolRunner = ToolExecutor(
        mapOf(
            "shell" to { args -> shellExecChannel(args["command"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(), (args["channel"] as? JsonPrimitive)?.contentOrNullSafe()) },
            "fs_read" to { args -> fsExec("fs_read", args) },
            "fs_write" to { args -> fsExec("fs_write", args) },
            "fs_list" to { args -> fsExec("fs_list", args) },
            "fs_op" to { args -> fsExec("fs_op", args) },
            "channel_list" to { _ -> channelListExec() },
            "run_bg" to { args -> bgExec("run_bg", args) },
            "bg_status" to { args -> bgExec("bg_status", args) },
            "bg_kill" to { args -> bgExec("bg_kill", args) },
            "phone" to phoneExec,
        ) + web.toolHandlers(),
    )

    /** 每个会话的插话队列，见 [interject]。 */
    private val interjects =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    override fun interject(sessionId: String, text: String) {
        if (text.isBlank()) return
        interjects.getOrPut(sessionId) { java.util.concurrent.ConcurrentLinkedQueue() }.add(text)
    }
    private val threads = AgentThreads()
    private val groupTurns = mutableMapOf<String, GroupTurn>()

    /** 上一个真正说完话的成员（sessionId -> agentId）：未 @ 时用来判断「是不是在追问它」。 */
    private val lastSpeaker = mutableMapOf<String, String>()

    /**
     * taskId -> 该任务最终发言。执行池只留状态（Done/Failed），不留正文，
     * 而自动机要拿上一棒的产出喂下一棒，所以这里存一份（每轮覆盖，超量直接清空）。
     */
    private val taskOutputs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 单 Agent 直跑时的权限等待队列（requestId -> 用户的选择）。
     *
     * 以前单聊里 confirm() 只把卡片广播出去就立刻返回「允许」，用户点不点都一样 ——
     * 卡片点了没反应，命令却已经执行了。现在两档共用同一套等待语义：
     * 群聊走执行池的 [ExecutionPool.requestPermission]，单聊走这里。
     */
    private val directPermissions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()

    /** requestId -> 发起者 agentId（单聊为空字符串）：别的成员点不了这张卡。 */
    private val directOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 有界并行执行池：最多 [GROUP_MAX_PARALLEL] 个成员同时跑；同 session 同 agent 串行。
     * 以前是单协程串行消费，群里 4 个人排队 4 轮，等于串行聊天。
     */
    private val pool = ExecutionPool(scope, maxParallelSetting = GROUP_MAX_PARALLEL, permissionEvent = { _events.emit(it) }) { task -> runMemberTask(task) }
    val agentStates: StateFlow<Map<String, AgentRuntimeState>> get() = pool.agents

    /** 群聊本轮快照：执行池的 runner 靠它还原成员配置与上下文。 */
    private data class GroupTurn(
        val plan: GroupPlan,
        val history: List<ChatMessage>,
        val userText: String,
        val turnId: String? = null,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun connect() {
        _state.value = RuntimeState.Starting("读取模型配置")
        val cfg = configProvider("")
        _state.value = if (cfg == null) {
            RuntimeState.Failed("未接入模型：到 Agent 页添加 API Key 后点重试", retryable = true)
        } else {
            RuntimeState.Ready(dev.dsh.mobile.core.model.TransportKind.InProcess, "本地引擎 · ${cfg.model}")
        }
    }

    override suspend fun disconnect() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.value = RuntimeState.NotConfigured
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        buildJsonObject { put("ok", true) }

    override suspend fun listSessions(cwd: String?): List<Session> = sessions.values.toList()

    override suspend fun newSession(cwd: String): Session {
        val s = Session(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            cwd = cwd,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        sessions[s.id] = s
        return s
    }

    override suspend fun resumeSession(sessionId: String): Session =
        sessions[sessionId] ?: Session(id = sessionId, title = "会话", cwd = "")

    override suspend fun closeSession(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
        sessions.remove(sessionId)
    }

    override suspend fun cancel(sessionId: String) {
        // 与 prompt 的「建档 + 挂 job」用同一把会话锁：取消和起轮严格串行，
        // 不会出现「取消把刚启动的新轮 job 误杀」的窗口。
        turnLocks.getOrPut(sessionId) { Mutex() }.withLock {
            jobs.remove(sessionId)?.cancel()
            // 群聊的活挂在执行池里，取消主协程不会带走它们：单独再收一次，
            // 否则界面已经显示「已停止」，后台成员还在烧 token（用户看到的「停下没反应」）。
            pool.cancel(sessionId)
            groupTurns.remove(sessionId)
            interjects.remove(sessionId)
            // 不再发 TurnEnd("cancelled")：结算交给 repo.cancel 同步完成（带 turnId），
            // 这里再发一条无主 End 只会在新轮启动后迟到、把新轮误结算（H2）。
        }
    }

    override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) = Unit

    /** G：权限卡只对发起者任务生效，别的 Agent 拿来会被拒。 */
    override suspend fun replyPermission(sessionId: String, requestId: String, optionId: String?) {
        // 单聊的卡：唤醒挂在 confirm() 里的协程；群聊的卡：交给执行池。
        if (directOwners[requestId]?.let { it != sessionId } == true) return
        val direct = directPermissions.remove(requestId)
        if (direct != null) {
            directOwners.remove(requestId)
            direct.complete(optionId)
            return
        }
        pool.resolvePermission(requestId, optionId, sessionId = sessionId)
    }

    @Volatile
    private var groupToolsEnabled: Boolean = false

    override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
        val group = runCatching { groupProvider(sessionId) }.getOrNull()
        val cfg = if (group.isNullOrEmpty()) configProvider(sessionId) else null
        if (group.isNullOrEmpty() && cfg == null) {
            throw IllegalStateException("未接入模型，请到 Agent 页添加 API Key")
        }
        val history = historyProvider(sessionId)
        val persona = if (group.isNullOrEmpty()) {
            runCatching { personaProvider(sessionId) }.getOrNull().orEmpty().trim()
        } else {
            ""
        }
        // 与 cancel 同一把会话锁：建档 + 挂 job 原子化，单槽 jobs[sessionId] 不丢、不并发。
        val job = turnLocks.getOrPut(sessionId) { Mutex() }.withLock {
            scope.launch {
                if (group.isNullOrEmpty()) {
                    runLoop(
                        sessionId = sessionId,
                        cfg = cfg!!,
                        history = history,
                        userText = text,
                        userImages = images,
                        extraSystem = if (persona.isBlank()) "" else "用户为这个会话设定的人设与规则，请始终遵守：\n$persona",
                        turnId = turnId,
                    )
                } else {
                    runGroup(sessionId, group!!, history, text, turnId)
                }
            }.also { jobs[sessionId] = it }
        }
        job.join()
        jobs.remove(sessionId)
        return "ok"
    }

    // ------------------------------------------------------------------ loop

    private suspend fun runLoop(
        sessionId: String,
        cfg: LlmConfig,
        history: List<ChatMessage>,
        userText: String,
        userImages: List<String> = emptyList(),
        authorId: String? = null,
        authorName: String? = null,
        extraSystem: String = "",
        useTools: Boolean = true,
        roster: List<String> = emptyList(),
        /** 群聊时由 AgentThreads 预先拼好的上下文（含 system）；给了就不再自己拼。 */
        presetMessages: List<JsonObject>? = null,
        /** 执行池任务：非空时不可逆命令走权限确认，任务状态置 WaitingPermission。 */
        task: AgentTask? = null,
        onFinal: (String) -> Unit = {},
        turnId: String? = null,
    ) {
        val deepSeekThinking = cfg.model.contains("deepseek", ignoreCase = true) ||
            cfg.model.contains("ds4", ignoreCase = true) || cfg.baseUrl.contains("deepseek", ignoreCase = true)
        // 每次进入本轮都清掉同轮去抖状态；60 秒滑动窗口跨轮保留，跨轮死循环也要拦。
        loopGuard.beginTurn()
        val messages =
            if (presetMessages != null) presetMessages.toMutableList() else mutableListOf<JsonObject>()
        if (presetMessages == null) {
        val system = buildString {
            append(cfg.systemPrompt)
            append("\n\n")
            if (useTools) {
                append(toolHelp())
                append("\n\n")
                append(web.helpText())
            }
            if (extraSystem.isNotBlank()) {
                append("\n\n")
                append(extraSystem)
            }
        }
        messages += buildJsonObject {
            put("role", "system")
            put("content", system)
        }
        // 历史里已经包含刚落库的这条用户消息；再拼一次 userText 会让模型看到两遍问题，
        // 它就会把同一段回答说两遍（「为什么输出了两次」的真正原因）。
        val tail = history.lastOrNull()
        val hist = if (
            tail != null &&
            tail.role == ChatMessage.Role.USER &&
            tail.text.trim() == userText.trim()
        ) history.dropLast(1) else history
        hist.forEach { m ->
            val role = when (m.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                else -> return@forEach
            }
            if (m.text.isBlank()) return@forEach
            val content = if (authorId != null && m.authorName != null) {
                "[${m.authorName}] ${m.text}"
            } else {
                m.text
            }
            messages += buildJsonObject {
                put("role", role)
                put("content", content)
                if (deepSeekThinking && useTools && role == "assistant" && m.thought.isNotBlank()) {
                    put("reasoning_content", m.thought)
                }
            }
        }
        }
        // user content 两条分支都要拼：群聊走 presetMessages，以前整段漏在这里面，
        // 群成员拿不到图片（「群聊里看不到图片」）。
        // 但 presetMessages 已由 buildContext 装好本轮用户消息（含图片）时不能再追一条，
        // 否则群成员看到两遍同一个请求 → 重复作答、双倍 token。
        val presetEndsWithUser = presetMessages?.lastOrNull()
            ?.get("role")?.toString()?.contains("user") == true
        if (!presetEndsWithUser) {
            messages += buildJsonObject {
                put("role", "user")
                put("content", buildUserContent(userText, userImages))
            }
        }

        if (presetMessages == null) {
            val assembly = threads.assembleForSingleChat(
                sessionId = sessionId,
                budgetChars = contextBudgetProvider(sessionId),
                system = messages.first()["content"]!!.jsonPrimitive.content,
                history = messages.drop(1).dropLast(1),
                userMsg = messages.takeLast(1),
            )
            messages.clear()
            messages.addAll(assembly.messages)
            AppLog.w("single context usedChars=${assembly.usedChars} fullChars=${assembly.fullChars} overBudget=${assembly.overBudget} overflowChars=${assembly.overflowChars}")
        }

        // 子代理继承的会话系统提示词：只取拼好的 system（含工具说明与 persona），
        // 子代理自己再加一句角色说明。群聊里 messages[0] 就是成员自己的 system。
        val parentSystem = messages.firstOrNull()
            ?.takeIf { it["role"]?.jsonPrimitive?.contentOrNullSafe() == "system" }
            ?.get("content")?.jsonPrimitive?.contentOrNullSafe().orEmpty()

        // 已发出的正文：每轮只发增量，避免上游整段重发导致仓库侧累加出重复气泡。
        var emitted = ""
        // 本轮累计发出的正文字符数：收尾时据此判断「这一轮到底说没说话」。
        var visibleChars = 0
        // 最近一次工具失败的「命令：错误」摘要（见 toolFailureTrace），只在收尾留痕时用。
        var lastToolError: String? = null
        // 断流自动重试：整轮最多一次（硬上限，避免上游持续掐断时打成死循环）。
        var streamRetried = false
        // 本轮已经真正执行过的工具数：只有在这之前没有任何副作用，才允许重发同一请求。
        var toolRunsThisTurn = 0

        /**
         * 收尾留痕：异常收尾时若本轮没有任何正文，补一条 MessageChunk 再发 TurnEnd。
         *
         * 以前轮数用尽 / 工具死循环 / 工具失败后模型直接收尾这三条路径只发 ToolCallEvent
         * + TurnEnd，正文为空；ChatRepository.finalizeTurn 只在正文非空时落库，于是会话里
         * 只剩一张工具卡，用户以为「做着做着断了」，还得自己来问一句。
         */
        fun emitTurnEndNotice(
            reason: String,
            roundsExhausted: Boolean = false,
            loopDetail: String? = null,
        ) {
            val notice = turnEndNotice(
                reason = reason,
                visibleChars = visibleChars,
                lastToolError = lastToolError,
                roundsExhausted = roundsExhausted,
                maxRounds = MAX_ROUNDS,
                loopDetail = loopDetail,
            ) ?: return
            _events.tryEmit(DshEvent.MessageChunk(sessionId, notice, authorId, authorName, turnId))
        }
        // 兜底：万一还有异常逃出主循环（上游/工具/序列化），本轮也要给出正文并结算，
        // 否则仓库 finalizeTurn 拿不到任何正文，这一轮在库里就只剩一行 USER。
        try {
        repeat(MAX_ROUNDS) { round ->
            // 「插入」的消息在下一轮请求里生效：不打断当前生成，只是把用户的话
            // 追加成一条 user 消息，模型这一轮就会看到（DSH 的 insert 语义）。
            interjects.remove(sessionId)?.let { q ->
                while (true) {
                    val text = q.poll() ?: break
                    if (text.isBlank()) continue
                    messages += buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    }
                }
            }
            // 本轮上游已按 delta 推过来的推理累计量：收尾只补差额，ThoughtChunk 不叠加。
            var streamedReasoning = ""
            // 本轮上游已按 delta 推过来的正文累计量：onText 里增量上抛，收尾只补差额。
            var streamedText = ""
            val body = buildJsonObject {
                put("model", cfg.model)
                if (deepSeekThinking) {
                    // DeepSeek OpenAI-format controls: /guides/thinking_mode (not enable_thinking).
                    // 强度来自模型配置（Agent 页「思考强度」），不再写死 high。
                    val effort = cfg.reasoningEffort.lowercase().ifBlank { "high" }
                    if (effort == "off") {
                        putJsonObject("thinking") { put("type", "disabled") }
                    } else {
                        putJsonObject("thinking") { put("type", "enabled") }
                        put("reasoning_effort", effort)
                    }
                }
                if (cfg.temperature >= 0f) put("temperature", cfg.temperature)
                if (cfg.topP >= 0f) put("top_p", cfg.topP)
                put("messages", JsonArray(messages))
                // 最后一轮不再给工具：模型必须直接用正文收尾，而不是再要一次工具、
                // 然后被「轮数用尽」静默掐断（用户看到的是一句话没说完就停了）。
                // engine:// 也发表：插件 provider 把 tools 原样转给上游（OpenAI 形状），
                // 回包里的 tool_calls 由 postToEngine 还原成 choices[].message.tool_calls。
                if (useTools && cfg.supportsTools && shouldOfferTools(round, MAX_ROUNDS)) {
                    put("tools", toolsJson())
                }
                if (cfg.maxTokens > 0) put("max_tokens", cfg.maxTokens)
                // Always SSE: several upstreams reject non-stream outright.
                put("stream", true)
            }
            // 正文里混着的工具调用标记在流里就要吞掉：先显示再删会闪一坨 DSML 给用户看。
            val dsmlGate = DsmlStreamFilter()
            fun pushVisible(text: String) {
                if (text.isEmpty()) return
                streamedText += text
                val newDelta = incrementalDelta(emitted, streamedText)
                if (newDelta.isNotEmpty()) {
                    emitted = streamedText
                    visibleChars += newDelta.length
                    _events.tryEmit(DshEvent.MessageChunk(sessionId, newDelta, authorId, authorName, turnId))
                }
            }
            var retriedThisRound = false
            var resp = runCatching {
                post(
                    cfg,
                    body,
                    onReasoning = { delta ->
                        if (delta.isNotEmpty()) {
                            streamedReasoning += delta
                            _events.tryEmit(DshEvent.ThoughtChunk(sessionId, delta, authorId, authorName, turnId))
                        }
                    },
                    onText = { delta ->
                        // 真流式：正文逐 delta 上抛，跨轮用 emitted 去重（工具循环里模型会整段重发）。
                        // 群聊清洗要整段做，流式只在单聊（roster 空）原样发。
                        if (delta.isNotEmpty() && roster.isEmpty()) pushVisible(dsmlGate.feed(delta))
                    },
                )
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                AppLog.w("llm call failed: ${e.message}")
                _events.tryEmit(DshEvent.MessageChunk(sessionId, "\n\n[请求失败] " + friendlyRequestError(e, cfg.baseUrl), authorId, authorName, turnId))
                _events.tryEmit(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                return
            }

            // 断流兜底：连接在 [DONE] 之前被掐断。本轮还没跑过任何工具时，同一请求静默重发一次
            // （重试期间不推流式正文，只保留最后一轮的 content），别让用户看到半句话 + 中断提示。
            if (resp.bool("dsh_eof_without_done") == true && toolRunsThisTurn == 0 && !streamRetried) {
                streamRetried = true
                retriedThisRound = true
                AppLog.w("stream cut before [DONE]; retry once")
                resp = runCatching { post(cfg, body) }.getOrElse {
                    AppLog.w("stream retry failed: ${it.message}")
                    resp
                }
            }
            // 非重试路径：把闸门押着的尾巴放出来（普通文字不能吞；标记块已在 finish 里丢弃）。
            if (!retriedThisRound && roster.isEmpty()) pushVisible(dsmlGate.finish())

            if (!currentCoroutineContext().isActive) return

            val choice = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            // 收尾原因（length / content_filter）：这一轮没正文时靠它给用户一个可读原因。
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNullSafe()
            val msg = choice?.get("message")?.jsonObject
            val rawContent = msg?.get("content")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            // 正文里混着的  thinking… 段（非流式整包返回时 post 没经过分流）在这里剥掉。
            val split = splitThinking(rawContent)
            // 工具调用标记也得在推 UI 之前剥掉：plain 整包（post 里提前 return 的那条）与 engine://
            // 送进来的正文都没经过流式闸门，先发后删就是让用户看见一屏 markup。
            val dsml = stripDsmlToolCalls(split.first)
            var content = dsml.first
            // 思维链：字段名各家用得不一样，逐个认（reasoning_content / reasoning / thinking / …）。
            val reasoning = buildString {
                reasoningOf(msg)?.let { if (it.isNotBlank()) append(it) }
                if (split.second.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(split.second)
                }
            }
            val reasoningDelta = incrementalDelta(streamedReasoning, reasoning)
            if (reasoningDelta.isNotEmpty()) {
                streamedReasoning += reasoningDelta
                _events.tryEmit(DshEvent.ThoughtChunk(sessionId, reasoningDelta, authorId, authorName, turnId))
            }
            if (content.isNotBlank()) {
                val clean = if (roster.isEmpty()) content else sanitizeGroupText(content, authorName ?: "", roster)
                if (clean.isNotBlank()) {
                    val delta = incrementalDelta(emitted, clean)
                    if (delta.isNotEmpty()) {
                        emitted = clean
                        visibleChars += delta.length
                        _events.tryEmit(DshEvent.MessageChunk(sessionId, delta, authorId, authorName, turnId))
                    }
                    // onFinal 给的是「本轮累计正文」，群聊侧据此整段替换而不是追加。
                    onFinal(clean)
                }
            }

            // 断流：上游在 [DONE] 之前就关了连接。正文已流式发出，补一句标注并落到终态（不再 Running）。
            if (resp.bool("dsh_eof_without_done") == true) {
                _events.tryEmit(DshEvent.MessageChunk(sessionId, "（回复中断 · 连接意外断开，请重试）", authorId, authorName, turnId))
                _events.tryEmit(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                return
            }

            resp["usage"]?.jsonObject?.let { u ->
                val prompt = u["prompt_tokens"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                val cached = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull()
                    ?: (u["prompt_cache_hit_tokens"] as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                val output = u["completion_tokens"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                _events.tryEmit(
                    DshEvent.Usage(sessionId, TokenUsage(input = (prompt - cached).coerceAtLeast(0L), output = output.toLong(), cached = cached, lastPrompt = prompt), authorId, turnId = turnId),
                )
            }

            var toolCalls = msg?.get("tool_calls")?.jsonArray
            // 非流式整包（postOnce 的 plain 分支）不经过上面的兜底，正文里混的标记在这里再兜一次。
            if (toolCalls.isNullOrEmpty() && dsml.second.isNotEmpty()) {
                toolCalls = JsonArray(dsml.second.map { it.toToolCallJson() })
            }
            if (toolCalls.isNullOrEmpty()) {
                // 上游这一轮什么都没给（error 字段 / choices 为空 / 被长度截断 / 纯空正文）时，
                // 以前直接 TurnEnd("stop")：界面一片空白，用户只能猜是不是网断了。
                if (visibleChars == 0) {
                    val cause = upstreamEmptyReason(resp, finishReason)
                        .ifBlank { lastToolError?.let { "工具失败后没有后续正文（" + it.trim() + "）" }.orEmpty() }
                        .ifBlank { "上游返回了空内容（没有正文也没有工具调用）" }
                    // 群聊成员的零输出由 runMemberTask 统一写「（该成员本轮无输出）」，
                    // 这里再发一条会变成两条说明。
                    if (authorId == null) {
                        _events.tryEmit(
                            DshEvent.MessageChunk(sessionId, "（本轮没有任何输出：" + cause + "）", authorId, authorName, turnId),
                        )
                    }
                    _events.tryEmit(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                    return
                }
                // 模型自己收尾：工具失败后没再说话、上游只回空，都在这里交代一句。
                emitTurnEndNotice(reason = "stop")
                _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", authorId, turnId))
                return
            }

            messages += buildJsonObject {
                put("role", "assistant")
                put("content", content)
                if (deepSeekThinking && useTools) {
                    reasoningOf(msg)?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
                }
                put("tool_calls", toolCalls)
            }

            toolCalls.forEach { tc ->
                val obj = tc.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: UUID.randomUUID().toString()
                val fn = obj["function"]?.jsonObject
                val name = fn?.get("name")?.jsonPrimitive?.contentOrNullSafe() ?: "shell"
                val argsRaw = fn?.get("arguments")?.jsonPrimitive?.contentOrNullSafe() ?: "{}"
                val args = runCatching { DshJson.parseToJsonElement(argsRaw).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                // shell 用 command 字段；phone 等工具用完整参数 JSON 作为展示与去重键（含坐标，避免同动作不同坐标被误合并）。
                val command = if (name == "shell") {
                    args["command"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                } else {
                    args.toString()
                }
                // 子代理的标题要带 name（「子代理 · 查日志」）；其余工具仍是命令前 60 字。
                val titleText = if (name == "subagent") "子代理 · " + subagentLabel(args) else command.take(60)
                val signature = ToolSignature.of(name, command)
                val requestJson = describeRequest(body, round, name, command)

                // A：死循环 → 本次不执行，结束本轮；同轮重复 → 复用上次结果，不再执行。
                val verdict = loopGuard.check(signature)
                if (verdict is ToolVerdict.Loop) {
                    _events.tryEmit(
                        DshEvent.ToolCallEvent(
                            sessionId,
                            ToolCall(
                                id,
                                title = titleText,
                                kind = name,
                                status = ToolStatus.Failed,
                                detail = verdict.detail,
                                args = command,
                                requestJson = requestJson,
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    emitTurnEndNotice(reason = "tool_loop", loopDetail = verdict.detail)
                    _events.tryEmit(DshEvent.TurnEnd(sessionId, "tool_loop", authorId, turnId))
                    return
                }
                val output: String = if (verdict is ToolVerdict.Reuse) {
                    _events.tryEmit(
                        DshEvent.ToolCallEvent(
                            sessionId,
                            ToolCall(
                                id,
                                title = titleText,
                                kind = name,
                                status = ToolStatus.Completed,
                                detail = verdict.detail + "\n" + verdict.cachedOutput.take(1_500),
                                args = command,
                                requestJson = requestJson,
                                responseJson = verdict.cachedOutput.truncateForTool(),
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    verdict.cachedOutput
                } else {
                    _events.tryEmit(
                        DshEvent.ToolCallEvent(
                            sessionId,
                            ToolCall(
                                id,
                                title = titleText,
                                kind = name,
                                status = ToolStatus.InProgress,
                                args = command,
                                requestJson = requestJson,
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    // 工具执行本身也必须兜住：子代理侧（引擎未就绪 / 子代理请求失败 / 超时）一抛异常，
                    // 以前会直接逃出整个 runLoop —— 不收 InProgress 卡、不发 TurnEnd，
                    // 仓库那边只能走 failTurn，于是 DB 里只有 USER 行、界面上没有助手气泡。
                    val result = runCatching {
                        when {
                        name == "shell" && command.isBlank() -> ToolExecResult(ok = false, output = "", error = "参数缺失")
                        // G：不可逆 shell 命令先问用户；没确认就当被拒，错误照常回灌模型。phone 参数非 shell 命令，不确认。
                        name == "shell" && needsConfirmation(command) && confirm(task, sessionId, command) != "allow" ->
                            ToolExecResult(ok = false, output = "", error = "用户未确认，已跳过该命令")
                        // 子代理不能走 toolRunner：那里是 30 秒单次超时 + 1 次重试，
                        // 而一个子代理要跑满 8 轮工具往返、总预算 180 秒。
                        name == "subagent" -> runSubagent(cfg, parentSystem, args) { step ->
                            _events.tryEmit(
                                DshEvent.ToolCallEvent(
                                    sessionId,
                                    ToolCall(
                                        id,
                                        title = titleText,
                                        kind = name,
                                        status = ToolStatus.InProgress,
                                        detail = step.take(1_500),
                                        args = command,
                                        requestJson = requestJson,
                                    ),
                                    authorId,
                                    authorName,
                                    turnId,
                                ),
                            )
                        }
                        else -> toolRunner.run(name, args)
                        }
                    }.getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        currentCoroutineContext().ensureActive()
                        AppLog.w("tool " + name + " failed: " + e.message)
                        ToolExecResult(
                            ok = false,
                            output = "",
                            error = "工具执行失败：" + friendlyRequestError(e, cfg.baseUrl),
                        )
                    }
                    loopGuard.recordResult(signature, result.output)
                    toolRunsThisTurn++
                    // 失败摘要留给收尾用：本轮若最终没有正文，turnEndNotice 会把它写成一条说明。
                    // 这里不发 MessageChunk —— 它追加到同一个气泡，模型随后还要接着写正文，
                    // 中途插一行会把回答劈成两半（失败本身已在工具卡 detail 里可见）。
                    if (!result.ok) lastToolError = toolFailureTrace(command, result.error)
                    // B：失败不炸整轮 —— 错误进 ToolCall.detail，同时作为 tool 结果回灌模型。
                    val body = if (result.ok) {
                        result.output
                    } else {
                        "[执行失败] " + (result.error ?: "未知错误") + "\n" + result.output
                    }
                    _events.tryEmit(
                        DshEvent.ToolCallEvent(
                            sessionId,
                            ToolCall(
                                id,
                                title = titleText,
                                kind = name,
                                status = if (result.ok) ToolStatus.Completed else ToolStatus.Failed,
                                detail = body.take(2_000),
                                args = command,
                                requestJson = requestJson,
                                // 失败时 output 为空，响应框会显示成「—」；把错误信息放进去，
                                // 展开工具卡就能看到通道为什么没跑起来。
                                responseJson = (result.output.ifBlank { result.error.orEmpty() }).truncateForTool(),
                                elapsedMs = result.elapsedMs,
                                attempts = result.attempts,
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    if (result.output.isNotBlank()) result.output else (result.error ?: "")
                }
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", output.truncateForTool())
                }
            }
        }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            currentCoroutineContext().ensureActive()
            AppLog.w("turn crashed: " + e.message)
            _events.tryEmit(
                DshEvent.MessageChunk(
                    sessionId,
                    "（本轮异常结束：" + friendlyRequestError(e, cfg.baseUrl) + "）",
                    authorId,
                    authorName,
                    turnId,
                ),
            )
            _events.tryEmit(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
            return
        }

        // 兜底：最后一轮本该无工具、正常 return；万一上游还硬塞 tool_calls，
        // 也不能让这一轮悬着不结算。
        emitTurnEndNotice(reason = "stop", roundsExhausted = true)
        _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", authorId, turnId))
    }

    /**
     * 群组：拆任务 → 进执行池串行跑 → 全部结束后再发一个总 TurnEnd。
     *
     * 每个成员拿到的是「自己线程 + 公共历史 + 他人已完成产出」，跑完写回自己线程，
     * 所以第二个人不会重复第一个人干过的活（需求 D）。
     */
    private suspend fun runGroup(
        sessionId: String,
        plan: GroupPlan,
        history: List<ChatMessage>,
        userText: String,
        turnId: String? = null,
    ) {
        // 每次开跑都把当前设置灌进执行池与成员开关（设置页改完立刻生效，不用重连）。
        pool.maxParallelSetting = plan.maxParallel
        groupToolsEnabled = plan.allowTools
        threads.beginTurn(sessionId)
        // 公共历史：谁说了什么都按时间记一份，成员上下文从这里取。
        threads.appendPublic(sessionId, "user", userText, "用户")
        groupTurns[sessionId] = GroupTurn(plan, history, userText, turnId)
        // 先分诊：办公室分工 / 并行 / 接力 / 自动机（一棒做、一棒验收，打回重做）。
        val mode = detectGroupMode(userText, plan.members.size, plan.mode)
        // 点名优先：用户 @（或直呼）了谁，就只让这几位出场。
        // 不这样做的话，办公室/并行档会按全员分工，@ 一个人也会把整组拉起来，
        // 群里看起来就是「@ 了没用、分不清哪段是谁的任务」。
        val namedMembers = parseMentions(userText, plan.members).map { it.first }
            .ifEmpty { namedMentions(userText, plan.members) }
            .distinctBy { it.id }
        if (mode != GroupMode.Automaton && namedMembers.isNotEmpty() &&
            namedMembers.size < plan.members.size
        ) {
            val focused = plan.copy(members = namedMembers, mode = "chain")
            val tasks = planGroupTasks(
                sessionId = sessionId,
                plan = focused,
                userText = userText,
                history = history,
                lastSpeakerId = null,
                mode = GroupMode.Chain,
            )
            dispatchGroupTasks(sessionId, tasks)
            pool.await(sessionId)
            groupTurns.remove(sessionId)
            _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        if (mode == GroupMode.Automaton) {
            runAutomaton(sessionId, plan, userText)
            groupTurns.remove(sessionId)
            _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        if (mode == GroupMode.Office) {
            runOffice(sessionId, plan, history, userText)
            groupTurns.remove(sessionId)
            _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        val tasks = planGroupTasks(
            sessionId = sessionId,
            plan = plan,
            userText = userText,
            history = history,
            lastSpeakerId = synchronized(lastSpeaker) { lastSpeaker[sessionId] },
            mode = mode,
        )
        if (tasks.isEmpty()) {
            groupTurns.remove(sessionId)
            _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        // E：经执行池派活 —— 有界并行、依赖唤醒、状态同步到 agents flow。
        dispatchGroupTasks(sessionId, tasks)
        // 等本 session 的活全部结束（完成/失败/取消都会唤醒），不靠轮询 sleep。
        pool.await(sessionId)
        groupTurns.remove(sessionId)
        _events.tryEmit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
    }

    /**
     * 自动机：一棒干活、一棒验收，验收不过打回重做，直到验收者说【完成】或撞上轮次上限。
     *
     * 为什么不用 dependsOn 表达：dependsOn 是 DAG，跑一遍就结束；
     * 「做一步 → 验收 → 打回 → 重做 → 再下一步」是**带环**的，只能由调度方按轮次驱动。
     * 每一棒仍走执行池（[ExecutionPool.submitOne] + [ExecutionPool.await]），
     * 所以忙碌态、取消、权限拦截、token 账全部照旧生效，不是另开一条旁路。
     *
     * 默认 worker = 第一个 @ 的人（否则第一个成员），reviewer = 第二个 @ 的人（否则另一个成员）。
     * 用户那句「CN 算 x+1 直到 x=10，GJ 在 CN 算完后把 x 回写成 CN 算出的数」就是这种形态。
     */
    private suspend fun runAutomaton(sessionId: String, plan: GroupPlan, userText: String) {
        val members = plan.members
        val mentioned = parseMentions(userText, members).map { it.first }
        val worker = mentioned.getOrNull(0) ?: members.firstOrNull() ?: return
        val reviewer = mentioned.getOrNull(1)
            ?: members.firstOrNull { it.id != worker.id }
            ?: return
        // 群共享上下文的总预算来自设置：预算越小，共享摘要越短（省 token）。
        val sharedLimit = (plan.contextBudget / 120).coerceIn(120, 600)
        val shared = digest(sharedText(userText, members), sharedLimit).ifBlank { digest(userText, 200) }
        val rounds = automatonRounds(userText)
        var round = 0
        var rework = 0
        var progress = "还没开始，起始值按用户规则来。"
        while (round < rounds) {
            round++
            val stepId = pool.submitOne(
                sessionId,
                autoTask(
                    sessionId = sessionId,
                    member = worker,
                    prompt = "自动机第 " + round + " 轮，轮到你执行。\n" +
                        "用户给的规则：" + shared + "\n" +
                        "当前进度：" + progress,
                    hint = "你是本轮执行者：只做这一轮该你做的那一步，不要跳步、" +
                        "不要替 " + reviewer.name + " 做验收、不要一次把后面几轮全算完。" +
                        "做完用一两句话说明这一步的结果，以及这个值现在是几。",
                ),
            )
            pool.await(sessionId)
            if (!taskSucceeded(stepId)) return
            val workerOut = taskOutputs[stepId].orEmpty()
            val checkId = pool.submitOne(
                sessionId,
                autoTask(
                    sessionId = sessionId,
                    member = reviewer,
                    prompt = "用户给的规则：" + shared + "\n" +
                        "上一位 " + worker.name + " 本轮的输出：\n" + digest(workerOut, 600),
                    hint = "你是本轮验收者：只核对和回写，不要重算一遍。" +
                        "核对无误就把值回写进结论；有错就指出错在哪。" +
                        "最后一行只写下面三者之一：【继续】还没到终点、【完成】整个任务已达成、【打回】<一句原因> 这一轮不合格要重做。",
                ),
            )
            pool.await(sessionId)
            if (!taskSucceeded(checkId)) return
            val reviewOut = taskOutputs[checkId].orEmpty()
            progress = digest(reviewOut, 300)
            when (automatonVerdict(reviewOut)) {
                Verdict.Rework -> {
                    if (rework >= MAX_AUTOMATON_REWORK) {
                        AppLog.w("自动机：" + reviewer.name + " 连续打回 " + rework + " 次，提前收工")
                        return
                    }
                    rework++
                    round-- // 这一轮不算数，重做同一轮
                }
                Verdict.Done -> return
                Verdict.More -> Unit
            }
        }
        AppLog.w("自动机：撞到轮次上限 " + rounds + "，收工")
    }

/**
     * 办公室模式（office）：经理拆活 → 各自认领 → 同时干 → 经理汇总。
     *
     * 为什么要有它：`Parallel` 是把同一句话广播出去，每个人自己猜「我该做哪部分」，
     * 结果四个人交了四份完整答案；`Chain` 是排队，第二个人开始的时候第一个人早跑完了。
     * 真正的分工必须先有**一份计划**：谁做什么写清楚，再各自只做自己那条。
     *
     * 经理 = 第一个成员（专业名 / 第一个被 @ 的人优先），它不参与干活，只负责拆和收口。
     * 拆出来的条目多于可干活的人数时，超出的并到同一个人身上（池里同 agent 串行，不会互相踩）。
     */
    private suspend fun runOffice(
        sessionId: String,
        plan: GroupPlan,
        history: List<ChatMessage>,
        userText: String,
    ) {
        val members = plan.members
        if (members.size < 2) return
        val manager = members.first()
        val workers = members.drop(1)
        val shared = digest(sharedText(userText, members), 400).ifBlank { digest(userText, 400) }
        val rosterLine = members.joinToString("、") { "${it.name}（${it.cfg.model}）" }

        // 1) 经理拆活。这一棒也走执行池：取消/权限/token 账照旧生效。
        // 拆活只需要输出一张几行的分工表，推理模型不封顶会先写掉几千字思维链才动笔
        // （实测 4 分钟才出表），这里给它一个单独的紧上限。
        val planner = manager.copy(cfg = manager.cfg.copy(maxTokens = PLANNER_MAX_TOKENS))
        val planTaskId = pool.submitOne(
            sessionId,
            autoTask(
                sessionId = sessionId,
                member = planner,
                prompt = "用户请求：" + shared +
                    "\n\n群成员：" + rosterLine +
                    "\n\n请把这件事拆成最多 " + workers.size + " 条互不重叠的子任务，" +
                    "一条交给一个成员。",
                hint = "你是这个群的组织者。只输出分工表，一条一行，格式严格是：\n" +
                    "成员名 :: 一句话任务\n" +
                    "不要写解释、不要写编号、不要自己动手做任何一条。" +
                    "每条任务必须能独立完成，彼此不要重叠、不要互相等待。",
            ),
        )
        pool.await(sessionId)
        if (!taskSucceeded(planTaskId)) return
        val planText = taskOutputs[planTaskId].orEmpty()
        val split = parseOfficeSplit(planText, workers)

        // 2) 分派。经理只点了几个人时，把剩下的成员按原请求补上，
        // 不让「分工表写漏」变成「一半人整轮摸鱼」；整张表都解析失败就全员并行。
        val generic = { w: GroupMember ->
            w to ("从你自己的专业角度处理这个请求，只给结论与关键细节：" + shared)
        }
        val assigned = split.map { it.first.id }.toSet()
        val assignments: List<Pair<GroupMember, String>> = split +
            workers.filter { it.id !in assigned }.map(generic)
        val taskIds = assignments.map { (member, task) ->
            // 成员产出会被 digest 压到 500 字再交给经理，写更长也是被扔掉；
            // 给个上限，别让推理模型在这里把并行优势耗光。
            val worker = member.copy(
                cfg = member.cfg.copy(
                    maxTokens = if (member.cfg.maxTokens > 0) minOf(member.cfg.maxTokens, WORKER_MAX_TOKENS) else WORKER_MAX_TOKENS,
                ),
            )
            val id = pool.submitOne(
                sessionId,
                autoTask(
                    sessionId = sessionId,
                    member = worker,
                    prompt = "办公室任务分配 —— 整件事是：" + shared + "\n你这一条：" + task,
                    hint = "你是 " + member.name + "，负责其中一条子任务：" + task +
                        "。只做这一条，做完就收；不要替别人做，也不要重复别人会做的部分。" +
                        "输出你这条的结果即可，不要写「我的任务是」这类开场白。",
                ),
            )
            id to member
        }
        pool.await(sessionId)

        // 3) 汇总。成员产出按顺序拼给经理，由它收口成一条给用户看的答复。
        val done = taskIds.mapNotNull { (id, member) ->
            val out = taskOutputs[id].orEmpty().trim()
            if (out.isEmpty() || isNoAdd(out)) null else member.name to out
        }
        if (done.isEmpty()) return
        val digestText = done.joinToString("\n\n") { (name, out) ->
            "【" + name + "】\n" + digest(out, 500)
        }
        // 收口同样只写结论，不写推理散文。
        val summarizer = manager.copy(cfg = manager.cfg.copy(maxTokens = SUMMARIZER_MAX_TOKENS))
        val summaryId = pool.submitOne(
            sessionId,
            autoTask(
                sessionId = sessionId,
                member = summarizer,
                prompt = "用户请求：" + shared + "\n\n各成员已完成的部分：\n" + digestText,
                hint = "你是汇总者。把上面的结果合成一条直接给用户的最终答复：" +
                    "不要罗列谁做了什么、不要重复同一件事，按用户要看的东西组织；" +
                    "确实有成员没给出有效内容就跳过它。",
            ),
        )
        pool.await(sessionId)
        if (!taskSucceeded(summaryId)) return
        synchronized(lastSpeaker) { lastSpeaker[sessionId] = manager.id }
    }

    /**
     * 解析经理的分工表。认 `名字 :: 任务` / `名字：任务` / `名字 | 任务` 三种写法；
     * 名字允许带 @ 和括号里的模型名。同一成员被分了多条就合并成一条。
     */
    internal fun parseOfficeSplit(
        output: String,
        workers: List<GroupMember>,
    ): List<Pair<GroupMember, String>> {
        if (output.isBlank() || workers.isEmpty()) return emptyList()
        val acc = LinkedHashMap<String, StringBuilder>()
        val byId = LinkedHashMap<String, GroupMember>()
        for (raw in output.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            while (line.isNotEmpty() && (line[0] == '-' || line[0] == '*' || line[0] == '•' || line[0] == '·')) {
                line = line.substring(1).trim()
            }
            line = line.trimStart('1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', '、', ')', '）', ' ')
            if (line.isEmpty()) continue
            val sep = line.indexOfFirst { it == ':' || it == '：' || it == '|' || it == '｜' }
            if (sep <= 0) continue
            var who = line.substring(0, sep).trim().trim('@', '[', ']', '【', '】', '*', '"', '「', '」')
            val task = line.substring(sep + 1).trim()
            if (task.length < 2) continue
            // 「GJ（deepseek-chat）」这种带模型名的剥掉括号。
            who = who.substringBefore('(').substringBefore('（').trim()
            if (who.isEmpty()) continue
            // 单字/两字以下的名字不做包含匹配：经理常输出「A: 任务」这种编号，
            // "A" 会被 "OpenAI 兼容 #2" 包含进去，结果整张分工表只派出一个人。
            val member = workers.firstOrNull { it.name.equals(who, ignoreCase = true) }
                ?: runCatching { namedMentions(who, workers).firstOrNull() }.getOrNull()
                ?: workers.takeIf { who.length >= 3 }
                    ?.firstOrNull { it.name.contains(who, ignoreCase = true) || who.contains(it.name, ignoreCase = true) }
                ?: continue
            val sb = acc.getOrPut(member.id) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append("；")
            sb.append(task)
            byId[member.id] = member
        }
        return acc.mapNotNull { (id, sb) -> byId[id]?.let { it to sb.toString() } }
    }


    /** 自动机的一棒：和普通群聊任务同一套字段，只是多了角色提示 [AgentTask.hint]。 */
    private fun autoTask(sessionId: String, member: GroupMember, prompt: String, hint: String) = AgentTask(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        agentId = member.id,
        agentName = member.name,
        prompt = prompt,
        hint = hint,
    )

    /** 「（无补充）」这类收尾占位：真实产出为空，不该被当成成员成果拼进汇总。 */
    private fun isNoAdd(text: String): Boolean {
        val t = text.trim().trim('（', '）', '(', ')', '。', '.', ' ')
        return t.isEmpty() || t == "无补充" || t.equals("no add", ignoreCase = true)
    }

    /** 这一棒跑成了没有：被取消/失败（用户打断、上游报错）就别再往下一棒走。 */
    private fun taskSucceeded(taskId: String): Boolean =
        pool.tasks.value.firstOrNull { it.id == taskId }?.state == TaskState.Done

    /**
     * 把计划好的任务交给执行池。
     *
     * 同一个 agent 上一轮还在跑时不再开第二条并行的活 —— 那会让它一边说一边说、自相矛盾；
     * 改成一条排队任务，prompt 前标「（补充）」，由池按「同 agent 串行」的规则接着跑。
     */
    private fun dispatchGroupTasks(sessionId: String, tasks: List<AgentTask>) {
        val agents = pool.agents.value
        for (task in tasks) {
            val state = agents[task.agentId]?.state
            val busy = state == AgentRunState.Thinking || state == AgentRunState.ToolRunning
            pool.submitOne(sessionId, if (busy) task.copy(prompt = "（补充）" + task.prompt) else task)
        }
    }

    /** 执行池的 runner：把一条 [AgentTask] 跑成这个成员的发言。 */
    private suspend fun runMemberTask(task: AgentTask): String {
        val turn = groupTurns[task.sessionId] ?: return ""
        val member = turn.plan.members.firstOrNull { it.id == task.agentId } ?: return ""
        val roster = turn.plan.members.map { it.name }
        val extra = buildString {
            append("你正在一个多 Agent 群组里协作，群成员：")
            append(turn.plan.members.joinToString("、") { "${it.name}（${it.cfg.model}）" })
            append("。你是 ")
            append(member.name)
            if (task.hint.isNotBlank()) {
                // 编排层已经说清这一棒的角色（执行 / 验收 / 并行分工），按它来。
                append("。")
                append(task.hint)
            } else if (roster.size == 1) {
                append("。这一轮由你回答，其他成员保持安静，不要替他们发言。")
            } else if (task.dependsOn.isEmpty()) {
                // 链首：这轮由它先答，必须答完整，不能一句「无补充」把话头丢掉。
                append("。你是本轮第一个发言人：把用户的问题完整回答清楚，不要只丢一句「无补充」。")
            } else {
                // 链上后续成员：群聊是**轮流**发言，不是各自写一份答案，前面的人说过的已在公共历史。
                append("。群聊按顺序轮流发言：你前面成员说过的话以摘要列在公共历史里。")
                append("先读一遍他们说了什么，只补充还没被说到的部分；")
                append("别人已经说清楚的内容不要再重复一遍，也不要另起一个话题。")
                append("如果你确实没有新内容可说，就只输出「（无补充）」四个字。")
            }
            append("只输出你自己要说的话：不要写「[名字]」这样的前缀，不要替其他成员发言。")
        }
        val images = turn.history.lastOrNull { it.role == ChatMessage.Role.USER }?.attachments.orEmpty()
        // 本轮用户消息按「这个任务自己的 prompt」装进上下文（图片照旧带上），
        // 不再由 runLoop 兜底追加 —— 那条兜底会和这里重复一遍。
        val userTurn = listOf(
            buildJsonObject {
                put("role", "user")
                put("content", buildUserContent(task.prompt, images))
            },
        )
        // 群状态行每轮现算：谁在跑、谁空着。成员据此知道自己要不要等别人。
        val stateLine = groupStateLine(pool.agents.value, turn.plan.members, member.id)
        val asm = threads.buildContextReport(
            sessionId = task.sessionId,
            agentId = member.thread,
            systemPrompt = member.cfg.systemPrompt + "\n\n" + extra,
            groupState = stateLine,
            userTurn = userTurn,
            budgetChars = turn.plan.contextBudget,
        )
        // token 账：本次实际发出去的字符数 vs 按全量历史发会是多少。
        pool.reportContext(asm.usedChars, asm.fullChars)
        var finalText = ""
        runLoop(
            sessionId = task.sessionId,
            cfg = member.cfg,
            history = turn.history,
            userText = task.prompt,
            userImages = images,
            authorId = member.id,
            authorName = member.name,
            // 群聊里默认成员只负责说话；用户打开「允许群组成员使用工具」时才各自跑命令。
            useTools = groupToolsEnabled,
            roster = roster,
            presetMessages = asm.messages,
            task = task,
            onFinal = { finalText = it },
            turnId = turn.turnId,
        )
        val noAdd = finalText.trim().let { it == "（无补充）" || it == "(无补充)" || it.isEmpty() }
        recordTaskOutput(task.id, finalText)
        threads.appendThread(task.sessionId, member.thread, "assistant", finalText)
        if (noAdd) {
            // 明确表示没补充：不往公共历史塞噪音，只记一条「已看过」。
            threads.markFinished(task.sessionId, member.thread, member.name, "")
            synchronized(lastSpeaker) { lastSpeaker[task.sessionId] = member.id }
            return finalText
        }
        // 省 token 的核心：公共历史只写 200 字摘要（'名字: 摘要'），全文只进它自己的线程。
        // 以前全文写公共历史，N 个成员下一轮各自重读 N 份全文，上下文直接爆。
        threads.appendPublic(task.sessionId, "assistant", digest(finalText, 200), member.name)
        threads.markFinished(task.sessionId, member.thread, member.name, finalText)
        synchronized(lastSpeaker) { lastSpeaker[task.sessionId] = member.id }
        return finalText
    }

    /** 记下这一棒的最终发言（自动机下一棒要用）；量大了整体清空，避免长会话里无界增长。 */
    private fun recordTaskOutput(taskId: String, text: String) {
        if (taskOutputs.size > 200) taskOutputs.clear()
        taskOutputs[taskId] = text
    }

    /** 只取新增部分：新文本以旧文本开头就发剩余；整段重复则不发。 */
    private fun incrementalDelta(previous: String, current: String): String = when {
        previous.isEmpty() -> current
        current == previous -> ""
        current.startsWith(previous) -> current.substring(previous.length)
        previous.startsWith(current) -> ""
        else -> current
    }

    /**
     * CoT 展开面板要的是**真正发出去的请求**，不是摘要：模型名、轮次、
     * 这个工具调用的参数字段，外加整包 body 原文（超长截断）。
     *
     * 摘要只能让人知道"发过一次请求"，原文才能判断上游到底收到了什么。
     */
    private fun describeRequest(body: JsonObject, round: Int, toolName: String, command: String): String {
        val raw = body.toString()
        val capped = if (raw.length <= JSON_VIEW_LIMIT) {
            raw
        } else {
            raw.take(JSON_VIEW_LIMIT) + "…（已截断，原文 ${raw.length} 字符）"
        }
        return buildJsonObject {
            put("round", round + 1)
            put("tool", toolName)
            put("args", command.take(2_000))
            put("body", capped)
        }.toString()
    }

    /** 不可逆命令先问用户（rm -rf / reboot / pm uninstall…）。 */
    private fun needsConfirmation(command: String): Boolean {
        val c = command.lowercase()
        return CONFIRM_PATTERNS.any { c.contains(it) }
    }

    /**
     * 等用户对一条不可逆命令表态。
     *
     * 单聊没有执行池任务，用 [directPermissions] 挂一个 gate；群聊的任务卡交给执行池，
     * 这样「谁问的谁才能点」的语义只有一份实现。
     */
    private suspend fun confirm(task: AgentTask?, sessionId: String, command: String): String {
        val auto = runCatching { autoApprove() }.getOrDefault(false)
        if (auto) return "allow"
        val request = PermissionRequest(
            sessionId = sessionId,
            requestId = UUID.randomUUID().toString(),
            title = "确认执行：" + command.take(100),
            options = listOf(
                PermissionOption("allow", "允许", "allow"),
                PermissionOption("deny", "拒绝", "deny"),
            ),
        )
        if (task != null) return pool.requestPermission(task, request) ?: "deny"
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        directPermissions[request.requestId] = gate
        directOwners[request.requestId] = sessionId
        return try {
            _events.emit(DshEvent.Permission(sessionId, request))
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { gate.await() } ?: "deny"
        } finally {
            directPermissions.remove(request.requestId)
            directOwners.remove(request.requestId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                _events.emit(DshEvent.PermissionResolved(sessionId, request.requestId))
            }
        }
    }

    /** 工具表与执行器保持一致，原生文件和后台命令不借 shell 转义。 */
    internal fun toolsJson(): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "shell")
                    put("description", toolHelp())
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "要执行的命令，例如 ls /sdcard、dumpsys battery、pm list packages")
                            }
                            putJsonObject("channel") {
                                put("type", "string")
                                put("description", "默认 proot；读 /sdcard 用 app 通道")
                                putJsonArray("enum") { add("proot"); add("app"); add("shizuku"); add("root") }
                            }
                            putJsonObject("reason") {
                                put("type", "string")
                                put("description", "为什么要执行这条命令，一句话说明")
                            }
                        }
                        putJsonArray("required") { add("command") }
                    }
                }
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "phone")
                    put("description", phoneHelp())
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add("tap"); add("swipe"); add("type"); add("back"); add("home")
                                    add("recents"); add("readscreen"); add("openapp"); add("screenshot")
                                }
                                put("description", "动作：tap 点击 / swipe 滑动 / type 输入 / back 返回 / home 主页 / recents 最近任务 / readscreen 读屏 / openapp 开应用 / screenshot 截图")
                            }
                            putJsonObject("fx") { put("type", "number"); put("description", "起点横坐标（分数 0-1，相对屏幕宽，优先于 x）") }
                            putJsonObject("fy") { put("type", "number"); put("description", "起点纵坐标（分数 0-1，相对屏幕高，优先于 y）") }
                            putJsonObject("x") { put("type", "number"); put("description", "起点横坐标（绝对像素）") }
                            putJsonObject("y") { put("type", "number"); put("description", "起点纵坐标（绝对像素）") }
                            putJsonObject("fx2") { put("type", "number"); put("description", "终点横坐标（分数 0-1，swipe 用）") }
                            putJsonObject("fy2") { put("type", "number"); put("description", "终点纵坐标（分数 0-1，swipe 用）") }
                            putJsonObject("x2") { put("type", "number"); put("description", "终点横坐标（绝对像素，swipe 用）") }
                            putJsonObject("y2") { put("type", "number"); put("description", "终点纵坐标（绝对像素，swipe 用）") }
                            putJsonObject("text") { put("type", "string"); put("description", "要输入的文本（type 用）") }
                            putJsonObject("package") { put("type", "string"); put("description", "应用包名（openapp 用）") }
                            putJsonObject("query") { put("type", "string"); put("description", "按文本/描述查找节点并点击（tap 用，可选）") }
                            putJsonObject("durationMs") { put("type", "number"); put("description", "手势时长毫秒（tap 长按 / swipe 滑动时长）") }
                            putJsonObject("maxItems") { put("type", "number"); put("description", "读屏最多返回条目数（readscreen 用，默认 120）") }
                        }
                        putJsonArray("required") { add("action") }
                    }
                }
            },
        )
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_read")
                put("description", "读取原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("offset") { put("type", "integer") }
                        putJsonObject("limit") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_write")
                put("description", "写入原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                        putJsonObject("append") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path"); add("content") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_list")
                put("description", "列出原生目录")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("recursive") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_op")
                put("description", "操作原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("op") { put("type", "string"); putJsonArray("enum") { add("move"); add("copy"); add("delete"); add("mkdir"); add("stat") } }
                        putJsonObject("src") { put("type", "string") }
                        putJsonObject("dst") { put("type", "string") }
                    }
                    putJsonArray("required") { add("op"); add("src") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "channel_list")
                put("description", "列出 shell 通道与可用状态")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {

                    }
                    putJsonArray("required") {  }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "run_bg")
                put("description", "启动后台命令")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("command") { put("type", "string") }
                        putJsonObject("logfile") { put("type", "string") }
                    }
                    putJsonArray("required") { add("command") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "bg_status")
                put("description", "查询后台进程和日志")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pid") { put("type", "integer") }
                        putJsonObject("logfile") { put("type", "string") }
                    }
                    putJsonArray("required") { add("pid") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "bg_kill")
                put("description", "终止后台进程")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pid") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("pid") }
                }
            }
        })
        add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "subagent")
                put("description", subagentHelp())
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("prompt") {
                            put("type", "string")
                            put(
                                "description",
                                "要委派的完整任务：目标、要看的东西、期望的输出形态。子代理看不到当前对话，别写「上面那个文件」。",
                            )
                        }
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "短标签（2-6 字），只用于进度显示，例如「查日志」")
                        }
                        putJsonObject("model") {
                            put("type", "string")
                            put("description", "可选：换一个模型跑这个子任务；留空继承当前模型")
                        }
                    }
                    putJsonArray("required") { add("prompt") }
                }
            }
        })
        web.functionSpecs().forEach { add(it) }
    }

    /** phone 工具说明独立成段，避免污染 shell 的通道说明。 */
    private fun phoneHelp(): String = buildString {
        append("phone 工具：直接操控手机（无需 root/ADB，需先在 设置→无障碍 开启「DSH Mobile 手机控制」）。")
        append("动作 action：tap（点击，用 fx/fy 分数坐标最稳，或 x/y 像素；带 query 按文本点；带 durationMs 变长按）")
        append("、swipe（滑动，起点 fx/fy 或 x/y，终点 fx2/fy2 或 x2/y2，durationMs 控制快慢）")
        append("、type（往聚焦输入框写字，参数 text）、back/home/recents（系统键）")
        append("、readscreen（读当前屏幕文本，紧凑列表，maxItems 限条目）")
        append("、openapp（package 包名或 query 应用名模糊匹配）、screenshot（Android 11+ 存本地文件）。")
        append("坐标优先用 fx/fy（0-1 相对屏幕），模型看的是缩放截图，绝对像素容易点偏。")
        append("读屏输出每行 text|desc|cx,cy|clickable|class。")
    }

    /** subagent 工具说明：讲清「独立上下文 + 只回结论 + 看不到当前对话 + 不能嵌套」。 */
    private fun subagentHelp(): String = buildString {
        append("subagent 工具：把独立、可并行的子任务交给子代理。")
        append("子代理有自己的上下文和工具（shell / 文件 / phone），只把结论回给你，")
        append("不占用也不污染主对话历史——适合「同时查三件事」「先读完一大堆代码再给我结论」。")
        append("它看不到当前对话，prompt 必须自带全部背景，别写「上面那个文件」；它也不能再派子代理。")
        append("互不相关的子任务就同一轮里发多个 subagent：依次执行、各自回结论。")
    }

    /** 子代理显示名：参数给了 name 就用，否则「子代理」。 */
    internal fun subagentLabel(args: JsonObject): String =
        args["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() } ?: "子代理"

    /**
     * 子代理的工具表：从默认表里按白名单过滤。
     *
     * subagent 自己不在白名单里，所以嵌套深度天然只有 1 层；[allowed] 可注入，单测直接传集合。
     */
    internal fun subagentToolsJson(allowed: Set<String> = SUBAGENT_TOOLS): JsonArray {
        val nameOf: (JsonElement) -> String? = { element ->
            ((element as? JsonObject)?.get("function") as? JsonObject)
                ?.get("name")?.jsonPrimitive?.contentOrNullSafe()
        }
        return JsonArray(toolsJson().filter { nameOf(it)?.let { name -> name in allowed } == true })
    }

    /**
     * subagent 工具：把一个只有自己上下文的子代理跑完，只把结论带回父代理。
     *
     * 三条硬约束：子代理看不到主对话（prompt 必须自带背景）、不能再派子代理（白名单里没有
     * subagent）、失败也回可读文本（父代理绝不会收到空串）。
     */
    private suspend fun runSubagent(
        cfg: LlmConfig,
        parentSystem: String,
        args: JsonObject,
        onStep: (String) -> Unit,
    ): ToolExecResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return ToolExecResult(
                ok = false,
                output = "",
                error = "prompt 参数缺失：写清要委派什么，子代理看不到当前对话",
            )
        }
        val label = subagentLabel(args)
        val model = args["model"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.model
        val childCfg = if (model == cfg.model) cfg else cfg.copy(model = model)
        val started = System.currentTimeMillis()
        // 复用同一条流式请求路径（含网关自举与引擎路由），子代理才会和主代理看到同样的上游行为。
        val outcome = SubagentLoop(
            model = model,
            parentSystemPrompt = parentSystem,
            chat = { body -> post(childCfg, body) },
            runTool = { name, a -> toolRunner.run(name, a) },
            tools = subagentToolsJson(),
            label = label,
            onStep = onStep,
        ).run(prompt)
        return ToolExecResult(
            ok = outcome.ok,
            output = if (outcome.ok) outcome.text else "",
            error = if (outcome.ok) null else outcome.text,
            elapsedMs = System.currentTimeMillis() - started,
            attempts = 1,
        )
    }

    /**
     * 一次 chat 往返，永远走 SSE。
     *
     * 流式不是为了省字：好几个上游（部分海外节点）会直接拒绝非流式请求
     * （"Non-stream chat request is currently not supported"）。统一要 SSE 再自己拼回整包，
     * 一条代码路径就能同时喂饱这些上游和思维链增量。
     */
    private suspend fun postOnce(
        cfg: LlmConfig,
        body: JsonObject,
        onReasoning: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): JsonObject {
        // 引擎路由：engine://<provider-id> 由 :node 引擎里插件注册的 provider 出答案，不碰网络。
        if (isEngineRoute(cfg.baseUrl)) return postToEngine(cfg, body, onText)
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val streamed = JsonObject(body.toMutableMap().apply { put("stream", JsonPrimitive(true)) })
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + cfg.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(streamed.toString().toRequestBody(JSON))
            .build()
        return http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP " + r.code + ": " + r.body?.string().orEmpty().take(300))
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = mutableMapOf<Int, JsonObject>()
            var usage: JsonObject? = null
            val plain = StringBuilder()
            // SSE 正常收尾必带 [DONE]；EOF 直出（连接被中途掐断）则保持 false。
            var sawDone = false
            // 上游的收尾原因（length / content_filter）：空回复的原因判定全靠它。
            var finishReason: String? = null
            r.body?.source()?.use { src ->
                while (true) {
                    // 阻塞读不认协程取消：用户点停止后必须自己出圈，否则会继续把上一轮的
                    // 内容推回 UI，看起来像「已经停了还在输出」。
                    if (!currentCoroutineContext().isActive) break
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) {
                        // 上游无视 stream:true、直接回了一整包 JSON：留下来整体解析。
                        plain.append(line)
                        continue
                    }
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") { sawDone = true; break }
                    val frame = runCatching { DshJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    frame["usage"]?.let { u -> if (u is JsonObject) usage = u }
                    val choice = (frame["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    // finish_reason 只在最后一帧出现：正文被长度截断时它是唯一的证据。
                    (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }
                        ?.let { finishReason = it.content }
                    val delta = choice?.get("delta") as? JsonObject
                    val message = choice?.get("message") as? JsonObject
                    delta?.get("content")?.let {
                        if (it is JsonPrimitive && it.isString) {
                            content.append(it.content)
                            onText(it.content)
                        }
                    }
                    // 思维链字段各家用得不一样，统一交给 reasoningOf 认。
                    (reasoningOf(delta) ?: reasoningOf(message) ?: reasoningOf(frame))
                        ?.takeIf { it.isNotEmpty() }?.let {
                        reasoning.append(it)
                        onReasoning(it)
                    }
                    (delta?.get("tool_calls") as? JsonArray)?.forEach { element ->
                        val call = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                        val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val merged = toolCalls[index] ?: JsonObject(emptyMap())
                        val id = call["id"]?.jsonPrimitive?.contentOrNullSafe()
                            ?: merged["id"]?.jsonPrimitive?.contentOrNullSafe()
                        val name = call["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                            ?: merged["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        val argDelta = call["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                        val argsSoFar = merged["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                        toolCalls[index] = buildJsonObject {
                            id?.let { put("id", it) }
                            put("type", "function")
                            putJsonObject("function") {
                                name?.let { put("name", it) }
                                put("arguments", argsSoFar + argDelta)
                            }
                        }
                    }
                }
            }
            if (content.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty() && plain.isNotBlank()) {
                runCatching { DshJson.parseToJsonElement(plain.toString()).jsonObject }.getOrNull()?.let { return@use it }
            }
            // 兜底：上游有时把 DeepSeek 原生工具调用标记当正文流回来（DSML / <tool_call>）。
            // 本轮 tool_calls 为空时从正文里解析出来补进 tool_calls，并把标记文本从正文剔掉。
            val finalContent = if (toolCalls.isEmpty()) {
                val stripped = stripDsmlToolCalls(content.toString())
                stripped.second.forEachIndexed { index, call ->
                    toolCalls[index] = call.toToolCallJson()
                }
                stripped.first
            } else {
                content.toString()
            }
            buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", JsonPrimitive(0))
                        finishReason?.let { put("finish_reason", it) }
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", finalContent)
                            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
                            if (toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    toolCalls.toSortedMap().values.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
                usage?.let { put("usage", it) }
                // 断流标记：没收到 [DONE] 就 EOF，说明上游中途断了（非流式整包在上方已提前 return）。
                if (!sawDone) put("dsh_eof_without_done", true)
            }
        }
    }

    /** baseUrl 是否是引擎路由（engine:// 前缀，忽略首尾空白）。 */
    private fun isEngineRoute(baseUrl: String): Boolean =
        baseUrl.trim().startsWith(ENGINE_SCHEME)

    /**
     * 一次引擎往返：把 [body] 原样当 JSONObject 递进 :node，拿 value.content 当整段回复。
     *
     * 契约（引擎侧 /llm chat）：返回 {ok:true,value:{content,toolCalls?,usage?}}；content 是完整正文，
     * 不流式（引擎里没有 token 级回调），所以只 onText 一次，再由调用方做增量去重；
     * toolCalls 是插件 provider 转出来的 OpenAI 形状调用，原样塞进 choices[].message.tool_calls。
     */
    private suspend fun postToEngine(
        cfg: LlmConfig,
        body: JsonObject,
        onText: (String) -> Unit = {},
    ): JsonObject {
        val call = engineChat ?: throw IOException(ENGINE_NOT_READY)
        val payload = runCatching { JSONObject(body.toString()) }
            .getOrElse { throw IOException("引擎请求体无法构造：" + it.message) }
        payload.put("model", engineModelOf(cfg))
        val resp = call(payload)
        if (!resp.optBoolean("ok", true)) {
            throw IOException("引擎回复失败：" + resp.optString("error").ifBlank { "未知错误" })
        }
        val value = resp.optJSONObject("value") ?: JSONObject()
        val rawContent = value.optString("content")
        // 插件 provider 转出来的结构化工具调用（OpenAI 形状），直接还原成 choices[].message.tool_calls。
        val calls = mutableListOf<JsonObject>()
        value.optJSONArray("toolCalls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val one = arr.optJSONObject(i) ?: continue
                runCatching { DshJson.parseToJsonElement(one.toString()).jsonObject }.getOrNull()?.let { calls += it }
            }
        }
        // 插件没给结构化调用时，再从正文里捞 DSML / <tool_call> 标记（部分 provider 只会写正文），
        // 捞到的同样进 tool_calls，标记文本从正文剔掉，避免 markup 整屏涌进 UI。
        val content = if (calls.isEmpty()) {
            val stripped = stripDsmlToolCalls(rawContent)
            stripped.second.forEach { calls += it.toToolCallJson() }
            stripped.first
        } else {
            rawContent
        }
        if (content.isNotEmpty()) onText(content)
        // usage 各家字段不一致，原样透给调用方（那边本来就用 contentOrNullSafe 宽松取值）。
        val usage = value.optJSONObject("usage")
        return buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    put("index", JsonPrimitive(0))
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                    }
                }
            }
            put("usage", usageJsonOf(usage))
        }
    }


    /** engine:// 后面的那段就是插件声明的模型 id；留空则退回配置里的 model 名。 */
    private fun engineModelOf(cfg: LlmConfig): String =
        cfg.baseUrl.trim().removePrefix(ENGINE_SCHEME).trim().ifBlank { cfg.model }

    private fun usageJsonOf(usage: JSONObject?): JsonObject =
        if (usage == null || usage.length() == 0) {
            JsonObject(emptyMap())
        } else {
            runCatching { DshJson.parseToJsonElement(usage.toString()).jsonObject }
                .getOrNull() ?: JsonObject(emptyMap())
        }

    /**
     * 引擎里可用的 provider 模型 id 列表（/llm models）。
     *
     * 引擎没起来、或宿主没注入 engineRpc 时抛 IOException——探活是可选能力，
     * 不要让它拖住主对话。
     */
    suspend fun engineModels(): List<String> {
        val rpc = engineRpc ?: throw IOException(ENGINE_NOT_READY)
        val resp = rpc("/llm", "models", JSONObject())
        if (!resp.optBoolean("ok", true)) {
            throw IOException("引擎回复失败：" + resp.optString("error").ifBlank { "未知错误" })
        }
        val value = resp.opt("value")
        val out = mutableListOf<String>()
        fun collect(from: org.json.JSONArray?) {
            if (from == null) return
            for (i in 0 until from.length()) {
                val item = from.opt(i)
                when (item) {
                    is String -> if (item.isNotBlank()) out += item
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("model") }
                        if (id.isNotBlank() && id !in out) out += id
                    }
                }
            }
        }
        if (value is JSONObject) {
            collect(value.optJSONArray("models"))
            collect(value.optJSONArray("data"))
            value.optString("model").takeIf { it.isNotBlank() }?.let { if (it !in out) out += it }
        } else if (value is org.json.JSONArray) {
            collect(value)
        } else if (value is String && value.isNotBlank()) {
            out += value
        }
        return out
    }

    /**
     * 请求 + 网关自举 + 只在连接类错误上退避重试。
     *
     * 模型地址常填本机网关（插件提供的进程）：它可能没起来、被系统杀掉、或上一次还在绑端口。
     * 连不上时先把网关拉起来再试，别让用户看到「断断续续」。
     */
    private suspend fun post(
        cfg: LlmConfig,
        body: JsonObject,
        onReasoning: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): JsonObject {
        val host = runCatching { java.net.URI(cfg.baseUrl.trim()).host }.getOrNull()
        val loopback = LoopbackGateway.isLoopback(host)
        if (loopback) LoopbackGateway.ensure()
        var last: Throwable? = null
        var attempt = 0
        while (attempt < GATEWAY_ATTEMPTS) {
            try {
                return postOnce(cfg, body, onReasoning, onText)
            } catch (e: Throwable) {
                last = e
                if (!isConnectFailure(e)) throw e
                if (loopback) LoopbackGateway.ensure()
                if (attempt < GATEWAY_ATTEMPTS - 1) delay(GATEWAY_BACKOFF_MS)
                attempt++
            }
        }
        throw last ?: IOException("请求失败")
    }

    private fun isConnectFailure(e: Throwable): Boolean {
        if (e is java.net.ConnectException) return true
        if (e is java.net.SocketTimeoutException) return true
        val msg = e.message?.lowercase() ?: return false
        return CONNECT_HINTS.any { msg.contains(it) }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    /** 把连接类错误翻成人话：本机网关没起来时，用户根本不知道要去哪点一下。 */
    private fun friendlyRequestError(e: Throwable, baseUrl: String): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host ?: ""
        val isLocal = host == "127.0.0.1" || host == "localhost" || host == "::1"
        val refused = raw.contains("Failed to connect", true) ||
            raw.contains("Connection refused", true) ||
            raw.contains("ConnectException", true)
        if (isLocal && refused) {
            val port = uri?.port?.takeIf { it > 0 } ?: 80
            return "本机网关 127.0.0.1:" + port + " 没在运行（" + raw + "）。\n" +
                "宿主启动时会自动拉起它；仍失败就到「插件」页点一次启动，或把这个模型的地址改成上游直连。"
        }
        return raw
    }

    private companion object {
        const val MAX_ROUNDS = 64
        /** 引擎路由前缀：baseUrl="engine://<provider-model-id>"。 */
        const val ENGINE_SCHEME = "engine://"
        const val ENGINE_NOT_READY = "引擎未就绪：先在插件页打开一次，让 :node 进程起来"


        const val PERMISSION_TIMEOUT_MS = 180_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
        val CONFIRM_PATTERNS = listOf("rm -rf", "mkfs", "pm uninstall", "reboot", " su ", "su -", "fastboot")
    }
}

/** 子代理：最多 8 轮工具往返（主循环 64 轮，但子代理的结论必须能被父代理等到）。 */
internal const val SUBAGENT_MAX_ROUNDS = 8

/** 子代理总预算：180 秒。超时回可读错误，不会返回空串。 */
internal const val SUBAGENT_BUDGET_MS = 180_000L

/** 子代理工具白名单：不含 subagent，嵌套深度天然 1 层。 */
internal val SUBAGENT_TOOLS: Set<String> = setOf("shell", "fs_read", "fs_list", "fs_write", "fs_op", "phone")

/** 子代理角色说明，拼在会话系统提示词后面。 */
internal const val SUBAGENT_ROLE = "你是被派发的子代理，只完成交给你的子任务，直接给结论；你看不到主对话历史。"

/** 宽松取字符串：null / JsonNull / 非字符串一律当空串（上游字段各家不统一）。 */
private fun JsonElement?.strOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * 上游这一轮「什么都没给」的可判定原因；返回空串 = 就是纯空回复。
 *
 * 以前 [LocalEngineTransport] 拿到这种响应会直接 TurnEnd("stop")，界面一片空白，
 * 用户只能猜是不是网断了——所以能判定的原因这里全部显式拼出来。
 */
internal fun upstreamEmptyReason(resp: JsonObject, finishReason: String?): String {
    val error = resp["error"] as? JsonObject
    val errorText = error?.get("message").strOrEmpty()
        .ifBlank { error?.get("code").strOrEmpty() }
        .ifBlank { (resp["error"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    if (errorText.isNotBlank()) return "上游报错：" + errorText.take(160)
    val choices = resp["choices"] as? JsonArray
    if (choices == null || choices.isEmpty()) return "上游没有返回任何 choices"
    return when (finishReason) {
        "length" -> "上游 finish_reason=length（输出撞上长度上限被截断）"
        "content_filter" -> "上游 finish_reason=content_filter（内容被过滤）"
        else -> ""
    }
}

/** 一次委派的结果：[ok]=false 时 [text] 是可读错误，从不为空串。 */
internal data class SubagentOutcome(val ok: Boolean, val text: String, val steps: Int)

/**
 * 子代理循环：独立 messages、独立工具白名单、最多 [maxRounds] 轮、总预算 [budgetMs]。
 *
 * 为什么单独一个类：它不碰主对话的 messages、不发 TurnEnd、不弹权限卡，
 * 于是「假上游 + 假工具」就能在 JVM 单测里把整条委派路径跑通。
 */
internal class SubagentLoop(
    private val model: String,
    private val parentSystemPrompt: String,
    /** 一次上游往返（复用主代理的流式路径，含网关自举与引擎路由）。 */
    private val chat: suspend (JsonObject) -> JsonObject,
    /** 一次工具执行（复用主代理的 ToolExecutor）。 */
    private val runTool: suspend (String, JsonObject) -> ToolExecResult,
    /** 白名单工具表（不含 subagent）。 */
    private val tools: JsonArray,
    private val label: String = "子代理",
    private val maxRounds: Int = SUBAGENT_MAX_ROUNDS,
    private val budgetMs: Long = SUBAGENT_BUDGET_MS,
    /** 每走一步的进度回调：父代理把它翻成 ToolCallEvent 的 detail。 */
    private val onStep: (String) -> Unit = {},
) {
    suspend fun run(userPrompt: String): SubagentOutcome {
        val messages = mutableListOf<JsonObject>(
            buildJsonObject {
                put("role", "system")
                put("content", parentSystemPrompt.trim().ifBlank { "你是运行在用户 Android 手机上的助手。" } + "\n\n" + SUBAGENT_ROLE)
            },
            buildJsonObject {
                put("role", "user")
                put("content", userPrompt)
            },
        )
        var steps = 0
        var lastText = ""
        val finished = withTimeoutOrNull(budgetMs) {
            repeat(maxRounds) {
                val resp = chat(
                    buildJsonObject {
                        put("model", model)
                        put("messages", JsonArray(messages))
                        if (tools.isNotEmpty()) put("tools", tools)
                    },
                )
                val msg = (resp["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?.get("message") as? JsonObject
                val rawContent = msg?.get("content").strOrEmpty()
                val explicit = msg?.get("tool_calls")?.jsonArray
                val content: String
                val calls: JsonArray
                if (explicit == null || explicit.isEmpty()) {
                    // 与主循环同一套兜底：上游把原生工具标记当正文流回来。
                    val stripped = stripDsmlToolCalls(rawContent)
                    content = stripped.first
                    calls = JsonArray(stripped.second.map { it.toToolCallJson() })
                } else {
                    content = rawContent
                    calls = explicit
                }
                if (content.isNotBlank()) lastText = content
                if (calls.isEmpty()) return@withTimeoutOrNull true
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    put("tool_calls", calls)
                }
                for (tc in calls) {
                    val obj = tc.jsonObject
                    val id = obj["id"].strOrEmpty().ifBlank { UUID.randomUUID().toString() }
                    val fn = obj["function"] as? JsonObject
                    val name = fn?.get("name").strOrEmpty()
                    val argsRaw = fn?.get("arguments").strOrEmpty().ifBlank { "{}" }
                    val args = runCatching { DshJson.parseToJsonElement(argsRaw).jsonObject }
                        .getOrDefault(JsonObject(emptyMap()))
                    val brief = if (name == "shell") args["command"].strOrEmpty() else args.toString()
                    steps++
                    onStep(name + "：" + brief.take(120))
                    // 白名单二次校验：工具表里已经滤掉 subagent，模型硬写也执行不了（深度只有 1 层）。
                    val result = if (name in SUBAGENT_TOOLS) {
                        runTool(name, args)
                    } else {
                        ToolExecResult(ok = false, output = "", error = "子代理不能用工具：" + name)
                    }
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", resultReply(result).truncateForTool())
                    }
                }
            }
            return@withTimeoutOrNull false
        }
        return when {
            finished == null -> SubagentOutcome(
                ok = false,
                text = "[子代理超时] 「" + label + "」跑了 " + (budgetMs / 1000) + " 秒没给结论，已中止；把任务拆小再派一次。",
                steps = steps,
            )
            lastText.isBlank() -> SubagentOutcome(
                ok = false,
                text = "[子代理无输出] 「" + label + "」用完 " + maxRounds + " 轮工具往返也没写下结论。",
                steps = steps,
            )
            else -> SubagentOutcome(ok = true, text = lastText, steps = steps)
        }
    }

    /** 工具结果回灌：失败也把错误交回去，子代理才有机会换个做法。 */
    private fun resultReply(r: ToolExecResult): String =
        if (r.ok) r.output else "[执行失败] " + (r.error?.takeIf { it.isNotBlank() } ?: "未知错误") + "\n" + r.output
}
