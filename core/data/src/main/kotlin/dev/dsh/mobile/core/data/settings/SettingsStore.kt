package dev.dsh.mobile.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dsh.mobile.core.common.ProxyEnv
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.model.BackMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

// 旧版本 prefs proto 解不开时（升级/降级/写入被打断）退回默认值，不让首启直接崩
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "dsh_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(private val context: Context) {

    // internal 而不是 private：同文件的顶层纯函数 [resolveBackMaterial] / [writeBackMaterial]
    // 要读写这批键，好让「材质读写与旧键迁移」能在纯 JVM 单测里覆盖，不必起 Android Context。
    internal object K {
        val defaultPresetId = stringPreferencesKey("default_preset_id")
        val themeMode = stringPreferencesKey("theme_mode")
        val accent = longPreferencesKey("accent")
        val bubbleMine = longPreferencesKey("bubble_mine")
        val bubbleOther = longPreferencesKey("bubble_other")
        val radius = intPreferencesKey("corner_radius")
        val fontScale = floatPreferencesKey("font_scale")
        val density = floatPreferencesKey("density")
        val compact = booleanPreferencesKey("compact_bubbles")
        val showAgentStatus = booleanPreferencesKey("show_agent_status")
        val showTokens = booleanPreferencesKey("show_token_usage")
        val groupSessions = booleanPreferencesKey("group_sessions")
        val defaultCwd = stringPreferencesKey("default_cwd")
        val notifications = booleanPreferencesKey("notifications")
        val dnd = booleanPreferencesKey("dnd")
        val heartbeat = longPreferencesKey("heartbeat_ms")
        val endpoint = stringPreferencesKey("active_endpoint")
        val runtimeVersion = stringPreferencesKey("runtime_version")
        val quickCommands = stringPreferencesKey("quick_commands")
        val templates = stringPreferencesKey("prompt_templates")
        val showThoughts = booleanPreferencesKey("show_thoughts")
        val autoApprove = booleanPreferencesKey("auto_approve_tools")
        val pluginUiExtend = booleanPreferencesKey("plugin_ui_extend")
        val cwdTreeUri = stringPreferencesKey("cwd_tree_uri")
        val notifyOnComplete = booleanPreferencesKey("notify_on_complete")
        val notifyOnPermission = booleanPreferencesKey("notify_on_permission")
        val notifyVibrate = booleanPreferencesKey("notify_vibrate")
        val notifyHeadsUp = booleanPreferencesKey("notify_heads_up")

        val notifyOngoing = booleanPreferencesKey("notify_ongoing")
        val notifyShowThought = booleanPreferencesKey("notify_show_thought")
        val notifyThoughtLines = intPreferencesKey("notify_thought_lines")
        val notifySuppressForeground = booleanPreferencesKey("notify_suppress_foreground")
        val notifyInlineActions = booleanPreferencesKey("notify_inline_actions")
        val notifyReplySnippet = booleanPreferencesKey("notify_reply_snippet")
        val notifyKeepAfterDone = booleanPreferencesKey("notify_keep_after_done")
        val notifyVibrateStrength = intPreferencesKey("notify_vibrate_strength")

        val sendMode = stringPreferencesKey("send_mode")
        val queueLimit = intPreferencesKey("queue_limit")
        val autoScroll = booleanPreferencesKey("auto_scroll")
        val showDuration = booleanPreferencesKey("show_duration")
        val colorizeAuthors = booleanPreferencesKey("colorize_authors")
        val thoughtsExpanded = booleanPreferencesKey("thoughts_expanded")
        val maxOutputTokens = intPreferencesKey("max_output_tokens")
        val renderSpecialFormats = booleanPreferencesKey("render_special_formats")
        val renderSpecialOverrides = stringPreferencesKey("render_special_overrides")
        val sessionPresetIds = stringPreferencesKey("session_preset_ids")
        val sessionPresetSwitches = stringPreferencesKey("session_preset_switches")

        val groupMaxParallel = intPreferencesKey("group_max_parallel")
        val groupAllowTools = booleanPreferencesKey("group_allow_tools")
        val groupMode = stringPreferencesKey("group_mode")
        val groupOfficePrompt = booleanPreferencesKey("group_office_prompt")
        val groupIsolateMembers = booleanPreferencesKey("group_isolate_members")
        val shellChannel = stringPreferencesKey("shell_channel")
        val groupContextBudget = intPreferencesKey("group_context_budget")
        val collapsedGroups = stringPreferencesKey("collapsed_groups")
    val customFolders = stringPreferencesKey("custom_folders")
        val webSearchEngine = stringPreferencesKey("web_search_engine")
        val searxngInstance = stringPreferencesKey("searxng_instance")
        val maxDownloadBytes = longPreferencesKey("max_download_bytes")
        val proxyUrl = stringPreferencesKey("proxy_url")

        val appIconStyle = stringPreferencesKey("app_icon_style")
        val customIconUri = stringPreferencesKey("custom_icon_uri")
        val startupAnimation = stringPreferencesKey("startup_animation")
        val startupAnimationMs = intPreferencesKey("startup_animation_ms")
        val backgroundUri = stringPreferencesKey("background_uri")
        val backgroundDim = floatPreferencesKey("background_dim")
        val accentHue = floatPreferencesKey("accent_hue")
        val borderWidth = intPreferencesKey("border_width")
        /** 新键：面板材质。旧键 [glassEnabled] 仅用于迁移，不再单独写入（写入时会镜像一份）。 */
        val backMaterial = stringPreferencesKey("back_material")
        val glassEnabled = booleanPreferencesKey("glass_enabled")
        val glassAlpha = floatPreferencesKey("glass_alpha")
        val glassBlur = intPreferencesKey("glass_blur")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings.DEFAULT
        val material = resolveBackMaterial(p)
        AppSettings(
            // 没存过 → 鲸英模式（新的出厂默认）；存过就尊重用户，不动。
            defaultPresetId = AgentPresets.resolveId(p[K.defaultPresetId] ?: AgentPresets.defaultPresetId),
            sessionPresetIds = parseSessionMap(p[K.sessionPresetIds]),
            sessionPresetSwitches = parseSessionMap(p[K.sessionPresetSwitches]),
            themeMode = runCatching { ThemeMode.valueOf(p[K.themeMode] ?: d.themeMode.name) }.getOrDefault(d.themeMode),
            accent = p[K.accent] ?: d.accent,
            bubbleMine = p[K.bubbleMine] ?: d.bubbleMine,
            bubbleOther = p[K.bubbleOther] ?: d.bubbleOther,
            cornerRadiusDp = p[K.radius] ?: d.cornerRadiusDp,
            fontScale = p[K.fontScale] ?: d.fontScale,
            density = p[K.density] ?: d.density,
            compactBubbles = p[K.compact] ?: d.compactBubbles,
            showAgentStatus = p[K.showAgentStatus] ?: d.showAgentStatus,
            showTokenUsage = p[K.showTokens] ?: d.showTokenUsage,
            groupSessions = p[K.groupSessions] ?: d.groupSessions,
            defaultCwd = p[K.defaultCwd] ?: d.defaultCwd,
            notificationsEnabled = p[K.notifications] ?: d.notificationsEnabled,
            dnd = p[K.dnd] ?: d.dnd,
            heartbeatMs = p[K.heartbeat] ?: d.heartbeatMs,
            activeEndpointId = p[K.endpoint] ?: d.activeEndpointId,
            runtimeVersion = p[K.runtimeVersion] ?: d.runtimeVersion,
            quickCommands = p[K.quickCommands]?.split('\u0001')?.filter { it.isNotBlank() } ?: d.quickCommands,
            promptTemplates = p[K.templates]?.split('\u0001')?.filter { it.isNotBlank() } ?: d.promptTemplates,
            showThoughts = p[K.showThoughts] ?: d.showThoughts,
            autoApproveTools = p[K.autoApprove] ?: d.autoApproveTools,
            pluginUiExtend = p[K.pluginUiExtend] ?: d.pluginUiExtend,
            cwdTreeUri = p[K.cwdTreeUri] ?: d.cwdTreeUri,
            collapsedGroups = p[K.collapsedGroups]?.split('\u0001')?.filter { it.isNotBlank() } ?: d.collapsedGroups,
            customFolders = p[K.customFolders]?.split('\u0001')?.filter { it.isNotBlank() } ?: d.customFolders,
            webSearchEngine = p[K.webSearchEngine] ?: d.webSearchEngine,
            searxngInstance = p[K.searxngInstance] ?: d.searxngInstance,
            maxDownloadBytes = p[K.maxDownloadBytes] ?: d.maxDownloadBytes,
            proxyUrl = p[K.proxyUrl] ?: d.proxyUrl,
            notifyOnComplete = p[K.notifyOnComplete] ?: d.notifyOnComplete,
            notifyOnPermission = p[K.notifyOnPermission] ?: d.notifyOnPermission,
            notifyVibrate = p[K.notifyVibrate] ?: d.notifyVibrate,
            notifyHeadsUp = p[K.notifyHeadsUp] ?: d.notifyHeadsUp,
            notifyOngoing = p[K.notifyOngoing] ?: d.notifyOngoing,
            notifyShowThought = p[K.notifyShowThought] ?: d.notifyShowThought,
            notifyThoughtLines = p[K.notifyThoughtLines] ?: d.notifyThoughtLines,
            notifySuppressForeground = p[K.notifySuppressForeground] ?: d.notifySuppressForeground,
            notifyInlineActions = p[K.notifyInlineActions] ?: d.notifyInlineActions,
            notifyReplySnippet = p[K.notifyReplySnippet] ?: d.notifyReplySnippet,
            notifyKeepAfterDone = p[K.notifyKeepAfterDone] ?: d.notifyKeepAfterDone,
            notifyVibrateStrength = p[K.notifyVibrateStrength] ?: d.notifyVibrateStrength,
            sendMode = runCatching { SendMode.valueOf(p[K.sendMode] ?: d.sendMode.name) }.getOrDefault(d.sendMode),
            queueLimit = p[K.queueLimit] ?: d.queueLimit,
            autoScroll = p[K.autoScroll] ?: d.autoScroll,
            showDuration = p[K.showDuration] ?: d.showDuration,
            colorizeAuthors = p[K.colorizeAuthors] ?: d.colorizeAuthors,
            thoughtsExpanded = p[K.thoughtsExpanded] ?: d.thoughtsExpanded,
                maxOutputTokens = p[K.maxOutputTokens] ?: d.maxOutputTokens,
            renderSpecialFormats = p[K.renderSpecialFormats] ?: d.renderSpecialFormats,
            renderSpecialOverrides = parseRenderSpecialOverrides(p[K.renderSpecialOverrides]),
            groupMaxParallel = p[K.groupMaxParallel] ?: d.groupMaxParallel,
            groupAllowTools = p[K.groupAllowTools] ?: d.groupAllowTools,
            groupMode = p[K.groupMode] ?: d.groupMode,
            groupOfficePrompt = p[K.groupOfficePrompt] ?: d.groupOfficePrompt,
            groupContextBudget = p[K.groupContextBudget] ?: d.groupContextBudget,
        groupIsolateMembers = p[K.groupIsolateMembers] ?: d.groupIsolateMembers,
        shellChannel = p[K.shellChannel] ?: d.shellChannel,
        appIconStyle = p[K.appIconStyle] ?: d.appIconStyle,
        customIconUri = p[K.customIconUri] ?: d.customIconUri,
        startupAnimation = p[K.startupAnimation] ?: d.startupAnimation,
        startupAnimationMs = p[K.startupAnimationMs] ?: d.startupAnimationMs,
        backgroundUri = p[K.backgroundUri] ?: d.backgroundUri,
        backgroundDim = p[K.backgroundDim] ?: d.backgroundDim,
        accentHue = p[K.accentHue] ?: d.accentHue,
        borderWidthDp = p[K.borderWidth] ?: d.borderWidthDp,
        backMaterial = material.key,
        glassAlpha = BackMaterial.clampStrength(p[K.glassAlpha] ?: d.glassAlpha),
        glassBlurDp = p[K.glassBlur] ?: d.glassBlurDp,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[K.themeMode] = mode.name }
    suspend fun setAccent(color: Long) = edit { it[K.accent] = color }
    suspend fun setBubbleMine(color: Long) = edit { it[K.bubbleMine] = color }
    suspend fun setBubbleOther(color: Long) = edit { it[K.bubbleOther] = color }
    suspend fun setRadius(dp: Int) = edit { it[K.radius] = dp }
    suspend fun setFontScale(scale: Float) = edit { it[K.fontScale] = scale }
    suspend fun setDensity(d: Float) = edit { it[K.density] = d }
    suspend fun setCompact(compact: Boolean) = edit { it[K.compact] = compact }
    suspend fun setShowAgentStatus(v: Boolean) = edit { it[K.showAgentStatus] = v }
    suspend fun setShowTokenUsage(v: Boolean) = edit { it[K.showTokens] = v }
    suspend fun setGroupSessions(v: Boolean) = edit { it[K.groupSessions] = v }

    suspend fun setDefaultCwd(v: String) = edit { it[K.defaultCwd] = v }
    suspend fun setNotifications(v: Boolean) = edit { it[K.notifications] = v }
    suspend fun setDnd(v: Boolean) = edit { it[K.dnd] = v }
    suspend fun setHeartbeat(ms: Long) = edit { it[K.heartbeat] = ms }
    suspend fun setActiveEndpoint(id: String) = edit { it[K.endpoint] = id }
    suspend fun setRuntimeVersion(v: String) = edit { it[K.runtimeVersion] = v }
    suspend fun setQuickCommands(list: List<String>) = edit { it[K.quickCommands] = list.joinToString("\u0001") }
    suspend fun setPromptTemplates(list: List<String>) = edit { it[K.templates] = list.joinToString("\u0001") }
    suspend fun setShowThoughts(v: Boolean) = edit { it[K.showThoughts] = v }
    suspend fun setAutoApprove(v: Boolean) = edit { it[K.autoApprove] = v }
    suspend fun setPluginUiExtend(v: Boolean) = edit { it[K.pluginUiExtend] = v }
    suspend fun setCwdTreeUri(v: String) = edit { it[K.cwdTreeUri] = v }
    suspend fun setNotifyOnComplete(v: Boolean) = edit { it[K.notifyOnComplete] = v }
    suspend fun setNotifyOnPermission(v: Boolean) = edit { it[K.notifyOnPermission] = v }
    suspend fun setNotifyVibrate(v: Boolean) = edit { it[K.notifyVibrate] = v }
    suspend fun setNotifyHeadsUp(v: Boolean) = edit { it[K.notifyHeadsUp] = v }

    suspend fun setNotifyOngoing(v: Boolean) = edit { it[K.notifyOngoing] = v }
    suspend fun setNotifyShowThought(v: Boolean) = edit { it[K.notifyShowThought] = v }
    suspend fun setNotifyThoughtLines(v: Int) = edit { it[K.notifyThoughtLines] = v }
    suspend fun setNotifySuppressForeground(v: Boolean) = edit { it[K.notifySuppressForeground] = v }
    suspend fun setNotifyInlineActions(v: Boolean) = edit { it[K.notifyInlineActions] = v }
    suspend fun setNotifyReplySnippet(v: Boolean) = edit { it[K.notifyReplySnippet] = v }
    suspend fun setNotifyKeepAfterDone(v: Boolean) = edit { it[K.notifyKeepAfterDone] = v }
    suspend fun setNotifyVibrateStrength(v: Int) = edit { it[K.notifyVibrateStrength] = v }

    suspend fun setDefaultPresetId(v: String) = edit { it[K.defaultPresetId] = v }

    /**
     * 会话内切换预设：只改这一个会话，全局默认不动（下次新建会话仍按默认）。
     * 同时清掉本会话的开关覆盖 —— 开关属于上一个预设，换了预设就该按新预设的默认来。
     */
    suspend fun setSessionPresetId(sessionId: String, presetId: String) = edit {
        val cur = parseSessionMap(it[K.sessionPresetIds]).toMutableMap()
        cur[sessionId] = presetId
        it[K.sessionPresetIds] = encodeSessionMap(cur)
        val switches = parseSessionMap(it[K.sessionPresetSwitches]).toMutableMap()
        if (switches.remove(sessionId) != null) it[K.sessionPresetSwitches] = encodeSessionMap(switches)
    }

    /** 会话内开关：写整份「打开的开关 id」，空集合 = 这个会话的开关全关。 */
    suspend fun setSessionPresetSwitches(sessionId: String, on: Set<String>) = edit {
        val cur = parseSessionMap(it[K.sessionPresetSwitches]).toMutableMap()
        cur[sessionId] = on.joinToString(",")
        it[K.sessionPresetSwitches] = encodeSessionMap(cur)
    }

    suspend fun setSendMode(v: SendMode) = edit { it[K.sendMode] = v.name }
    suspend fun setQueueLimit(v: Int) = edit { it[K.queueLimit] = v }
    suspend fun setAutoScroll(v: Boolean) = edit { it[K.autoScroll] = v }
    suspend fun setShowDuration(v: Boolean) = edit { it[K.showDuration] = v }
    suspend fun setColorizeAuthors(v: Boolean) = edit { it[K.colorizeAuthors] = v }
    suspend fun setThoughtsExpanded(v: Boolean) = edit { it[K.thoughtsExpanded] = v }
    suspend fun setMaxOutputTokens(v: Int) = edit { it[K.maxOutputTokens] = v }

    /** 全局默认：新会话是否默认渲染特殊格式。 */
    suspend fun setRenderSpecialFormats(v: Boolean) = edit { it[K.renderSpecialFormats] = v }

    /** 会话内部滑块：只改这一个会话，不碰全局默认。 */
    suspend fun setRenderSpecialOverride(sessionId: String, v: Boolean) = edit {
        val cur = parseRenderSpecialOverrides(it[K.renderSpecialOverrides]).toMutableMap()
        cur[sessionId] = v
        it[K.renderSpecialOverrides] = encodeRenderSpecialOverrides(cur)
    }

    suspend fun setGroupMaxParallel(v: Int) = edit { it[K.groupMaxParallel] = v }
    suspend fun setGroupAllowTools(v: Boolean) = edit { it[K.groupAllowTools] = v }
    suspend fun setGroupMode(v: String) = edit { it[K.groupMode] = v }
    suspend fun setGroupOfficePrompt(v: Boolean) = edit { it[K.groupOfficePrompt] = v }
    suspend fun setGroupContextBudget(v: Int) = edit { it[K.groupContextBudget] = v }
    suspend fun setGroupIsolateMembers(v: Boolean) = edit { it[K.groupIsolateMembers] = v }
    suspend fun setShellChannel(v: String) = edit { it[K.shellChannel] = v }
    suspend fun setCollapsedGroups(v: List<String>) = edit { it[K.collapsedGroups] = v.joinToString("\u0001") }

    suspend fun setCustomFolders(v: List<String>) = edit { it[K.customFolders] = v.joinToString("\u0001") }

    suspend fun setWebSearchEngine(v: String) = edit { it[K.webSearchEngine] = v }

    suspend fun setSearxngInstance(v: String) = edit { it[K.searxngInstance] = v }

    suspend fun setMaxDownloadBytes(v: Long) = edit { it[K.maxDownloadBytes] = v }

    /** 存规整后的代理地址；空串 = 直连（ProxyEnv 只在非空时注入）。 */
    suspend fun setProxyUrl(v: String) = edit { it[K.proxyUrl] = ProxyEnv.normalize(v) }

    // ---- 外观 ----
    suspend fun setAppIconStyle(v: String) = edit { it[K.appIconStyle] = if (v == "custom") "custom" else "system" }
    suspend fun setCustomIconUri(v: String) = edit { it[K.customIconUri] = v }
    suspend fun setStartupAnimation(v: String) = edit { it[K.startupAnimation] = v }
    suspend fun setStartupAnimationMs(v: Int) = edit { it[K.startupAnimationMs] = v.coerceIn(0, 5000) }
    suspend fun setBackgroundUri(v: String) = edit { it[K.backgroundUri] = v }
    suspend fun setBackgroundDim(v: Float) = edit { it[K.backgroundDim] = v.coerceIn(0f, 0.8f) }
    suspend fun setAccentHue(v: Float) = edit { it[K.accentHue] = if (v < 0f) -1f else v.coerceIn(0f, 360f) }
    suspend fun setBorderWidth(dp: Int) = edit { it[K.borderWidth] = dp.coerceIn(0, 8) }
    /**
     * 写材质：新键落盘，同时镜像旧键 `glass_enabled`，这样降级回 v0.05 也还是同样的观感。
     */
    suspend fun setBackMaterial(v: String) = edit { writeBackMaterial(it, v) }

    /** 旧入口：只表达「开 / 关玻璃」，保留给尚未改成材质选择的调用方。 */
    suspend fun setGlassEnabled(v: Boolean) = setBackMaterial(if (v) BackMaterial.MATTE.key else BackMaterial.NONE.key)
    suspend fun setGlassAlpha(v: Float) = edit { it[K.glassAlpha] = BackMaterial.clampStrength(v) }
    suspend fun setGlassBlur(dp: Int) = edit { it[K.glassBlur] = dp.coerceIn(0, 40) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }
}

