package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.download.PendingImageQueue
import dev.dsh.mobile.core.platform.device.DeviceCapability
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.GroupLedgerSink
import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.model.ReasoningStyle
import dev.dsh.mobile.core.model.isDeepSeekFamily
import dev.dsh.mobile.core.model.resolveReasoningStyle
import dev.dsh.mobile.core.model.PermissionOption
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.model.ToolCall.ToolStatus
import dev.dsh.mobile.core.protocol.group.GROUP_SCHEDULER_LEGACY
import dev.dsh.mobile.core.protocol.group.GROUP_SCHEDULER_MODERATED
import dev.dsh.mobile.core.protocol.mcp.McpToolSource
import dev.dsh.mobile.core.protocol.tool.ToolCtx
import dev.dsh.mobile.core.protocol.tool.contentOrNullSafe
import dev.dsh.mobile.core.protocol.tool.ToolRegistry
import dev.dsh.mobile.core.protocol.web.WebToolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import dev.dsh.mobile.core.model.context.WarmLayerStore
import dev.dsh.mobile.core.protocol.context.Compaction
import dev.dsh.mobile.core.protocol.context.RecallHit
import dev.dsh.mobile.core.protocol.context.RecallPlanner
import dev.dsh.mobile.core.protocol.context.WarmAssembly
import dev.dsh.mobile.core.protocol.context.WarmMessage
import dev.dsh.mobile.core.protocol.context.WarmRefreshReport
import dev.dsh.mobile.core.protocol.group.GroupRunner
import dev.dsh.mobile.core.protocol.group.MemberTurn
import dev.dsh.mobile.core.protocol.llm.ModelStream
import dev.dsh.mobile.core.protocol.llm.SUBAGENT_TOOLS
import dev.dsh.mobile.core.protocol.llm.SubagentLoop
import dev.dsh.mobile.core.protocol.llm.strOrEmpty
import dev.dsh.mobile.core.protocol.llm.SUBAGENT_MAX_DEPTH
import dev.dsh.mobile.core.protocol.llm.SubagentBudget
import dev.dsh.mobile.core.protocol.llm.depthRefusal
import dev.dsh.mobile.core.protocol.llm.parseSubagentTasks
import dev.dsh.mobile.core.protocol.llm.reasoningParams
import dev.dsh.mobile.core.protocol.llm.subagentToolNames

/** 一个可接入的模型后端描述（协议层只认这些字段，不依赖数据层）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = DEFAULT_SYSTEM,
    /** 单轮 max_tokens；0 表示不限制（推理模型会写出几万字思维链，群组一轮能跑十分钟）。 */
    val maxTokens: Int = 0,
    /** 思考强度 off|low|medium|high；实际发不发、怎么发由 [reasoningStyle] 决定。 */
    val reasoningEffort: String = "high",
    /** 提供方 id（推断思考风格用）；空串 = 未知，按 baseUrl/模型名推。 */
    val providerId: String = "",
    /** 思考参数发送方式；[ReasoningStyle.auto] = 按 providerId/baseUrl/模型名推断。 */
    val reasoningStyle: ReasoningStyle = ReasoningStyle.auto,
    /** 采样温度，<0 表示不发送该参数（由上游默认）。 */
    val temperature: Float = -1f,
    /** top_p，<0 表示不发送该参数。 */
    val topP: Float = -1f,
    /** 模型是否支持 function calling；false 时不发 tools，避免 400。 */
    val supportsTools: Boolean = true,
    /**
     * 兜底模型链（默认空）：人设会话被拒答时，第 2 次重试用这里的下一个模型。
     * 留空就只用主模型重试 —— 上游只有一把 Key/一个模型时什么都不用配。
     */
    val fallbackModels: List<String> = emptyList(),
    /** 推理档位映射（M5）：每行 `档位=参数名:值`；空串 = 不映射，行为与旧版一致。 */
    val reasoningMap: String = "",
) {
    companion object {
        val DEFAULT_SYSTEM = """
            You are a DSH agent running on the user's Android phone.
            - Call the shell tool when you need to operate the phone (commands run on the device itself and return stdout).
            - Common capabilities: am/pm/settings/dumpsys, file read/write, curl, python (if Termux is installed).
            - Never invent command output; when a command fails, adjust based on stderr.
            - Reply in Chinese, concise and direct.
            - When the user must pick one of several options, write the body as usual and append a separate options block at the end, in this format:
              [[OPTIONS]]
              A: first option
              B: second option
              [[/OPTIONS]]
              The client renders it as tappable buttons; tapping one means the user picked that line.
        """.trimIndent()
    }
}

