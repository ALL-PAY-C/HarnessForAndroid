# 插件契约

四条通道并存：数据插件（`plugin.json`）、APK 插件、Rhino 脚本、进程内 cordis 插件（`engine/`）。安装只有一个入口「插件市场 → 从文件安装」，判型看包内 entry 不看后缀：含 `AndroidManifest.xml` 或 `classes*.dex` 走 APK 插件，其余解包成插件目录（`PluginPackageFormat.kt:26-32`、`:66-67`，用例 `PluginImportTest.kt:38-48`）。

## 数据插件

`plugin.json` 缺 `id` 直接拒装，`name` 缺省回 id、`version` 缺省 `0.0.0`（`PluginPackage.kt:45-49`）。`contributions[]` 字段 `slot`、`id`、`label`、`subtitle`、`icon`、`action`、`ui`（`PluginPackage.kt:59-73`）；`config[]` 字段 `key`、`label`、`type`、`default`、`hint`、`options`（`:87-94`）。`type` 是自由字符串，缺省 `text`，没有取值校验（`:125`）；`ConfigFieldType` 里那套 Text/Secret/Switch/Number/Select（`PluginApi.kt:289`）目前无人引用。

槽位七个 wire 值（`PluginApi.kt:214-235`）：`chat.composer.plus`、`chat.topbar.menu`、`session.row.menu`、`app.drawer`、`app.settings`、`plugin.page`、`agent.preset`。

声明式插件的动作只认四种（`DeclarativePlugin.kt:81-87`）：`http`（method/url/body）、`link`（uri）、`page`（pageId）、`custom`（仅 APK 插件）。其余一律按 `prompt`（text，submit 默认 true）处理，`kind` 缺省也是 `prompt`（`PluginPackage.kt:110`）；`request`、`uri`、`open`、`navigate`、`send` 这些同义写法在这条路径上不存在。

`ui` 子树由宿主翻成原生 Compose（`PluginUiJson.kt:47-160`）。节点名大小写不敏感，别名并列：`column|vstack|vertical|panel|view|container|stack|box|div`、`row|hstack|horizontal`、`text|label|paragraph|heading|title|caption`、`button|action`、`iconbutton|icon`、`toggle|switch|checkbox`、`slider|range`、`textfield|input|editor`、`card`、`section|group`、`divider|hr|line`、`spacer|gap|space`、`badge|tag|chip`、`menu|dropdown|list`；富节点另有 `markdown|md|richtext`、`html|htm|rawhtml`、`code|pre|codeblock|snippet`。

颜色只能写 `colorRole`，五档 `Primary` / `OnSurface` / `OnSurfaceVariant` / `Outline` / `Error`（`PluginUi.kt:13`）。按钮 `tonal`、`text|plain`、`outlined|outline` 之外落 Filled（`PluginUiJson.kt:77-82`）；badge tone 认 `info`、`success|ok`、`warning|warn`、`danger|error`，其余 Neutral（`:133-139`）。认不出的类型不抛异常：有子节点就保留子节点成 column，没有就降级一行文本（`:153-159`）。同一条贡献里 `ui` 与 `content` 都写时 `content` 被忽略（`PluginApi.kt:282-285`）。

## APK 插件

清单里必须有 `<meta-data android:name="dsh.plugin" android:value="true"/>`（`ExternalPlugins.kt:477`、`:316`），`assets/plugin.json` 写 `entry` 完整类名（`:342`、`:464`），缺省 `dev.dsh.mobile.plugin.html.HtmlPlugin`（`:474`）。

加载走 `PathClassLoader(apk.absolutePath, context.classLoader)`（`ExternalPlugins.kt:358`），失败退 `InMemoryDexClassLoader` 直接吃 `classes*.dex`（`:377-393`）；装完把 APK 与目录收成只读，否则 ART 拒绝加载可写位置的 dex（`:399-408`）。`createPackageContext(CONTEXT_INCLUDE_CODE)` 那条路已经废弃：`:330-333` 记着实测 API 35 全部 load failed。

同理 `:plugin-api` 与 Compose 必须全部 `compileOnly`（`plugin-html/build.gradle.kts:67`、`:70-74`），打进包会和宿主那份冲突。后台常驻再实现 `BackgroundPlugin.ensureStarted(context)`（`BackgroundPlugin.kt:17-18`），冷启动与热重载各调一次（`AppContainer.kt:326`、`:345`），实现必须幂等。

能力枚举 `PluginCapability` 十档：Theme、Layout、BubbleStyle、QuickCommand、SettingsItem、BottomTab、SessionRow、AgentCard、ComposerButton、MessageRenderer（`PluginApi.kt:24-27`）。消息渲染器按 `rendererId` 命中 `registry.messageRenderers`，命中后整条正文交给插件，宿主只包一层 `PluginBoundary`（`PluginHost.kt:210-211`；字段定义 `Message.kt:13`）。内置 HTML 渲染器占 `html.web`（`HtmlPlugin.kt:39`），自己在 `fillMaxWidth()` 下按内容测高（`HtmlMessageView.kt:66`）。

## Rhino 脚本

入口按 `dsh.main` → `dsh.entry` → `main` → `index.js` → `lib/index.js` 取第一个真实存在的（`JsPluginRuntime.kt:138-155`）。

`ctx` 只有这些成员（`JsPluginRuntime.kt:233-315`）：`pluginId`、`log`、`config` / `setConfig`、`registerContribution`、`command`、`composerButton`、`renderer({id,template})`（模板里 `{{text}}` 换正文，`:389`）、`http`（同步，10 s 超时，`:164`）、`prompt`；没有文件、进程和 Java 对象（`:179-180` 用 `initSafeStandardObjects` 起的沙箱）。