/**
 * 材质读取（含旧键迁移）。做成纯函数是为了能直接喂 Preferences 做单测：
 * 起一个 Context 才能验迁移的话，这段逻辑实际上永远不会被测到。
 *
 * 顺序：新键 `back_material` 优先 → 旧键 `glass_enabled`=true → 磨砂 →
 * 旧键=false → 无材质 → 两个键都没有 → 默认磨砂。
 */
internal fun resolveBackMaterial(p: Preferences): BackMaterial = BackMaterial.migrate(
    stored = p[SettingsStore.K.backMaterial],
    legacyGlassEnabled = if (p.contains(SettingsStore.K.glassEnabled)) p[SettingsStore.K.glassEnabled] else null,
)

/**
 * 材质写入：新键落盘，同时镜像旧键 `glass_enabled`，降级回 v0.05 的用户观感不变。
 * 读不走镜像键（[resolveBackMaterial] 新键优先），所以两边不会互相漂移。
 */
internal fun writeBackMaterial(p: androidx.datastore.preferences.core.MutablePreferences, value: String) {
    val material = BackMaterial.parse(value)
    p[SettingsStore.K.backMaterial] = material.key
    p[SettingsStore.K.glassEnabled] = material.isGlass
}

/**
 * 会话级渲染覆盖的落盘格式：`sid=1\u0001sid2=0`。
 *
 * 用 \u0001 分隔（和 collapsedGroups 等列表型字段一致），值只存 1/0。
 * 做成纯函数是为了能直接单测：起 Context 才能验的话这段逻辑实际上永远不会被测到。
 */
