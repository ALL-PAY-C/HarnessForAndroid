# 群聊房间账本（T5.3 外环实现规格）

> 承接 docs/design/group-orchestration-v2.md 第 5 节（Ledger 只停在抽象层，从未产出）。
> 本文件把「账本到底装什么、谁写、谁读、怎么不膨胀」定死，实现按此进行。

## 1. 为什么需要它

现在的主持人每轮只看 transcript + 成员状态 + 空账本 + 预算，因此：

- 同一个待办会被反复讨论（没人记住它还没做完）；
- 被打回的意见下一轮消失（打回计数与重做依据只在自动机分支里）；
- 用户第二轮追问「刚才那个还没做」时，房间已经换了一个全新的上下文；
- 成员之间看不到彼此领了什么活（pendingTasks 现在靠 openTasks.count { it.contains(name) }，而 openTasks 恒空）。

账本是房间的唯一事实来源：任务、结论、未解问题、每人用量都进账本，主持人只读它。

## 2. 数据模型（core/protocol/.../group/RoomLedger.kt）

enum class LedgerState { Open, Done, Reworked, Dropped }

data class LedgerItem(
    val id: String,          // 任务 id（= AgentTask.id）；问题/事实用 q-<digest8> / f-<digest8>
    val title: String,       // 进主持人输入的一行，已 compress 到 <=120 字
    val ownerId: String?,    // 成员 id，用于 MemberState.pendingTasks；问题/事实为 null
    val state: LedgerState,
    val round: Int,          // 创建轮次
)

class RoomLedger(private val sessionId: String) {
    fun mergeTasks(tasks: List<AgentTask>, round: Int)      // 入池即记账，标题取 task.prompt 首行
    fun markDone(taskIds: Collection<String>)
    fun markRework(taskIds: Collection<String>)
    fun addQuestion(text: String, round: Int)               // 去重 + 上限
    fun addFact(text: String, round: Int)
    fun view(): LedgerView                                  // 给 RoomView 的只读快照
    fun digest(): String                                    // 开会/散会播报的一行
    fun openTasksOf(ownerId: String): Int
}

上限（超过就丢最旧的）：open 任务 20、未解问题 10、事实 20。去重键 = 去空白的标题前 60 字。

## 3. 谁写账本（全部落在 GroupRunner.kt）

| 时机 | 写入 | 落点 |
|---|---|---|
| dispatchGroupTasks(sessionId, tasks) 派活前 | mergeTasks(tasks, round) | GroupRunner.kt:714 |
| submitSubagentTasks(...) 子任务进池 | mergeTasks(subTasks, round) | GroupRunner.kt:850 |
| 一轮跑完且 taskSucceeded(id) 为真 | markDone(ids) | 每轮 outputs 汇总处 |
| 自动机验收打回 | markRework(ids) | runAutomaton 打回分支 |
| 每轮成员输出扫描 | addQuestion / addFact | 每轮 outputs 处（保守抽取，见第 4 节） |

## 4. 从自由文本抽问题/事实（保守，宁可漏）

- 按行切（split 换行符），单行长度 8..200 才看。
- 问题：行尾是问号，或含 待确认 / 需要确认 / 待补充 / 缺信息。每轮最多 2 条。
- 事实：行首或冒号后含 结论 / 确认 / 已修复 / 根因。每轮最多 2 条。
- 一律过 digest(text, 120) 再进账本；isNoAdd 文本（「（无补充）」等）直接跳过。

## 5. 谁读账本

- roomViewOf(...) 改成读 ledger.view()（GroupRunner.kt:353-393），MemberState.pendingTasks 改成 ledger.openTasksOf(m.id)，不再用 contains(name) 猜。
- 主持人 prompt 里已带 LedgerView.summary()；不改格式，只是现在有真内容。
- 群聊 notice：
  - 房间开场若账本非空：[账本] 未完成 N 条：a；b；c（上一轮遗留）；
  - 散会前：[账本] 本轮完成 X 条，未完成 Y 条（成员分布：m1 2 / m2 1），打回 Z 次。
- 自动机档（runAutomaton）也读同一账本：开场任务直接 mergeTasks，Done 时 markDone。

## 6. 跨轮存活（外环的实质）

账本按 sessionId 存在 GroupRunner 的 mutableMapOf<String, RoomLedger>() 里，跨用户轮不重建；forget(sessionId)（GroupRunner.kt:875）里一并清掉。这样用户在第二句话问「刚才那两件做完了吗」时，主持人手里真有东西。

内存上限：单房间 50 条 item 总量（20+10+20），一个会话几十 KB 封顶；会话删除即释放。

## 7. UI（feature/chat/GroupUi.kt，只加不改）

- 成员胶囊行右侧加一枚「待办 N」胶囊（p.warn 底，N=0 不显示），点击展开 LedgerSheet；
  - 三组：未完成（含负责人）、未解问题、已完成（折叠，显示最近 5 条）；
  - 每项右侧状态色点：Open 蓝、Reworked 琥珀、Done 绿。
- 账本数据走 RoomView.ledger 已有的注入路径到 ChatScreen，不新开全局状态；没有账本时组件不渲染（零视觉变化）。

## 8. 验收（必须全部满足）

1. :core:protocol:testDebugUnitTest 全绿；新增 RoomLedgerTest 至少 8 例：去重、上限淘汰、状态流转（Done/Rework 互斥）、openTasksOf 计数、view() 字段映射、空账本 digest() 不崩。
2. 主持人输入里能出现账本摘要：mock 网关日志里 roles=moderator 的请求 body 含 未完成任务。
3. 群聊第二轮用户消息后，开场 notice 出现 [账本] 未完成。
4. GroupSupport.kt 与 group/*Scheduler.kt 的 git diff 为空（T5.2 的纯函数群不动）。
5. 账本 item 标题不含完整成员上下文、不含模型名（进主持人输入的只能是摘要）。

## 9. 不做的事

- 不做持久化到磁盘：房间账本是运行期结构，历史留给会话消息。
- 不做账本驱动的自动派活（谁是 owner 由 planGroupRound 决定，账本只记账）。
- 不改 RoomView / LedgerView 的字段（LedgerView 已有 openTasks/openQuestions/doneCount/reworkCount，够用）。
