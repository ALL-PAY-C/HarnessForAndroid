# 重构结项说明（refactor/v2）

> 分支 refactor/v2｜应用版本 v0.08（`gradle.properties:10-11`）｜逐条证据（构建输出行、截图路径、单测数、提交号）在 `D:\DSH\HFA-WORK\项目管理规划书.md` §四第 5 列、§六、§九。
> 本文只回答三件事：做到什么状态、还差什么、怎么验证。下列路径全部相对仓库根 `D:\DSH\HFA-WORK\app`。

## 一、一句话结论

P0–P8 主体已落地并在模拟器上跑通：debug/release 均可构建出包、装机启动无 FATAL，分层收口、下载链路、手机控制、群聊编排、插件与传输接缝、上帝类拆分、冷启动性能都已完成归档。

可以发布，但发布动作有前置：必须重新 `assembleRelease` 并显式选择签名（见 §四），同时接受三项现状——release 关闭 minify/shrink、默认使用 legacy 证书、两项审计任务的归档回填尚未做（见 §三）。

## 二、已完成清单

按规划书 §四 的阶段分组；每条给出当前落点文件，路径相对 `app/`。

### P0 环境与基线

- SDK 固定在 `D:\Android`（platform-tools / platforms;android-35 / build-tools;35.0.0 / NDK 26.3.11579264 / cmake）；Gradle 8.11.1 发行包经腾讯镜像手工铺入 `%USERPROFILE%\.gradle\wrapper\dists`。
- 引擎依赖用 `npm install` 重建（`npm ci` 因 lock 与 package.json 不同步报 EUSAGE），产物 `engine/node_modules`，lock 改动随代码提交。
- 模拟器 AVD `dsh35`（android-35 google_apis x86_64）：debug 与 release 两套包都能装能起（2026-11-03 复核：`adb install -r HarnessForAndroid-release-v0.08.apk` → `Success`，`am start -W` → `Status: ok`，截图 `D:\DSH\HFA-WORK\research\shots\release-v008\launch.png`）。
- 回归脚本 `tools/verify.ps1`：构建→装机→启动 `-W`→无障碍探针→四页截图→崩溃缓冲检查，退出码 0/1，实跑 EXIT=0。

### P1 结构止血

- 版本单源：`gradle/libs.versions.toml` 加根 `build.gradle.kts` 统一 compileSdk/minSdk/JVM/junit，221 条依赖坐标改前改后 0 差异。
- 分层收口：plugin-host 去 `:render` 改注入槽，terminal 摘掉误挂的 `:design`，`api(project)` 42→9，`:app` 显式声明 18 个 project 依赖。
- 构建期下载统一：`build-logic/src/main/kotlin/dev/dsh/buildlogic/DownloadVerifiedArtifact.kt`，镜像列表单源在 `build-logic/src/main/kotlin/dev/dsh/buildlogic/Mirrors.kt`，含 sha256 与 `--offline` 语义。
- zip 解包与 zip-slip 防护 6 份合 1：`core/common/src/main/kotlin/dev/dsh/mobile/core/common/archive/ZipExtract.kt`（条目数/单条/总量限额，越界整包失败）。
- 运行时栈收敛：删 `EmbeddedRuntimeHost`，只走 `:node` 子进程；状态映射在 `app/src/main/kotlin/dev/dsh/mobile/app/node/RuntimeControlAdapter.kt`，实现在 `runtime-host/src/main/kotlin/dev/dsh/mobile/runtimehost/NodeEngineHost.kt`。
- 签名链收口：正式证书移出仓库、`app/keystore.properties.example`（真实 `keystore.properties` 已出库，工作树中不存在）、`-PdshSigning=legacy|release` 切换（`app/build.gradle.kts:174-272`）。

### P2 UI 一致性与手感

