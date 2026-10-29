# 更新日志

结构参照 Keep a Changelog，版本号用 SemVer。

## v0.05

`versionName` v0.05、`versionCode` 92。

### 新增

- 会话内图形渲染：`svg` / `mermaid`（`mmd`）/ `dot`（`graphviz`、`gv`）/ `plantuml`（`puml`）/ `echarts` / `chart.js`（`chart`、`chartjs`）/ `ascii` 代码围栏直接画成图，不再只是源码块。离线库随包（mermaid 3.5 MB、ECharts 1.0 MB、Chart.js 206 KB、Graphviz WASM 1.3 MB），WebView 走 `loadDataWithBaseURL(file://…)`；卡片支持双指缩放（0.5x–4x，只放大后才吞竖滑）、拖动、复制源码、⛶ 全屏（全屏用 WebView 原生缩放控件，带「重置缩放」）。`render` 侧新增 `DiagramKinds` 归一 26 个围栏别名，插件侧新增 `DiagramView` / `DiagramHtml` / `DiagramAssets` / `DiagramWeb` / `PlantUml`。
- 插件设置页：插件页的「插件设置」文件夹现在按插件列条目（内置的三项排前面），点进去是该插件自己的设置界面——声明式 ui 由 `PluginUiTranslator` 翻成原生控件（开关 / 文本 / 数字 / 下拉），html 设置页走内置 WebView，插件声明了 `homepage` 时给「在浏览器打开」。改动即时回写插件并持久（`shared_prefs` 实测）。
- 添加模型预设去掉「引擎插件」，内置扩到 9 个（DeepSeek / OpenAI / Gemini / 通义千问 / 智谱 / Kimi / Anthropic / OpenAI 兼容 / 自定义），预设行改成单行横滑。

### 修复

- 内置插件资源其实一直没进 APK：`app/build.gradle.kts` 把 `plugin-html` 的 resources 挂在 `java.srcDir` 上，Java 源集只认 `.kt`/`.java`，`render.js` 与新增的图形库全被丢掉。改挂 `resources.srcDir` 后 `render.js`、`diagram/*.js` 正常进包（release APK 实测条目齐全）。
- 图形卡并发复制离线库互相踩临时文件：多个卡片同时首次渲染时都写同一个 `<name>.tmp`，后到的 `renameTo` 成功、先到的回头 `copyTo` 就 `NoSuchFileException`（日志「释放离线库失败」）。`DiagramAssets.dir()` 加 `@Synchronized`，临时文件名带线程 id。
- 长 Markdown 消息渲染卡死主线程（ANR 栈在 `String.indexOf`）：`Markdown.parseInline` 有 5 处无界前向扫描（`[`、`**`、`~~`、`]`、`)`），一个未配对标记就把剩余全文扫一遍且不记忆，密集未配对时 O(n²)。改成每个针只真扫一次、失败后 O(1) 记忆（`NeedleScan`）；行扫描按首字符门控正则、裸表格探针缓存、制表符按需展开；`RichText.safePrefixLength` 改免分配。实测 400k 字符单段未配对标记 134 ms → 6 ms。
- 插件设置页的开关与下拉点了不动：控件直接绑定列表构建时的快照，写回后不重组。改为本地 `remember` 状态。

### 测试

- `render`：新增 `DiagramKindsTest`（7 条）、`MarkdownPerfBenchTest`（3 条，含 5 万字符语料与未配对标记规模曲线），累计 93 条。
- `plugin-html`：新增 `DiagramHtmlTest`（6 条：别名降级、各 kind 引对库、graphviz 降级链、echarts 初始高度、源码转义、dark）。
- `feature:plugins`：新增 `PluginSettingsModelTest`（10 条）。
- 全量 `:render`、`:core:protocol`、`:core:data`、`:feature:plugins`、`:app` 140 个套件 986 条用例，0 失败。

### 已知限制

