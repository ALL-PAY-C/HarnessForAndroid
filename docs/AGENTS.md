# 工程约束与陷阱（改代码前先读）

这份文件是给「下一个人 / 下一个 Agent」的护栏：哪些东西不能破、哪些坑踩过一次就别再踩、模块边界在哪。

## 1. 不可破坏条款

1. **`:app` 之外不许再出现新的 `api(project(...))`**。目前只剩 9 处有意保留的 api（`core/data:20,21`、`core/protocol:10`、`design:12`、`linux-runtime:25`、`plugin-api:15`、`plugin-host:20`、`runtime-host:11,15`）。新增依赖用 `implementation`；确实要传导再加 api 并在 build 文件里写一行理由。
2. **「0 引用」必须双查**：`import a.b.Foo` 与全限定名 `a.b.Foo` 两种写法都要 grep，漏一种就会删出编译错误（本项目真发生过：`design/Components.kt:535` 用全限定 `dev.dsh.mobile.core.common.TimeFmt`）。
3. **`DshTransport.kt` 是对外契约**：传输层重构不许改它的方法签名，新传输实现 `DshCarrier` 即可。
4. **工具声明与执行同源**：加工具只加 `core/protocol/tool/ToolRegistry.kt` 的 `ToolCatalog` 条目，不要在别处另写一份 JSON 声明或授权表。
5. **副作用工具必须 `noRetry`**：`phone`/`phone_tree` 等有真实副作用的动作，执行器不重试（`ToolExecutor` 的重试会让点击被重放）。
6. **版本只改 `gradle.properties` 的 `dshAppVersionName` / `dshAppVersionCode`**；依赖版本只改 `gradle/libs.versions.toml`。
7. **构建期下载必须走 `DownloadVerifiedArtifact`**：镜像前缀统一在 `build-logic` 的 `Mirrors.kt`，不要在模块里另写 gh-proxy 列表。
8. **发布证书只认 `docs/signing.md` 的那两张**；带 `-PdshSigning` 出包，发版前 `apksigner verify --print-certs` 核对 SHA-256。
9. **`gradlew.bat` 的换行差异不要提交**：`git add -A -- . ':!gradlew.bat'`。

## 2. 已知陷阱（每条都是真踩过的）

| 陷阱 | 现象 | 正确做法 |
| --- | --- | --- |
| Compose 页面没有 `Scaffold` | 内容被底栏切成两半、最后一项点不到 | 滚动容器加 `qqPageBottomInset(tabBarVisible)`，别手摆 padding |
| 设置页原来的内联手风琴 | 展开后整列位移，连点两次必误触 | 一级列表统一跳子页，展开不重排其它卡 |
| 无障碍服务开关 | `am force-stop` / 重装会清掉 `enabled_accessibility_services` | 装完再 `settings put secure`，服务绑定要几秒，首条命令报 `E_A11Y_OFF` 属正常 |
| 无障碍读树 | 同一次广播内读到的树与代次不刷新 | 判断「界面变了」看 `generation`，不要用树指纹；跨广播才刷新 |
| 无障碍滚动 | 在 Compose 列表上 `ACTION_SCROLL` 返回 true 但一像素不动 | 先 `performAction`，未变代次再退手势滚动 |
| 读屏根节点 | 会把自身 `TYPE_ACCESSIBILITY_OVERLAY` 与状态栏子树算进去 | `treeRoots` 按 focused → TYPE_APPLICATION 排序后再剔除自身 |
| 聊天页自动化 | Compose 输入框在 uiautomator 里不暴露 `EditText` | 用固定坐标点输入框，发送按钮按 `content-desc="发送"` 找 |
| `adb shell` 路径 | MSYS 会把 `/sdcard/x` 改写成 Windows 路径 | `export MSYS_NO_PATHCONV=1` |
| `am broadcast` 传 JSON | 多层引号被逐层剥掉 | 把命令写进脚本文件再执行 |
| `logcat` | 单条约 4KB，节点树会被截断 | 让探针写文件，`run-as` 取回 |
| `engine/node_modules` | `npm ci` 报 EUSAGE / Missing | `npm install --no-audit --no-fund`，并把 lock 一起提交 |
| 并发 Gradle | 多个构建同时跑会互相污染 `build/` | 构建串行；子代理一律不跑 gradle |
| 群聊取配置 | 群聊会话没有单聊 config，`configProvider` 返回 null | 主持人/成员模型要回落到成员 cfg |
| transcript 的 speakerId | 传任务 id 会让 `spokeCount` 恒 0、主持人永远停在首轮 | 记 `task.agentId`（成员 id） |

## 3. 分层归属

| 层 | 模块 | 只允许依赖 |
| --- | --- | --- |
| 契约 | `core/model` `core/common` `plugin-api` | 无（不许依赖 Android UI，`core:common` 零 Android 依赖） |
| 引擎 | `core/protocol` `core/data` | 契约层 |
| 设备 | `toolbridge` `linux-runtime` `terminal` `node-runtime` | 契约层 + 引擎层 |
| 表现 | `design` `render` `feature/*` | 上层全部，但**引擎层不许反过来依赖表现层**（`plugin-host → :render` 已经拆掉，别再长回来） |
| 装配 | `app` | 全部，负责注入（`RichContentInstaller`、`DshApplication`） |

## 4. 验证口径

改完至少跑通这三条，缺一条不算验完：

```bash
./gradlew testDebugUnitTest        # 单测
./gradlew assembleDebug            # 编译（含 x86_64，可进模拟器）
adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n dev.dsh.mobile.debug/dev.dsh.mobile.app.MainActivity
```

UI 改动还要截图对照基线（`baseline/matrix/`），行为改动要在模拟器上真跑一遍（手机工具用 `com.android.settings` 做靶子，探针与脚本见 `D:\DSH\HFA-WORK\`）。
