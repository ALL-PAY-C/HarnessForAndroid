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
import dev.dsh.mobile.core.platform.ProxyEnv
import dev.dsh.mobile.core.platform.UiLanguage
import dev.dsh.mobile.core.platform.UiLocale
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.model.port.SettingsApi
import dev.dsh.mobile.core.model.BackMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// S1: 类型本体已上移 core:model.settings，这里只留源级兼容桥（S5 删桥改 import）。
typealias ThemeMode = dev.dsh.mobile.core.model.settings.ThemeMode
typealias SendMode = dev.dsh.mobile.core.model.settings.SendMode
typealias AppSettings = dev.dsh.mobile.core.model.settings.AppSettings


// 旧版本 prefs proto 解不开时（升级/降级/写入被打断）退回默认值，不让首启直接崩
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "dsh_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 快照里 Set<String> 的分隔符：stringSetPreferencesKey 只能存集合本身，序列化要自己定分隔。 */
private const val LIST_SEP = "\u0001"

class SettingsStore(private val context: Context) : SettingsApi {

    // internal 而不是 private：同文件的顶层纯函数 [resolveBackMaterial] / [writeBackMaterial]
    // 要读写这批键，好让「材质读写与旧键迁移」能在纯 JVM 单测里覆盖，不必起 Android Context。
    internal object K {
        val defaultPresetId = stringPreferencesKey("default_preset_id")
        val themeMode = stringPreferencesKey("theme_mode")
        val uiLanguage = stringPreferencesKey("ui_language")
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
        /** 崩溃上传（默认关：只落盘，不联网）。 */
        val crashUploadEnabled = booleanPreferencesKey("crash.upload.enabled")
        val crashUploadEndpoint = stringPreferencesKey("crash.upload.endpoint")
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
        val subagentMaxDepth = intPreferencesKey("subagent_max_depth")
        val capabilityTiers = stringPreferencesKey("capability_tiers")
        val groupScheduler = stringPreferencesKey("group_scheduler")
        val groupModeratorModel = stringPreferencesKey("group_moderator_model")
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