- TikZ 与 Python（matplotlib）围栏只显示源码：需要本地 LaTeX / Python 解释器。
- PlantUML 依赖 kroki.io，离线或 4 秒超时回落源码卡。
- 双指缩放没做自动化验证（adb 造不出多指手势），只验了全屏原生缩放。
## v0.04

`versionName` v0.04、`versionCode` 91。

### 修复

- 模型回复里的工具调用标记不再当正文铺出来：网关（含引擎插件路由）把 `<||DSML||invoke name="shell">` 这类标记塞进 `delta.content` 时，正文推给界面前先剥掉（`LocalEngineTransport` 主循环统一剥，不再「先发后删」）。`engine://` 端点本来就没有工具执行通道，剥完补一行说明：请求了哪个工具、为什么不执行、换什么端点才能执行。
- 引擎端点的「拉取模型列表」不再报 `unknown protocol: engine`：`core/data` 的 `ModelCatalog` 在入口按 scheme 分流，`engine://` 交给宿主引擎通道（`AppContainer.engineModelIds()` → `nodeEngine.rpc("/llm", "models")`），列出的就是插件声明的模型 id。HTTP 路只在 `http`/`https` 上走，绝不再把 `engine://` 递给 `java.net.URL`。
- 行内数学认 `\( \)` 与 `\[ \]`：以前被通用反斜杠转义吃掉，公式退化成正文。
- 货币区间不再误判成公式：`$5 到 10$` 这类「数字开头、后面没有运算符」的按正文处理。
- `$$` 与公式同行时不再漏出 `$$` 并在正文里断成两半。
- `script` / `style` / `figure` / `figcaption` / `section` 等片段不再把标签原文铺出来（`HTML_TAGS` 补齐到约 90 个）。
- HTML 表格单元格不再粘连（闭合 `td`/`th` 补空格）。
- 大块 HTML 的折叠预览不再漏出被截断的 `script` 正文。

### 测试

- `core/protocol`：新增 `DsmlEngineRouteTest`（5 条）；`SubagentFailureVisibilityTest` 的引擎路由用例改为断言「不执行工具、正文给说明」。
- `core/data`：新增 `ModelCatalogEngineTest`（5 条）。
- `render`：新增 `MathDelimiterTest`（14 条）、`HtmlFragmentTest`（10 条）。
## v0.03

`versionName` v0.03、`versionCode` 90。

### 修复

- Markdown 表格识别：模型只写 `| 列 | 列 |` 再加几行数据、不给 `|---|---|` 分隔行时也渲染成表格。`MarkdownParser` 新增 `barePipeTable`——连续以 `|` 开头、列数一致、至少两行的块整体收成表（`Markdown.kt` 的 `isBlockStart` 同步认它），正文里的 `a | b` 和行内代码里的 `\|` 不会被误判。
- 一条回答里多张表不再只剩最后一张：同一个根因，前面几张没有分隔行就被判成普通正文，整段露出 `|` 竖线源码，只有最后一张（带分隔行的那张）成表。三张表的回归用例钉在 `render/src/test/kotlin/dev/dsh/mobile/render/TableVariantsTest.kt`。
- 侧边栏回到左侧滑出：抽屉改回 Material3 的起始边，和 QQ 手机端一致（左上角按钮或列表页左滑出会话与聊天记录）。
- 事件流单条事件出错不再带走整个 collector（`ChatRepository`）：`handleEvent` 抛非 `CancellationException` 时只记一条日志继续收，后续轮的 chunk / TurnEnd 照常落库。以前这里一抛，collector 直接结束，会话永久失聪——界面上只留 USER 行，助手正文再也不落库。

### 新增


### 变更

- 抽屉搜索的 `LIKE` 模式加转义：`\` `%` `_` 先转成 `\\` `\%` `\_`，SQL 侧配 `ESCAPE '\'`。不转的话搜「100%」里的 `%` 是通配符，命中全库。

## v0.02

