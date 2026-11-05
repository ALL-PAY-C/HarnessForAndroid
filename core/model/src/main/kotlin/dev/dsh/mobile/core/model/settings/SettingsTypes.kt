package dev.dsh.mobile.core.model.settings

import dev.dsh.mobile.core.model.BackMaterial
import dev.dsh.mobile.core.model.UiLanguage
import dev.dsh.mobile.core.model.preset.AgentPresets

enum class ThemeMode { System, Light, Dark }

/** 忙的时候再发消息怎么办：排队 / 插话 / 立即打断。 */
enum class SendMode { Queue, Steer, Insert }

/** Everything the user can customize. Persisted in DataStore; survives restarts and process death. */
data class AppSettings(
    val defaultPresetId: String = "default",
    /**
     * 会话级预设覆盖：单独切过预设的会话记在这里，没记的跟随 [defaultPresetId]。
     * 和 [renderSpecialOverrides] 同一套语义——会话内切换不动全局默认。
     */
    val sessionPresetIds: Map<String, String> = emptyMap(),
    /**
     * 会话级开关覆盖：只记用户动过的会话，值 = 逗号分隔的「打开的开关 id」。
     * 没有记录的会话按预设自带的 defaultOn，因此预设升级换开关时老会话自然跟上。
     */
    val sessionPresetSwitches: Map<String, String> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.System,
    /** 界面语言三态（T9.11 §2.1）：System 跟随系统首选语言。 */
    // 默认中文：本产品面向中文用户，英文只作为显式选择（含「跟随系统」）。
    val uiLanguage: UiLanguage = UiLanguage.Zh,
    val accent: Long = 0xFF3B7CFF,
    val bubbleMine: Long = 0xFF3B7CFF,
    val bubbleOther: Long = 0xFFF1F2F5,
    val cornerRadiusDp: Int = 18,
    val fontScale: Float = 1.0f,
    val density: Float = 1.0f,
    val compactBubbles: Boolean = false,
    val showAgentStatus: Boolean = true,
    val showTokenUsage: Boolean = true,
    val groupSessions: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val dnd: Boolean = false,
    val heartbeatMs: Long = 15_000L,
    val activeEndpointId: String = "local",
    val runtimeVersion: String = "bundled",
    val quickCommands: List<String> = listOf("/new", "/clear", "/compact", "/model"),
    val promptTemplates: List<String> = listOf(
        "解释这段代码",
        "帮我修这个 bug",
        "把这段重构得更清晰",
        "写单元测试",
    ),
    val showThoughts: Boolean = true,
    val autoApproveTools: Boolean = false,
    /** 设置 → 拓展：允许插件往宿主界面注入 UI（扩展插件 UI 配置）。默认关闭。 */
    val pluginUiExtend: Boolean = false,

    // ---- 通知：基础四开关 ----
    val notifyOnComplete: Boolean = true,
    val notifyOnPermission: Boolean = true,
    val notifyVibrate: Boolean = true,
    val notifyHeadsUp: Boolean = true,

    // ---- 通知：常驻聚合（n 个会话 + 思维链 + 通知内选项）----
    /** 常驻一条聚合通知：展示每个正在思考的会话与它的思维链。 */
    val notifyOngoing: Boolean = true,
    /** 聚合通知里展示思维链片段。 */
    val notifyShowThought: Boolean = true,
    /** 每条会话在通知里最多显示几行思维链。 */
    val notifyThoughtLines: Int = 3,
    /** 正在看的会话不在通知里重复显示。 */
    val notifySuppressForeground: Boolean = true,
    /** 待确认时在通知里直接给出选项按钮。 */
    val notifyInlineActions: Boolean = true,
    /** 完成时把最后一句回复摘要放进通知。 */
    val notifyReplySnippet: Boolean = true,
    /** 一次思考结束但还有别的会话在跑时，仍然刷新常驻通知。 */
    val notifyKeepAfterDone: Boolean = true,
    /** 震动强度 0=轻 1=中 2=重。 */
    val notifyVibrateStrength: Int = 1,

    // ---- 对话 ----
    /** 忙时发送默认走哪个模式。 */
    val sendMode: SendMode = SendMode.Queue,
    /** 队列最多缓存多少条，超出提示。 */
    val queueLimit: Int = 20,
    /** 流式输出时自动滚到底。 */
    val autoScroll: Boolean = true,
    /** 展示「本轮耗时」。 */
    val showDuration: Boolean = true,
    /** 群聊里每个成员用独立头像色。 */
    val colorizeAuthors: Boolean = true,
    /** 思考过程默认展开。 */
    val thoughtsExpanded: Boolean = true,
    /** 单轮回复的 max_tokens 上限；0 = 不限制。推理模型不设上限会写出几万字思维链。 */
    val maxOutputTokens: Int = 0,

    // ---- 特殊格式渲染（HTML / LaTeX / Markdown / 代码块）----
    /** 全局默认：消息里识别到特殊格式就富渲染。 */
    val renderSpecialFormats: Boolean = true,
    /** 会话级覆盖：单独切过开关的会话记在这里，没记的跟随 [renderSpecialFormats]。 */
    val renderSpecialOverrides: Map<String, Boolean> = emptyMap(),

    // ---- 群组编排 ----
    /** 群成员并行上限。 */
    val groupMaxParallel: Int = 3,
    /** 群成员可以使用工具（shell 等）。 */
    val groupAllowTools: Boolean = false,
    /** 编排策略：auto / parallel / chain / automaton。 */
    val groupMode: String = "auto",
    /** 群聊里注入的「办公室」分工提示。 */
    val groupOfficePrompt: Boolean = true,
    /** 每个成员的上下文预算（字符）。 */
    val groupContextBudget: Int = 24_000,
    /** 群聊里同一个人被多次拉入时，允许每个实例独立上下文（关掉就退化成共享，一般别关）。 */
    val groupIsolateMembers: Boolean = true,
    /** 房间编排器：legacy（默认，首轮固定+轮转）/ brief-moderated（T5.2b 主持人调度）。 */
    val groupScheduler: String = "legacy",
    /** 主持人模型 id；空串 = 跟召集者会话的模型走。 */
    val groupModeratorModel: String = "",
    /** 子代理最多嵌套几层（1 = 主代理能派、子代理不能再派；默认 2）。 */
    val subagentMaxDepth: Int = 2,
    /** 六类系统能力的三档授权，落盘形态见 [dev.dsh.mobile.core.platform.device.CapabilityTiers]；空串 = 全 Ask。 */
    val capabilityTiers: String = "",

    // ---- 工具通道 ----
    /** 手机工具执行通道：auto / shizuku / root / app / termux。 */
    val shellChannel: String = "auto",

    /** Default workspace for new chats; empty means the app-private dir. */
    val defaultCwd: String = "",
    /** SAF tree uri：只用于展示与提示，运行时读写仍走 [defaultCwd]。 */
    val cwdTreeUri: String = "",
    /** 会话列表里被折叠的分组名（进程重启后仍然折叠）。 */
    val collapsedGroups: List<String> = emptyList(),
    /** 用户显式建过的文件夹（含空文件夹）。空文件夹不靠会话反推，否则删不掉也建不出来。 */
    val customFolders: List<String> = emptyList(),
    /** web_search 的引擎选择：`auto` 或具体引擎 id（bing / duckduckgo / searxng / exa）。 */
    val webSearchEngine: String = "auto",
    /** SearXNG 实例地址；留空表示这台机器没配这个需密钥引擎。 */
    val searxngInstance: String = "",
    /** 单个下载的大小上限（字节）。 */
    val maxDownloadBytes: Long = 64L * 1024 * 1024,
    /** 全局代理（HTTP(S)_PROXY）。空 = 直连；非空时所有子进程 env 都带上它。 */
    val proxyUrl: String = "",

    // ---- 外观（Wallpaper 式：图标 / 背景 / 主色调 / 边框 / 玻璃）----
    /** 图标样式：`system` 用内置矢量图标，`custom` 用 [customIconUri] 指向的图片。 */
    val appIconStyle: String = "system",
    /** 自定义图标的 SAF uri（content://）；空 = 未设置。 */
    val customIconUri: String = "",
    /** 启动动画 spec；空串 = 不开（默认）。写法见 dev.dsh.mobile.design.startup.StartupAnimations.parse。 */
    val startupAnimation: String = "",
    /** 启动动画时长（ms）；0 = 用样式自带默认值。 */
    val startupAnimationMs: Int = 0,
    /** 自定义背景图的 SAF uri；空 = 按 [themeMode] 走纯色背景。 */
    val backgroundUri: String = "",
    /** 背景压暗（0f-0.8f），给亮色壁纸上叠黑罩，保证正文可读。 */
    val backgroundDim: Float = 0f,
    /** 主色调色相 0-360；负值 = 不接管，仍然用 [accent] 的色值。 */
    val accentHue: Float = -1f,
    /** 卡片 / 面板边框宽度（0-8dp），0 = 无边框。 */
    val borderWidthDp: Int = 0,
    /**
     * 面板材质：`none` / `matte` / `liquid` / `acrylic`，见 [BackMaterial]。
     * 默认实底（L2 卡面统一实底后的观感）；旧键 `glass_enabled` 的迁移在读取处完成。
     */
    val backMaterial: String = BackMaterial.DEFAULT.key,
    /** 面板强度 / 不透明度（0.45f-1f），只对 matte / liquid 生效。 */
    val glassAlpha: Float = 0.82f,
    /** 背景雾化半径（0-40dp）；Android 12 以下自动降级为 0。 */
    val glassBlurDp: Int = 0,
) {
    /** [backMaterial] 的类型化读法；脏值回落到默认材质。 */
    val material: BackMaterial get() = BackMaterial.parse(backMaterial)

    /**
     * 旧字段名，保留给尚未迁移的调用方（自更新降级到 v0.05 时也还能读）。
     * 做成计算属性而不是构造参数：只要它不从 [backMaterial] 推导，两个字段迟早互相漂移
     * ——单测 glassEnabledGetterIsDerivedFromBackMaterial 就是为这条设的。
     */
    val glassEnabled: Boolean get() = material.isGlass

    /**
     * 该会话实际生效的特殊格式渲染。单独切过的会话用自己的值，
     * 新会话没有覆盖记录，自然跟随全局默认（= 建会话时按设置初始化）。
     */
    fun renderSpecialFor(sessionId: String?): Boolean =
        sessionId?.let { renderSpecialOverrides[it] } ?: renderSpecialFormats

    /** 该会话实际生效的预设 id：会话切过就用会话的，否则跟随全局默认。 */
    fun presetFor(sessionId: String?): String =
        AgentPresets.resolveId(sessionId?.let { sessionPresetIds[it] } ?: defaultPresetId)

    /** 该会话记录过的开关集合；null = 没动过，按预设默认。 */
    fun switchOnFor(sessionId: String?): Set<String>? =
        sessionId?.let { sessionPresetSwitches[it] }?.split(',')?.filter { it.isNotBlank() }?.toSet()

    companion object {
        val DEFAULT = AppSettings()
    }
}
