# 群聊编排 v2 设计规范（T5.1）

> 作者：调度 Agent ｜ 依据：`research/A2-surface.md` 第 7–8 节实测、`research/ext/meeting.md`、`research/ext/orch.md`、`research/C-dshgo.md`（dsbot 宿主插件模式）
> 约束：不破坏现有群聊观感与交互理念（R14）；数据可迁移（D5）；不引入新第三方依赖。

## 1. 目标与非目标

**目标**
1. 群聊能真正完成「一个人搞不定」的多步任务：先分工、再各自执行、最后汇总，且**能嵌套**（成员可以再派子代理）。
2. 发言顺序由主持人按信息增益决定，避免 O(N·R²) 全连接广播。
3. 任何失控（跑飞、死循环、烧钱）都有硬闸门，且用户在界面上能看见原因。

**非目标**
- 不重写消息存储与 UI 渲染层（保留现有 message 表与渲染管线）。
- 不引入 AutoGen/CrewAI 之类外部框架；只借其模式。
- 不做「同一群里 N 个模型同时流式输出」的视觉花活。

## 2. 现状根因（14 条缺陷归为 4 类）

| 根因 | 代表缺陷（行号） | 后果 |
| --- | --- | --- |
| A 静默截断 | `MAX_GROUP_SPEAKERS=4`（`GroupSupport.kt:549`） | 5 人以上群永远有人不发言，且无人知道 |
| B 顺序污染 | `parallelSplitTasks` 依赖 `orderForTurn`（`:558`），`orderForTurn` 按 idx 轮转（`:480`） | 并行分片随发言顺序漂移，结果不可复现 |
| C 终止条件贫乏 | 只有 ≤20 轮 / `Verdict.Done` / 连续打回 2；`automatonVerdict` 认不出静默 Done（`:611`） | 跑飞无兜底，该停不停 |
| D 无主控/子任务 | `subagent`（`LocalEngineTransport.kt:2211`）只回文本、`SUBAGENT_TOOLS`（`:2622`）不含 `subagent` | **深度恒为 1**，superagent 无从谈起 |

另有计数状态机缺陷（打回计数不清零 `LocalEngineTransport.kt:1192-1200`、`lastSpeaker` 不更新 `:1143-1210`）属于「状态该由编排层单一持有」的证据，随 v2 一并消除。

## 3. 领域模型（新增 5 个概念，全部落 `core/model`）

```
Room        群聊房间 = 成员集合 + 预算 + 账本
Member      房间成员 = 一个独立 agent 会话的绑定（现有 group member 概念扩展）
Turn        一次发言单元 = memberId + 触发原因(Trigger) + 产出(Message | AgentTask[] | Verdict)
AgentTask   结构化任务 = id / goal / owner(memberId) / deps / status / artifacts / 预算
Ledger      房间账本 = 任务列表 + 已知事实 + 未解决问题 + 每成员 token 用量
```

**关键决定**：`AgentTask` 与 `Turn` 分离。这是修根因 D 的最小改动——`subagent` 目前只回文本，改造成「回文本 + 可选产 AgentTask」，编排层即可看见并调度子代理。

## 4. 调度算法：brief-then-moderated

### Phase A · Brief（首轮固定）
- 全体成员各发言一次，顺序按 **memberId 的 code-unit 排序**（可复现、可审计，不用随机也不用 join 顺序）。
- 每人的 brief 限制在 N 字（默认 300 token），要求：能做什么 / 缺什么信息 / 打算怎么干。
- 理由：第 1 轮人人有输出时信息量最大，成本却是线性的（N 次调用）。

### Phase B · Moderated（后续由主持人决定）
主持人（Moderator）约束：
- 模型 = 召集者会话的模型（不额外指定旗舰模型，避免成本失控）。
- **不持有任何与会者的上下文**；输入只有四样：群聊记录（超长按「最近 K 轮 + 摘要」裁剪）、成员名单与状态（已发言/未发言/被阻断/上次发言轮次）、Ledger 摘要（未解决问题 + 未完成任务）、剩余预算（轮次/token/时间）。
- 输出必须是结构化决策：`{ action: next_speaker | roundtable | done | escalate | ask_user, memberId?, reason, n? }`。
- 决策记录进账本，界面可见（用户能问责「为什么又让它说话」）。

**发言优先级（数值化，替代现有 @→名字→RoundRobin 的串行 if）**：
```
强制 @点名        1000
用户直呼名字        500
账本里有它的待办     300 + 未完成数
未发言过            200
与上一发言人不同      +50（防同一人连续占麦）
更久没说话           + 轮次间隔
```

### 并行档（Parallel）
- 分片**按 taskId 稳定序**切分，与发言顺序彻底解耦（修根因 B）。
- 并行度上限 = min(成员数, 4)，超出的任务排队进执行池。

### 截断必须显式（修根因 A）
- `MAX_GROUP_SPEAKERS` 超限时不再 `take(4)` 静默丢弃，改为产生一条系统消息 `RoundSplit`：「本轮 X 人未发言，已排入下一轮」。