`versionName` v0.02、`versionCode` 89。这一版只动观感：面板不跟着材质一起半透明了，全新安装默认给实底。

### 修复

- 卡片不再是半透明：`panelAlpha` 三种材质全部固定为 1，玻璃感只留在页面底色那一层。原来卡片按强度当 alpha 使，白底主题上是一层灰雾，深色主题压浅壁纸时白字直接糊在图上。
- 强度滑块改成只调页面染色：`page(base) = base + 0.35 * strength`（无壁纸时恒为 1）。磨砂底 0.45 → 0.60、液态底 0.32 → 0.52，滑到头页面接近实底。

### 变更

- 全新安装默认材质从「磨砂」改「实底」（`BackMaterial.DEFAULT = NONE`）。玻璃仍是选项，在设置 → 外观 → 材质里挑；已经选过的值不变。
- 亚克力不再受强度影响（原来先减 0.35 再夹一遍，拖动时像「拖了没反应」）。
- 主程序与具体插件彻底解耦：Agent 页不再内置任何插件的账号卡（连带那条插件专属 RPC 调用），`assets/` 里的 provider 种子、`jniLibs/` 里的原生可执行件、`AndroidManifest.xml` 里的插件包名 `<queries>`、Gradle 里的插件模块全部移出本仓；插件要常驻本地服务就自己实现 `BackgroundPlugin`。
- 插件市场缓存格式升到 v2（`MarketCacheCodec.VERSION`），旧快照里失效的内置条目一次性作废。
- 仓库与 release 里不再挂任何插件产物：插件只走插件市场或本地文件安装，不随主程序发布。
- release 的 APK 改为一律跑 v1 + v2 + v3 三套签名方案（`app/build.gradle.kts` 的两个 signingConfig 都写死 `enableV1Signing/enableV2Signing/enableV3Signing = true`）。之前只带 v2，部分安装器和老系统会直接判定「安装包无效」。同 release 里附 `apk-sha256.txt` 供下载后校验。
- 主程序不预置任何插件与账号：`assets/` 里不再放 provider 种子、原生网关可执行件与任何具体插件的源码模块，账号一律填在用户自己的模型或插件配置里。

### 已知问题

- 16 KB 页大小：`libnode.so` 四个 LOAD 段仍是 4 KB 对齐，Android 15 的 16 KB 内核上跑不起来；要换一份按 16 KB 编译的 libnode。
- release 只带 `arm64-v8a`，x86_64 模拟器请用 debug 包。
- `engine://` 端点不实现 function calling，模型看不到 subagent 等工具。

## v0.01

第一个公开版本。`versionName` v0.01、`versionCode` 88，接 0.07 的 87；Android 只比 `versionCode`，降号会让旧包盖不上新包。

### 新增

- 侧边栏改成 QQ 手机端的形态：头部是运行时状态，下面是搜索框和「常用 / 最近会话」；框里一有输入就切成「会话 / 聊天记录」两组结果，标题和消息正文都能搜，命中词高亮，点一条直接进那个会话。搜索 250 ms 防抖，返回顺序乱的旧结果直接丢。
- 引擎新增 subagent 工具：模型能派一个独立上下文的子代理去干活。子代理只拿 shell / fs_read / fs_write / fs_list / fs_op / phone 六个工具，不能再往下派；单次预算 8 轮 / 180 s，过程以「子代理 · 名字」出现在工具调用里，失败也回一段可读文本。
- 引擎内置技能库换成有界递归扫描（最多 4 层、见到 SKILL.md 就收、跳过 node_modules / .git / dist / build），一次可以装多个包；首启把 5 个方法类技能（code-review / systematic-debugging / test-first / commit-message / perf-triage）装进技能库，用户自己关掉的不再被点亮。
- Agent 预设扩到六个内置：标准 / 批量 / 创作 / 省 Token / 梁神 / 鲸英，默认鲸英。预设按会话记，抽屉里点一下只切当前会话；梁神多一个「调度模式」开关、鲸英多一个「省 Token」开关，开关在聊天页「+」面板里。
- 离线预置插件 `assets/plugins/dsh-offline-hello.zip`、`dsh-offline-whoami.zip`：首启装进插件目录，按包名记账，卸载不复活，改内容要 bump `OfflineBundled.REV`。
- Node 22.23.2 运行时（`OpenFlun/nodejs-mobile` fork，`NODE_MODULE_VERSION` 127）：进程内直接跑 `@deepseek-ai/cordis` 4.0.5 插件。
- 引擎内置 `/skill-manager`（`engine/host/skill-manager.js`）：没装外部插件时技能中心不再空白；装了外部插件仍以它为准。
- 插件市场并发拉 12 个源，去重后 500 多条，缓存 `files/market-cache.json` 上限 1 MB、损坏即丢，单源连接 3 s、读 5 s、总预算 8 s，启动先渲染缓存再后台刷。

