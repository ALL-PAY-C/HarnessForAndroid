package dev.dsh.mobile.core.model

/** 单个 Agent 的运行态（执行池对外可见）。 */
enum class AgentRunState { Idle, Thinking, ToolRunning, WaitingPermission, Done, Failed }

/** 执行池里的任务状态机：Queued -> Running -> (WaitingPermission) -> Done/Failed/Cancelled。 */
enum class TaskState { Queued, Running, WaitingPermission, Done, Failed, Cancelled, Skipped }

/**
 * 一次派给某个 Agent 的执行任务。群聊里 @ 多个 Agent 会拆成多个任务，
 * [dependsOn] 指向必须先完成的任务 id（依赖唤醒）。
 */
data class AgentTask(
    val id: String,
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val prompt: String,
    val state: TaskState = TaskState.Queued,
    val dependsOn: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val error: String? = null,
    /**
     * 编排层给的额外角色指令（自动机里「这一轮你执行 / 你验收」）。
     * 为空表示按 [dependsOn] 推断接力语义，群聊的普通轮转不受影响。
     */
    val hint: String = "",
)

/** 执行池里某个 Agent 的实时状态，UI 用它显示“谁在执行什么”。 */
data class AgentRuntimeState(
    val agentId: String,
    val agentName: String,
    val state: AgentRunState = AgentRunState.Idle,
    val taskId: String? = null,
    val since: Long = 0L,
    val note: String = "",
)
