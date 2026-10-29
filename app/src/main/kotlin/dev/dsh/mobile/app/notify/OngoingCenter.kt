package dev.dsh.mobile.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.dsh.mobile.app.MainActivity
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.DshOption
import dev.dsh.mobile.core.common.splitOptions
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.repo.TurnState
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 一个会话此刻在干嘛。聚合通知按这个列表渲染。 */
data class SessionTrace(
    val sessionId: String,
    val title: String,
    /** 正在思考 / 正在执行工具 / 等你确认 / 刚完成。 */
    val phase: Phase,
    /** 思维链尾部（已按设置裁行）。 */
    val thought: String,
    /** 群聊里是谁在思考。 */
    val speaker: String,
    val permission: PermissionRequest?,
    val since: Long,
    /** 这条回复尾巴上带的可选动作：没有权限卡时也能在通知里直接点。 */
    val options: List<DshOption> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(),
) {
    enum class Phase { Thinking, Tool, Waiting, Done }
}

/**
 * 常驻聚合通知：整个应用只有这一条常驻通知。
 *
 * - 没有会话在跑时：一行显示运行时状态（就绪/未配置/失败），低优先级、不打扰。
 * - 有 n 个会话在跑时：标题变成「n 个会话进行中」，正文逐行列出每个会话的
 *   当前阶段与思维链尾部（按 [AppSettings.notifyThoughtLines] 裁行）。
 * - 有会话在等确认时：把选项直接挂成通知按钮，点一下就把答案回传；
 *   另挂一个「其他」按钮跳到会话里自己输入（前台服务通知不允许带 RemoteInput）。
 * - 会话做完：保留一行「✓ 完成」[KEEP_DONE_MS] 毫秒再收起，用户不用守着屏幕。
 *
 * 与 [NotificationCenter] 分工：那边负责「完成/待确认」的一次性提醒（可震动/横幅），
 * 这边负责「现在有什么在跑」这一条常驻面板，ID 固定，永远是同一条。
 */
class OngoingCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    settings: Flow<AppSettings>,
    private val repo: ChatRepository,
    /** 延迟取 [NotificationCenter]：两者互相引用，直接构造会成环。 */
    private val notifierOf: () -> NotificationCenter,
) {
    @Volatile private var current: AppSettings = AppSettings.DEFAULT
    @Volatile private var runtime: RuntimeState = RuntimeState.NotConfigured
    @Volatile private var lastSignature: String = ""
    @Volatile private var foregroundUpdater: ((Notification) -> Unit)? = null
    // 三个收集器（settings/sessions/turns）都跑在 AppScope（Dispatchers.Default，多线程）上，
    // 共享容器必须是并发安全的：裸 HashMap/HashSet 会 ConcurrentModificationException 或标题错乱。
    // lastSignature 已是 @Volatile，但只护住了标量，没护住这些集合。
    private val titles = ConcurrentHashMap<String, String>()
    /** 刚做完、还在「✓ 完成」停留期里的会话。 */
    private val doneUntil = ConcurrentHashMap<String, Long>()
    /** 上一帧还在跑的会话，用来捕捉「忙 → 闲」这条边。 */
    private var lastRunning: Set<String> = emptySet()
    /** 已经提醒过的确认请求，避免同一条反复弹。 */
    private val alertedPerms = ConcurrentHashMap.newKeySet<String>()
    /**
     * 每个会话最后一轮的正文尾巴。
     *
     * finalizeTurn 一旦落库就把 TurnState 整个重置成空，此后 turns 里再也读不到
     * 那句话——「✓ 完成」那一行和它尾巴上的选项按钮会跟着一起消失。所以在还能
     * 读到的时候留一份，等「完成」停留期过了再清掉。
     */
    private val lastTail = ConcurrentHashMap<String, String>()
    private var hideJob: Job? = null
    private var renderJob: Job? = null
    private var lastRenderAt: Long = 0L

    init {
        scope.launch {
            settings.collect { s ->
                val old = current
                current = s
                // 开关变化要立刻反映到面板上（比如刚打开「常驻通知」）。
                if (old.notifyOngoing != s.notifyOngoing ||
                    old.notifyShowThought != s.notifyShowThought ||
                    old.notifyThoughtLines != s.notifyThoughtLines ||
                    old.notificationsEnabled != s.notificationsEnabled ||
                    old.dnd != s.dnd ||
                    old.notifyInlineActions != s.notifyInlineActions
                ) {
                    lastSignature = ""
                    render(repo.turns.value, s)
                }
            }
        }
        scope.launch {
            repo.observeSessions().map { list -> list.associate { it.id to it.title } }.collect { map ->
                titles.clear()
                titles.putAll(map)
            }
        }
        scope.launch {
            combine(repo.turns, settings) { turns, s -> turns to s }
                .collect { (turns, s) ->
                    // 完成/待确认的一次性提醒放在这里而不是聊天页：用户可以停在
                    // 任何页面（甚至已经按 Home），只要这一轮跑完就一定能收到。
                    notifyEdges(turns, s)
                    scheduleRender(turns, s)
                }
        }
    }

    /** 运行时状态变化（[dev.dsh.mobile.app.service.RuntimeService] 喂进来）。 */
    fun onRuntime(state: RuntimeState) {
        runtime = state
        lastSignature = ""
        render(repo.turns.value, current)
    }

    /** 供前台服务使用的第一帧通知（服务启动时必须同步拿到一条）。 */
    fun foregroundNotification(): Notification {
        val s = current
        val panelOn = s.notificationsEnabled && !s.dnd && s.notifyOngoing
        return build(if (panelOn) tracesOf(repo.turns.value, s) else emptyList(), s, force = true)
    }

    private fun render(turns: Map<String, TurnState>, s: AppSettings) {
        val traces = tracesOf(turns, s)
        // 开关关掉「常驻面板」时不只是不通知：前台服务本身需要一条通知，
        // 所以退化成一行运行时状态（不带会话与思维链）。
        val panelOn = s.notificationsEnabled && !s.dnd && s.notifyOngoing
        // 常驻面板的职责就是「现在有什么在跑」——前台会话也要照常列出，
        // 否则用户点进会话看回复时面板就空了，正好丢掉他想看的思维链。
        // 「隐藏当前会话」只作用在「✓ 完成」那一行：人正盯着它，不用再提醒。
        val visible = if (panelOn) traces.filter {
            !(s.notifySuppressForeground && it.sessionId == NotificationCenter.foregroundSessionId &&
                it.phase == SessionTrace.Phase.Done)
        } else emptyList()
        // 只剩「✓ 完成」停留行时到点清掉，回到纯运行时状态行。
        val runningLeft = visible.any { it.phase != SessionTrace.Phase.Done }
        if (!runningLeft) {
            val lastDone = doneUntil.values.maxOrNull() ?: 0L
            if (lastDone > System.currentTimeMillis()) {
                scheduleHide(lastDone)
            } else {
                doneUntil.clear()
                lastTail.clear()
            }
        }
        val signature = buildString {
            append(runtime.javaClass.simpleName).append('|')
            append(panelOn).append(s.notifyShowThought).append(s.notifyThoughtLines)
            append(s.notifyInlineActions).append('|')
            visible.forEach {
                append(it.sessionId).append(':').append(it.phase).append(':')
                    .append(it.thought.hashCode()).append(':')
                    .append(it.permissions.map { p -> p.requestId }).append(':')
                    .append(it.options.joinToString(",") { o -> o.id }).append('|')
            }
        }
        // 思维链每来一个 token 都重发通知会把通知栏刷爆：内容没变就跳过。
        if (signature == lastSignature) return
        lastSignature = signature
        runCatching { post(visible, s) }
            .onFailure { AppLog.w("聚合通知失败：${it.message}") }
    }

    /**
     * 思维链是按 token 流进来的，一 token 一帧通知会被系统按速率丢弃
     * （logcat 里就是「Package enqueue rate is 5.4 … Shedding 0|dev.dsh.mobile.debug|1002」），
     * 连带的后果是「完成提醒」也被一起 shed 掉，用户根本收不到。
     * 所以常驻面板最多每 [MIN_RENDER_MS] 刷一次，中途的变化合并到下一次。
     */
    private fun scheduleRender(turns: Map<String, TurnState>, s: AppSettings) {
        val now = System.currentTimeMillis()
        val wait = now - lastRenderAt
        if (wait >= MIN_RENDER_MS) {
            renderJob?.cancel()
            renderJob = null
            lastRenderAt = now
            render(turns, s)
        } else if (renderJob?.isActive != true) {
            renderJob = scope.launch {
                delay(MIN_RENDER_MS - wait)
                lastRenderAt = System.currentTimeMillis()
                render(repo.turns.value, current)
            }
        }
    }

    /**
     * 从 turns 的变化里捕捉两类「一次性事件」并发系统通知：
     *  · 会话从「跑着」变成「不跑了」→ 完成提醒（可震动/横幅）；
     *  · 出现新的 permission 请求 → 待确认提醒。
     * 放在仓储流上而不是聊天页，是因为聊天页被销毁（返回列表/切页签/杀掉界面）
     * 之后 composable 的 LaunchedEffect 就没了，用户会彻底收不到完成提醒。
     */
    private fun notifyEdges(turns: Map<String, TurnState>, s: AppSettings) {
        val nowRunning = turns.filterValues { it.running }.keys
        val finished = lastRunning - nowRunning
        lastRunning = nowRunning
        if (finished.isNotEmpty()) {
            finished.forEach { sid ->
                val t = turns[sid]
                val snippet = t?.text?.trim().orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                    ?.take(80).orEmpty()
                runCatching {
                    notifierOf().notifyTurnComplete(
                        sessionId = sid,
                        title = titles[sid].orEmpty().ifBlank { "会话已完成" },
                        body = snippet.ifBlank { "本轮回复已完成" },
                    )
                }
            }
        }
        // 待确认：按 requestId 记账，同一条只提醒一次；请求消失后清账。
        val live = turns.values.flatMap { it.permissions }.map { it.requestId }.toSet()
        (alertedPerms - live).forEach { notifierOf().cancelPermission(it) }
        alertedPerms.retainAll(live)
        turns.flatMap { (sid, t) -> t.permissions.map { sid to it } }.forEach { (sid, perm) ->
            if (!alertedPerms.add(perm.requestId)) return@forEach
            runCatching {
                notifierOf().notifyPermission(
                    sessionId = sid,
                    requestId = perm.requestId,
                    agentName = perm.ownerAgentName.orEmpty(),
                    title = perm.ownerAgentName?.takeIf { it.isNotBlank() }?.let { "$it 请求确认" } ?: "需要确认",
                )
            }
        }
    }

    private fun tracesOf(turns: Map<String, TurnState>, s: AppSettings): List<SessionTrace> {
        val now = System.currentTimeMillis()
        val out = ArrayList<SessionTrace>(turns.size)
        turns.forEach { (sid, t) ->
            if (t.running || t.parts.any { it.text.isNotBlank() || it.thought.isNotBlank() }) {
                doneUntil.remove(sid)
                rememberTail(sid, t)
                t.toTrace(sid, s)?.let { out.add(it) }
            } else if (s.notifyKeepAfterDone) {
                val tail = lastTail[sid].orEmpty()
                if (tail.isBlank()) return@forEach
                // 尾巴上带 A/B/C 选项的回复要在通知里多留一会儿，否则用户
                // 刚看到通知、还没点，那一行连同按钮就一起消失了。
                val keep = if (optionsOfText(tail).isEmpty()) KEEP_DONE_MS else OPTION_KEEP_MS
                val until = doneUntil[sid]
                if (until == null) {
                    doneUntil[sid] = now + keep
                    out.add(doneTrace(sid, s, tail))
                } else if (until > now) {
                    out.add(doneTrace(sid, s, tail))
                }
            }
        }
        return out.sortedBy { it.since }
    }

    private fun rememberTail(sid: String, t: TurnState) {
        val tail = t.parts.lastOrNull { it.text.isNotBlank() }?.text?.ifBlank { null } ?: t.text
        if (tail.isNotBlank()) lastTail[sid] = tail
    }

    private fun doneTrace(sid: String, s: AppSettings, tail: String): SessionTrace = SessionTrace(
        sessionId = sid,
        title = titles[sid].orEmpty().ifBlank { "会话" },
        phase = SessionTrace.Phase.Done,
        // 完成那一行带上最后一句回复：通知栏里直接能读懂它答了什么，
        // 不用点进去。选项块已由 splitOptions 切掉，不会把 A/B 行当正文。
        thought = replyTail(tail),
        speaker = "",
        permission = null,
        since = System.currentTimeMillis(),
        options = optionsOfText(tail),
    )

    /**
     * 这条回复尾巴上的可选动作。
     *
     * 解析放在 core:common（[splitOptions]）里，和聊天页的选项气泡用同一套规则；
     * 群聊里取最后一个开口的成员，因为「选项是谁给的」在群组里必须是那个人。
     */
    /** 回复正文的最后一句（选项块已经切掉），最多 80 字。 */
    private fun replyTail(tail: String): String {
        if (tail.isBlank()) return ""
        val body = runCatching { splitOptions(tail).first }.getOrDefault(tail)
        // 通知里那一行要像人话：围栏代码块里的 HTML 源码（`<!doctype html>…`）
        // 一旦被选成「最后一句」，通知栏就会显示一整串标签。代码块整段丢掉，
        // 再跳过围栏、纯符号行，取最后一句真正的话。
        var inFence = false
        var last = ""
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence || line.isEmpty()) continue
            if (line.startsWith("<") || line.startsWith("</") || line.endsWith("/>")) continue
            if (line.none { it.isLetterOrDigit() || it.code > 0x2E80 }) continue
            last = line
        }
        return last.take(80)
    }

    private fun optionsOfText(tail: String): List<DshOption> {
        if (tail.isBlank()) return emptyList()
        return runCatching { splitOptions(tail).second }.getOrElse { emptyList() }
    }

    private fun scheduleHide(at: Long) {
        hideJob?.cancel()
        hideJob = scope.launch {
            // 上限按「带选项的停留期」算：夹到 KEEP_DONE_MS 会让带 A/B/C 的那条
            // 一路被重画、又把停留期重新算一遍，永远收不起来。
            delay((at - System.currentTimeMillis()).coerceIn(200L, OPTION_KEEP_MS))
            lastSignature = ""
            render(repo.turns.value, current)
        }
    }

    private fun TurnState.toTrace(sid: String, s: AppSettings): SessionTrace? {
        val perm = permission
        val activeParts = parts.filter { it.text.isNotBlank() || it.thought.isNotBlank() }
        // 群聊：取最后一个有思维链的成员当「当前思考者」。
        val speakerPart = parts.lastOrNull { it.thought.isNotBlank() } ?: parts.lastOrNull()
        val rawThought = when {
            activeParts.isNotEmpty() -> speakerPart?.thought.orEmpty()
            else -> thought
        }
        val running = running || activeParts.isNotEmpty()
        if (!running && perm == null) return null
        val phase = when {
            perm != null -> SessionTrace.Phase.Waiting
            tools.lastOrNull()?.status == ToolCall.ToolStatus.InProgress -> SessionTrace.Phase.Tool
            else -> SessionTrace.Phase.Thinking
        }
        return SessionTrace(
            sessionId = sid,
            title = titles[sid].orEmpty().ifBlank { "会话" },
            phase = phase,
            thought = tailLines(rawThought, s.notifyThoughtLines),
            speaker = speakerPart?.name.orEmpty(),
            permission = perm,
            permissions = permissions,
            since = startedAt,
        )
    }

    private fun post(traces: List<SessionTrace>, s: AppSettings) {
        val n = build(traces, s)
        val nm = context.getSystemService(NotificationManager::class.java)
        // 前台服务的通知由系统托管：同 ID 调 notify() 在部分 ROM 上会被忽略，
        // 必须再走一次 startForeground 才会刷新内容。服务起来了就用它。
        val updater = foregroundUpdater
        if (updater != null) {
            runCatching { updater(n) }.onFailure { AppLog.w("聚合通知更新失败：${it.message}") }
        }
        runCatching { nm.notify(ID_ONGOING, n) }
            .onFailure { AppLog.w("聚合通知失败：${it.message}") }
    }

    /** [dev.dsh.mobile.app.service.RuntimeService] 注入：用 startForeground 刷新自己那条通知。 */
    fun setForegroundUpdater(updater: ((Notification) -> Unit)?) {
        foregroundUpdater = updater
        lastSignature = ""
        render(repo.turns.value, current)
    }

    private fun build(traces: List<SessionTrace>, s: AppSettings, force: Boolean = false): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        // 同一 ID 只留一条：旧版另发 id=1001 的运行时通知，合并后统一在这里维护。
        runCatching { nm.cancel(LEGACY_RUNTIME_ID) }

        // 标题永远按「跑着的会话数」算：一个在跑就叫它的名字，多个就报个数。
        // 以前 running.size <= 1 时会把「已完成的」会话标题顶上来，看起来像那个会话
        // 还在跑；顺手在标题里带上并行数，不用展开也看得出有几个在干活。
        val running = traces.filter { it.phase != SessionTrace.Phase.Done }
        val title = when {
            running.isEmpty() && traces.isEmpty() -> "DSH"
            running.size <= 1 -> {
                val only = running.firstOrNull() ?: traces.first()
                only.title
            }
            else -> "${running.size} 个会话进行中"
        }
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentIntent(openIntent((running.firstOrNull() ?: traces.firstOrNull())?.sessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setGroup(GROUP)
            .setCategory(if (running.isEmpty()) Notification.CATEGORY_SERVICE else Notification.CATEGORY_PROGRESS)

        // 折叠态那一行必须是「正在跑的」，否则一个刚跑完的会话会把整条通知
        // 的正文顶成「✓ 完成」，看着像所有活儿都干完了。
        val ordered = traces.filter { it.phase != SessionTrace.Phase.Done } +
            traces.filter { it.phase == SessionTrace.Phase.Done }
        val lines = ArrayList<String>(ordered.size + 1)
        ordered.forEach { lines.add(formatLine(it, s)) }
        val status = runtimeLine()
        if (lines.isEmpty()) {
            builder.setContentText(status)
            builder.setStyle(Notification.BigTextStyle().bigText(status))
            return builder.build()
        }
        builder.setContentText(lines.first())
        val inbox = Notification.InboxStyle().setBigContentTitle(title)
        lines.forEach { inbox.addLine(it) }
        if (ordered.size > 6) inbox.setSummaryText("还有 ${ordered.size - 6} 个…")
        builder.setStyle(inbox)

        // 需要选择：把选项直接挂成通知按钮（最多 3 个，多的引导进应用）。
        val waiting = traces.firstOrNull { it.permission != null }
        if (waiting != null && s.notifyInlineActions) {
            val perm = waiting.permission!!
            perm.options.take(3).forEach { opt ->
                val pi = PendingIntent.getBroadcast(
                    context,
                    (perm.requestId + opt.optionId).hashCode(),
                    Intent(context, NotificationActionReceiver::class.java)
                        .setAction(NotificationActionReceiver.ACTION_ANSWER)
                        .setData(Uri.Builder().scheme("dsh-permission").authority("answer")
                            .appendPath(waiting.sessionId).appendPath(perm.requestId).appendPath(opt.optionId).build())
                        .putExtra(NotificationActionReceiver.EXTRA_SESSION, waiting.sessionId)
                        .putExtra(NotificationActionReceiver.EXTRA_REQUEST, perm.requestId)
                        .putExtra(NotificationActionReceiver.EXTRA_OPTION, opt.optionId)
                        .putExtra(NotificationActionReceiver.EXTRA_OWNER, perm.ownerAgentId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val label = if (perm.ownerAgentName.isNullOrBlank()) opt.name else "${opt.name}·${perm.ownerAgentName}"
                builder.addAction(Notification.Action.Builder(null, label, pi).build())
            }
            // 「其他」跳进会话自己打字：前台服务通知不能带 RemoteInput（Android 12+ 会
            // 直接判成 Bad notification for startForeground 把进程干掉），所以走 Activity。
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "其他",
                    openIntent(waiting.sessionId),
                ).build(),
            )
        }

        // 回复尾巴上带 A/B/C 的时候也挂成按钮：模型问「要不要继续」时，
        // 用户不该为了点一个字母先解锁、找应用、翻到那个会话。
        // 权限卡优先（上面已经挂过），这里只在没有权限卡时补。
        val pickable = traces.lastOrNull { it.options.isNotEmpty() }
        if (waiting == null && pickable != null && s.notifyInlineActions) {
            pickable.options.take(3).forEach { opt ->
                val pi = PendingIntent.getBroadcast(
                    context,
                    (pickable.sessionId + opt.id).hashCode(),
                    Intent(context, NotificationActionReceiver::class.java)
                        .setAction(NotificationActionReceiver.ACTION_PICK)
                        .setData(Uri.Builder().scheme("dsh-permission").authority("pick")
                            .appendPath(pickable.sessionId).appendPath(opt.id).appendPath(opt.value).build())
                        .putExtra(NotificationActionReceiver.EXTRA_SESSION, pickable.sessionId)
                        .putExtra(NotificationActionReceiver.EXTRA_OPTION, opt.value),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                // 按钮上只写 A/B 等于让用户盲选：把选项内容一起带上（通知按钮
                // 单行会被截断，所以截到 18 个字，剩下的进会话里看）。
                val brief = opt.value.replace('\n', ' ').take(18)
                val label = if (brief.isBlank()) opt.label else "${opt.label} ${brief}"
                builder.addAction(Notification.Action.Builder(null, label, pi).build())
            }
        }
        return builder.build()
    }

    private fun runtimeLine(): String = when (val r = runtime) {
        is RuntimeState.Ready -> "运行时就绪 · ${r.transport}"
        is RuntimeState.Starting -> "运行时启动中 ${r.detail}"
        is RuntimeState.Reconnecting -> "重连中 #${r.attempt}"
        is RuntimeState.Failed -> "连接失败：${r.reason}"
        RuntimeState.NotConfigured -> "未配置运行时 · 到设置里选择内置运行时或连接地址"
    }

    private fun formatLine(t: SessionTrace, s: AppSettings): String {
        val head = when (t.phase) {
            SessionTrace.Phase.Waiting -> "⏸ 等你确认"
            SessionTrace.Phase.Tool -> "⚙ 执行中"
            SessionTrace.Phase.Done -> "✓ 完成"
            SessionTrace.Phase.Thinking -> "… 思考中"
        }
        val who = if (t.speaker.isBlank() || t.speaker == t.title) "" else "${t.speaker} "
        val body = when {
            // 完成行里的 thought 是「最后一句回复」，跟运行中的思维链不是一回事，
            // 分别由「回复摘要」和「显示思维链」两个开关管。
            t.phase == SessionTrace.Phase.Done ->
                if (s.notifyReplySnippet) t.thought else ""
            s.notifyShowThought && t.thought.isNotBlank() -> t.thought
            t.permission != null -> t.permission.title
            else -> ""
        }.replace('\n', ' ')
        return if (body.isBlank()) "${t.title} ·$head" else "${t.title} ·$head $who$body"
    }

    private fun tailLines(text: String, max: Int): String {
        if (text.isBlank()) return ""
        val lines = text.trim().lines().filter { it.isNotBlank() }
        val head = lines.takeLast(max.coerceAtLeast(1)).joinToString(" ")
        return head.take(220)
    }

    /** 从通知里答完一次确认后强制重画一遍（不然要等下一次 turns 变化）。 */
    fun refresh() {
        lastSignature = ""
        render(repo.turns.value, current)
    }

    /** 彻底收起常驻通知（用户关掉「常驻通知」开关时才会走到这里）。 */
    fun cancel() {
        doneUntil.clear()
        lastSignature = "canceled"
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_ONGOING) }
    }

    private fun ensureChannel(nm: NotificationManager) {
        // 这条通道绑着前台服务，系统不允许删——删了也不会生效，只会刷警告日志。
        // 因此只在缺失时创建，重要性固定为 DEFAULT（无声音、无震动）。
        if (nm.getNotificationChannel(CHANNEL_ONGOING) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "进行中的会话", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "常驻展示正在思考的会话与思维链"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private fun openIntent(sessionId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        sessionId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (sessionId != null) putExtra(NotificationCenter.EXTRA_SESSION_ID, sessionId) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ONGOING = "dsh_ongoing"
        /** 全应用唯一的常驻通知 ID（前台服务通知也用它）。 */
        const val ID_ONGOING = 1002
        const val GROUP = "dsh_group"
        const val KEY_TEXT = "dsh.inline.text"
        /** 面板刷新的最短间隔：太快会被系统按速率丢弃。 */
        private const val MIN_RENDER_MS = 1_200L
        /** 会话做完后「✓ 完成」在面板上停留的时间。 */
        const val KEEP_DONE_MS = 8_000L
        /** 做完但尾巴上带选项时，面板连同按钮停留的时间（够用户从口袋里掏出手机）。 */
        const val OPTION_KEEP_MS = 60_000L
        /** 1.20 及以前独立的运行时通知，升级后清掉。 */
        private const val LEGACY_RUNTIME_ID = 1001
    }
}