## 5. 终止条件（可组合，AND/OR 树）

| 条件 | 参数 | 说明 |
| --- | --- | --- |
| `MaxRounds` | 默认 8（不再是 20） | 兜底 |
| `GlobalBudget` | token / 墙钟时间 | **新增**，房间级预算，超限强制 `done` + 降级为 brief-only |
| `VerdictDone` | — | 主持人判定或全员一致 |
| `SilenceAllButOne` | — | 连续 n 轮除一人外无人有新增信息（**自定义条件**，AutoGen 无内建） |
| `StallDetector` | 窗口 2 轮 | 消息相似度 > 阈值 + 无新任务 → 判卡死，触发重规划 |
| `UserInterrupt` | — | 用户消息优先级最高，直接插话并可改主持人决策 |

组合方式：`OR(MaxRounds, GlobalBudget, UserInterrupt, AND(VerdictDone, 无未完成任务))`。

## 6. 子任务与执行池（修根因 D，superagent 的落点）

1. `subagent` 工具返回值扩展：`{ text, tasks?: AgentTask[] }`。
2. `SUBAGENT_TOOLS` 加入 `subagent`，但受两道闸门：**深度上限**（默认 2，可配置）+ **预算分摊**（子代理消耗记入父任务）。
3. 执行池（`core/model` 新组件）：容量上限、排队、可取消、状态可查。子代理产出的 task 进池，主持人可引用池状态做决策。
4. 深度 >1 的调用必须在消息里留痕（父任务 id），否则审查无法追责。

## 7. 成本控制

- 房间级预算 = token 上限 + 墙钟上限；用量按 memberId 累计并在 MemberCard 上显示。
- 预算 >80% 时主持人输入里加一条「预算告急」并倾向 `done`。
- 关掉「每轮广播全员」这个隐藏成本源（现有实现是 N 人 × R 轮全连接）。

## 8. UI 契约（供 T5.4 使用，观感沿用现有群聊）

| 组件 | 内容 |
| --- | --- |
| RoomHeader | 房间名 + 预算条（token/时间）+ 轮次 + 硬闸门状态 |
| MemberCard | 头像/名字/状态（idle·speaking·waiting·blocked）/ 已用 token / 未完成任务数 |
| TurnStream | 现有消息流 + 可折叠的「为什么让它发言」reason 条 |
| ModeratorBar | 手动指定发言人 / 暂停编排 / 立即散会 / 切换 brief-only |
| TaskPanel | 执行池：任务树（含父任务链）+ 状态 + 产物入口 |

原则：新增信息默认折叠，不改变现有阅读节奏；任何自动行为都有可见原因。

## 9. 迁移路径（4 步，每步可独立回滚）

1. **抽取**：把 `GroupSupport.kt:528-574` 的 `planGroupTasks` 抽成 `Scheduler` 接口，旧实现作 `LegacyScheduler`，用 feature flag 切换。
2. **换实现**：`BriefThenModeratedScheduler` 上线，`detectGroupMode` 降级为「提示」而非硬分类（用户可在 ModeratorBar 里改）。
3. **接执行池**：`subagent` 返回值扩展 + `SUBAGENT_TOOLS` 加 `subagent` + 深度/预算闸门。
4. **拆状态机**：把打回计数、`lastSpeaker`、轮次全部收进 `Ledger` 单一持有（消除 `LocalEngineTransport.kt:1143-1210/:1192-1200` 的分散状态）。

## 10. 验收标准（T5.2 / T5.3 的硬门）

1. 5 人群聊跑一个多步任务：RoundSplit 系统消息出现，且被排入下一轮（不是静默丢弃）。
2. 同一人不得连续两次发言超过 2 次（除非被强制 @）。
3. 并行档两次运行的分片结果**完全一致**（可复现）。
4. 预算耗尽时房间在 ≤1 轮内停下，并显示原因。
5. 子代理产出至少 1 个 `AgentTask` 进池，且父任务链可查；深度 2 的子代理可运行，深度 3 被闸门拒绝并给出可读错误。
6. 卡死场景（故意让全员重复同一句话）在 2 轮内被 `StallDetector` 捕获并触发重规划。
7. 现有群聊回归：单 agent 群、双人群聊、@点名、手动指定发言人 —— 行为与改动前一致（截图对比）。

## 11. 失败模式与对策

| 失败模式 | 对策 |
| --- | --- |
| 主持人只会轮流点名（退化回 RoundRobin） | 决策里若 action=next_speaker 且 reason 与账本无关，记一条告警；连续 3 次则强制走一次 `roundtable` 打破模式 |
| 全员复读（信息增益为 0） | `StallDetector` + 每 Turn 记「新增信息量」评分，为 0 的成员在下一轮降权 |
| 子代理 recursive 爆炸 | 深度闸门 + 预算分摊 + 池容量三重复合 |
| 用户插话被淹 | 用户消息永远插在流中间（不排队），并可一键改主持人决策 |