- 几何令牌真正接线：`design/src/main/kotlin/dev/dsh/mobile/design/QqGeometry.kt`（`QqShape`/`QqSpace`/`qqBorder`/`qqFontBody`），`design/src/main/kotlin/dev/dsh/mobile/design/Theme.kt` 增 field/chip/sheet 三个派生半径；设置里改圆角/密度/材质已肉眼生效。
- 字面量收敛：`RoundedCornerShape` 74→23、裸 `fontSize` 149→40 量级，统计口径写在规划书 T2.1b 行。
- 布局骨架统一：`design/src/main/kotlin/dev/dsh/mobile/design/QqTopBar.kt` 单源顶栏 + imePadding/navigationBarsPadding；一级设置项全部改为跳子页，删掉会漂移的内联手风琴（`feature/settings/src/main/kotlin/dev/dsh/mobile/feature/settings/SettingsScreen.kt`）。
- 顶栏单源收尾：抽屉面板不再自绘顶栏（`app/src/main/kotlin/dev/dsh/mobile/app/nav/AppDrawer.kt` 用 `QqScreenTopBar`），技能中心安装/刷新提到宿主 trailing（`feature/plugins/src/main/kotlin/dev/dsh/mobile/feature/plugins/SkillCenterScreen.kt`）。
- 视觉回归 v2（T7.8 顶替已废止的 T2.4）：26 项覆盖、28 张截图 + 28 份 dump，报告 `D:\DSH\HFA-WORK\research\T78-visual.md`；失败 1 项由 T7.9 修掉——通知页分组标题与顶栏同串、会话列表补「会话」标题（`feature/sessions/src/main/kotlin/dev/dsh/mobile/feature/sessions/SessionsScreen.kt`）。

### P3 下载/更新/分发

- 分段下载器上移泛化：`core/common/src/main/kotlin/dev/dsh/mobile/core/common/download/SegmentedDownloader.kt`（6 连接、段内 Range、`.part`/`.seg` 续传、条件请求）。
- 内容寻址缓存与调用封装：`core/common/src/main/kotlin/dev/dsh/mobile/core/common/download/Downloads.kt`；镜像前缀单源 `core/common/src/main/kotlin/dev/dsh/mobile/core/common/RuntimeMirrors.kt`。
- 更新清单并行探测 + 磁盘兜底 + TTL：`core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/ManifestFetch.kt` 与 `core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/ManifestCache.kt`（TTL 6h、原子写、内存/磁盘三级回退）。
- 源测速排序与失败切换：`core/data/src/main/kotlin/dev/dsh/mobile/core/data/update/SourceSpeedStore.kt`（EMA α=1/4、失败罚分 10s、源数≤2 时保持旧行为）。
- 技能、预设、远程插件三处裸 `HttpURLConnection` 全换到同一条下载链，sha256 不匹配拒绝落地。
- 文档对齐：`docs/update.md` 41→132 行，超时、续传、sha256、失败文案与代码一致。

### P4 手机控制与权限

- 结构化读屏：`toolbridge/src/main/kotlin/dev/dsh/mobile/toolbridge/phone/PhoneNode.kt` 与 `toolbridge/src/main/kotlin/dev/dsh/mobile/toolbridge/phone/PhoneSelector.kt`（按 viewId/文本/坐标定位，返回节点层级、bounds、可点击标记，带 maxNodes/maxDepth 截断）。
- 动作面扩展：`toolbridge/src/main/kotlin/dev/dsh/mobile/toolbridge/phone/PhoneBridge.kt` 25 个动作（tree/tap/swipe/scroll/drag/pinch/sequence/wait_for/keyevent/screenshot 等），错误码 16 个并分 retryable 与 effectLanded（`toolbridge/src/main/kotlin/dev/dsh/mobile/toolbridge/phone/PhoneError.kt`）。
- 三档授权：`core/common/src/main/kotlin/dev/dsh/mobile/core/common/device/Capability.kt` 与 `toolbridge/src/main/kotlin/dev/dsh/mobile/toolbridge/phone/CapabilityGate.kt`（ANY/SESSION_ONLY/ASK_ONLY × 上限三档 + epoch 撤销），设置入口 `feature/settings/src/main/kotlin/dev/dsh/mobile/feature/settings/AppCapabilityScreen.kt`。
- 逐调用授权：`core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/ToolPolicy.kt`（敏感度四档映射 Allow/Deny/Ask，副作用动作 noRetry）。
- 截图回图：`core/common/src/main/kotlin/dev/dsh/mobile/core/common/PendingImageQueue.kt`（2 张 / 2 MiB / TTL 30s），由 `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/LocalEngineTransport.kt` 注入下一轮 user part。
- 实机回归五修：相机权限崩溃、前台服务超时、确认卡文案重复、动作文案过期、终端字号与圆角裁切。

### P5 群聊编排

