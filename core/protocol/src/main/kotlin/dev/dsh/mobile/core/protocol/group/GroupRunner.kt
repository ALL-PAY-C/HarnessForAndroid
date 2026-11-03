package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.TaskState
import dev.dsh.mobile.core.protocol.AgentThreads
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.ExecutionPool
import dev.dsh.mobile.core.protocol.GroupMember
import dev.dsh.mobile.core.protocol.GroupMode
import dev.dsh.mobile.core.protocol.GroupPlan
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.MAX_AUTOMATON_REWORK
import dev.dsh.mobile.core.protocol.PLANNER_MAX_TOKENS
import dev.dsh.mobile.core.protocol.SUMMARIZER_MAX_TOKENS
import dev.dsh.mobile.core.protocol.StallDetector
import dev.dsh.mobile.core.protocol.Verdict
import dev.dsh.mobile.core.protocol.WORKER_MAX_TOKENS
import dev.dsh.mobile.core.protocol.automatonRounds
import dev.dsh.mobile.core.protocol.automatonVerdict
import dev.dsh.mobile.core.protocol.buildUserContent
import dev.dsh.mobile.core.protocol.detectGroupMode
import dev.dsh.mobile.core.protocol.digest
import dev.dsh.mobile.core.protocol.groupStateLine
import dev.dsh.mobile.core.protocol.llm.ModelStream
import dev.dsh.mobile.core.protocol.namedMentions
import dev.dsh.mobile.core.protocol.nextReworkCount
import dev.dsh.mobile.core.protocol.parseMentions
import dev.dsh.mobile.core.protocol.planGroupRound
import dev.dsh.mobile.core.protocol.planGroupTasks
import dev.dsh.mobile.core.protocol.sharedText
import dev.dsh.mobile.core.protocol.tool.contentOrNullSafe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * 群聊编排（T7.1b 从 LocalEngineTransport 拆出，纯搬移，语义逐字未改）。
 *
 * 只管「怎么排、排给谁、什么时候收工」：拆任务 → 执行池 → 汇总 / 主持人房间 / 自动机 / 办公室分工。
 * 一棒成员怎么说话不归这里：那仍是传输层的 runLoop（见 [MemberTurn]），
 * 所以成员发言路径、上下文装配、事件流与单聊完全同一条代码。
 *
 * 线程、执行池、事件出口、模型 I/O 全部注入：本类不持有 HTTP 客户端、不自己建池。
 */
