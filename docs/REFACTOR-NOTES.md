# 重构说明（v2 分支）

本文件记录这一轮重构做了什么、为什么这么做、后续怎么继续。面向接手的人和插件作者。

## 1. 一句话概括

功能一个没减，把「能改」变成「改得起」：分层收口、单一真源、工具面/插件面/传输面各自有明确接缝，UI 令牌真正接线，下载与编排放到同一条可测的链路上。

## 2. 结构上变了什么

| 原来 | 现在 | 为什么 |
| --- | --- | --- |
| 版本号散在 21 个 build 文件里 | gradle/libs.versions.toml + 根 build.gradle.kts 的 applyDshAndroidSdk() | 升 AGP 只改一处；依赖坐标 0 差异迁移 |
| plugin-host 依赖 :render（把 Compose 拖进引擎链） | plugin-api 的 RichContentRenderers 注入槽 + DshApplication 注入 | 插件宿主不再依 UI 层；插件仍能画富内容 |
| :app 靠 api() 泄漏拿到全仓编译面 | 41 处 api() 收到 9 处，:app 显式声明 18 个 project 依赖 | 模块可替换、可单独编译 |
| zip 解包 + zip-slip 防护写了 6 份 | core:common 的 ZipExtract（限额 + 越界整体失败） | 一份审计过的实现 |
| 两套运行时栈（EmbeddedRuntimeHost / NodeEngineHost） | 只留 :node 子进程 | 内嵌栈的资产从不随包发布，那条路本来就是死的 |
| downloadLibnode / downloadKernelArchives 各写 300 行 | build-logic 的 DownloadVerifiedArtifact | 镜像前缀单源、sha256 内容寻址缓存、尊重 --offline |
| 下载散成 9 处裸 HttpURLConnection | core:common 的 SegmentedDownloader + Downloads（内容寻址）+ MirrorSelector | 6 连接分段 Range、.part/.seg 续传、sha256 校验统一走一条路 |
| LocalEngineTransport 3273 行、ChatScreen 2308 行、Components 1702 行 | 拆成 tool/llm/group/context 与 Chat*/Design* 分文件 | 上帝文件按「谁改它」切，不按行数切 |

## 3. 三个对外接缝（可扩展性的落点）

### 3.1 工具面：tool/ToolSpec.kt + tool/ToolRegistry.kt

工具的唯一真源在 ToolCatalog：名字、敏感度、是否禁重试、JSON 声明、执行体一处定义。新增工具 = 加一条 ToolSpec，声明与执行不可能再漂移；授权读同一张表（ToolPolicy.verdict）。

```kotlin
ToolSpec("my_tool", ToolSensitivity.ReversibleWrite, noRetry = false, declaration = { /* json */ }) { ctx -> /* 执行 */ }
```

### 3.2 插件面：PluginCompat 协商 + PluginView JSON 视图

- Kotlin 渲染接口（老）与 JSON 视图（新）并存：ui 优先，其次 view，老 ABI 一行没改。
- JSON 视图限制写在 PluginViewLimits（16 KiB / 256 节点 / 深度 12），超限整体拒收，未知节点画灰占位。
- 配额按语义拆：composer.actions 4（含提示词）、composer.buttons 2。

### 3.3 传输面：DshCarrier / DshMux / DshRpc

DshCarrier 只搬帧（frames / losses / connect / send / close），DshMux 管握手、代次、重连与流重开。加一种新传输（WebRTC、HTTP、本地管道）= 实现一个 DshCarrier，上层 DshTransport 零改动。

## 4. UI：令牌真的接线了

以前 LocalThemeTokens 在 :design 之外 0 引用，用户在设置里改圆角/密度/材质页面毫无反应。现在：QqShape.card/field/chip/sheet/pill、QqSpace.xs/s/m/l/xl、qqFontBody/qqFontCode、qqBorder() 全部读令牌；cornerRadius 默认 18→16 dp，fieldRadius/chipRadius/sheetRadius 按比例派生。

设置页统一成「一级列表都是跳子页」，去掉了会漂移的内联手风琴（点一次错一次的那个）。

## 5. 群聊编排 v2

设计文档：docs/design/group-orchestration-v2.md。落地状态：

- 首轮 brief 按 memberId code-unit 升序可复现；后续由主持人逐步决定下一个发言者（brief-moderated）。
- RoundSplit 不再静默截断：本轮只前 4 位发言，其余进 deferred 下一轮优先，并发一条 notice。
- 终止条件从「20 轮 / Done / 连打回 2」扩到 MaxRounds 8 + StallDetector + 预算闸门。
- 子代理结论里的任务清单解析成 AgentTask 走执行池，parentId 可溯源；嵌套深度默认 2，可在设置里改（一层/两层/三层）。

## 6. 手机控制

工具面从「9 个动作 + 纯文本读屏」扩到「结构化树 + 选择器 + 手势 + sequence + wait_for」：phone / phone_tree 两个入口，25 个动作，16 个错误码，retryable 与 effectLanded 分开。副作用动作标记 noRetry（执行器不再重试），逐调用授权按敏感度分档（只读放行、破坏性弹确认）。

## 7. 怎么继续

- 计划书与任务状态在 D:\DSH\项目管理规划书.md（不在仓库内），每项的验收标准与证据都写在里面。
- 新功能先看有没有现成实现（DSHA / DSHBox / dsh-go 的调研报告在 D:\DSH\HFA-WORK\research\）。
- 改完必须过 ./gradlew testDebugUnitTest assembleDebug，再装模拟器实测；只跑单测不算验完。