- brief-then-moderated 两阶段落地：设计文档 `docs/design/group-orchestration-v2.md`，执行侧在 `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/group/GroupRunner.kt`。
- 三处硬伤修复：`core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/GroupSupport.kt` 不再静默截断（本轮超出的人进 deferred 顺延并播 notice）、分片顺序与发言顺序解耦、连续打回计数清零。
- 任务/进度账本外环：`core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/group/RoomLedger.kt`（五写入点，未完成任务进主持人请求）。
- 账本 UI：`feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/GroupUi.kt`（成员胶囊/成员卡/发言脉冲/notice 分块/`GroupLedgerChip`/`LedgerSheet`）。

### P6 可扩展性

- 工具面注册化：`core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/tool/ToolSpec.kt` 与 `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/tool/ToolRegistry.kt`，声明、执行、授权同源，新增工具只改一处。
- 插件面版本化与 JSON 视图：`plugin-api/src/main/kotlin/dev/dsh/mobile/pluginapi/PluginCompat.kt`、`plugin-api/src/main/kotlin/dev/dsh/mobile/pluginapi/PluginView.kt`（16 KiB / 256 节点 / 深度 12），校验器 `plugin-host/src/main/kotlin/dev/dsh/mobile/pluginhost/PluginViewJson.kt`，老 ABI 一行未改。
- 传输接缝：`core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/transport/DshCarrier.kt` 与 `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/transport/DshMux.kt`（握手/代次/重连/流重开）；硬判据 `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/DshTransport.kt` 零改动。

### P7 拆类与性能

- `core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/LocalEngineTransport.kt` 按 tool/llm/group/context 拆分；`feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatScreen.kt` 2308→599，其余落到 `feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatMessageList.kt`、`feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatComposer.kt`、`feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatHeader.kt`、`feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatDialogs.kt`。
- `core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepository.kt` 1229→202，实现分到 `core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepoSend.kt`、`core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepoQueries.kt`、`core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepoSessions.kt`、`core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepoMutate.kt` 等 8 个文件，28 个 public 签名逐字等价。
- 冷启动：debug 8.2–9.3s → 4.0–4.4s、release 1.5–1.9s；服务启动与通知刷新移出首帧窗口（`app/src/main/kotlin/dev/dsh/mobile/app/MainActivity.kt`、`app/src/main/kotlin/dev/dsh/mobile/app/service/RuntimeService.kt`）。

### P8 收尾交付

- 防回归文档 `docs/AGENTS.md`：9 条不可破坏条款、14 条踩过的陷阱、五层归属表、验证口径。
- release 出包与签名核对：默认 legacy 与 `-PdshSigning=release` 两套产物都装过机；新证书 SHA-256 `54445f7e…b6930d`，仓库外路径 `C:\Users\ljk06\.dsh\hfa-release\app-release.jks`。
- 阶段提交：P1→P8 每阶段一个 commit，统一用 `git add -A -- . ':!gradlew.bat'` 排除换行差异，分支不 push。
- 结项复核（2026-11-03 21:00 实跑，证据可复现）：`assembleRelease` BUILD SUCCESSFUL 2m40s → `app/build/outputs/apk/release/HarnessForAndroid-release-v0.08.apk`，115,810,284 B；`apksigner verify --print-certs` 证书 CN=DSH Mobile、SHA-256 `767bc8b4bcac50fa01f6f4372638aa45dcbdc4024791cf9faf92671aa549c623`（legacy）；`adb install -r` → Success、`am start -W` → `Status: ok`（3466 ms），截图 `D:\DSH\HFA-WORK\research\shots\release-v008\launch.png`；三模块单测 `--rerun` 全绿：131 suite / 908 tests / 0 failures / 1 skipped；`tools/verify.ps1` EXIT=0，崩溃缓冲无 FATAL。

## 三、未做与已知限制

取自规划书 §四 未归档行、§五 决策、§十一 已知坑。每条写现象、现在怎么绕、影响面。