internal class GroupRunner(
    /** 每 Agent 线程：成员上下文与公共历史都在这里（与单聊共用同一份）。 */
    private val threads: AgentThreads,
    /** 执行池：成员并行与权限等待都走它。实例由传输层持有（agentStates 读的是同一份）。 */
    private val pool: ExecutionPool,
    /** 一次上游往返（含网关自举与引擎路由）；群聊自己没有 HTTP 依赖。 */
    private val stream: ModelStream,
    private val configProvider: suspend (String) -> LlmConfig?,
    /** 事件出口：系统通知 / 正文 / TurnEnd 直接进传输层的事件流。 */
    private val emit: (DshEvent) -> Unit,
    /** 一棒成员发言：由传输层传 ::runLoop，参数表逐一对齐 [MemberTurn]。 */
    private val chatTurn: MemberTurn,
    /** 上一轮 tool 里截的图：成员发言与单聊一样并进 user content。 */
    private val takeQueuedImages: () -> List<String>,
) {
    private val groupTurns = mutableMapOf<String, GroupTurn>()

    /** 上一个真正说完话的成员（sessionId -> agentId）：未 @ 时用来判断「是不是在追问它」。 */
    private val lastSpeaker = mutableMapOf<String, String>()

    /**
     * sessionId -> 上一轮因发言上限没轮到的成员（下一轮优先）。
     *
     * 宿主每轮都从设置重建 GroupPlan，plan 上的字段带不过去，所以这里单独存一份。
     */
    private val pendingSpeakers = mutableMapOf<String, List<String>>()

    /**
     * taskId -> 这一棒实际执行的工具次数。执行池只留状态不留过程，
     * 僵局检测要判断「到底有没有真的动工具」，所以单独记一份。
     */
    private val taskToolCallCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * taskId -> 该任务最终发言。执行池只留状态（Done/Failed），不留正文，
     * 而自动机要拿上一棒的产出喂下一棒，所以这里存一份（每轮覆盖，超量直接清空）。
     */
    private val taskOutputs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 群成员这一轮能不能用工具：runGroup 每次开跑按 plan.allowTools 灌一次（设置改完立即生效）。 */
    @Volatile
    private var groupToolsEnabled: Boolean = false

    /** 群聊本轮快照：执行池的 runner 靠它还原成员配置与上下文。 */
    private data class GroupTurn(
        val plan: GroupPlan,
        val history: List<ChatMessage>,
        val userText: String,
        val turnId: String? = null,
    )

    /**
     * 群组：拆任务 → 进执行池串行跑 → 全部结束后再发一个总 TurnEnd。
     *
     * 每个成员拿到的是「自己线程 + 公共历史 + 他人已完成产出」，跑完写回自己线程，
     * 所以第二个人不会重复第一个人干过的活（需求 D）。
     */
    internal suspend fun runGroup(
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
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        if (mode == GroupMode.Automaton) {
            runAutomaton(sessionId, plan, userText)
            groupTurns.remove(sessionId)
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        if (mode == GroupMode.Office) {
            runOffice(sessionId, plan, history, userText)
            groupTurns.remove(sessionId)
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        // 上一轮被上限挤下来的人优先；plan 上的字段带不过来（宿主每轮重建），所以优先取本会话那份。
        val prior = synchronized(pendingSpeakers) { pendingSpeakers[sessionId] }.orEmpty()
            .ifEmpty { plan.pendingSpeakerIds }
        // 编排器开关（T5.2b）：默认 LegacyScheduler —— 它只回答「这一轮该不该开」，
        // 派活仍旧走下面的 planGroupRound，所以顺序/截断/顺延规则只有一份实现，零行为变化。
        if (plan.scheduler == GROUP_SCHEDULER_MODERATED) {
            runModeratedRoom(sessionId, plan, history, userText)
            groupTurns.remove(sessionId)
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        val legacyRoom = roomViewOf(
            plan = plan,
            userText = userText,
            transcript = emptyList(),
            round = 0,
            charsUsed = userText.length,
            elapsedMs = 0L,
            ledger = LedgerView(),
            lastSpeakerId = synchronized(lastSpeaker) { lastSpeaker[sessionId] },
        )
        if (LegacyScheduler(plan, userText, prior).next(legacyRoom) is SchedulerDecision.Done) {
            // 一个能说话的成员都没有：旧路径走到这里同样是空任务直接收尾（不发说明）。
            groupTurns.remove(sessionId)
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        val round = planGroupRound(
            sessionId = sessionId,
            plan = plan,
            userText = userText,
            history = history,
            lastSpeakerId = synchronized(lastSpeaker) { lastSpeaker[sessionId] },
            mode = mode,
            priorityIds = prior,
        )
        // 被上限挤到下一轮的人：记进 GroupPlan 字段 + 本会话待发言表，并在群里留一句话说明。
        synchronized(pendingSpeakers) { pendingSpeakers[sessionId] = round.deferred }
        groupTurns[sessionId]?.let { it ->
            groupTurns[sessionId] = it.copy(plan = it.plan.copy(pendingSpeakerIds = round.deferred))
        }
        round.notice?.let { text -> emitGroupNotice(sessionId, text) }
        val tasks = round.tasks
        if (tasks.isEmpty()) {
            groupTurns.remove(sessionId)
            emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
            return
        }
        // E：经执行池派活 —— 有界并行、依赖唤醒、状态同步到 agents flow。
        dispatchGroupTasks(sessionId, tasks)
        // 等本 session 的活全部结束（完成/失败/取消都会唤醒），不靠轮询 sleep。
        pool.await(sessionId)
        groupTurns.remove(sessionId)
        emit(DshEvent.TurnEnd(sessionId, "stop", turnId = turnId))
    }

    /**
     * brief-then-moderated 房间（T5.2b，设计文档 §4/§5/§7）。
     *
     * 和自动机一样在**一轮用户消息内**按轮驱动：问调度器要决策 → 派活 → 等结算 →
     * 记进 transcript/账本 → 回顶部做终止判定。终止条件与优先级全在 evaluateStop 里，
     * 这里只负责喂信号、把决策落成任务、把原因写进群聊 notice（任何自动行为都要能问责）。
     *
     * 成本控制：房间级预算沿用 plan.contextBudget（24k 字符语义），80% 喊一声，
     * 到顶直接散会 —— 不再有「每轮广播全员」这个隐藏成本源（§7）。
     */
    private suspend fun runModeratedRoom(
        sessionId: String,
        plan: GroupPlan,
        history: List<ChatMessage>,
        userText: String,
    ) {
        // 主持人默认取召集者会话模型；设置里给了覆盖就用覆盖的。
        val scheduler = BriefThenModeratedScheduler { prompt -> moderatorCall(sessionId, plan, prompt) }
        val stall = StallDetector()
        val silence = SilenceTracker()
        val transcript = ArrayList<TranscriptLine>()
        val ledger = LedgerView()
        val startedAt = System.currentTimeMillis()
        var charsUsed = userText.length
        var round = 0
        var warned = false
        var lastSpeakerId: String? = synchronized(lastSpeaker) { lastSpeaker[sessionId] }
        while (true) {
            // 用户打断优先级最高：协程被取消就直接收手，别再派活。
            if (!currentCoroutineContext().isActive) {
                emitGroupNotice(sessionId, "[群聊已散会] 用户打断，立即散会。")
                return
            }
            val budget = BudgetView(
                round = round,
                maxRounds = MAX_ROOM_ROUNDS,
                charsUsed = charsUsed,
                charBudget = plan.contextBudget,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
            val room = roomViewOf(
                plan = plan,
                userText = userText,
                transcript = transcript,
                round = round,
                charsUsed = charsUsed,
                elapsedMs = budget.elapsedMs,
                ledger = ledger,
                lastSpeakerId = lastSpeakerId,
            )
            val stop = evaluateStop(
                StopSignals(
                    stall = stall.stalled,
                    stallReason = stall.reason,
                    silentStreak = silence.streak,
                    round = round,
                ),
                budget,
            )
            if (stop != null) {
                AppLog.w("群聊调度：" + stop.kind + " → " + stop.reason)
                emitGroupNotice(sessionId, "[群聊已散会] " + stop.reason)
                return
            }
            if (budget.tight && !warned) {
                warned = true
                emitGroupNotice(
                    sessionId,
                    "[预算告急] 房间预算已用 " + budget.percent + "%，接近上限会自动散会。",
                )
            }
            val decision = scheduler.next(room)
            val speakers: List<GroupMember> = when (decision) {
                is SchedulerDecision.NextSpeaker ->
                    plan.members.filter { it.id == decision.memberId }
                is SchedulerDecision.Roundtable -> scheduler.roundtableOrder(room)
                    .mapNotNull { state -> plan.members.firstOrNull { it.id == state.id } }
                is SchedulerDecision.Done -> {
                    emitGroupNotice(sessionId, "[群聊已散会] " + decision.reason)
                    return
                }
                is SchedulerDecision.Escalate -> {
                    emitGroupNotice(sessionId, "[群聊已升级] " + decision.reason)
                    return
                }
                is SchedulerDecision.AskUser -> {
                    emitGroupNotice(sessionId, "[主持人提问] " + decision.question)
                    return
                }
            }
            if (speakers.isEmpty()) {
                emitGroupNotice(sessionId, "[群聊已散会] 调度器没给出可发言的成员。")
                return
            }
            emitGroupNotice(sessionId, "主持人：" + decision.explain)
            // 决策 → 任务仍旧走 planGroupRound：座次就是 speakers 的顺序（brief 阶段已按 id 升序）。
            val tasks = planGroupRound(
                sessionId = sessionId,
                plan = plan.copy(members = speakers, pendingSpeakerIds = emptyList()),
                userText = userText,
                history = history,
                lastSpeakerId = lastSpeakerId,
                mode = GroupMode.Chain,
            ).tasks
            if (tasks.isEmpty()) {
                emitGroupNotice(sessionId, "[群聊已散会] 这一轮没有可执行的任务。")
                return
            }
            dispatchGroupTasks(sessionId, tasks)
            pool.await(sessionId)
            // 记发言必须用「成员 id」（task.agentId），不能用任务 id：
            // RoomView.briefPhase 靠 members[i].spokeCount 判断首轮是否结束，
            // 用任务 id 会让 spokeCount 永远为 0 → 永远停在首轮简报 → 主持人一次都不会被调用。
            val outputs = tasks.map { Triple(it.id, it.agentId, taskOutputs[it.id].orEmpty()) }
            outputs.forEach { (_, memberId, text) ->
                val name = plan.members.firstOrNull { it.id == memberId }?.name.orEmpty()
                transcript.add(transcriptOf(memberId, name, text, round))
                charsUsed += text.length
            }
            lastSpeakerId = tasks.lastOrNull()?.agentId ?: lastSpeakerId
            stall.onRound(
                texts = outputs.map { it.third },
                toolCalls = tasks.sumOf { taskToolCalls(it.id) },
                newTasks = 0,
            )
            silence.onRound(contributorsOf(outputs.map { it.second to it.third }))
            round++
        }
    }

    /** 房间快照：调度器能看到的全部信息（主持人没有与会者上下文，只有这四样）。 */
    private fun roomViewOf(
        plan: GroupPlan,
        userText: String,
        transcript: List<TranscriptLine>,
        round: Int,
        charsUsed: Int,
        elapsedMs: Long,
        ledger: LedgerView,
        lastSpeakerId: String?,
    ): RoomView {
        val mentioned = parseMentions(userText, plan.members).map { it.first }
            .ifEmpty { namedMentions(userText, plan.members) }
        val forcedIds = mentioned.map { it.id }.toSet()
        val namedIds = namedMentions(userText, plan.members).map { it.id }.toSet()
        val members = plan.members.map { m ->
            val mine = transcript.filter { it.speakerId == m.id }
            MemberState(
                id = m.id,
                name = m.name,
                spokeCount = mine.size,
                lastSpokeRound = mine.lastOrNull()?.round ?: -1,
                pendingTasks = ledger.openTasks.count { it.contains(m.name) },
                forced = m.id in forcedIds,
                named = m.id in namedIds,
            )
        }
        return RoomView(
            transcript = transcript,
            members = members,
            ledger = ledger,
            budgetLeft = BudgetView(
                round = round,
                maxRounds = MAX_ROOM_ROUNDS,
                charsUsed = charsUsed,
                charBudget = plan.contextBudget,
                elapsedMs = elapsedMs,
            ),
            round = round,
            lastSpeakerId = lastSpeakerId,
        )
    }

    /**
     * 主持人一次往返：只回一行 JSON。
     *
     * 空串 = 没有可用模型或调用失败（调度器据此走可解释的优先级兜底）。
     * 单独一条通道、单独一个 max_tokens：主持人不是成员，不能借成员的上下文与预算。
     */
    private suspend fun moderatorCall(sessionId: String, plan: GroupPlan, prompt: String): String {
        // 群聊会话没有单聊 config（configProvider 在群聊里返回 null，见 prompt()），
        // 主持人只能从成员席位借一份模型配置；否则这里恒返回空串，
        // runModeratedRoom 每轮都落进 priorityFallback，用户看到的就是「开了主持但没人主持」。
        val base = configProvider(sessionId)
            ?: plan.members.firstOrNull { it.cfg.model.isNotBlank() }?.cfg
            ?: run { AppLog.w("主持人：群内没有可用模型，本轮回落到优先序仲裁"); return "" }
        val cfg = if (plan.moderatorModel.isBlank()) base else base.copy(model = plan.moderatorModel)
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", MODERATOR_MAX_TOKENS)
            put("temperature", 0)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", MODERATOR_SYSTEM_PROMPT)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        val resp = runCatching { stream.postOnce(cfg, body) }.getOrElse { e ->
            AppLog.w("主持人：调用失败 " + (e.message ?: e.javaClass.simpleName))
            return ""
        }
        return resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNullSafe().orEmpty()
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
        // 开场先落一句可见文本：自动机档只靠成员气泡说话，一轮下来若没有这句，
        // 空正文会让仓库层补一条「（本轮没有产生任何回复，请重试）」，看着像失败。
        emitGroupNotice(
            sessionId,
            "[自动机] 共 " + rounds + " 轮：" + worker.name + " 执行、" + reviewer.name + " 验收。",
        )
        var round = 0
        var rework = 0
        var progress = "还没开始，起始值按用户规则来。"
        val stall = StallDetector()
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
            // 自动机档也要更新 lastSpeaker：不然「未 @ 的追问」会一直找错人（默认档早就更新了）。
            synchronized(lastSpeaker) { lastSpeaker[sessionId] = worker.id }
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
            synchronized(lastSpeaker) { lastSpeaker[sessionId] = reviewer.id }
            val reviewOut = taskOutputs[checkId].orEmpty()
            progress = digest(reviewOut, 300)
            val verdict = automatonVerdict(reviewOut)
            // 打回计数只统计**连续**的：任一通过轮（继续/完成）直接归零，跨轮累计不算数。
            rework = nextReworkCount(rework, verdict)
            when (verdict) {
                Verdict.Rework -> {
                    if (rework > MAX_AUTOMATON_REWORK) {
                        AppLog.w("自动机：" + reviewer.name + " 连续打回 " + rework + " 次，提前收工")
                        emitGroupNotice(
                            sessionId,
                            "自动机连续打回 " + rework + " 次（上限 " + MAX_AUTOMATON_REWORK + "），已收工，等你看一眼下一步。",
                        )
                        return
                    }
                    round-- // 这一轮不算数，重做同一轮
                }
                // 验收说完成就完成：Done 的优先级高于僵局判定（短发言也可能就是正确答案）。
                Verdict.Done -> {
                    emitGroupNotice(sessionId, "[自动机已收工] 第 " + round + " 轮验收通过，任务达成。")
                    return
                }
                Verdict.More -> Unit
            }
            // 僵局：连续两轮都「没工具调用 + 短发言 + 没有新任务」，说明在原地打转，别烧到轮次上限。
            val calls = taskToolCalls(stepId) + taskToolCalls(checkId)
            if (stall.onRound(listOf(workerOut, reviewOut), toolCalls = calls, newTasks = 0)) {
                AppLog.w("自动机：判定僵局（" + (stall.reason ?: "") + "），收工")
                emitGroupNotice(
                    sessionId,
                    "[群聊已停下] 判定僵局：" + (stall.reason ?: "") +
                        "。连续两轮都在原地打转，先停手，避免继续空转烧预算。",
                )
                return
            }
        }
        AppLog.w("自动机：撞到轮次上限 " + rounds + "，收工")
        emitGroupNotice(sessionId, "[自动机已收工] 跑满 " + rounds + " 轮仍未判定完成，先停手等你看一眼。")
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
    internal suspend fun runMemberTask(task: AgentTask): String {
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
        // 群成员这一轮同样要看到上一轮的截图（与单聊同一条队列、同一次注入）。
        val images = turn.history.lastOrNull { it.role == ChatMessage.Role.USER }?.attachments.orEmpty() +
            takeQueuedImages()
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
        // 位置实参即 runLoop 的形参顺序（函数型不支持具名实参）；extraSystem 位传空串 = 原来的默认值。
        chatTurn(
            task.sessionId,
            member.cfg,
            turn.history,
            task.prompt,
            images,
            member.id,
            member.name,
            "",
            // 群聊里默认成员只负责说话；用户打开「允许群组成员使用工具」时才各自跑命令。
            groupToolsEnabled,
            roster,
            asm.messages,
            task,
            { finalText = it },
            turn.turnId,
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

    /** 记下这一棒实际调了几次工具（僵局检测用）；量大了整体清空，避免长会话里无界增长。 */
    internal fun recordTaskToolCalls(taskId: String?, count: Int) {
        if (taskId == null) return
        if (taskToolCallCounts.size > 200) taskToolCallCounts.clear()
        taskToolCallCounts[taskId] = count
    }

    /** 某一棒的工具调用次数；没记录就是 0（没动过工具）。 */
    private fun taskToolCalls(taskId: String): Int = taskToolCallCounts[taskId] ?: 0

    /**
     * 群里发一条系统说明：不调模型、不进成员上下文，走的是普通消息气泡的渲染路径，
     * 用户看得见「为什么这轮有人没说话 / 为什么停了」。
     */
    private fun emitGroupNotice(sessionId: String, text: String) {
        val turnId = groupTurns[sessionId]?.turnId
        emit(DshEvent.MessageChunk(sessionId, text, "system", "系统", turnId))
    }

    /** 记下这一棒的最终发言（自动机下一棒要用）；量大了整体清空，避免长会话里无界增长。 */
    private fun recordTaskOutput(taskId: String, text: String) {
        if (taskOutputs.size > 200) taskOutputs.clear()
        taskOutputs[taskId] = text
    }


    /** 会话取消/关闭时丢掉这一轮的群状态（与传输层 cancel 同一时刻，语义不变）。 */
    internal fun forget(sessionId: String) {
        groupTurns.remove(sessionId)
    }
}

/**
 * 一棒成员发言的函数型：与 LocalEngineTransport.runLoop 的形参**顺序与类型逐一对齐**。
 * 函数型调用不支持具名实参，所以调用点用位置实参，顺序即 runLoop 的声明顺序。
 */
internal typealias MemberTurn = suspend (
    String,
    LlmConfig,
    List<ChatMessage>,
    String,
    List<String>,
    String?,
    String?,
    String,
    Boolean,
    List<String>,
    List<JsonObject>?,
    AgentTask?,
    (String) -> Unit,
    String?,
) -> Unit