### 变更

- 模型既没返回正文、也没调工具时不再静默收尾：界面收到「（本轮没有任何输出：原因）」加一条 error 结束事件；群聊里某个成员零输出也给一行占位。
- 上下文窗口以真实值为准：端点 /models 里带 context_length 的按端点值算，DeepSeek 系内置猜测从 64k 改 128k；「测试连接」成功后顺手把探测到的窗口写进模型配置。
- 抽屉改到右侧：ModalNavigationDrawer 外层把布局方向翻成 Rtl 让面板从右边滑出，内容区再翻回 Ltr。
- 插件安装页按草稿重排：折叠卡第一行是「插件市场」标题 + 搜索框 + 右端折叠箭头（整块收起，不是升降序），第二行左右两个排序控件；
  每条插件收起只留名称、版本来源和一句话简介，展开才给全文、下载量、「GitHub 详情」与「安装插件」；
  「文件安装 / 链接安装」挪出主卡常驻页面底部；整块收起后卡片自己缩成一行，不再占满屏。插件源与备份回档仍在列表末尾各自折叠。
- 底部导航与抽屉里的「插件市场」改名「插件安装」：这一页本来就是装插件的地方。
- 新增可扩展的启动动画：设置 → 外观 → 启动动画。默认关闭（`startupAnimation` 为空串时不叠任何节点、不起协程），
  内置「淡入 / 画 H / 弹跳」三种，时长可选默认 / 快 / 标准 / 慢；也能选一张图片或 GIF，或者自己写 spec ——
  `builtin:<id>`、`image:<uri>`、`plugin:<id>`。插件调用 `StartupAnimations.register` 注册同名 id 就能出现在这里，
  宿主认不出的 id 自动退回不播；播放中点击屏幕可跳过。
- 启动器图标换成黑白配色：纯黑圆角底 + 白色字母 H，自适应图标背景也改纯黑；先前的蓝底渐变一并换掉。
- 引擎插件可作为模型直接用：引擎新增 `/llm` 端点（`engine/host/llm.js`），模型预设第 5 项 `engine://` 走设备内插件，不需要 API Key。
- 引擎搬进 `:node` 子进程：`NodeEngineService` 跑原 `NodeEngineHost`，主进程 `RemoteNodeEngine` 经 AIDL 调。原先进程内跑，脚本 `process.exit` 会连主进程一起走，libnode 一进程只能起一次、`stop()` 只断 socket 不杀线程。
- `packEngineAssets` 在 `engine/node_modules` 缺失时直接失败，`-PdshAllowEmptyEngine=true` 才放行：原来只 warn，打出来的包装机后插件 import 必断。
- `app/keystore/app-release.jks` 与 `app/keystore.properties` 进版本库（`.gitignore` 用否定规则放行）：原来被挡在外面，别的机器 clone 后回退 debug 证书，签名和已发布的包对不上。
- release 包从 183.7 MB 降到 128.4 MB：x86_64 的两份内核归档（约 54 MB）在打包期剔除，`engine.zip` 不再带 `node_modules` 里的 `.d.ts`、`.map`、`test`、README/LICENSE，条目 1833 → 967。
- release 只打 `arm64-v8a`，`x86_64` 留给 debug 模拟器；APK 名 `HarnessForAndroid-release-v0.01.apk`，版本单一来源在 `app/build.gradle.kts` 的 `appVersionName` / `appVersionCode`。
- 面板材质从布尔开关改成四档枚举，旧键 `glass_enabled` 首次读取自动迁移。