- **（已关闭）T7.6 / T7.7 归档回填**：2026-11-03 21:00 已写回规划书 §四。T7.6 产物 `D:\DSH\HFA-WORK\research\T76-topbar-audit.md`（68 行）逐页含判定与 file:line；T7.7 两处改动经核对仍在位——`feature/settings/src/main/kotlin/dev/dsh/mobile/feature/settings/SettingsScreen.kt:1195` 的 `SectionFrame(title = null, …)`，与 `feature/plugins/src/main/kotlin/dev/dsh/mobile/feature/plugins/PluginMarketScreen.kt:249` 注释「页级标题只由宿主顶栏给（PluginHubScreen 的 QqScreenTopBar『插件安装』）」；对应实机核对由 T7.9 的 5/5 PASS 承担。
- **release 已开 R8 与资源收缩（Q1 已执行，2026-11-05）**：`app/build.gradle.kts` release 块 `isMinifyEnabled = true`、`isShrinkResources = true`，keep 规则写在 `app/proguard-rules.pro` 八段（pluginapi/pluginhost 反射面、`**$Stub`、`@JavascriptInterface`、Rhino、kotlinx.serialization、Room、`**$WhenMappings` 等）。
  - 实测：`assembleRelease` BUILD SUCCESSFUL 2m58s，产物 105,344,356 B（压缩前 116,897,440 B，−9.9%）；装机冷启 3896 ms。
  - 实测：R8 包内模型保存、引擎 `ENGINE_REMOTE_READY`、流式回复、思考链折叠、工具调用、MCP `initialize/tools/list/tools/call`（回复「工具返回：echo:ping」）全部正常。
  - 实测：内置插件 `HTML 渲染 / 示例 Hello / 示例 Whoami` 自检全 OK（`dsh.html selfcheck: OK 入口类可反射无参构造`），声明式 zip 插件 `pl.purge` 与市场 npm 插件 `dsh-auto-model-routing` 均装上并显示「已启用」。
  - 影响面：新增对外部插件 dex 的命名约束——插件侧不得依赖被 R8 改名的宿主类名，宿主侧 keep 规则改动后必须重跑上述三条实测。

- **签名双轨（Q2）**：默认 legacy 证书，新正式证书在仓库外。
  - 现象：不带参数构建得到的是 legacy 包，与已装旧包同证书。
  - 绕法：发布用 `-PdshSigning=release`；换证书的包在已装旧包设备上必须卸载或换 applicationId（构建期会打印这条 WARNING）。
  - 影响面：发布流程多一个必须记住的参数。
- **Root / Shizuku（Q4 已执行）**：`toolbridge` 依赖 `dev.rikka.shizuku:api` 与 `:provider` 13.1.5，合并清单含 `rikka.shizuku.ShizukuProvider` 与 `moe.shizuku.manager.permission.API_V23`；`Channels.kt` 负责 binder 授予与 `requestPermission`，设置页展示三态并给授权入口；无 Shizuku 时回退 Root(su)，无 su 时回退 App shell。
  - 现象：模拟器无 root、未装 Shizuku，只能验证到第三档「App shell 当前」。
  - 绕法：真机装 Shizuku 后走同一套 `requestPermission` 流程。
  - 影响面：系统级注入与跨应用点击在没有 Shizuku/Root 的设备上仍受限于无障碍与运行时权限。

- **截图回图的轮内限制（T4.4）**：`role=tool` 的消息放不进 image part。
  - 现象：同一轮工具循环内看不到刚截的图，裁剪也未做。
  - 绕法：降采样加 `PendingImageQueue` 注入下一轮 user part。
  - 影响面：连续多步视觉操作必须跨轮，单轮内无法闭环。
- **下载链路残留（T3.4 登记）**：`Updater.httpGet` 仍是裸连接（只取清单 JSON）、弱缓存无 LRU、同 sha 并发无去重。
  - 现象：清单请求不复用统一下载链。
  - 绕法：调用点本身串行，当前无并发冲突。
  - 影响面：请求量与限流场景才显现，功能不受影响。
- **真实源未端到端验（T3.2 残留）**：模拟器网络受限，GitHub 真实源没做完整下载实测。
  - 现象：镜像切换、断点续传只有单测与 mock 源证据。
  - 绕法：用镜像配置与 mock 网关复现路径。
  - 影响面：真实源 403 与限速场景的兜底未被实测覆盖。
- **死依赖与空实现（T1.6 残留）**：`api(:core:protocol)`、`api(:core:model)` 已成死依赖未删；`clearLogs` 在 node 侧无固定日志目录。
  - 现象：终端「清日志」只清视图，不清磁盘。
  - 绕法：清日志后重启会话；死依赖留着等下一批清理。
  - 影响面：仅整洁度与一处无效按钮语义。
- **传输接缝未完成项（T6.4 残留）**：`pingIntervalMs` 未接线，HTTP carrier 只有骨架。
  - 现象：长连接保活依赖底层 socket 行为。
  - 绕法：本地管道与 WebSocket 两条路已可用。
  - 影响面：接远程传输时要自己实现 carrier 与保活。