internal fun encodeRenderSpecialOverrides(map: Map<String, Boolean>): String =
    map.entries.joinToString("\u0001") { "${it.key}=${if (it.value) 1 else 0}" }

/**
 * 会话级预设 / 开关的落盘格式：`sid=值\u0001sid2=值2`。
 * 值本身不含 \u0001，所以按最后一个 '=' 切分就够；脏项直接丢掉，手改过的 prefs 不该让读取崩。
 */
internal fun encodeSessionMap(map: Map<String, String>): String =
    map.entries.joinToString("\u0001") { "${it.key}=${it.value}" }

internal fun parseSessionMap(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (item in raw.split('\u0001')) {
        val sep = item.lastIndexOf('=')
        if (sep <= 0) continue
        out[item.substring(0, sep)] = item.substring(sep + 1)
    }
    return out
}

/** [encodeRenderSpecialOverrides] 的逆运算；脏项（无分隔符 / 空 id）直接丢掉，不让手改过的 prefs 崩掉读取。 */
internal fun parseRenderSpecialOverrides(raw: String?): Map<String, Boolean> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Boolean>()
    for (item in raw.split('\u0001')) {
        val sep = item.lastIndexOf('=')
        if (sep <= 0) continue
        out[item.substring(0, sep)] = item.substring(sep + 1).trim() == "1"
    }
    return out
}