### 修复

- 卸载一次后同 id 插件被永久拉黑：只摘登记，重新从文件安装即可。
- 从文件安装只看后缀，选到后缀不对的一律「安装失败」：改按文件头判型（zip / gzip / APK 插件包 / 其他），`.apk` 旧分发格式照收，只剩真正不认识的文件提示「所选文件不是插件包」。
- 安装失败只剩一行 message：完整堆栈进 UI 日志和 `.errors/<id>.txt`，Node 插件回显 `stage`、`stack`、`dir` 与包内根层前 40 项。
- 换壁纸不刷新、大图 OOM：壁纸复制进私有目录，按长边 1600 px 采样解码。
- 返回键在抽屉、插件列表、终端等二级页直接跳出会话：改由二级页自己消费，判定失败兜底回会话 tab。
- 会话列表每行底下的左滑动作条常驻绘制：卡片底色是半透明的，色块和「归档 / 分组」会从卡片底下透出来，现在只有真的左滑才画。
- 亚克力固定 10dp、液态固定 3dp 的背景雾化：设了壁纸就是一团糊，现在雾化只跟「背景雾化」滑块走。
- 启动器图标还是带「APP」角标的旧素材：换成和 App 内一致的黑底白 H，自适应图标背景改纯黑。

- 抽屉里的二级页（Agent 预设 / 手机控制 / 技能中心）没有顶栏和返回：补一层统一外壳，标题与返回箭头由壳提供。
- 面板太不透明、壁纸被压死：哑光 0.62 / 液态 0.42 / 亚克力 0.78 调到 0.50 / 0.34 / 0.66，壁纸重新透出来。
- 引擎插件填了模型名却存不下、会话报「未接入模型」：空 API Key 在 `engine://` 下放行。
- 随包的 5 个技能一个都没铺出去：引擎启动时先跑了自己的首启逻辑、把 `.builtin-bootstrapped` 写掉，客户端的铺资产逻辑看到同一标记直接返回。两个标记分开（`.bundled-skills-seeded`），资产该铺的照铺。
- 装了 Node 插件冷启动后 `/llm` 仍然没有 provider：引擎一进程只起一次，宿主启动时没把插件目录传下去（`plugins=0`）。改成启动即带全部插件目录，重复 `plugins.load` 幂等。
- 从聊天页返回会话列表偶发出现「还没有会话」空态：数据在库里，是 `stateIn(WhileSubscribed)` 让首帧拿到初始空态；改成 `Eagerly` 并加 `loaded` 标记再判空。
- 「测试连接」对引擎插件报 `unknown protocol: engine`：探测走 `java.net.URL`，非 http 端点先短路成「成功 · 引擎插件，免网络」。
- 发出去的消息偶发「对面没输出就结束、连错误都没有」：工具执行分支没有异常隔离，子代理侧一抛（端点没就绪 / 500 / 超时）就逃出主循环，既不结算工具卡也不结束回合，库里只剩 USER 行。现在工具异常转成「失败」卡片并把错误回灌给模型，主循环外层兜底发「（本轮异常结束：原因）」+ 结束回合，单条事件异常也不会让会话永久失聪。

### 已知问题

- 技能中心的项目级启停要宿主传 `projectRoot`，当前宿主没传，自定义技能只能全局启停。
- 引擎插件（`engine://`）的上游只做纯文本对话，不实现 function calling，因此模型看不到 `subagent` 等工具；要委派子代理得用 HTTP 模型端点。
