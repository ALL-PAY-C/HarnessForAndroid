package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 执行池对外广播的事件。 */
sealed interface AgentEvent {
    data class TaskQueued(val task: AgentTask) : AgentEvent
    data class TaskStarted(val task: AgentTask) : AgentEvent
    data class TaskFinished(val task: AgentTask, val output: String) : AgentEvent
    data class TaskFailed(val task: AgentTask, val error: String) : AgentEvent
    data class PermissionRequested(val taskId: String, val request: PermissionRequest) : AgentEvent
    data class PermissionResolved(val taskId: String, val requestId: String, val optionId: String?) : AgentEvent
}

/**
 * 执行池统计。
 *
 * [promptCharsTotal] / [digestCharsTotal] 是「省 token」的可观测口径：
 * 前者是真正发给模型的上下文字符数，后者是「如果按全量历史发」多出来的那部分。
 */
data class PoolStats(
    val queued: Int = 0,
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val promptCharsTotal: Long = 0,
    val digestCharsTotal: Long = 0,
)

/** 任务是否已经不会再变化。Skipped 也算终态，否则下游会无限等一个永不完结的依赖。 */
internal fun isTerminalTaskState(state: TaskState): Boolean =
    state == TaskState.Done || state == TaskState.Failed ||
        state == TaskState.Cancelled || state == TaskState.Skipped

/**
 * 多 Agent 执行池：有界并行 + 依赖门控 + 失败隔离。
 *
 * - 事件驱动调度：任务入队 / 完成 / 失败 / 取消都会立刻触发一次 [schedulePump]，
 *   不靠轮询 sleep；
 * - 同时最多跑 [maxParallel] 个任务；
 * - 同一 session 内同一 agentId 同时只允许 1 个 Running（群聊里同一个人不会自相矛盾地并行发言）；
 * - [AgentTask.dependsOn] 全部 Done 才启动；任一依赖 Failed/Cancelled/Skipped → 下游 Skipped（不无限等待）；
 * - 单任务异常只把它自己置 Failed，其它任务照常跑；
 * - 状态同步到 [tasks] / [agents] / [stats]，事件走 [events]。
 */
