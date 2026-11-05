# terminal —— vendored 第三方移植模块（禁改）

**结论先行：本模块的代码是 vendored（第三方移植），不是自研代码。** 不做重构、不计入自研指标、
不按自有模块的代码规范裁剪。要动它，先读本文。

## 一、来源

| 项 | 值 |
|---|---|
| 上游 | termux-app 的终端模拟器（`terminal-emulator` + `terminal-view`） |
| 包名 | `com.termux.terminal`（16 个文件）、`com.termux.view`（7 个文件，含 `textselection`） |
| 原生件 | `src/main/jni/termux.c` + `src/main/jni/Android.mk`，只做 PTY 辅助，`APP_PLATFORM=android-26` |
| 规模 | 23 个 `.kt`，合计 7,101 行（`TerminalEmulator.kt` 1,838 / `TerminalView.kt` 1,222 / `WcWidth.kt` 571 / `TerminalBuffer.kt` 534） |
| Gradle namespace | `dev.dsh.mobile.terminal` —— **仅作为 AAR namespace 存在**，该包下没有任何自研源文件 |

语言形态：上游 Java 逐类镜像移植为 Kotlin，类名、方法名、控制序列处理逻辑与上游保持一致，
目的是让后续从上游同步 diff 时可读。读起来"不像 Kotlin 风格"是刻意的，不是待修的债。

## 二、禁改约定

1. **不做重构。** 不拆类、不改包名、不换 API 形态、不引入 Flow/Compose、不把 `TerminalEmulator`
   的状态机拆成小类。它现在能被上游 diff 核对，拆完就永久失去这层能力。
2. **不计入自研指标。** 行数、复杂度、覆盖率、代码规范扫描一律把本模块排除；`terminal` 的 7.1k 行
   不是"技术债"，是"依赖的源码形态"。
3. **不做逐文件头注释。** 本文 + `build.gradle.kts` 顶部 4 行注释即为全部标注；23 个文件各贴一遍
   vendored 头是纯噪声（2026-11-06 O5 决策）。
4. **允许的改动**只有编译级修补：上游 API 在新 SDK / 新 AGP 下编不过时的最小适配、依赖版本对齐、
   已知崩溃的一行修。这类改动必须在本节"改动台账"追加一行，写清原因与日期。

## 三、改动台账（自研部分全部在此）

| 时间 | 改动 | 原因 |
|---|---|---|
| T1.2 | 去掉 `api(project(":design"))` | 误挂：全模块对设计系统 0 引用 |
| T1.2 | 依赖改 `annotation:1.9.1` / `core-ktx:1.15.0`，不进 catalog | 需要比全局 1.8.1 / 1.13.1 更高的版本，且不愿抬高其它模块 |
| 既有 | `testOptions.unitTests.isReturnDefaultValues = true` | `TerminalEmulatorTest` 只吃 JVM，`android.util.Base64` / `Log` 走桩返回默认值 |

除上表以外的 `terminal/` 内容均属上游。

## 四、升级流程（要新版本时）

1. 取上游对应版本的 `terminal-emulator/src/main/java/com/termux/**` 与 `terminal-view/**`，
   按现有 Kotlin 镜像规则重新落地（不要另开一套命名）。
2. `src/main/jni/` 与上游同版本 `termux.c`、`Android.mk` 一并替换。
3. 逐个确认第三节台账里的三处改动是否仍需保留（上游若已改掉，就删掉我方补丁）。
4. 跑 `assembleDebug` + `terminal` 单测（`TerminalEmulatorTest`），与升级前结果比对。
5. 在第三节台账追加一行升级记录。

## 五、相关文档

- `docs/REFACTOR-NOTES.md` §五：vendored 与纯壳模块的处置结论（O5/O6）
- `D:\DSH\HFA-WORK\项目管理规划书.md` §十五 O5
