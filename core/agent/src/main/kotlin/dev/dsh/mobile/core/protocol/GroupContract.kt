package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.GroupLedgerSink
import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.protocol.group.GROUP_SCHEDULER_LEGACY

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