class ExecutionPool(
    private val scope: CoroutineScope,
    /** 同时运行的任务上限。群聊里 3 个成员并行足够，再多就是白烧 token。 */
    maxParallelSetting: Int = 3,
    private val permissionEvent: suspend (DshEvent) -> Unit = {},
    private val runner: suspend (AgentTask) -> String,
) {
    /** 可在运行时调整的并行上限（群组设置面板改完立刻生效）。 */
    @Volatile
    var maxParallelSetting: Int = maxParallelSetting.coerceIn(1, 8)

    private val maxParallel: Int get() = maxParallelSetting

    /** 权限选择的结果：只认发起者，别的 Agent 拿来会被拒。 */
    sealed interface PermissionOutcome {
        data object Accepted : PermissionOutcome
        data class Rejected(val reason: String) : PermissionOutcome
        data object Unknown : PermissionOutcome
    }

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    private val _agents = MutableStateFlow<Map<String, AgentRuntimeState>>(emptyMap())
    val agents: StateFlow<Map<String, AgentRuntimeState>> = _agents.asStateFlow()

    private val _stats = MutableStateFlow(PoolStats())
    val stats: StateFlow<PoolStats> = _stats.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val lock = Any()
    private val completions = HashMap<String, CompletableDeferred<Unit>>()
    private val waiting = HashMap<String, CompletableDeferred<String?>>()
    private val owners = HashMap<String, AgentTask>()

    /** taskId -> 正在跑它的协程。取消要能精确 cancel，不能按 session 覆盖。 */
    private val jobs = HashMap<String, Job>()

    private var pumping = false
    private var pumpAgain = false
    private var promptChars = 0L
    private var digestChars = 0L

    // ---------------------------------------------------------------- 派活

    /** 派一个任务，立刻返回 taskId（不等它跑完）。 */
    fun submitOne(sessionId: String, task: AgentTask): String =
        submit(sessionId, listOf(task)).firstOrNull().orEmpty()

    /**
     * 派一批任务，立刻返回它们的 taskId。
     *
     * 依赖关系先做拓扑排序入队，真正的启动时机由 [pumpOnce] 决定；
     * 要等它们全部结束用 [await]。
     */
    fun submit(sessionId: String, tasks: List<AgentTask>): List<String> {
        if (tasks.isEmpty()) return emptyList()
        val ordered = orderByDependencies(tasks).map {
            if (it.sessionId.isBlank()) it.copy(sessionId = sessionId) else it
        }
        synchronized(lock) {
            ordered.forEach { task ->
                _tasks.value = _tasks.value + task
                completions.getOrPut(task.id) { CompletableDeferred() }
            }
        }
        ordered.forEach { emit(AgentEvent.TaskQueued(it)) }
        refreshStats()
        schedulePump()
        return ordered.map { it.id }
    }

    /** 等到该 session 下没有非终态任务为止（含等依赖、等权限）。 */
    suspend fun await(sessionId: String) {
        while (true) {
            val pending = synchronized(lock) {
                _tasks.value
                    .filter { it.sessionId == sessionId && !isTerminalTaskState(it.state) }
                    .map { completions.getOrPut(it.id) { CompletableDeferred() } }
            }
            if (pending.isEmpty()) return
            pending.forEach { it.await() }
        }
    }

    /** 取消某个会话下所有未结束的任务：Running 的协程直接 cancel，Queued 的置 Cancelled。 */
    fun cancel(sessionId: String) {
        val targets = _tasks.value.filter {
            it.sessionId == sessionId && !isTerminalTaskState(it.state)
        }
        val toCancel = synchronized(lock) { targets.mapNotNull { jobs[it.id] } }
        // 先同步改状态：cancel() 返回后就不该再有 Running（协程收尾是异步的）。
        targets.forEach { finish(it, TaskState.Cancelled, null, "已取消") }
        toCancel.forEach { it.cancel() }
        releaseWaiting(sessionId)
    }

    /** 上下文预算上报：本次实际发出去的字符数，以及「按全量历史发」会是多少。 */
    fun reportContext(usedChars: Int, fullChars: Int) {
        synchronized(lock) {
            promptChars += usedChars.coerceAtLeast(0)
            digestChars += (fullChars - usedChars).coerceAtLeast(0)
        }
        refreshStats()
    }

    /**
     * 任务等待用户确认：任务置 WaitingPermission，返回用户选的 optionId（null = 取消）。
     */
    suspend fun requestPermission(task: AgentTask, request: PermissionRequest): String? {
        val owned = request.copy(ownerAgentId = task.agentId, ownerAgentName = task.agentName)
        val deferred = CompletableDeferred<String?>()
        synchronized(lock) {
            waiting[request.requestId] = deferred
            owners[request.requestId] = task
        }
        current(task)?.let { updateTask(it.copy(state = TaskState.WaitingPermission)) }
        setAgent(task, AgentRunState.WaitingPermission, "等待用户确认")
        return try {
            emit(AgentEvent.PermissionRequested(task.id, owned))
            permissionEvent(DshEvent.Permission(task.sessionId, owned))
            withTimeoutOrNull(180_000L) { deferred.await() }.also {
                current(task)?.takeIf { it.state == TaskState.WaitingPermission }?.let { current ->
                    updateTask(current.copy(state = TaskState.Running))
                    setAgent(task, AgentRunState.Thinking, "权限等待结束，继续执行")
                }
            }
        } finally {
            synchronized(lock) {
                waiting.remove(request.requestId)
                owners.remove(request.requestId)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                permissionEvent(DshEvent.PermissionResolved(task.sessionId, request.requestId))
            }
        }
    }

    /**
     * 用户点了某个权限卡。只对发起者任务生效；[agentId] 传了但不是发起者时返回
     * [PermissionOutcome.Rejected]（「不是你的提问，忽略」）。
     */
    fun resolvePermission(
        requestId: String,
        optionId: String?,
        agentId: String? = null,
        sessionId: String? = null,
    ): PermissionOutcome {
        val task = synchronized(lock) { owners[requestId] } ?: return PermissionOutcome.Unknown
        if (sessionId != null && sessionId != task.sessionId) return PermissionOutcome.Unknown
        if (agentId != null && agentId != task.agentId) {
            return PermissionOutcome.Rejected("不是你的提问，忽略")
        }
        val deferred = synchronized(lock) { waiting.remove(requestId) } ?: return PermissionOutcome.Unknown
        if (!deferred.complete(optionId)) return PermissionOutcome.Unknown
        current(task)?.takeUnless { isTerminalTaskState(it.state) }?.let {
            updateTask(it.copy(state = TaskState.Running))
            setAgent(task, AgentRunState.Thinking, "权限已确认，继续执行")
            emit(AgentEvent.PermissionResolved(task.id, requestId, optionId))
        }
        return PermissionOutcome.Accepted
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 请求一次调度。
     *
     * 任务完成会从 [start] 的 finally 里回调这里，所以「依赖唤醒」是事件驱动的：
     * 上游一结束就立刻重排，不需要定时器。
     */
    private fun schedulePump() {
        synchronized(lock) {
            if (pumping) {
                pumpAgain = true
                return
            }
            pumping = true
        }
        try {
            do {
                synchronized(lock) { pumpAgain = false }
                pumpOnce()
            } while (synchronized(lock) { pumpAgain })
        } finally {
            synchronized(lock) { pumping = false }
        }
    }

    private fun pumpOnce() {
        // 1) 依赖已经死掉（失败/取消/跳过）的下游直接 Skipped。Skipped 也是终态，级联传播。
        while (true) {
            val dead = synchronized(lock) {
                _tasks.value.filter { it.state == TaskState.Queued && hasDeadDependency(it) }
            }
            if (dead.isEmpty()) break
            dead.forEach { finish(it, TaskState.Skipped, "上游任务失败或已取消，跳过", null) }
        }
        // 2) 在并行上限内启动依赖已满足的任务。
        val batch = synchronized(lock) { pickRunnable() }
        batch.forEach { start(it) }
        refreshStats()
    }

    /** 依赖里有「终态但不是 Done」的 → 这条永远等不到了。 */
    private fun hasDeadDependency(task: AgentTask): Boolean {
        val snap = _tasks.value
        return task.dependsOn.any { dep ->
            val state = snap.firstOrNull { it.id == dep }?.state
            state != null && state != TaskState.Done && isTerminalTaskState(state)
        }
    }

    /** 依赖全 Done（或依赖根本不在池子里，视为已满足）才算可跑。 */
    private fun depsDone(task: AgentTask, snap: List<AgentTask>): Boolean =
        task.dependsOn.all { dep ->
            val state = snap.firstOrNull { it.id == dep }?.state
            state == null || state == TaskState.Done
        }

    private fun pickRunnable(): List<AgentTask> {
        val snap = _tasks.value
        var running = snap.count { it.state == TaskState.Running }
        val out = ArrayList<AgentTask>()
        for (task in snap) {
            if (running >= maxParallel) break
            if (task.state != TaskState.Queued) continue
            if (!depsDone(task, snap)) continue
            // 同一 session 同一 agent 同时只跑一个：第二个排队，避免同一个人自相矛盾地并行发言。
            val busy = snap.any {
                it.state == TaskState.Running &&
                    it.sessionId == task.sessionId &&
                    it.agentId == task.agentId
            }
            if (busy) continue
            out += task
            running++
        }
        return out
    }

    private fun start(task: AgentTask) {
        val cur = _tasks.value.firstOrNull { it.id == task.id } ?: return
        if (cur.state != TaskState.Queued) return
        val started = cur.copy(state = TaskState.Running, startedAt = System.currentTimeMillis())
        updateTask(started)
        setAgent(started, AgentRunState.Thinking, "开始执行")
        emit(AgentEvent.TaskStarted(started))
        // LAZY + 先登记 job 再 start：避免协程同步跑完时 jobs 里还留着一个已结束的句柄。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val output = runner(started)
                val done = started.copy(state = TaskState.Done, finishedAt = System.currentTimeMillis())
                updateTask(done)
                setAgent(started, AgentRunState.Done, "已完成")
                emit(AgentEvent.TaskFinished(done, output))
            } catch (c: CancellationException) {
                finish(started, TaskState.Cancelled, null, "已取消")
                if (!currentCoroutineContext().isActive) throw c
            } catch (t: Throwable) {
                // 失败文本同时进 task.error 与 TaskFailed 事件，UI 能直接看到；
                // 这里只补一行进程日志（不引 android.util.Log，纯 JVM 单测可跑）。
                System.err.println("[ExecutionPool] task " + started.id + " failed: " + t.message)
                finish(started, TaskState.Failed, t.message ?: "执行失败", null)
            } finally {
                synchronized(lock) { jobs.remove(started.id) }
                complete(started.id)
                // 事件驱动的依赖唤醒：本条一结束立刻重排，下游不用轮询等。
                schedulePump()
            }
        }
        synchronized(lock) { jobs[started.id] = job }
        job.start()
    }

    // ---------------------------------------------------------------- 状态

    private fun finish(task: AgentTask, state: TaskState, error: String?, note: String?) {
        val cur = _tasks.value.firstOrNull { it.id == task.id } ?: return
        if (isTerminalTaskState(cur.state)) return
        val done = cur.copy(state = state, error = error, finishedAt = System.currentTimeMillis())
        updateTask(done)
        setAgent(
            done,
            if (state == TaskState.Failed) AgentRunState.Failed else AgentRunState.Idle,
            note ?: error.orEmpty(),
        )
        when (state) {
            TaskState.Failed -> emit(AgentEvent.TaskFailed(done, error ?: "执行失败"))
            TaskState.Cancelled, TaskState.Skipped -> emit(AgentEvent.TaskFailed(done, error ?: state.name))
            else -> emit(AgentEvent.TaskFinished(done, ""))
        }
        complete(task.id)
        schedulePump()
    }

    private fun updateTask(task: AgentTask) {
        synchronized(lock) {
            _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        }
        refreshStats()
    }

    private fun refreshStats() {
        val snap = _tasks.value
        _stats.value = PoolStats(
            queued = snap.count { it.state == TaskState.Queued },
            running = snap.count { it.state == TaskState.Running },
            done = snap.count { it.state == TaskState.Done },
            failed = snap.count { it.state == TaskState.Failed },
            cancelled = snap.count { it.state == TaskState.Cancelled || it.state == TaskState.Skipped },
            promptCharsTotal = synchronized(lock) { promptChars },
            digestCharsTotal = synchronized(lock) { digestChars },
        )
    }

    private fun current(task: AgentTask): AgentTask? = _tasks.value.firstOrNull { it.id == task.id }

    private fun setAgent(task: AgentTask, state: AgentRunState, note: String) {
        synchronized(lock) {
            _agents.value = _agents.value + (
                task.agentId to AgentRuntimeState(
                    agentId = task.agentId,
                    agentName = task.agentName,
                    state = state,
                    taskId = task.id,
                    since = System.currentTimeMillis(),
                    note = note,
                )
                )
        }
    }

    private fun releaseWaiting(sessionId: String) {
        synchronized(lock) {
            owners.filterValues { it.sessionId == sessionId }.keys.mapNotNull { waiting.remove(it) }
        }.forEach { it.complete(null) }
    }

    private fun complete(id: String) {
        synchronized(lock) { completions[id] }?.complete(Unit)
    }

    private fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }

    /** 依赖先入队：保证 pump 看到的是「依赖在前」的顺序，同轮唤醒不会乱序。 */
    private fun orderByDependencies(tasks: List<AgentTask>): List<AgentTask> {
        val byId = tasks.associateBy { it.id }
        val out = ArrayList<AgentTask>(tasks.size)
        val seen = HashSet<String>()
        fun visit(task: AgentTask, depth: Int) {
            if (depth > 32 || !seen.add(task.id)) return
            task.dependsOn.mapNotNull { byId[it] }.forEach { visit(it, depth + 1) }
            out += task
        }
        tasks.forEach { visit(it, 0) }
        return out
    }
}