/** 群组里的一个 Agent 席位：同一模型可以被拉进来多次，各自并行干活。 */
data class GroupMember(
    val id: String,
    val name: String,
    /** 旧的人设自由文本；[card] 为空时回落到它（老调用方只填 persona 也能用）。 */
    val persona: String,
    val cfg: LlmConfig,
    /**
     * 席位身份卡渲染结果（IdentityCard.render()）。空 = 没卡，退到 [persona]。
     * 群聊里每个成员的自述/边界都靠它，没有它就只是一段没有名字的人设文本。
     */
    val card: String = "",
    /**
     * 这个席位自己的上下文线程键。
     *
     * 默认等于 [id]，也就是同一个 Agent 被拉进群几次就有几份互相独立的记忆。
     * 设置里关掉「同人多次拉入各自独立」时，[dev.dsh.mobile.app.AppContainer] 会把它
     * 改成 modelId：几个实例共享一条线程，用来对照「共享 vs 独立」的差别。
     */
    val thread: String = id,
    /**
     * 角色席位（T9.7b）：null = 未指定，这个席位不参与角色编排。
     * 角色决定它在编排循环里什么时候被叫到（A 监督 / B 规划 / C 检测 / D 执行）。
     */
    val role: GroupRole? = null,
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
    /**
     * 编排器开关（T5.2b）：[GROUP_SCHEDULER_LEGACY] 默认，逐字段等价于旧实现（零行为变化）；
     * [GROUP_SCHEDULER_MODERATED] 走 brief-then-moderated 主持人调度。
     */
    val scheduler: String = GROUP_SCHEDULER_LEGACY,
    /** 主持人模型覆盖；空串 = 用召集者会话的模型（不额外指定旗舰模型，避免成本失控）。 */
    val moderatorModel: String = "",
    /**
     * 上一轮因发言上限（[MAX_GROUP_SPEAKERS]）没轮到的成员 id：下一轮排最前先发言。
     *
     * 宿主每轮都从设置重建 [GroupPlan]（AppContainer.groupPlan），所以编排层另按 sessionId
     * 存一份（见 GroupRunner 的 pendingSpeakers）；这个字段是给外部调度/持久化看的载体。
     */
    val pendingSpeakerIds: List<String> = emptyList(),
    /**
     * 规划书写入口（T9.7b）：角色编排循环用它落账。
     * null = 没接（老调用方 / 单测），此时角色卡照样注入提示词，只是不跑编排循环。
     */
    val roleLedger: GroupLedgerSink? = null,
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
    private val toolHelp: () -> String = { "shell tool: runs shell commands on the phone and returns their output." },
    /** phone 工具：通过无障碍服务模拟点击/滑动/输入/读屏/开应用/截屏（参数为 JSON 对象）。 */
    private val phoneExec: suspend (JsonObject) -> String = { "phone tool is not wired up" },
    /** 会话人设 / 预设（普通会话生效；群聊各自带 persona）。 */
    private val personaProvider: suspend (String) -> String = { "" },
    /** 设置里的「自动确认工具」：为真时不再弹确认卡，直接放行。 */
    private val autoApprove: suspend () -> Boolean = { false },
    /** 网络工具（web_search / web_fetch / download）；网络层与工作区都可注入，默认不含工作区。 */
    private val web: WebToolkit = WebToolkit(),
    /** 原生文件工具：保留 JSON 参数和结果，避免 shell 转义损失。 */
    private val fsExec: suspend (String, JsonObject) -> String = { _, _ -> "file tool is not wired up" },
    /** 通道缺省时保留旧 shell 接线。 */
    private val shellExecChannel: suspend (String, String?) -> String = { cmd, _ -> shellExec(cmd) },
    private val channelListExec: suspend () -> String = { "[]" },
    private val bgExec: suspend (String, JsonObject) -> String = { _, _ -> "background job tool is not wired up" },
    /**
     * 宿主注入的技能读取通道（skill 工具的落地实现）。
     *
     * 这里只认「参数对象 -> 文本」，不碰文件：技能库在 :node 引擎的 DSH_HOME 下，本类连路径都不该知道；
     * 失败也由注入方转成可读文本，工具结果要能让模型看到原因。
     */
    private val skillExec: suspend (JsonObject) -> String = { "skills are not wired up" },
    /** 与群聊相同，预算直接以字符计，默认复用群聊预算。 */
    private val contextBudgetProvider: suspend (String) -> Int = { GroupPlan(emptyList()).contextBudget },
    /**
     * 引擎路由：baseUrl 以 engine:// 开头时，把请求交给 :node 引擎里的 /llm chat，
     * 不再走 HTTP。null 表示宿主没有可用的引擎桥（例如 APK 内没有 Node 运行时）。
     */
    private val engineChat: (suspend (JSONObject) -> JSONObject)? = null,
    /** 引擎通用 RPC（path, endpoint, payload）；用于 engineModels 探测，不与 engineChat 合并。 */
    private val engineRpc: (suspend (String, String, JSONObject) -> JSONObject)? = null,
    /** 大工具输出落盘（P0-4）：超阈值写 app 私有目录，正文留头尾 + locator，可 read_output 读回。 */
    private val spill: ToolOutputSpill = ToolOutputSpill(),
    /** 会话投影（P0-6）：todo / goal / 上下文用量 / 压缩统计的只读来源，UI 直接 collect。 */
    val projections: SessionProjections = SessionProjections(),
    /** 摘要持久化（P0-1）：默认不落盘，宿主注入 SharedPrefsSummaryStore 才有跨进程记忆。 */
    private val summaryStore: ContextSummaryStore = ContextSummaryStore.None,
    /**
     * 模型上下文窗口（tokens）。压缩预算 = 窗口 * [ContextBudget.COMPACT_RATIO]，
     * 必须与顶栏 [dev.dsh.mobile.core.data.settings.contextWindowOf] 同一个来源，
     * 否则又会出现「回执写预算 6000、顶栏写 / 32k」的两套口径。
     */
    private val contextWindowProvider: suspend (String) -> Int = { ContextBudget.DEFAULT_WINDOW_TOKENS },
    /** 温层存储（T9.8 S2）：不注入 = 不刷温层，装配与压缩行为与 S1 完全一致。 */
    private val warmStore: WarmLayerStore? = null,
    /** 温层输入历史（带 messages.rowid）：不注入时温层拿不到输入，等于关闭。 */
    private val warmHistoryProvider: (suspend (String) -> List<WarmMessage>)? = null,
    /**
     * 冷层回捞的词搜索（T9.8 S4）：(sessionId, 已转义 LIKE, limit) -> 命中行（带 rowid）。
     *
     * 不注入 = 自动回捞整段关闭，装配出的消息序列与 S3 逐字节相同 —— 宿主没有 messages 表时
     * 本类不该假装能查。
     */
    private val recallSearch: (suspend (String, String, Int) -> List<RecallHit>)? = null,
    /** 冷层回捞的区间查询（rowid 闭区间）：/recall R<n> 与「最近 200 条」兜底都走它。 */
    private val recallRows: (suspend (String, Long, Long, Int) -> List<RecallHit>)? = null,
    /**
     * 额外工具源（T9.10 P3）：MCP 服务器的 tools/list 摊成 ToolSpec 挂进工具表。
     *
     * null = 不接 MCP：声明、执行、授权三处都与 P3 之前逐字节相同，单测不受影响。
     */
    private val extraTools: McpToolSource? = null,
) : DshTransport, Compactor, WarmRefresher, RecallLookup {

    /**
     * 事件出口（T9.14 修 1）：唯一实现是 [EventSink]，本类只保留「非挂起 / 挂起」两个门面。
     *
     * 以前是 MutableSharedFlow(extraBufferCapacity = 512) + 非挂起发送：慢 collector 一来事件就静默丢弃，
     * 正文缺一段、TurnEnd 没送到，界面表现成「说着说着停了」且没有任何报错。
     * 现在缓冲无界、丢弃统一计数（[droppedEventCount]），关键事件走 [postCritical] 阻塞到消费者接走。
     */
    private val sink = EventSink<DshEvent>()

    override val events: SharedFlow<DshEvent> = sink.events

    /** 累计丢弃的事件数（正常恒为 0）：只有流式 delta 这类可丢事件在缓冲溢出时才会增长。 */
    val droppedEventCount: Long get() = sink.droppedEventCount

    /** 最后一次推出事件的时间戳：prompt 用它区分「这一轮真卡死了」和「只是慢」。 */
    private val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /** 非挂起发送（流式 delta 等可丢事件）：溢出时由 [EventSink] 计数 + 打日志。 */
    private fun post(e: DshEvent) {
        lastEventAt.set(System.currentTimeMillis())
        sink.emit(e)
    }

    /** 挂起发送（TurnEnd / ToolCallEvent / Compacted / Permission）：缓冲满时等消费者接走，丢一条 UI 就永远转圈。 */
    private suspend fun postCritical(e: DshEvent) {
        lastEventAt.set(System.currentTimeMillis())
        sink.emitOrAwait(e)
    }

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotConfigured)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val sessions = mutableMapOf<String, Session>()
    private val jobs = mutableMapOf<String, Job>()

    /** 每会话起轮/取消锁：prompt 的「建档 + 挂 job」与 cancel 严格串行，取消不会误杀新轮。 */
    private val turnLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * 每会话最近一次上游上报的上下文占用（prompt + completion，tokens）。
     *
     * 与顶栏 ChatUiState.contextUsed 同源（都来自 [DshEvent.Usage] 的 lastPrompt + output），
     * /compact 回执里的「已用」直接取它，误差 0，不再拿字符数 /4 估一个对不上的数。
     */
    private val lastUsageTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- 工具防抖 / 执行器 / 每 Agent 线程 / 执行池（需求 A、B、D、E） ----
    private val loopGuard = ToolLoopGuard()

    /** 注入通道 + 注册表：声明、执行、授权三者共用同一份 ToolSpec。 */
    private val toolCtx = ToolCtx(
        shellExecChannel = shellExecChannel,
        fsExec = fsExec,
        channelListExec = channelListExec,
        bgExec = bgExec,
        phoneExec = phoneExec,
        skillExec = skillExec,
        spill = spill,
        shellHelp = toolHelp,
        web = web,
    )

    private val toolRegistry = ToolRegistry(toolCtx, extra = { extraTools?.snapshot() ?: emptyList() })

    /** internal 而非 private：单测要证明注入的通道真的被走到（toolsJson 只是声明，不执行）。 */
    internal val toolRunner = ToolExecutor(
        // 每轮现算：MCP 服务器连上/断开后，同一轮之后的工具调用立刻能看见新工具。
        executors = { toolRegistry.handlers() },
        // 超阈值不再硬截 6000 字符：落盘 + 头尾 + locator，模型按需读回全文。
        spill = spill,
        // 手机动作全是有副作用的（点击/输入/滚动），失败重试 1 次 = 再点一次。
        // 这里只关掉执行器那一层重试；业务失败由 phone 自己用错误包回（ok=false 的 code/message）。
        noRetryTools = { toolRegistry.noRetryNames() },
        // MCP 工具的单次预算由服务器配置给（最长 600s），其余工具仍走执行器默认 30s。
        timeoutOf = { name -> extraTools?.timeoutOf(name) },
    )

    /** 每个会话的插话队列，见 [interject]。 */
    private val interjects =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    override fun interject(sessionId: String, text: String) {
        if (text.isBlank()) return
        interjects.getOrPut(sessionId) { java.util.concurrent.ConcurrentLinkedQueue() }.add(text)
    }
    private val threads = AgentThreads()
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
    private val pool = ExecutionPool(scope, maxParallelSetting = GROUP_MAX_PARALLEL, permissionEvent = { sink.emitOrAwait(it) }) { task -> groupRunner.runMemberTask(task) }
    val agentStates: StateFlow<Map<String, AgentRuntimeState>> get() = pool.agents

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * 模型 I/O（T7.1b 拆件，见 [ModelStream]）：SSE 增量、usage、引擎路由、网关自举都在里面。
     * HTTP 客户端与端点/凭据仍由本类注入 —— 换 client 的地方只有上面一处。
     */
    private val stream = ModelStream(http, engineChat, engineRpc)

    /** 上下文压缩（T7.1b 拆件，见 [Compaction]）：压缩实现全在里面，本类只留接口覆写。 */
    private val compaction = Compaction(
        threads = threads,
        summaryStore = summaryStore,
        projections = projections,
        contextWindowProvider = contextWindowProvider,
        historyProvider = historyProvider,
        configProvider = configProvider,
        engineRpc = engineRpc,
        postOnce = { cfg, body -> stream.postOnce(cfg, body) },
        usedTokensOf = { sid -> lastUsageTokens[sid]?.toInt()?.takeIf { it > 0 } },
        // 压缩发生后历史里已经没有身份文本了：赶紧往事件流上喊一声，让注入侧重发。
        onCompacted = { sid ->
            // 压缩后把温层水位/版本一起带出去（S3）：消费者不必回读库就知道「记忆已经换过代」。
            val layer = runCatching { warmStore?.layer(sid) }.getOrNull()
            postCritical(
                DshEvent.Compacted(
                    sessionId = sid,
                    warmVersion = layer?.warmVersion ?: 0,
                    coveredUntilMessageId = layer?.coveredUntilMessageId ?: 0L,
                    source = layer?.source?.name?.lowercase().orEmpty(),
                ),
            )
        },
        warmStore = warmStore,
        warmHistoryProvider = warmHistoryProvider,
    )

    /**
     * 群聊编排（T7.1b 拆件，见 [GroupRunner]）。
     *
     * 一棒成员发言仍走本类的 [runLoop]（[MemberTurn] 传的就是 ::runLoop），
     * 所以群成员与单聊永远是同一条执行路径；执行池留在本类，agentStates 读的仍是它。
     */
    private val groupRunner: GroupRunner = GroupRunner(
        threads = threads,
        pool = pool,
        stream = stream,
        configProvider = configProvider,
        emit = { post(it) },
        chatTurn = ::runLoop,
        takeQueuedImages = ::takeQueuedImages,
    )

    override suspend fun connect() {
        _state.value = RuntimeState.Starting("Loading model config")
        val cfg = configProvider("")
        // 没配模型不是「运行时坏了」：留 NotConfigured，抽屉别把首次安装写成「运行时异常」。
        // AppContainer 的模型监听对 Failed/NotConfigured 一视同仁，配上 Key 会自动重连。
        _state.value = if (cfg == null) {
            RuntimeState.NotConfigured
        } else {
            RuntimeState.Ready(dev.dsh.mobile.core.model.TransportKind.InProcess, "Local engine · ${cfg.model}")
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
            // 标题留空：调用方按当前语言传入（getString(sessions_default_title)），
            // 仓库层 title 用 remote?.title?.ifBlank { title } 兜底，这里写死文案会盖掉它。
            title = "",
            cwd = cwd,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        sessions[s.id] = s
        return s
    }

    override suspend fun resumeSession(sessionId: String): Session =
        sessions[sessionId] ?: Session(id = sessionId, title = "", cwd = "")

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
            groupRunner.forget(sessionId)
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

    override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
        val group = runCatching { groupProvider(sessionId) }.getOrNull()
        val cfg = if (group.isNullOrEmpty()) configProvider(sessionId) else null
        if (group.isNullOrEmpty() && cfg == null) {
            throw IllegalStateException("No model connected — add an API key on the Agents page")
        }
        val history = historyProvider(sessionId)
        // P0-1：上次压缩出的摘要要能跨进程续用，否则重启后等于没压过。
        runCatching {
            val stored = summaryStore.read(sessionId)
            if (stored.isNotBlank() && threads.summary(sessionId).isBlank()) threads.setSummary(sessionId, stored)
        }.onFailure { AppLog.w("Failed to read the stored summary: " + (it.message ?: it.javaClass.simpleName)) }
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
                        extraSystem = if (persona.isBlank()) "" else "Persona and rules the user set for this session — always follow them:\n$persona",
                        turnId = turnId,
                    )
                } else {
                    groupRunner.runGroup(sessionId, group!!, history, text, turnId)
                }
            }.also { jobs[sessionId] = it }
        }
        // 空闲看门狗：runLoop 里任何一个上游读调用挂死，job 就永远不会结束，prompt 也就永远返回不了，
        // 界面上就是「一直在生成」。等满一段没有任何新事件就直接中止，别让 UI 无限转圈。
        var idleTimeout = false
        while (true) {
            if (withTimeoutOrNull(TURN_IDLE_TIMEOUT_MS) { job.join() } != null) break
            if (System.currentTimeMillis() - lastEventAt.get() >= TURN_IDLE_TIMEOUT_MS) {
                idleTimeout = true
                break
            }
        }
        if (idleTimeout) {
            AppLog.w("turn idle timeout (" + TURN_IDLE_TIMEOUT_MS + "ms): " + sessionId)
            job.cancel()
            post(DshEvent.MessageChunk(sessionId, "(Turn timed out: no output for " + TURN_IDLE_TIMEOUT_MS / 1000 + "s, aborted)", turnId = turnId))
            postCritical(DshEvent.TurnEnd(sessionId, "error", turnId = turnId))
        }
        jobs.remove(sessionId)
        return "ok"
    }

    // ------------------------------------------------------------------ loop

    /**
     * T4.4 回图像：工具阶段截的图进不了 role=tool 的 image_url，只能在发起下一次请求前
     * 从 [PendingImageQueue] 取出来并进本轮 user 消息的 content（限 2 张，空队列返回空表）。
     * 每次请求只调一次：取走即出队，别让同一张图串到后面几轮。
     */
    private fun takeQueuedImages(): List<String> {
        val queued = PendingImageQueue.drainForNextTurn(PendingImageQueue.MAX_ITEMS)
        if (queued.isEmpty()) return emptyList()
        AppLog.i("queued screenshots: inject=" + queued.size + " paths=" + queued.joinToString(", ") { it.path })
        return queued.map { it.path }
    }

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
        // 思考参数由能力驱动：手选值优先，auto 时按 providerId/baseUrl/模型名推断（映射表在 core:model 一处）。
        val reasoningStyle = resolveReasoningStyle(cfg.reasoningStyle, cfg.providerId, cfg.baseUrl, cfg.model)
        // 历史里回填 reasoning_content 只对 DeepSeek 系成立（别的上游会 400），与「发不发思考参数」是两件事。
        val deepSeekHistory = isDeepSeekFamily(cfg.providerId, cfg.baseUrl, cfg.model)
        // 每次进入本轮都清掉同轮去抖状态；60 秒滑动窗口跨轮保留，跨轮死循环也要拦。
        loopGuard.beginTurn()
        val messages =
            if (presetMessages != null) presetMessages.toMutableList() else mutableListOf<JsonObject>()
        if (presetMessages == null) {
        // [装配·system] 基础提示：模型配置里的 systemPrompt + 工具说明 + 网页工具说明。
        val system = buildString {
            append(cfg.systemPrompt)
            append("\n\n")
            if (useTools) {
                append(toolHelp())
                append("\n\n")
                append(web.helpText())
            }
            // [装配·身份卡] 人设与规则并进 system 尾部（本地路径不用 IdentityInjector）。
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
                if (deepSeekHistory && useTools && role == "assistant" && m.thought.isNotBlank()) {
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
        // [装配·尾部user] 历史之后只剩这一条用户消息（群聊由 presetMessages 自带）。
        if (!presetEndsWithUser) {
            // 上一轮 tool 里截的图并进来（队列为空时 content 与改造前逐字节一致）。
            val withQueued = userImages + takeQueuedImages()
            messages += buildJsonObject {
                put("role", "user")
                put("content", buildUserContent(userText, withQueued))
            }
        }

        if (presetMessages == null) {
            val budgetChars = contextBudgetProvider(sessionId)
            // P0-1：超预算先把被丢弃的最老若干轮压成结构化摘要（摘要本身也是 system 风格消息，
            // 排在保留段之前），再按预算装配 —— 不再只是「丢最老的 + 80 字截断」。
            runCatching { compaction.maybeSummarize(sessionId, cfg, budgetChars, messages) }
                .onFailure { AppLog.w("Context compaction failed: " + (it.message ?: it.javaClass.simpleName)) }
            val assembly = threads.assembleForSingleChat(
                sessionId = sessionId,
                budgetChars = budgetChars,
                system = messages.first()["content"]!!.jsonPrimitive.content,
                history = messages.drop(1).dropLast(1),
                userMsg = messages.takeLast(1),
            )
            messages.clear()
            messages.addAll(assembly.messages)
            // [装配·温层] 最终序列 system（含身份卡）→ 温层 → 历史 → 尾部 user：插在 index 1。
            // 温层为空（null 或 isEmpty）时这里一条都不加，产出的消息序列与接入前逐字节相同。
            val warmText = WarmAssembly.block(runCatching { warmStore?.layer(sessionId) }.getOrNull())
            if (warmText != null) {
                messages.add(1, buildJsonObject {
                    put("role", "system")
                    put("content", warmText)
                })
            }
            // [装配·回捞] 最终序列 system（含身份卡）→ 温层 → 回捞 → 历史 → 尾部 user。
            // 回捞排在温层之后、历史之前：它是「历史里被裁掉那段的线索」，位置比历史更靠前才读得到。
            // 判定为假 / 没命中时这里一条都不加 —— 消息序列与 S3 逐字节相同（有单测锁死）。
            val recallText = recallBlock(sessionId, userText, assembly.fullChars, budgetChars)
            if (recallText != null) {
                messages.add(if (warmText != null) 2 else 1, buildJsonObject {
                    put("role", "system")
                    put("content", recallText)
                })
            }
            AppLog.w("single context usedChars=${assembly.usedChars} fullChars=${assembly.fullChars} overBudget=${assembly.overBudget} overflowChars=${assembly.overflowChars}")
            // P0-6：上下文用量进会话投影，UI 只读展示（不再只躺在 ChatScreen 内存里）。
            // 数值统一成 token：投影字段名沿用 usedChars/budgetChars，但里面装的是 token，
            // ProjectionUi.usageLine 直接把它当 token 显示；分母用窗口（与顶栏同一个数），
            // 压缩预算（窗口 * 0.8）只出现在 /compact 回执里，别混进顶栏。
            projections.noteUsage(
                sessionId = sessionId,
                usedChars = ContextBudget.tokensOf(assembly.usedChars),
                fullChars = ContextBudget.tokensOf(assembly.fullChars),
                budgetChars = compaction.windowTokens(sessionId),
            )
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
        // 人设会话（extraSystem 非空）才管拒答重试：普通聊天的「抱歉」是正常回答。
        val personaActive = extraSystem.isNotBlank()
        val refusalRetry = RefusalRetry(fallbackModels = cfg.fallbackModels)

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
            post(DshEvent.MessageChunk(sessionId, notice, authorId, authorName, turnId))
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
            // 本轮已经上屏的正文。去重必须相对「本轮」而不是整轮对话：工具循环里下一轮是新正文，
            // 相对整轮比对会被判成前缀失配；而同一轮断流重试是同一段话从头再说，相对本轮比对才准。
            var roundShown = ""
            val body = buildJsonObject {
                put("model", cfg.model)
                // 思考参数：发什么由 reasoningParams 一处决定；none 时这里一个字段都不会加。
                reasoningParams(reasoningStyle, cfg.reasoningEffort, cfg.reasoningMap).forEach { (k, v) -> put(k, v) }
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
            // 人设会话：拒答只在 180 字以内的短文本里成立，所以这一轮的前 180 字先押住不上屏。
            // 判出来是拒答就整轮重发，用户永远不会先看到一句「我不能」，库里也只剩最终那一条；
            // 超过 180 字说明正文在写，立刻放开闸门恢复真流式。
            var holdForRefusal = personaActive && emitted.isEmpty()
            fun pushVisible(text: String) {
                if (text.isEmpty()) return
                streamedText += text
                if (holdForRefusal && streamedText.length <= RefusalGuard.MAX_REFUSAL_CHARS) return
                holdForRefusal = false
                val newDelta = incrementalDelta(roundShown, streamedText)
                if (newDelta.isNotEmpty()) {
                    roundShown = streamedText
                    emitted += newDelta
                    visibleChars += newDelta.length
                    post(DshEvent.MessageChunk(sessionId, newDelta, authorId, authorName, turnId))
                }
            }
            var retriedThisRound = false
            var resp = runCatching {
                stream.post(
                    cfg,
                    body,
                    onReasoning = { delta ->
                        if (delta.isNotEmpty()) {
                            streamedReasoning += delta
                            post(DshEvent.ThoughtChunk(sessionId, delta, authorId, authorName, turnId))
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
                // 断连 / 读超时（SocketTimeoutException 也是 IOException）以前一步失败就收场，
                // 网络抖一下整轮就是「[请求失败]」。本轮还没有任何工具副作用时重发一次；
                // 重发不挂流式回调：正文只以最终结果进 incrementalDelta，已上屏的半句不会被重说一遍。
                if (e is java.io.IOException && toolRunsThisTurn == 0 && !streamRetried) {
                    streamRetried = true
                    retriedThisRound = true
                    AppLog.w("llm call failed (io), retry once: ${e.message}")
                    runCatching { stream.post(cfg, body) }.getOrElse { second ->
                        if (second is kotlinx.coroutines.CancellationException) throw second
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        AppLog.w("llm retry failed: ${second.message}")
                        post(DshEvent.MessageChunk(sessionId, "\n\n[Request failed] " + stream.friendlyRequestError(second, cfg.baseUrl, userImages.isNotEmpty()), authorId, authorName, turnId))
                        postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                        return
                    }
                } else {
                    AppLog.w("llm call failed: ${e.message}")
                    post(DshEvent.MessageChunk(sessionId, "\n\n[Request failed] " + stream.friendlyRequestError(e, cfg.baseUrl, userImages.isNotEmpty()), authorId, authorName, turnId))
                    postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                    return
                }
            }

            // 断流兜底：连接在 [DONE] 之前被掐断。本轮还没跑过任何工具时，同一请求静默重发一次
            // （重试期间不推流式正文，只保留最后一轮的 content），别让用户看到半句话 + 中断提示。
            if (resp.bool("dsh_eof_without_done") == true && toolRunsThisTurn == 0 && !streamRetried) {
                streamRetried = true
                retriedThisRound = true
                AppLog.w("stream cut before [DONE]; retry once")
                resp = runCatching { stream.post(cfg, body) }.getOrElse {
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
            // 未闭合的块（上游在闭合标签前断流）在这里整段切掉并打标记：以前它原样留在正文里，
            // 而 toolCalls 是空的，于是这一轮默默按 "stop" 收尾 —— 用户看到半截 markup、调用也没执行。
            val guard = truncateOpenBlock(split.first)
            // ModelStream 已经替我们剥过一次（流式闸门 + plain 整包），它切掉的未闭合块只留下标记。
            var truncatedToolCall = guard.second || resp.bool("dsh_truncated_tool_call") == true
            val dsml = stripDsmlToolCalls(guard.first)
            if (dsml.first.length < guard.first.length) truncatedToolCall = true
            var content = dsml.first
            // 工具调用先算出来：下面要不要重发（重发会丢掉这一轮的 tool_calls）由它决定。
            var toolCalls = msg?.get("tool_calls")?.jsonArray
            // 非流式整包（postOnce 的 plain 分支）不经过上面的兜底，正文里混的标记在这里再兜一次。
            if (toolCalls.isNullOrEmpty() && dsml.second.isNotEmpty()) {
                toolCalls = JsonArray(dsml.second.map { it.toToolCallJson() })
            }
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
                post(DshEvent.ThoughtChunk(sessionId, reasoningDelta, authorId, authorName, turnId))
            }
            // 人设会话被拒答、或者干脆一个字都没回：押住的正文还没上屏，直接整轮重发。
            // 只在「没有工具调用、本轮没执行过工具、不是断流」时重试：有副作用的重发是错的。
            var refusalExhausted: RefusalRetry.Outcome? = null
            if (holdForRefusal && toolCalls.isNullOrEmpty() && toolRunsThisTurn == 0 && resp.bool("dsh_eof_without_done") != true) {
                val outcome = refusalRetry.run(content, cfg.model) { _, retryModel ->
                    AppLog.w("persona retry: model=" + retryModel)
                    val framed = stream.frameUserMessage(body, PERSONA_FRAME)
                    // 换模型的兜底重试：思考参数只有 DeepSeek 系认，别的上游会直接 400。
                    val retryBody = if (retryModel == cfg.model) framed
                    else JsonObject(framed.filterKeys { it != "thinking" && it != "reasoning_effort" })
                    val retryResp = stream.post(cfg.copy(model = retryModel), retryBody)
                    val retryRaw = retryResp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                    stripDsmlToolCalls(splitThinking(retryRaw).first).first
                }
                if (outcome.attempts > 1) {
                    AppLog.w(
                        "persona retry: attempts=" + outcome.attempts + " accepted=" + outcome.accepted +
                            " hit=" + outcome.refusalHit.orEmpty(),
                    )
                }
                content = outcome.text
                streamedText = content
                if (!outcome.accepted) refusalExhausted = outcome
            }
            holdForRefusal = false

            if (content.isNotBlank()) {
                val clean = if (roster.isEmpty()) content else sanitizeGroupText(content, authorName ?: "", roster)
                if (clean.isNotBlank()) {
                    val delta = incrementalDelta(roundShown, clean)
                    if (delta.isNotEmpty()) {
                        roundShown = clean
                        emitted += delta
                        visibleChars += delta.length
                        post(DshEvent.MessageChunk(sessionId, delta, authorId, authorName, turnId))
                    }
                    // onFinal 给的是「本轮累计正文」，群聊侧据此整段替换而不是追加。
                    onFinal(clean)
                }
            }
            // 重试用尽仍是拒答：正文照常上屏，再补一句为什么 —— 不能静默成「空回复」。
            refusalExhausted?.let { out ->
                val why = out.refusalHit?.let { "matched \"" + it + "\"" } ?: "several empty replies in a row"
                AppLog.w("persona retry: gave up after " + out.attempts + " attempts, reason=" + why)
                val note = "(persona was included, but the model refused " + out.attempts + " times in a row: " + why + ")"
                post(
                    DshEvent.MessageChunk(
                        sessionId,
                        (if (visibleChars > 0) "\n\n" else "") + note,
                        authorId,
                        authorName,
                        turnId,
                    ),
                )
                visibleChars += note.length
            }

            // 断流：上游在 [DONE] 之前就关了连接。正文已流式发出，补一句标注并落到终态（不再 Running）。
            if (resp.bool("dsh_eof_without_done") == true) {
                post(DshEvent.MessageChunk(sessionId, "(Reply interrupted · the connection dropped unexpectedly, please retry)", authorId, authorName, turnId))
                postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                return
            }

            resp["usage"]?.jsonObject?.let { u ->
                val prompt = u["prompt_tokens"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                val cached = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull()
                    ?: (u["prompt_cache_hit_tokens"] as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                val output = u["completion_tokens"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                // 与顶栏同一口径：prompt（含缓存命中）+ 本轮 completion。
                lastUsageTokens[sessionId] = prompt + output.toLong()
                post(
                    DshEvent.Usage(sessionId, TokenUsage(input = (prompt - cached).coerceAtLeast(0L), output = output.toLong(), cached = cached, lastPrompt = prompt), authorId, turnId = turnId),
                )
            }

            if (toolCalls.isNullOrEmpty()) {
                // 唯一能识别的「调用」是个没闭合的块：一个工具都没执行，绝不能按 "stop" 正常收尾。
                if (truncatedToolCall) {
                    val note = "(This turn's tool call was truncated: upstream dropped the stream before the closing tag, so the call never ran. Ask me to retry.)"
                    post(DshEvent.MessageChunk(sessionId, if (visibleChars > 0) "\n\n" + note else note, authorId, authorName, turnId))
                    visibleChars += note.length
                    postCritical(DshEvent.TurnEnd(sessionId, "truncated", authorId, turnId, truncatedToolCall = true))
                    return
                }
                // 上游这一轮什么都没给（error 字段 / choices 为空 / 被长度截断 / 纯空正文）时，
                // 以前直接 TurnEnd("stop")：界面一片空白，用户只能猜是不是网断了。
                if (visibleChars == 0) {
                    val cause = upstreamEmptyReason(resp, finishReason)
                        .ifBlank { lastToolError?.let { "No follow-up text after the tool failure (" + it.trim() + ")" }.orEmpty() }
                        .ifBlank { "Upstream returned empty content (no text and no tool call)" }
                    // 群聊成员的零输出由 runMemberTask 统一写「（该成员本轮无输出）」，
                    // 这里再发一条会变成两条说明。
                    if (authorId == null) {
                        post(
                            DshEvent.MessageChunk(sessionId, "(No output this turn: " + cause + ")", authorId, authorName, turnId),
                        )
                    }
                    postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                    return
                }
                // 模型自己收尾：工具失败后没再说话、上游只回空，都在这里交代一句。
                emitTurnEndNotice(reason = "stop")
                postCritical(DshEvent.TurnEnd(sessionId, "stop", authorId, turnId))
                return
            }

            messages += buildJsonObject {
                put("role", "assistant")
                put("content", content)
                if (deepSeekHistory && useTools) {
                    reasoningOf(msg)?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
                }
                put("tool_calls", toolCalls)
            }

            if (truncatedToolCall) {
                // 末尾还有一个没闭合的调用块：已解析出来的照常执行，但要让用户知道少了一个。
                val note = "(The tool call at the end of this turn was truncated; the last call never ran)"
                post(DshEvent.MessageChunk(sessionId, if (visibleChars > 0) "\n\n" + note else note, authorId, authorName, turnId))
                visibleChars += note.length
            }

            toolCalls.forEach { tc ->
                val obj = tc.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: UUID.randomUUID().toString()
                val fn = obj["function"]?.jsonObject
                val name = fn?.get("name")?.jsonPrimitive?.contentOrNullSafe() ?: "shell"
                val argsRaw = fn?.get("arguments")?.jsonPrimitive?.contentOrNullSafe() ?: "{}"
                // 参数被流截断时（`{"command":"ls -l` 这种）以前退化成空 map，shell 拿到空 command
                // 只会回一句「参数缺失」——把「流断了」伪装成「模型没给参数」，用户无从判断。
                val args = if (argsRaw.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { DshJson.parseToJsonElement(argsRaw).jsonObject }.getOrNull()
                }
                if (args == null) {
                    AppLog.w("tool args is not json (truncated?): " + argsRaw.take(120))
                    postCritical(
                        DshEvent.ToolCallEvent(
                            sessionId,
                            ToolCall(
                                id,
                                title = name + " (incomplete args)",
                                kind = name,
                                status = ToolStatus.Failed,
                                detail = "Tool arguments are not valid JSON (likely truncated by the stream); nothing was executed. Raw arguments: " + argsRaw.take(200),
                                args = argsRaw.take(200),
                                requestJson = describeRequest(body, round, name, argsRaw.take(200)),
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    val note = "(Incomplete tool arguments — no tool ran this round; ask me to retry.)"
                    post(DshEvent.MessageChunk(sessionId, if (visibleChars > 0) "\n\n" + note else note, authorId, authorName, turnId))
                    visibleChars += note.length
                    postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
                    return
                }
                // shell 用 command 字段；phone 等工具用完整参数 JSON 作为展示与去重键（含坐标，避免同动作不同坐标被误合并）。
                val command = if (name == "shell") {
                    args["command"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                } else {
                    args.toString()
                }
                // 子代理的标题要带 name（「子代理 · 查日志」）；其余工具仍是命令前 60 字。
                val titleText = if (name == "subagent") "Subagent · " + subagentLabel(args) else command.take(60)
                val signature = ToolSignature.of(name, command)
                val requestJson = describeRequest(body, round, name, command)

                // A：死循环 → 本次不执行，结束本轮；同轮重复 → 复用上次结果，不再执行。
                val verdict = loopGuard.check(signature)
                if (verdict is ToolVerdict.Loop) {
                    postCritical(
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
                    postCritical(DshEvent.TurnEnd(sessionId, "tool_loop", authorId, turnId))
                    return
                }
                val output: String = if (verdict is ToolVerdict.Reuse) {
                    postCritical(
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
                                responseJson = spill.present(verdict.cachedOutput, turnId, id + "-ui"),
                            ),
                            authorId,
                            authorName,
                            turnId,
                        ),
                    )
                    verdict.cachedOutput
                } else {
                    postCritical(
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
                        name == "shell" && command.isBlank() -> ToolExecResult(ok = false, output = "", error = "missing parameter")
                        // G：不可逆 shell 命令先问用户；没确认就当被拒，错误照常回灌模型。phone 参数非 shell 命令，不确认。
                        name == "shell" && needsConfirmation(command) && confirm(task, sessionId, command) != "allow" ->
                            ToolExecResult(ok = false, output = "", error = "user did not confirm; command skipped")
                        // 子代理不能走 toolRunner：那里是 30 秒单次超时 + 1 次重试，
                        // 而一个子代理要跑满 8 轮工具往返、总预算 180 秒。
                        name == "subagent" -> runSubagent(
                            cfg = cfg,
                            parentSystem = parentSystem,
                            args = args,
                            sessionId = sessionId,
                            turnId = turnId,
                            // 父任务链：执行池里的任务用 task.id，主代理直接答的那一轮用 turnId。
                            parentTaskId = task?.id ?: turnId,
                            agentId = authorId,
                            agentName = authorName,
                            // runLoop 只跑主代理/群成员这一层；再往下都由子代理递归，层级在那里 +1。
                            callerDepth = 0,
                        ) { step ->
                            post(
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
                        // P0-5：模型侧待办与目标。写进会话投影（P0-6），UI 只读展示。
                        // 桌面的 todo_write 是整表替换，这里语义一致 —— 不是增量修补。
                        name == "todo_write" -> todoWriteTool(sessionId, args)
                        name == "goal" -> goalTool(sessionId, args)
                        else -> {
                            // T6.1b：逐调用授权。判据在 ToolPolicy（按工具名 + 参数分档），
                            // shell 的命令文本判据仍在上面那条，两者不重复实现。
                            when (
                                val verdict = ToolPolicy.verdict(
                                    name = name,
                                    args = args,
                                    capabilityTiers = capabilityTiers,
                                    sessionGranted = sessionGrants(sessionId),
                                    // 注入的 MCP 工具不在 ToolCatalog 里，档位得从注册表现取。
                                    resolve = toolRegistry::get,
                                )
                            ) {
                                is ToolAuth.Allow ->
                                    // 带上 turnId + callId：落盘目录按轮次分组（tools-output/<turnId>/<callId>.txt），
                                    // 同名工具同一轮调多次也不会互相覆盖。
                                    toolRunner.run(name, args, turnId, id)

                                is ToolAuth.Deny -> ToolExecResult(ok = false, output = "", error = verdict.reason)

                                is ToolAuth.Ask ->
                                    if (confirmTool(task, sessionId, verdict.title) == "allow") {
                                        // T4.5：能力动作放行后记进本会话，Session 档下次不再问。
                                        verdict.capability?.let { rememberGrant(sessionId, it) }
                                        toolRunner.run(name, args, turnId, id)
                                    } else {
                                        ToolExecResult(ok = false, output = "", error = "user did not confirm; action skipped")
                                    }
                            }
                        }
                        }
                    }.getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        currentCoroutineContext().ensureActive()
                        AppLog.w("tool " + name + " failed: " + e.message)
                        ToolExecResult(
                            ok = false,
                            output = "",
                            error = "Tool execution failed: " + stream.friendlyRequestError(e, cfg.baseUrl),
                        )
                    }
                    loopGuard.recordResult(signature, result.output)
                    toolRunsThisTurn++
                    groupRunner.recordTaskToolCalls(task?.id, toolRunsThisTurn)
                    // 失败摘要留给收尾用：本轮若最终没有正文，turnEndNotice 会把它写成一条说明。
                    // 这里不发 MessageChunk —— 它追加到同一个气泡，模型随后还要接着写正文，
                    // 中途插一行会把回答劈成两半（失败本身已在工具卡 detail 里可见）。
                    if (!result.ok) lastToolError = toolFailureTrace(command, result.error)
                    // B：失败不炸整轮 —— 错误进 ToolCall.detail，同时作为 tool 结果回灌模型。
                    val body = if (result.ok) {
                        result.output
                    } else {
                        "[Execution failed] " + (result.error ?: "unknown error") + "\n" + result.output
                    }
                    postCritical(
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
                                responseJson = spill.present(result.output.ifBlank { result.error.orEmpty() }, turnId, id + "-ui"),
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
                    // 回灌给模型的是「头若干行 + locator + 尾若干行」，全文在 tools-output 下可 read_output 读回。
                    put("content", spill.present(output, turnId, id + "-ctx"))
                }
            }
        }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            currentCoroutineContext().ensureActive()
            AppLog.w("turn crashed: " + e.message)
            post(
                DshEvent.MessageChunk(
                    sessionId,
                    "(Turn ended with an error: " + stream.friendlyRequestError(e, cfg.baseUrl) + ")",
                    authorId,
                    authorName,
                    turnId,
                ),
            )
            postCritical(DshEvent.TurnEnd(sessionId, "error", authorId, turnId))
            return
        }

        // 兜底：最后一轮本该无工具、正常 return；万一上游还硬塞 tool_calls，
        // 也不能让这一轮悬着不结算。
        emitTurnEndNotice(reason = "stop", roundsExhausted = true)
        postCritical(DshEvent.TurnEnd(sessionId, "stop", authorId, turnId))
    }


    /** 只取新增部分：新文本以旧文本开头就发剩余；整段重复则不发。 */
    private fun incrementalDelta(previous: String, current: String): String = when {
        previous.isEmpty() -> current
        current == previous -> ""
        current.startsWith(previous) -> current.substring(previous.length)
        previous.startsWith(current) -> ""
        else -> {
            // 走到这里说明两次拿到的正文前缀对不上：多半是断流重试后上游从头重说了一遍。
            // 前半句已经上屏了，整段重发就是「半句 + 整段」的双份正文，所以只补公共前缀之后的部分。
            val shared = commonPrefixLength(previous, current)
            AppLog.w(
                "Text prefix mismatch: " + previous.length + " chars already on screen, this turn " + current.length +
                    " chars, common prefix " + shared + " — only append the delta, never re-emit what is already shown",
            )
            if (shared == 0) "" else current.substring(shared)
        }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = if (a.length < b.length) a.length else b.length
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
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
            raw.take(JSON_VIEW_LIMIT) + "…(truncated, original ${raw.length} chars)"
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
    /** [ToolAuth.Ask] 的入口：与 shell 共用同一套权限卡语义，只有文案不同。 */
    private suspend fun confirmTool(task: AgentTask?, sessionId: String, title: String): String =
        confirm(task, sessionId, title)

    private suspend fun confirm(task: AgentTask?, sessionId: String, command: String): String {
        val auto = runCatching { autoApprove() }.getOrDefault(false)
        if (auto) return "allow"
        val request = PermissionRequest(
            sessionId = sessionId,
            requestId = UUID.randomUUID().toString(),
            title = "Run: " + command.take(100),
            options = listOf(
                PermissionOption("allow", "Allow", "allow"),
                PermissionOption("deny", "Deny", "deny"),
            ),
        )
        if (task != null) return pool.requestPermission(task, request) ?: "deny"
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        directPermissions[request.requestId] = gate
        directOwners[request.requestId] = sessionId
        return try {
            sink.emitOrAwait(DshEvent.Permission(sessionId, request))
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { gate.await() } ?: "deny"
        } finally {
            directPermissions.remove(request.requestId)
            directOwners.remove(request.requestId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                sink.emitOrAwait(DshEvent.PermissionResolved(sessionId, request.requestId))
            }
        }
    }

    /** 工具表：由 [toolRegistry] 按同一份 ToolSpec 生成，执行面与授权档位读的也是它。 */
    internal fun toolsJson(): JsonArray = toolRegistry.declarations()

    // ── P0-5 模型侧待办 / 目标 ──────────────────────────────────────────────

    /**
     * todo_write：整表替换（不是增量修补），语义对齐桌面 dsh-tool-todo。
     *
     * 两处落点：会话投影（UI 只读展示，P0-6）与 :node 引擎的 /tool-state（桌面同一份状态）。
     * 引擎不在不影响本次调用 —— 移动端自己必须能用。
     */
    private suspend fun todoWriteTool(sessionId: String, args: JsonObject): ToolExecResult {
        val started = System.currentTimeMillis()
        val todos = parseTodos(args) ?: return ToolExecResult(
            ok = false,
            output = "",
            error = "Invalid parameter: todos must be an array of items with content (non-empty string) and status (pending / in_progress / completed).",
            elapsedMs = System.currentTimeMillis() - started,
        )
        projections.writeTodos(sessionId, todos)
        runCatching {
            val rpc = engineRpc ?: return@runCatching
            val list = org.json.JSONArray()
            todos.forEach { list.put(JSONObject().put("content", it.content).put("status", it.status)) }
            rpc("/tool-state", "todo-write", JSONObject().put("sessionId", sessionId).put("todos", list))
        }.onFailure { AppLog.w("todo_write sync to engine failed: " + (it.message ?: it.javaClass.simpleName)) }
        val done = todos.count { it.status == ProjectedTodo.STATUS_DONE }
        val text = buildString {
            if (todos.isEmpty()) {
                append("Todo list cleared.")
            } else {
                append("Todos updated (").append(todos.size).append(" items, ").append(done)
                append("/").append(todos.size).append(" done)")
                todos.forEach { t ->
                    append("\n").append(
                        when (t.status) {
                            ProjectedTodo.STATUS_DONE -> "- [x] "
                            ProjectedTodo.STATUS_DOING -> "- [~] "
                            else -> "- [ ] "
                        }
                    ).append(t.content)
                }
            }
        }
        return ToolExecResult(ok = true, output = text, error = "", elapsedMs = System.currentTimeMillis() - started)
    }

    /**
     * goal：get / create / update。update 用 revision 做乐观锁 —— 别处改过就明确报冲突，
     * 而不是静默覆盖掉用户刚改的目标。
     */
    private suspend fun goalTool(sessionId: String, args: JsonObject): ToolExecResult {
        val started = System.currentTimeMillis()
        fun fail(text: String) = ToolExecResult(ok = false, output = "", error = text, elapsedMs = System.currentTimeMillis() - started)
        fun ok(text: String) = ToolExecResult(ok = true, output = text, error = "", elapsedMs = System.currentTimeMillis() - started)
        val action = args["action"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        val current = projections.snapshot(sessionId).goal
        return when (action) {
            "get" -> ok(current?.let { describeGoal(it) } ?: "This session has no goal yet.")
            "create" -> {
                val objective = args["objective"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
                if (objective.isEmpty()) return fail("Invalid parameter: create requires objective.")
                val maxRounds = args["max_rounds"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0
                val goal = ProjectedGoal(objective = objective, revision = 1, maxRounds = maxRounds)
                projections.writeGoal(sessionId, goal)
                runCatching {
                    val rpc = engineRpc ?: return@runCatching
                    rpc(
                        "/tool-state", "goal-create",
                        JSONObject().put("sessionId", sessionId).put("objective", objective).put("maxRounds", maxRounds),
                    )
                }.onFailure { AppLog.w("goal sync to engine failed: " + (it.message ?: it.javaClass.simpleName)) }
                ok("Goal created (revision 1): " + objective)
            }
            "update" -> {
                if (current == null) return fail("No goal yet — run action=create first.")
                val expect = args["revision"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull()
                    ?: return fail("Invalid parameter: update requires revision (run action=get first to read the current one).")
                if (expect != current.revision) {
                    return fail("Version conflict: current revision=" + current.revision + ", yours " + expect + " is stale; run action=get and retry.")
                }
                val phase = args["phase"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                if (phase != null && phase !in ProjectedGoal.PHASES) {
                    return fail("Invalid parameter: phase must be one of " + ProjectedGoal.PHASES.joinToString(" / ") + ".")
                }
                val objective = args["objective"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: current.objective
                val maxRounds = args["max_rounds"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: current.maxRounds
                val next = current.copy(
                    objective = objective,
                    phase = phase ?: current.phase,
                    revision = current.revision + 1,
                    maxRounds = maxRounds,
                    blockedReason = if (phaseIsBlocked(phase, current.phase)) {
                        args["blocked_reason"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                            ?: current.blockedReason
                    } else {
                        null
                    },
                )
                projections.writeGoal(sessionId, next)
                runCatching {
                    val rpc = engineRpc ?: return@runCatching
                    rpc(
                        "/tool-state", "goal-update",
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put("action", next.phase)
                            .put("revision", expect)
                            .put("objective", objective)
                            .put("maxRounds", maxRounds)
                            .put("blockedReason", next.blockedReason.orEmpty()),
                    )
                }.onFailure { AppLog.w("goal sync to engine failed: " + (it.message ?: it.javaClass.simpleName)) }
                ok(describeGoal(next))
            }
            else -> fail("Invalid parameter: action must be get / create / update.")
        }
    }

    /** 只有 blocked 才留卡点原因；离开 blocked 就清掉，免得旧原因一直挂着。 */
    private fun phaseIsBlocked(phase: String?, current: String): Boolean =
        (phase ?: current) == ProjectedGoal.PHASE_BLOCKED

    private fun describeGoal(goal: ProjectedGoal): String = buildString {
        append("Goal (").append(goal.phase).append(", revision ").append(goal.revision)
        if (goal.maxRounds > 0) append(", at most ").append(goal.maxRounds).append(" rounds")
        append("): ").append(goal.objective)
        goal.blockedReason?.takeIf { it.isNotBlank() }?.let { append("\nBlocker: ").append(it) }
    }

    // T7.1b shim #1：Compactor 的覆写必须留在本类（接口 + 十几个测试直接构造 transport），
    // 实现已搬到 context/Compaction.kt。本方法只做一次转发，签名与行为逐字不变。
    // 删除时机 = Compactor 接口改成由 Compaction 直接实现、且测试不再调 transport.compact() 那天。
    override suspend fun compact(sessionId: String, budgetTokens: Int): CompactResult =
        compaction.compact(sessionId, budgetTokens)

    // T9.8-S2 shim #2：与上面的 compact 同款 —— 入口留在本类，实现与状态都在 Compaction。
    // /warm 是「现在就刷一次」，所以这里不走触发判定（轮次/预算），只保证同一批消息不会被重复处理
    // （去重水位在 Compaction.refreshWarm 里按 rowid 判断）。返回 null = 没可刷的东西。
    override suspend fun refreshWarm(sessionId: String): WarmRefreshReport =
        compaction.refreshWarm(sessionId)

    // ------------------------------------------------------------------ 冷层回捞（T9.8 S4）

    /** 手动 /recall 攒下的命中：只喂下一轮装配一次，用完即弃；绝不写回 messages 表。 */
    private val manualRecall = java.util.concurrent.ConcurrentHashMap<String, List<RecallHit>>()

    /**
     * 本轮要不要插一条回捞 system 块。
     *
     * 两个来源：① 手动 /recall 攒下的命中（优先，用掉即清）；② 自动回捞 —— 只有历史真被截断
     * （[historyChars] > [budgetChars]）且抽到检索词时才查库，否则一条 SQL 都不发。
     * 预算吃紧时上限收到 800（先砍回捞，温层 LOCKED 不动）。返回 null = 不插。
     */
    private suspend fun recallBlock(
        sessionId: String,
        userText: String,
        historyChars: Int,
        budgetChars: Int,
    ): String? {
        val cap = RecallPlanner.capFor(historyChars, budgetChars)
        val manual = manualRecall.remove(sessionId)
        if (manual != null && manual.isNotEmpty()) {
            return RecallPlanner.format(manual, minOf(cap, RecallPlanner.CAP_CHARS))
        }
        if (!RecallPlanner.needsRecall(userText, historyChars, budgetChars)) return null
        val search = recallSearch ?: return null
        val terms = RecallPlanner.keywords(userText).take(RecallPlanner.MAX_TERMS)
        if (terms.isEmpty()) return null
        val hits = java.util.LinkedHashMap<Long, RecallHit>()
        for (term in terms) {
            val rows = runCatching { search(sessionId, RecallPlanner.escapeLike(term), RecallPlanner.PER_TERM_LIMIT) }
                .onFailure { AppLog.w("Recall query failed, ignored: " + (it.message ?: it.javaClass.simpleName)) }
                .getOrDefault(emptyList())
            rows.forEach { hits.putIfAbsent(it.rowId, it) }
        }
        return RecallPlanner.format(topHits(hits.values), cap)
    }

    /**
     * /recall：`<词>` 按词查，`R<n>` 按第 n 次压缩的区间查（区间丢了按最近 200 条兜底）。
     *
     * 命中块留给下一轮装配（[manualRecall]），原文只回一句 rowid 摘要给 UI —— 不倒正文。
     */
    override suspend fun recall(sessionId: String, query: String): RecallReport {
        val q = query.trim()
        if (q.isEmpty()) return RecallReport(message = "Usage: /recall <word> or /recall R<number>.")
        val round = RecallPlanner.roundOf(q)
        val hits = if (round != null) recallByRound(sessionId, round) else recallByTerm(sessionId, q)
        if (hits.isEmpty()) {
            return RecallReport(message = "No history matched \"" + q.take(40) + "\".")
        }
        manualRecall[sessionId] = hits
        val from = hits.minOf { it.rowId }
        val to = hits.maxOf { it.rowId }
        return RecallReport(
            hits = hits.size,
            fromRowId = from,
            toRowId = to,
            rows = hits.map { "rowid " + it.rowId + " · " + it.role },
            message = "Recalled " + hits.size + " history rows (rowid " + from + "-" + to + "); they will ride with the next message.",
            text = RecallPlanner.format(hits, RecallPlanner.CAP_CHARS),
        )
    }

    /** 按词查：词本身也当检索词，逐词 LIKE，最多 3 个词，去重后取最新 6 条。 */
    private suspend fun recallByTerm(sessionId: String, text: String): List<RecallHit> {
        val search = recallSearch ?: return emptyList()
        val terms = (listOf(text.trim()) + RecallPlanner.keywords(text))
            .filter { it.length >= 2 }
            .take(RecallPlanner.MAX_TERMS)
        val hits = java.util.LinkedHashMap<Long, RecallHit>()
        for (term in terms) {
            val rows = runCatching { search(sessionId, RecallPlanner.escapeLike(term), RecallPlanner.PER_TERM_LIMIT) }
                .getOrDefault(emptyList())
            rows.forEach { hits.putIfAbsent(it.rowId, it) }
        }
        return topHits(hits.values)
    }

    /**
     * 按压缩轮次查：区间记得就直接取该区间（新→旧取 200 条再挑 6 条）；
     * 区间没记过（进程重启过）就退回最近 200 条。
     */
    private suspend fun recallByRound(sessionId: String, round: Int): List<RecallHit> {
        val rows = recallRows ?: return emptyList()
        val range = compaction.recallRange(sessionId, round)
        if (range != null) {
            val inRange = runCatching {
                rows(sessionId, range.first, range.last, RecallPlanner.FALLBACK_WINDOW)
            }.getOrDefault(emptyList())
            if (inRange.isNotEmpty()) return inRange.take(RecallPlanner.MAX_HITS)
        }
        val window = runCatching {
            rows(sessionId, 0L, Long.MAX_VALUE, RecallPlanner.FALLBACK_WINDOW)
        }.getOrDefault(emptyList())
        return window.take(RecallPlanner.MAX_HITS)
    }

    private fun topHits(all: Collection<RecallHit>): List<RecallHit> =
        all.filter { it.text.isNotBlank() }
            .sortedByDescending { it.rowId }
            .take(RecallPlanner.MAX_HITS)

    /**
     * 子代理嵌套上限（T5.3）：默认 2。深度口径 = 当前执行者层级，主代理第 0 层。
     * 由设置项「子代理嵌套」经 [setSubagentMaxDepth] 写入，取值 1..3。
     */
    @Volatile
    internal var subagentMaxDepth: Int = SUBAGENT_MAX_DEPTH

    /** 设置页改深度后调这里；越界就地夹紧，写成 0 不会把 subagent 工具永久关死。 */
    fun setSubagentMaxDepth(v: Int) {
        subagentMaxDepth = v.coerceIn(1, 3)
    }

    /**
     * 六类系统能力的档位原始串（T4.5）。AppContainer 把 SettingsStore 的值推到这里，
     * 与 [setSubagentMaxDepth] 同一套注入方式 —— 授权判据不读全局单例，测试可以直接换档。
     */
    @Volatile
    private var capabilityTiers: String = ""

    /** 本会话内已被用户放行的能力项：Session 档确认一次后同会话不再问，会话没了自然失效。 */
    private val capabilitySessionGrants = ConcurrentHashMap<String, MutableSet<DeviceCapability>>()

    fun setCapabilityTiers(raw: String) {
        capabilityTiers = raw
    }

    private fun sessionGrants(sessionId: String): Set<DeviceCapability> =
        capabilitySessionGrants[sessionId] ?: emptySet()

    private fun rememberGrant(sessionId: String, capability: DeviceCapability) {
        capabilitySessionGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(capability)
    }

    /**
     * 子代理预算闸门（T5.3）：用量读既有 [lastUsageTokens]，预算读房间预算 / settings 上下文预算。
     * 这里不存任何计数，只算百分比与并发分摊。
     */
    private val subagentBudget = SubagentBudget(
        usedTokensOf = { sid -> lastUsageTokens[sid] ?: 0L },
        budgetTokensOf = { sid ->
            val chars = groupRunner.roomBudgetTokens(sid).takeIf { it > 0 } ?: contextBudgetProvider(sid)
            ContextBudget.tokensOf(chars)
        },
    )

    /** 子代理显示名：参数给了 name 就用，否则「子代理」。 */
    internal fun subagentLabel(args: JsonObject): String =
        args["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() } ?: "Subagent"

    /**
     * 子代理的工具表：从默认表里按白名单过滤。
     *
     * 默认实参是底座白名单（不含 subagent）；要放开嵌套时由调用方传 [subagentToolNames] 算出的集合，
     * 深度闸门因此在工具表这一层就生效，而不是等模型调了才拒。[allowed] 可注入，单测直接传集合。
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
     * 三道闸门（T5.3）：子代理看不到主对话（prompt 必须自带背景）；嵌套深度受 [subagentMaxDepth]
     * 限制，超限回可读错误而不静默失败；父会话预算按并发数分摊，80% 落一条告急通知、100% 拒绝。
     * 结论里显式列出的任务清单解析成子任务投进执行池，父任务 id 记在 AgentTask.parentId 上。
     */
    private suspend fun runSubagent(
        cfg: LlmConfig,
        parentSystem: String,
        args: JsonObject,
        sessionId: String,
        turnId: String?,
        parentTaskId: String?,
        agentId: String?,
        agentName: String?,
        // 调用者所在层级：主代理 0，它派出的子代理 1……
        callerDepth: Int,
        onStep: (String) -> Unit,
    ): ToolExecResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return ToolExecResult(
                ok = false,
                output = "",
                error = "Missing prompt parameter: state exactly what to delegate — the subagent cannot see this conversation",
            )
        }
        // 深度闸门：模型自报的 depth 只能把层级抬高（更严），压不低运行时的真实层级。
        val declared = args["depth"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.toIntOrNull() ?: 0
        val depth = maxOf(callerDepth, declared)
        if (depth >= subagentMaxDepth) {
            return ToolExecResult(ok = false, output = "", error = depthRefusal(subagentMaxDepth, depth))
        }
        val verdict = subagentBudget.begin(sessionId)
        if (verdict.warn) {
            post(
                DshEvent.MessageChunk(
                    sessionId,
                    "[Budget low] " + verdict.percent + "% used; further subtasks may be rejected",
                    "system",
                    "System",
                    turnId,
                ),
            )
        }
        if (!verdict.allowed) return ToolExecResult(ok = false, output = "", error = verdict.reason)
        val label = subagentLabel(args)
        val model = args["model"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.model
        val childCfg = if (model == cfg.model) cfg else cfg.copy(model = model)
        val started = System.currentTimeMillis()
        val childDepth = depth + 1
        try {
            // 复用同一条流式请求路径（含网关自举与引擎路由），子代理才会和主代理看到同样的上游行为。
            val outcome = SubagentLoop(
                model = model,
                parentSystemPrompt = parentSystem,
                chat = { body -> stream.post(childCfg, body) },
                runTool = { name, a ->
                    if (name == "subagent") {
                        // 深度 2 以内允许再派：递归走同一条路径，层级 +1、父任务 id 一路带下去。
                        runSubagent(
                            childCfg, parentSystem, a, sessionId, turnId, parentTaskId, agentId, agentName, childDepth, onStep,
                        )
                    } else {
                        toolRunner.run(name, a)
                    }
                },
                tools = subagentToolsJson(subagentToolNames(childDepth, subagentMaxDepth)),
                label = label,
                depth = childDepth,
                maxDepth = subagentMaxDepth,
                budgetChars = verdict.shareChars,
                onStep = onStep,
                spill = spill,
            ).run(prompt)
            // 任务清单进池：解析失败就是「这轮没派活」，结论文本照常带回去。
            val specs = if (outcome.ok) parseSubagentTasks(outcome.text) else emptyList()
            val taskIds = groupRunner.submitSubagentTasks(
                sessionId = sessionId,
                parentTaskId = parentTaskId,
                agentId = agentId ?: "main",
                agentName = agentName ?: "Main agent",
                specs = specs,
            )
            val tasksNote = when {
                specs.isEmpty() -> ""
                taskIds.isEmpty() -> "\n\n[Subtasks not queued] The conclusion lists " + specs.size +
                    " tasks, but this session has no execution pool (only group rooms can dispatch work)."
                else -> "\n\n[Queued] " + taskIds.size + " subtasks: " +
                    specs.joinToString("; ") { it.title } + " (parent " + (parentTaskId ?: "-") + ")"
            }
            return ToolExecResult(
                ok = outcome.ok,
                output = if (outcome.ok) outcome.text + tasksNote else "",
                error = if (outcome.ok) null else outcome.text,
                elapsedMs = System.currentTimeMillis() - started,
                attempts = 1,
            )
        } finally {
            subagentBudget.end(sessionId)
        }
    }

    /**
     * 一次 chat 往返，永远走 SSE。
     *
     * 流式不是为了省字：好几个上游（部分海外节点）会直接拒绝非流式请求
     * （"Non-stream chat request is currently not supported"）。统一要 SSE 再自己拼回整包，
     * 一条代码路径就能同时喂饱这些上游和思维链增量。
     */
    /**
     * 引擎里可用的 provider 模型 id 列表（/llm models）。实现在 [ModelStream.engineModels]。
     *
     * T7.1b shim #2：EngineRouteTransportTest 直接调 transport.engineModels()，签名必须留在这里。
     * 删除时机 = 测试改成注入 ModelStream 调用的那天。
     */
    suspend fun engineModels(): List<String> = stream.engineModels()

    private companion object {
        const val MAX_ROUNDS = 64

        const val PERMISSION_TIMEOUT_MS = 180_000L

        /** 一轮对话多久没有任何事件就判定卡死（有输出会重置计时）。 */
        const val TURN_IDLE_TIMEOUT_MS = 300_000L
        val CONFIRM_PATTERNS = listOf("rm -rf", "mkfs", "pm uninstall", "reboot", " su ", "su -", "fastboot")
    }
}

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
    if (errorText.isNotBlank()) return "Upstream error: " + errorText.take(160)
    val choices = resp["choices"] as? JsonArray
    if (choices == null || choices.isEmpty()) return "Upstream returned no choices"
    return when (finishReason) {
        "length" -> "Upstream finish_reason=length (output hit the length cap and was truncated)"
        "content_filter" -> "Upstream finish_reason=content_filter (content was filtered)"
        else -> ""
    }
}