ESM 先降级成普通脚本（`EsmTransform.kt:3-28`）：认 default / namespace / named / `as` / bare import、`export default`、`export const|let|var|function|class`、`export {a as b}`、`import.meta.url`。`export *`、`export {x} from`、动态 `import()` 抛 `UnsupportedException` 带 `file:line`，不静默改。

明确不做的两件：入口里 `import` npm 包或 `node:*`（裸模块直接拒，并一次性列全所有缺的外部模块，`JsPluginRuntime.kt:501`、`ExternalModules.kt:11`）；React 组件树逐节点翻译（只认声明式 UI 描述与 HTML 面板）。路径 `../` 越出包目录在找文件之前就拒（`JsPluginRuntime.kt:501`）。

死循环靠指令计数兜：`OBSERVER_THRESHOLD = 100_000`、`MAX_STEPS = 5_000_000`，`optimizationLevel = -1` 强制解释执行（`JsPluginRuntime.kt:162-176`），超了抛「疑似死循环」（`:185-190`）。编译错误取 Rhino 自己的 message，stage 记 `编译`（`:485`）。

## Node 宿主

`libnode.so` 是 `OpenFlun/nodejs-mobile` 的 v22.23.2 预编译件，`NODE_MODULE_VERSION` 127（`NodeRuntime.kt:9-10`、`node-runtime/build.gradle.kts:59`）。`node::Start` 阻塞跑完整个事件循环，所以放在独立线程 `node-main` 上（`NodeRuntime.kt:13`、`node_starter.cpp:96`）；libnode 全局状态不可重入，同进程再调一次会崩（`NodeRuntime.kt:32`）。stdout/stderr 转 logcat，TAG `DshMobile`（`node_starter.cpp:18`、`NodeRuntime.kt:20`）。

`engine/` 打成 `assets/runtime/engine.zip`（`app/build.gradle.kts:9-14`），首次解到 `filesDir/runtime/engine-v1`（`NodeEngineHost.kt:37`、`:52`、`:413`）。源码树里的 host 文件是 `host/cordis.js`、`host/llm.js`、`host/native-bridge.js`、`host/loader.js`、`host/skill-manager.js`、`host/android-node-exec.js`、`host/node18-compat.js`、`host/unicode-properties.js`，加 `bin.js` 与 `node_modules`。

行协议一行一个 JSON：请求 `{"id","method","params"}`，响应 `{"id","ok":true,"result"}` 或 `{"id","ok":false,"error":{"stage","message"}}`（`bin.js:34-40`）。方法只有 `ping`、`plugins.load{dir,config?}`、`plugins.list`、`rpc{path,endpoint,payload}`、`shutdown`（`bin.js:97`、`:109`、`:118`、`:130`、`:141`）。`shutdown` 别发：它 `ctx.dispose()` 后直接 `process.exit(0)`，同进程的 Android 一起没（`bin.js:227-233`）。

root context 提供 `home`、`skills`、`connection`（`rpc.handle`）、`tools` / `commands` / `agents`、`webServer`、`loader`（load/unload/resolve）、`typert`（resolve）、`subprocess`（run/spawn/exec/execFile/execSync）、`llm`、`credentials`（`cordis.js:330-342`）。没实现的桩会抛「未在移动端实现（诚实桩，不伪造执行）」并带 `stage: apply`、`notImplemented: true`（`cordis.js:155`）。`llm` 与 `credentials` 走 `host.call` 反向请求 Kotlin，两个方向共用一条 socket，id 空间用 `h` 前缀隔开（`native-bridge.js:62`）。

Node 宿主插件的状态是 `Pending` / `Starting` / `Applied` / `Failed(stage,message)`（`NodeBackedPlugin.kt:29-34`）。失败分两层：宿主侧 `disabled`（禁用或安全模式，`:124`）、`engine`（libnode 缺失 / 解包失败 / 端口超时，`:133`）；引擎侧原样透传 `plugins.load` 的 `error.stage`（`:161`）。

`error.stage` 的值域实有 `load`（模块解析与 import）、`apply`（activate）、`rpc`、`parse`、`request`、`internal`、`transport`、`host`（`bin.js:144`、`:161`、`cordis.js:232`、`native-bridge.js:35`）。

技能中心走 `rpc("/skill-manager","list",{})`，引擎自带实现 `engine/host/skill-manager.js`，没装外部插件也能用（`:1`），回包与桌面插件同形 `{ok:true, value:{entries, projectRoots}}`（`:3-9`）。三处差异：不做 git / remote 来源、不写 `skills-lock.json`、不在 `ctx.skills` 注册 provider（`:14-18`）。技能库读 `$DSH_HOME/skill-manager/.agents/skills/<name>/SKILL.md`，开关记在同目录 `state.json`。

## 打包与排错

包布局：`package.json` + 入口文件 + 可选 `node_modules`。`cordis.patch.yml` 只被解析记录、不执行，回包带 `executed:false`（`loader.js:311`）。`engine.zip` 必须真打成 zip：按目录直推 assets 时 aapt2 跳过 `.` 与 `_` 开头的条目，851 个文件只进去 827 个（`NodeEngineHost.kt:384`）。手工打也用 `CreateEntry` 逐条写：`.NET` 的 `ZipFile.CreateFromDirectory` 会写反斜杠 entry 名，Android 解出来是扁平文件（`tools/pack-engine.ps1:2`、`:22`）。

安装或加载失败按三处查：UI 日志与 `.errors/<id>.txt` 有完整堆栈（`PluginPackageService.kt:142`、`:165`、`:321`）；Node 侧回显 `stage`、`dir` 和包内根层前 40 项 `entries`（目录带 `/`，超出补「…共 N 项」，`loader.js:174-175`）；`entries` 里找不到 `package.json`，说明包里多套了一层目录。