    override val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings.DEFAULT
        val material = resolveBackMaterial(p)
        AppSettings(
            // 没存过 → 鲸英模式（新的出厂默认）；存过就尊重用户，不动。
            defaultPresetId = AgentPresets.resolveId(p[K.defaultPresetId] ?: AgentPresets.defaultPresetId),
            sessionPresetIds = parseSessionMap(p[K.sessionPresetIds]),
            sessionPresetSwitches = parseSessionMap(p[K.sessionPresetSwitches]),
            themeMode = runCatching { ThemeMode.valueOf(p[K.themeMode] ?: d.themeMode.name) }.getOrDefault(d.themeMode),
            uiLanguage = runCatching { UiLanguage.valueOf(p[K.uiLanguage] ?: d.uiLanguage.name) }
                .getOrDefault(d.uiLanguage),
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
            groupScheduler = p[K.groupScheduler] ?: d.groupScheduler,
            subagentMaxDepth = p[K.subagentMaxDepth] ?: d.subagentMaxDepth,
            capabilityTiers = p[K.capabilityTiers] ?: d.capabilityTiers,
            groupModeratorModel = p[K.groupModeratorModel] ?: d.groupModeratorModel,
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

    override suspend fun setThemeMode(mode: ThemeMode) = edit { it[K.themeMode] = mode.name }

    /**
     * 写语言：DataStore 是唯一事实来源，同时同步写 SharedPreferences 镜像。
     * 镜像存在的唯一理由是 Application.attachBaseContext 必须同步拿到语言（T9.11 §2.1），
     * 不加镜像的话冷启动第一帧会先画中文再跳英文。
     */
    override suspend fun setUiLanguage(language: UiLanguage) {
        edit { it[K.uiLanguage] = language.name }
        UiLocale.mirror(context, language)
    }
    override suspend fun setAccent(color: Long) = edit { it[K.accent] = color }
    override suspend fun setBubbleMine(color: Long) = edit { it[K.bubbleMine] = color }
    override suspend fun setBubbleOther(color: Long) = edit { it[K.bubbleOther] = color }
    override suspend fun setRadius(dp: Int) = edit { it[K.radius] = dp }
    override suspend fun setFontScale(scale: Float) = edit { it[K.fontScale] = scale }
    override suspend fun setDensity(d: Float) = edit { it[K.density] = d }
    override suspend fun setCompact(compact: Boolean) = edit { it[K.compact] = compact }
    override suspend fun setShowAgentStatus(v: Boolean) = edit { it[K.showAgentStatus] = v }
    override suspend fun setShowTokenUsage(v: Boolean) = edit { it[K.showTokens] = v }
    override suspend fun setGroupSessions(v: Boolean) = edit { it[K.groupSessions] = v }

    override suspend fun setDefaultCwd(v: String) = edit { it[K.defaultCwd] = v }
    override suspend fun setNotifications(v: Boolean) = edit { it[K.notifications] = v }
    override suspend fun setDnd(v: Boolean) = edit { it[K.dnd] = v }
    override suspend fun setHeartbeat(ms: Long) = edit { it[K.heartbeat] = ms }
    suspend fun setActiveEndpoint(id: String) = edit { it[K.endpoint] = id }
    override suspend fun setRuntimeVersion(v: String) = edit { it[K.runtimeVersion] = v }
    override suspend fun setQuickCommands(list: List<String>) = edit { it[K.quickCommands] = list.joinToString("\u0001") }
    override suspend fun setPromptTemplates(list: List<String>) = edit { it[K.templates] = list.joinToString("\u0001") }
    override suspend fun setShowThoughts(v: Boolean) = edit { it[K.showThoughts] = v }
    override suspend fun setAutoApprove(v: Boolean) = edit { it[K.autoApprove] = v }
    override suspend fun setPluginUiExtend(v: Boolean) = edit { it[K.pluginUiExtend] = v }
    override suspend fun setCwdTreeUri(v: String) = edit { it[K.cwdTreeUri] = v }
    override suspend fun setNotifyOnComplete(v: Boolean) = edit { it[K.notifyOnComplete] = v }
    override suspend fun setNotifyOnPermission(v: Boolean) = edit { it[K.notifyOnPermission] = v }
    override suspend fun setNotifyVibrate(v: Boolean) = edit { it[K.notifyVibrate] = v }
    override suspend fun setNotifyHeadsUp(v: Boolean) = edit { it[K.notifyHeadsUp] = v }

    override suspend fun setNotifyOngoing(v: Boolean) = edit { it[K.notifyOngoing] = v }
    override suspend fun setNotifyShowThought(v: Boolean) = edit { it[K.notifyShowThought] = v }
    override suspend fun setNotifyThoughtLines(v: Int) = edit { it[K.notifyThoughtLines] = v }
    override suspend fun setNotifySuppressForeground(v: Boolean) = edit { it[K.notifySuppressForeground] = v }
    override suspend fun setNotifyInlineActions(v: Boolean) = edit { it[K.notifyInlineActions] = v }
    override suspend fun setNotifyReplySnippet(v: Boolean) = edit { it[K.notifyReplySnippet] = v }
    override suspend fun setNotifyKeepAfterDone(v: Boolean) = edit { it[K.notifyKeepAfterDone] = v }
    override suspend fun setNotifyVibrateStrength(v: Int) = edit { it[K.notifyVibrateStrength] = v }

    override suspend fun setDefaultPresetId(v: String) = edit { it[K.defaultPresetId] = v }

    /**
     * 会话内切换预设：只改这一个会话，全局默认不动（下次新建会话仍按默认）。
     * 同时清掉本会话的开关覆盖 —— 开关属于上一个预设，换了预设就该按新预设的默认来。
     */
    override suspend fun setSessionPresetId(sessionId: String, presetId: String) = edit {
        val cur = parseSessionMap(it[K.sessionPresetIds]).toMutableMap()
        cur[sessionId] = presetId
        it[K.sessionPresetIds] = encodeSessionMap(cur)
        val switches = parseSessionMap(it[K.sessionPresetSwitches]).toMutableMap()
        if (switches.remove(sessionId) != null) it[K.sessionPresetSwitches] = encodeSessionMap(switches)
    }

    /** 会话内开关：写整份「打开的开关 id」，空集合 = 这个会话的开关全关。 */
    override suspend fun setSessionPresetSwitches(sessionId: String, on: Set<String>) = edit {
        val cur = parseSessionMap(it[K.sessionPresetSwitches]).toMutableMap()
        cur[sessionId] = on.joinToString(",")
        it[K.sessionPresetSwitches] = encodeSessionMap(cur)
    }

    override suspend fun setSendMode(v: SendMode) = edit { it[K.sendMode] = v.name }
    override suspend fun setQueueLimit(v: Int) = edit { it[K.queueLimit] = v }
    override suspend fun setAutoScroll(v: Boolean) = edit { it[K.autoScroll] = v }
    override suspend fun setShowDuration(v: Boolean) = edit { it[K.showDuration] = v }
    override suspend fun setColorizeAuthors(v: Boolean) = edit { it[K.colorizeAuthors] = v }
    override suspend fun setThoughtsExpanded(v: Boolean) = edit { it[K.thoughtsExpanded] = v }
    override suspend fun setMaxOutputTokens(v: Int) = edit { it[K.maxOutputTokens] = v }

    /** 全局默认：新会话是否默认渲染特殊格式。 */
    override suspend fun setRenderSpecialFormats(v: Boolean) = edit { it[K.renderSpecialFormats] = v }

    /** 会话内部滑块：只改这一个会话，不碰全局默认。 */
    override suspend fun setRenderSpecialOverride(sessionId: String, v: Boolean) = edit {
        val cur = parseRenderSpecialOverrides(it[K.renderSpecialOverrides]).toMutableMap()
        cur[sessionId] = v
        it[K.renderSpecialOverrides] = encodeRenderSpecialOverrides(cur)
    }

    override suspend fun setGroupMaxParallel(v: Int) = edit { it[K.groupMaxParallel] = v }
    override suspend fun setGroupAllowTools(v: Boolean) = edit { it[K.groupAllowTools] = v }
    override suspend fun setGroupMode(v: String) = edit { it[K.groupMode] = v }
    override suspend fun setGroupOfficePrompt(v: Boolean) = edit { it[K.groupOfficePrompt] = v }
    override suspend fun setGroupContextBudget(v: Int) = edit { it[K.groupContextBudget] = v }

    /** 子代理嵌套深度：1..3，越界就地夹紧（滑杆/预置写错不会把工具永久关死）。 */
    override suspend fun setSubagentMaxDepth(v: Int) = edit { it[K.subagentMaxDepth] = v.coerceIn(1, 3) }

    /** 六类能力档位：整表覆盖写。读侧解析失败只会回落 Ask，配置坏了也不会静默放行。 */
    override suspend fun setCapabilityTiers(v: String) = edit { it[K.capabilityTiers] = v }
    override suspend fun setGroupIsolateMembers(v: Boolean) = edit { it[K.groupIsolateMembers] = v }
    override suspend fun setGroupScheduler(v: String) = edit { it[K.groupScheduler] = v }
    suspend fun setGroupModeratorModel(v: String) = edit { it[K.groupModeratorModel] = v }
    override suspend fun setShellChannel(v: String) = edit { it[K.shellChannel] = v }
    override suspend fun setCollapsedGroups(v: List<String>) = edit { it[K.collapsedGroups] = v.joinToString("\u0001") }

    override suspend fun setCustomFolders(v: List<String>) = edit { it[K.customFolders] = v.joinToString("\u0001") }

    override suspend fun setWebSearchEngine(v: String) = edit { it[K.webSearchEngine] = v }

    override suspend fun setSearxngInstance(v: String) = edit { it[K.searxngInstance] = v }

    override suspend fun setMaxDownloadBytes(v: Long) = edit { it[K.maxDownloadBytes] = v }

    /** 存规整后的代理地址；空串 = 直连（ProxyEnv 只在非空时注入）。 */
    override suspend fun setProxyUrl(v: String) = edit { it[K.proxyUrl] = ProxyEnv.normalize(v) }

    // ---- 外观 ----
    override suspend fun setAppIconStyle(v: String) = edit { it[K.appIconStyle] = if (v == "custom") "custom" else "system" }
    override suspend fun setCustomIconUri(v: String) = edit { it[K.customIconUri] = v }
    override suspend fun setStartupAnimation(v: String) = edit { it[K.startupAnimation] = v }
    override suspend fun setStartupAnimationMs(v: Int) = edit { it[K.startupAnimationMs] = v.coerceIn(0, 5000) }
    override suspend fun setBackgroundUri(v: String) = edit { it[K.backgroundUri] = v }
    override suspend fun setBackgroundDim(v: Float) = edit { it[K.backgroundDim] = v.coerceIn(0f, 0.8f) }
    override suspend fun setAccentHue(v: Float) = edit { it[K.accentHue] = if (v < 0f) -1f else v.coerceIn(0f, 360f) }
    override suspend fun setBorderWidth(dp: Int) = edit { it[K.borderWidth] = dp.coerceIn(0, 8) }
    /**
     * 写材质：新键落盘，同时镜像旧键 `glass_enabled`，这样降级回 v0.05 也还是同样的观感。
     */
    override suspend fun setBackMaterial(v: String) = edit { writeBackMaterial(it, v) }

    /** 旧入口：只表达「开 / 关玻璃」，保留给尚未改成材质选择的调用方。 */
    override suspend fun setGlassEnabled(v: Boolean) = setBackMaterial(if (v) BackMaterial.MATTE.key else BackMaterial.NONE.key)
    override suspend fun setGlassAlpha(v: Float) = edit { it[K.glassAlpha] = BackMaterial.clampStrength(v) }
    override suspend fun setGlassBlur(dp: Int) = edit { it[K.glassBlur] = dp.coerceIn(0, 40) }

    /** 崩溃上传开关与端点；步骤 3 的 CrashUploader 读这里。 */
    override suspend fun crashUpload(): Pair<Boolean, String> {
        val p = context.settingsStore.data.first()
        return (p[K.crashUploadEnabled] ?: false) to (p[K.crashUploadEndpoint] ?: "")
    }

    override suspend fun setCrashUpload(enabled: Boolean, endpoint: String) = edit {
        it[K.crashUploadEnabled] = enabled
        it[K.crashUploadEndpoint] = endpoint
    }

    /**
     * 全量快照（步骤 3 的 LastKnownGood 用）：键名 → 字符串化值。
     * 键名带命名空间前缀，回滚时不至于把别的 DataStore 的键写进来。
     */
    override suspend fun snapshotEntries(): Map<String, String> =
        context.settingsStore.data.first().asMap()
            .mapKeys { it.key.name }
            .mapValues { encodeEntry(it.value) }

    /**
     * 按快照写回：只写 [entries] 里出现的键，其余保持原样。
     * 类型由「当前配置里该键的实际类型」决定 —— DataStore 的强类型键不能用字符串硬塞，
     * 塞错类型下次读出来就是 ClassCastException，所以按现有值反推。
     */
    override suspend fun applyEntries(entries: Map<String, String>) = edit { p ->
        val typed = p.asMap().entries.associate { it.key.name to it.key }
        entries.forEach { (name, raw) ->
            val key = typed[name]
            if (key == null) {
                // 回滚要恢复「已被删掉」的键：只能按字符串写回，读方按默认值兜底。
                p[stringPreferencesKey(name)] = raw
            } else {
                putTyped(p, key, raw)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun putTyped(
        p: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<*>,
        raw: String,
    ) {
        val value: Any = when (p[key]) {
            is Boolean -> raw.toBooleanStrictOrNull() ?: return
            is Int -> raw.toIntOrNull() ?: return
            is Long -> raw.toLongOrNull() ?: return
            is Float -> raw.toFloatOrNull() ?: return
            is Set<*> -> raw.split(LIST_SEP).filter { it.isNotEmpty() }.toSet()
            else -> raw
        }
        p[key as Preferences.Key<Any>] = value
    }

    private fun encodeEntry(value: Any?): String = when (value) {
        null -> ""
        is Set<*> -> value.joinToString(LIST_SEP) { it.toString() }
        else -> value.toString()
    }

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
