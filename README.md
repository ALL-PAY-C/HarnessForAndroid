# HarnessForAndroid

DeepSeek Harness 的 Android 客户端。会话、模型接入、设备工具、插件宿主和 Linux 环境都在手机本地跑，不连桌面端。

## 下载

到 [Releases](https://github.com/ALL-PAY-C/HarnessForAndroid/releases) 取 APK 直接装。

| 项 | 值 |
| --- | --- |
| 文件名 | `HarnessForAndroid-release-v0.07.apk` |
| 最低系统 | Android 8.0（API 26） |
| CPU | `arm64-v8a`（release 只打这一个 ABI；`x86_64` 只在 debug 包里，给模拟器用） |
| 签名 | 签名材料随仓库走（`app/keystore/app-release.jks` + `app/keystore.properties`），任何机器构建出的证书都是 SHA-256 `767bc8b4bcac50fa01f6f4372638aa45dcbdc4024791cf9faf92671aa549c623` |
| 体积 | 113,093,023。release 只带 arm64 的两份内核归档，x86_64 那两份在打包期剔除；debug 包四份齐全 |
| SHA-256 | `c79186f5f07cd5e7bde3c0ed3dedf2823a4aeafe04a7b8a14d2ac218043eda29` |
| 覆盖安装 | 手机里若是更早那张证书签的包，先卸载再装 |

## 功能

- 会话：本地 SQLite 存消息，支持多模型、工具调用、群聊（多个模型轮流发言）。
- 模型接入：OpenAI 兼容端点、DeepSeek 官方、以及引擎插件自报的 provider（`engine://`，不需要 API Key，且支持工具调用——`shell` / 文件 / 手机控制 / `subagent` 都能在该路由上真跑）。添加模型时预设单行横滑：DeepSeek / OpenAI / Gemini / 通义千问 / 智谱 / Kimi / Anthropic / 兼容端点 / 自定义。
- 侧边栏：QQ 手机端那种，搜索框同时搜会话标题和消息正文，命中词高亮，点一条直接跳进那个会话。
- 子代理：引擎里的 subagent 工具，模型可以派一个独立上下文的子代理去跑一段多步任务，只给它 shell / 文件 / 手机控制这几类工具。
- Agent 预设：六个内置（标准 / 批量 / 创作 / 省 Token / 梁神 / 鲸英），按会话记，可在聊天页「+」面板里开对应开关。
- 插件：插件市场（十二源并发、缓存在本地）、文件安装、链接安装、GitHub 仓库直装；插件能挂工具、出页面、往抽屉里加条目。
- 技能：技能中心管 `SKILL.md` 库，内置 5 个方法类技能，也能从仓库拉别人的技能包。
- 图形渲染：消息里的 `svg` / `mermaid` / `dot` / `plantuml` / `echarts` / `chart.js` / `ascii` 代码块直接画成图，卡片可捏合缩放、拖动、复制源码、全屏查看；离线库（mermaid、ECharts、Chart.js、Graphviz WASM）随包，PlantUML 走 kroki.io（4 秒超时回落源码）。
- 插件设置：插件页的「插件设置」文件夹按插件列条目，点进去是插件自己的设置界面（声明式 ui 翻成原生控件，html 设置页走内置 WebView），改动即时回写并持久。
- 设备工具：shell、文件读写、手机控制（免 root，走无障碍）、后台任务。
- Linux 环境：musl node 加内核归档，跑真 shell。

## 构建

JDK 17、`compileSdk` 35、`minSdk` 26、NDK `26.3.11579264`。`:node-runtime` 走 CMake，`:terminal` 走 ndkBuild，CMake 版本没在脚本里钉，用 SDK 自带的那份。

```bash
export JAVA_HOME=/path/to/jdk-17                      # AGP 8.7.3 只认 JDK 17
export ANDROID_HOME=/path/to/AndroidSDK               # 或写 local.properties 的 sdk.dir
export DSH_LIBNODE_ZIP=/path/android-libnode.zip      # 可选，离线时用
export DSH_KERNEL_ASSETS_DIR=/path/kernel-assets      # 可选，离线时用

cd engine && npm ci && cd ..        # engine/node_modules 不进版本库
./gradlew assembleDebug             # 含 x86_64
./gradlew assembleRelease           # 只打 arm64-v8a
./gradlew testDebugUnitTest
```

首次构建联网补四样东西，之后走 Gradle 缓存：

| 谁下 | 下什么 | 落在 |
| --- | --- | --- |
| Gradle wrapper | `gradle-8.11.1-bin.zip` | `~/.gradle/wrapper/dists` |
| SDK Manager | platform-35、build-tools、NDK 26.3、CMake | `$ANDROID_HOME` |
| `downloadLibnode` | `android-libnode-v22.23.2.zip`（arm64 117,824,576 B / x86_64 122,936,592 B） | `~/.gradle/caches/dsh-nodejs-mobile/`，解到 `node-runtime/src/main/jniLibs/<abi>/` |
| `downloadKernelArchives` | 内核 2 个 + musl node 2 个归档 | `~/.gradle/caches/dsh-kernel/` |

两个下载任务走自己的 HTTP 客户端，`--offline` 拦不住；要完全断网就用上面两个环境变量（或 `-PdshLibnodeZip` / `-PdshKernelAssetsDir`）。libnode 解包后仍验 sha256，对不上中止构建。

产物落在 `app/build/outputs/apk/release/HarnessForAndroid-release-<版本>.apk`，文件名取 `appVersionName` 第一段，改版本只改 `app/build.gradle.kts` 顶部的 `appVersionName` / `appVersionCode`。

## 已知限制

- 没有 32 位 ARM；release 只发 `arm64-v8a`。
- 16 KB page size 未满足：`libnode.so` 的 `PT_LOAD p_align` 是 `0x1000`，16 KB 页设备上 `dlopen` 可能失败（4 KB 页不受影响），要合规得自建且加 `-Wl,-z,max-page-size=16384`。
- `libnode.so` 是 full-icu 加 V8 全符号导出，单 ABI 117.8 MB；该 fork 的上游 CI 从未成功，二进制不可复现，只能靠 sha256 钉死。
- 插件市场靠外部 registry；全源不可达时只剩缓存和内置索引。
- 引擎在 `:node` 子进程里，脚本 `process.exit` 或 OOM 只死这一个进程，主进程重连后重新拉起，代价是插件启动多一次进程创建（实测约 4 s）；`:node` 里不初始化 `AppContainer`。
- 技能中心的「项目级」开关要宿主传 `projectRoot`，当前宿主没传，所以自定义技能只能全局启停。
- TikZ 与 Python（matplotlib）代码块只显示源码：要本地 LaTeX / Python 解释器才能出图，容器里没带。
- PlantUML 需要外网（kroki.io），离线或超时时退化成源码卡。
- 图形卡首次打开要把约 8 MB 离线库从包内复制到缓存目录（IO 线程，一般 1 秒内），之后命中缓存。

## 目录

| 路径 | 职责 |
| --- | --- |
| `app` | Application、导航、前台服务与通知、更新提示、依赖注入 |
| `node-runtime` | `libnode.so` 获取与 sha256 校验、CMake 原生部分 |
| `core/common` `core/model` | 协程与日志基建；会话、成员、消息、端点与编排模型 |
| `core/protocol` | 传输层、工具执行、执行池、分组编排、提示词解析 |
| `core/data` | Room、DataStore、仓储、备份、密钥存储、预设与更新策略 |
| `design` `render` | 主题令牌与组件；Markdown / LaTeX / HTML 与长内容渲染 |
| `runtime-host` | 运行时宿主、Node 引擎宿主、JSON 行协议 |
| `toolbridge` | Shell 四条通道、文件与手机桥 |
| `linux-runtime` | rootfs 与 proot 工具链、内核归档、Linux 环境补齐 |
| `terminal` | 终端内核与视图 |
| `plugin-api` `plugin-host` | 插件契约；加载隔离、市场、安装与槽位 |
| `plugin-html` | 内置 HTML 渲染 |
| `feature/*` | 会话列表、消息流与导出、Agent 页、设置、市场与插件页 |
| `engine/` | Node 插件宿主源码树，构建期打成 `assets/runtime/engine.zip` |
| `scripts/` `tools/` | rootfs 与内核抓取、proot 构建、`engine.zip` 打包、图标脚本 |

其余文档：[docs/PLUGINS.md](docs/PLUGINS.md)、[docs/settings.md](docs/settings.md)、[docs/update.md](docs/update.md)、[CHANGELOG.md](CHANGELOG.md)。

## 声明

MIT，见 [LICENSE](LICENSE)。仅个人学习研究用，不含上游 DSH 的商业授权，与上游仓库无关。代码与文档在 AI 辅助下完成。