- **配色未全同源（T2.1c 残留）**：渲染器自有配色与 `QqPalette` 不同源，settings 有 4 处等价字面量。
  - 现象：改调色板时渲染器内配色不跟随。
  - 绕法：改色时同步改渲染器常量。
  - 影响面：主题一致性，非阻断。
- **视觉回归 5 条 WARN（T7.8）**：逐条判定在 `D:\DSH\HFA-WORK\research\T78-visual.md`，未整改。
  - 现象：个别页首卡间距、说明文字对比度属观感差异。
  - 绕法：按报告逐条复测后再改。
  - 影响面：不影响功能与发布判定。
- **环境硬前提（§七 / §十一）**：构建必须显式给 `JAVA_HOME` 与 `ANDROID_HOME`（PATH 首个 `java` 指向不存在的 `D:\JAVA`）。
  - 现象：裸跑 `gradlew` 会找不到 JDK；`services.gradle.org` 不可达，wrapper 只能走腾讯镜像；`npm ci` 是坏的。
  - 绕法：照 §四 命令逐条执行；重装包或 force-stop 后重设无障碍开关，否则手机控制探针全空。
  - 影响面：换机器、换会话时先跑一遍验证命令，别信旧状态。
- **证据不进库**：`D:\DSH\HFA-WORK\baseline\`、`D:\DSH\HFA-WORK\research\`、`D:\DSH\HFA-WORK\logs\` 在仓库外或已 gitignore。
  - 现象：clone 出来的仓库没有截图与报告。
  - 绕法：在 `D:\DSH\HFA-WORK` 本地目录取证据。
  - 影响面：跨机器复核需要额外拷贝。

## 四、验证命令

全部在 PowerShell 7 下可直接粘贴；每条自带工作目录，`cd` 前缀不可省。构建约 1–5 分钟，不要并发跑多条 gradle。

1. debug 构建：
   `cd D:\DSH\HFA-WORK\app && $env:JAVA_HOME='C:\Users\ljk06\scoop\apps\temurin17-jdk\current'; $env:ANDROID_HOME='D:\Android'; $env:ANDROID_SDK_ROOT='D:\Android'; .\gradlew.bat --console=plain --no-daemon :app:assembleDebug`
2. release 构建（默认 legacy 证书，可覆盖升级旧包）：
   `cd D:\DSH\HFA-WORK\app && $env:JAVA_HOME='C:\Users\ljk06\scoop\apps\temurin17-jdk\current'; $env:ANDROID_HOME='D:\Android'; $env:ANDROID_SDK_ROOT='D:\Android'; .\gradlew.bat --console=plain --no-daemon :app:assembleRelease`
3. release 构建（新正式证书）：
   `cd D:\DSH\HFA-WORK\app && $env:JAVA_HOME='C:\Users\ljk06\scoop\apps\temurin17-jdk\current'; $env:ANDROID_HOME='D:\Android'; $env:ANDROID_SDK_ROOT='D:\Android'; .\gradlew.bat --console=plain --no-daemon :app:assembleRelease -PdshSigning=release`
4. 单元测试（规划书 §十二 指定的三个模块）：
   `cd D:\DSH\HFA-WORK\app && .\gradlew.bat --console=plain --no-daemon :core:protocol:testDebugUnitTest :core:data:testDebugUnitTest :feature:chat:testDebugUnitTest`
5. 装 debug 包并启动：
   `cd D:\DSH\HFA-WORK\app && adb install -r app\build\outputs\apk\debug\app-debug.apk; adb shell am start -W -n dev.dsh.mobile.debug/dev.dsh.mobile.app.MainActivity`
6. 查崩溃缓冲里的 FATAL：
   `cd D:\DSH\HFA-WORK\app && adb logcat -d -b crash | Select-String 'FATAL'`
7. 全流程回归（构建→装机→无障碍探针→四页截图→崩溃检查，退出码 0/1）：
   `cd D:\DSH\HFA-WORK\app && pwsh -File tools\verify.ps1`
8. 引擎依赖重建（`npm ci` 不可用，工作目录在 engine）：
   `cd D:\DSH\HFA-WORK\app\engine && npm install --no-audit --no-fund`

产物位置：debug `app/build/outputs/apk/debug/app-debug.apk`，release `app/build/outputs/apk/release/HarnessForAndroid-release-v0.08.apk`。
