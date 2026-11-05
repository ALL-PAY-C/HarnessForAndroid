package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.core.platform.ProxyEnv
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.core.platform.UiLanguage
import dev.dsh.mobile.core.platform.device.CapabilityTier
import dev.dsh.mobile.core.platform.device.CapabilityTiers
import dev.dsh.mobile.core.platform.device.DeviceCapability
import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import dev.dsh.mobile.core.data.settings.WebSecretKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.EndpointRepository
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.SendMode
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.core.data.update.ShaMismatchException
import dev.dsh.mobile.core.model.update.ShaStatus
import dev.dsh.mobile.core.data.update.UpdateInfo
import dev.dsh.mobile.core.data.update.Updater
import java.io.File
import dev.dsh.mobile.core.model.BackMaterial
import dev.dsh.mobile.core.model.Endpoint
import dev.dsh.mobile.core.model.RuntimeControl
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EndpointDraft(
    val id: String = "",
    val name: String = "",
    val kind: TransportKind = TransportKind.Remote,
    val url: String = "ws://192.168.1.10:8787/ws",
    val token: String = "",
    val cwd: String = "",
    val heartbeatMs: Long = 15_000L,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val endpoints: List<Endpoint> = emptyList(),
    val runtime: RuntimeState = RuntimeState.NotConfigured,
    val versions: List<String> = emptyList(),
    val storageBytes: Long = 0L,
    val busy: Boolean = false,
    val message: UiText? = null,
    // ---- 自更新 ----
    val appVersion: String = "",
    val updateSource: String = "",
    val updateInfo: UpdateInfo? = null,
    val updateStatus: UiText? = null,
    val updateProgress: Int = 0,
    val updateReady: Boolean = false,
    /** 「源：gh-proxy.com · 486ms」；检查还没跑或回退到本地副本时也非空（见 Updater.sourceLine）。 */
    val updateSourceLine: String? = null,
    /** sha256 结论那一行；未校验时为 null。 */
    val updateDigest: UiText? = null,
    /** true = 校验通过，false = 不通过（此时 updateReady 恒为 false）。 */
    val updateDigestOk: Boolean? = null,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val secretStore: EndpointSecretStore,
    private val linuxActions: LinuxSettingsActions,
    private val endpoints: EndpointRepository,
    private val runtime: RuntimeControl,
    private val updater: Updater,
    private val onReconnect: () -> Unit,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)
    private val storage = MutableStateFlow(0L)
    private val updateSource = MutableStateFlow(updater.sourceUrl())
    private val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    private val updateStatus = MutableStateFlow<UiText?>(null)
    private val updateProgress = MutableStateFlow(0)
    private val updateReady = MutableStateFlow(false)
    private val updateSourceLine = MutableStateFlow<String?>(null)
    private val updateDigest = MutableStateFlow<UiText?>(null)
    private val updateDigestOk = MutableStateFlow<Boolean?>(null)
    private var downloaded: File? = null
    private val updateTail = combine(updateReady, updateSourceLine, updateDigest, updateDigestOk) { r, l, d, ok ->
        UpdateTail(r, l, d, ok)
    }

    /**
     * 运行时版本归一化（一次性、幂等）。
     *
     * 历史设置把 [SettingsStore] 的 runtimeVersion 默认落成占位值 "bundled"，而现在
     * [RuntimeControl.versions] 只报 :node 侧的真实引擎版本（"engine-v1"）。设置页的版本 chip 是按
     * 字符串相等判定的，占位值留着会表现为「默认不高亮、点一次才高亮」，且点那一次会白跑一次重连。
     * 这里只在检测到占位值时把真实版本写回一次；写完之后条件不再成立，不会重复写。
     * 放在 init 而不是 uiState 的 combine 里，避免每次状态变化/重组都触发写盘。
     */
    init {
        viewModelScope.launch {
            val current = settingsStore.settings.first().runtimeVersion
            if (current == LEGACY_BUNDLED && runtime.hasBundled(current)) {
                runtime.versions().firstOrNull()?.let { settingsStore.setRuntimeVersion(it) }
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        endpoints.observeEndpoints(),
        runtime.state,
        combine(busy, message, storage) { b, m, s -> Triple(b, m, s) },
        combine(updateSource, updateInfo, updateStatus, updateProgress, updateTail) { src, info, st, pr, tail ->
            UpdateSlice(src, info, st, pr, tail)
        },
    ) { s, e, r, extra, upd ->
        SettingsUiState(
            settings = s,
            endpoints = e,
            runtime = r,
            versions = runtime.versions().ifEmpty { listOf("bundled") },
            storageBytes = extra.third,
            busy = extra.first,
            message = extra.second,
            appVersion = updater.installedVersion().second,
            updateSource = upd.source,
            updateInfo = upd.info,
            updateStatus = upd.status,
            updateProgress = upd.progress,
            updateReady = upd.tail.ready,
            updateSourceLine = upd.tail.sourceLine,
            updateDigest = upd.tail.digest,
            updateDigestOk = upd.tail.digestOk,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private data class UpdateSlice(
        val source: String,
        val info: UpdateInfo?,
        val status: UiText?,
        val progress: Int,
        val tail: UpdateTail,
    )

    /** combine 只有 5 个参数的重载是强类型的，第 6 个起就退化成数组：所以先收成一个。 */
    private data class UpdateTail(
        val ready: Boolean,
        val sourceLine: String?,
        val digest: UiText?,
        val digestOk: Boolean?,
    )


    private val _linuxState = MutableStateFlow(LinuxSettingsState())
    val linuxState: StateFlow<LinuxSettingsState> = _linuxState.asStateFlow()
    private val _exaConfigured = MutableStateFlow(false)
    val exaConfigured: StateFlow<Boolean> = _exaConfigured.asStateFlow()

    init {
        refreshStorage()
        refreshLinux()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { !secretStore.get(WebSecretKeys.EXA).isNullOrBlank() }
                .onSuccess { _exaConfigured.value = it }
                .onFailure { message.value = UiText.Raw(it.message ?: it.toString()) }
        }
    }

    fun setWebSearchEngine(value: String) = launch {
        require(value in listOf("auto", "bing", "duckduckgo", "searxng", "exa"))
        withContext(Dispatchers.IO) { settingsStore.setWebSearchEngine(value) }
    }

    fun setSearxngInstance(value: String) = launch {
        withContext(Dispatchers.IO) { settingsStore.setSearxngInstance(value.trim()) }
    }

    fun setExaApiKey(value: String) = launch {
        withContext(Dispatchers.IO) {
            if (value.isBlank()) secretStore.remove(WebSecretKeys.EXA)
            else secretStore.set(WebSecretKeys.EXA, value.trim())
            _exaConfigured.value = !secretStore.get(WebSecretKeys.EXA).isNullOrBlank()
        }
    }

    fun setMaxDownloadMb(value: String) = launch {
        val bytes = downloadLimitBytes(value)
        if (bytes == null) {
            message.value = UiText.Res(DesignR.string.settings_err_download_limit)
            return@launch
        }
        withContext(Dispatchers.IO) { settingsStore.setMaxDownloadBytes(bytes) }
    }

    fun setProxyUrl(raw: String) = launch {
        if (proxyInvalid(raw)) {
            message.value = UiText.Res(DesignR.string.settings_err_proxy_invalid)
            return@launch
        }
        val value = ProxyEnv.normalize(raw)
        withContext(Dispatchers.IO) { settingsStore.setProxyUrl(value) }
        message.value = if (value.isEmpty()) {
            UiText.Res(DesignR.string.settings_proxy_direct)
        } else {
            UiText.Res(DesignR.string.settings_proxy_saved, listOf(value))
        }
    }

    fun bootstrapLinuxEnv() = linuxOperation { linuxActions.bootstrapEnvironment(it) }

    fun cleanupLinux() = linuxOperation { linuxActions.cleanup() }
    fun clearLinuxDiagnostics() = linuxOperation { linuxActions.clearDiagnostics() }
    fun refreshLinux() = linuxOperation { linuxActions.inspect() }
    fun installLinux() = linuxOperation { linuxActions.installEnvironment(it) }
    fun repairRuntime() = linuxOperation { linuxActions.repairRuntime() }
    fun reinstallRuntime() = linuxOperation { linuxActions.reinstallRuntime(it) }
    fun installKernel() = linuxOperation { linuxActions.installKernel(it) }
    fun runLinuxSelfCheck() = linuxOperation { linuxActions.selfCheck(it) }

    private fun linuxOperation(action: suspend ((String) -> Unit) -> LinuxSettingsState) {
        if (_linuxState.value.busy) return
        _linuxState.value = _linuxState.value.copy(busy = true, progress = UiText.Res(DesignR.string.settings_update_reading_env), error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    action { detail -> _linuxState.value = _linuxState.value.copy(progress = UiText.Raw(detail)) }
                }
                _linuxState.value = result
                message.value = (result.error ?: result.message)?.let(UiText::Raw)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: error.toString()
                _linuxState.value = _linuxState.value.copy(error = detail)
                message.value = UiText.Raw(detail)
            } finally {
                _linuxState.value = _linuxState.value.copy(busy = false, progress = null)
            }
        }
    }

    // ---- 自更新 ----
    fun setUpdateSource(url: String) {
        updateSource.value = url
        updater.setSourceUrl(url)
    }

    /** 检查清单；有新版本就记下来，等用户点下载。 */
    fun checkUpdate() = viewModelScope.launch {
        updateStatus.value = UiText.Res(DesignR.string.settings_update_checking)
        updateReady.value = false
        updateSourceLine.value = null
        updateDigest.value = null
        updateDigestOk.value = null
        updater.checkDetailed()
            .onSuccess { check ->
                val info = check.info
                updateInfo.value = info
                // 失败切换的可观测性：不管有没有新版，都告诉用户这轮实际用了哪个源、花了多久。
                updateSourceLine.value = check.sourceLine()
                updateStatus.value = if (info == null) {
                    UiText.Res(DesignR.string.settings_update_latest, listOf(updater.installedVersion().second))
                } else {
                    UiText.Res(DesignR.string.settings_update_found, listOf(info.versionName))
                }
            }
            .onFailure { updateStatus.value = UiText.Res(DesignR.string.settings_update_check_failed, listOf(it.message ?: "")) }
    }

    fun downloadUpdate() {
        val info = updateInfo.value ?: return
        viewModelScope.launch {
            updateStatus.value = UiText.Res(DesignR.string.settings_update_downloading)
            updateProgress.value = 0
            updateDigest.value = null
            updateDigestOk.value = null
            updater.downloadChecked(info) { pct -> updateProgress.value = pct }
                .onSuccess { report ->
                    downloaded = report.file
                    updateReady.value = true
                    updateStatus.value = UiText.Res(DesignR.string.settings_update_downloaded)
                    when (val sha = report.sha) {
                        is ShaStatus.Verified -> {
                            updateDigestOk.value = true
                            updateDigest.value = UiText.Res(DesignR.string.settings_update_digest_ok, listOf(sha.sha256.take(12)))
                        }
                        ShaStatus.NotProvided -> {
                            updateDigest.value = UiText.Res(DesignR.string.settings_update_digest_skip)
                        }
                    }
                }
                .onFailure { err ->
                    // 校验不过就走这里：文件已被删除，updateReady 保持 false，安装入口不出现。
                    downloaded = null
                    updateReady.value = false
                    if (err is ShaMismatchException) {
                        updateDigestOk.value = false
                        updateDigest.value = err.message?.let(UiText::Raw) ?: UiText.Res(DesignR.string.settings_update_digest_fail)
                        updateStatus.value = UiText.Res(DesignR.string.settings_update_verify_failed)
                    } else {
                        updateStatus.value = UiText.Res(DesignR.string.settings_update_download_failed, listOf(err.message ?: ""))
                    }
                }
        }
    }

    /** 交给界面去 startActivity（ViewModel 里没有 Activity 上下文）。 */
    fun installIntent(): android.content.Intent? {
        val file = downloaded ?: return null
        return updater.installIntent(file)
    }

    fun refreshStorage() {
        viewModelScope.launch { storage.value = runtime.storageBytes() }
    }

    // ---- theme & appearance ----
    fun setThemeMode(m: ThemeMode) = launch { settingsStore.setThemeMode(m) }

    fun setUiLanguage(language: UiLanguage) = launch { settingsStore.setUiLanguage(language) }
    fun setAccent(c: Long) = launch { settingsStore.setAccent(c) }
    fun setBubbleMine(c: Long) = launch { settingsStore.setBubbleMine(c) }
    fun setBubbleOther(c: Long) = launch { settingsStore.setBubbleOther(c) }
    fun setRadius(dp: Int) = launch { settingsStore.setRadius(dp) }
    fun setFontScale(f: Float) = launch { settingsStore.setFontScale(f) }
    fun setDensity(d: Float) = launch { settingsStore.setDensity(d) }
    fun setCompact(v: Boolean) = launch { settingsStore.setCompact(v) }

    // ---- 外观（Wallpaper 式：图标 / 背景 / 边框 / 玻璃）----
    fun setAppIconStyle(v: String) = launch { settingsStore.setAppIconStyle(v) }
    fun setCustomIconUri(v: String) = launch { settingsStore.setCustomIconUri(v) }
    fun setStartupAnimation(v: String) = launch { settingsStore.setStartupAnimation(v) }
    fun setStartupAnimationMs(v: Int) = launch { settingsStore.setStartupAnimationMs(v) }
    fun setBackgroundUri(v: String) = launch { settingsStore.setBackgroundUri(v) }
    fun setBackgroundDim(v: Float) = launch { settingsStore.setBackgroundDim(v) }
    fun setAccentHue(v: Float) = launch { settingsStore.setAccentHue(v) }
    fun setBorderWidth(v: Int) = launch { settingsStore.setBorderWidth(v) }
    /** 材质选择（none / matte / liquid / acrylic）。旧 setGlassEnabled 仍保留，供未迁移处调用。 */
    fun setBackMaterial(v: String) = launch { settingsStore.setBackMaterial(v) }
    fun setGlassEnabled(v: Boolean) = launch { settingsStore.setGlassEnabled(v) }
    fun setGlassAlpha(v: Float) = launch { settingsStore.setGlassAlpha(v) }
    fun setGlassBlur(v: Int) = launch { settingsStore.setGlassBlur(v) }

    /**
     * 「恢复默认外观」的唯一实现。以前这段内联在设置页的折叠卡里，页面拆成子页后
     * 两处都要用，所以收进 ViewModel —— 不再有第二份同样的十行调用。
     */
    fun resetAppearance() {
        setAppIconStyle("system")
        setCustomIconUri("")
        setBackgroundUri("")
        setAccent(DEFAULT_ACCENT)
        setRadius(18)
        setFontScale(1f)
        setDensity(1f)
        // 材质也一起回默认：否则「恢复默认外观」后仍停在液态，用户会以为没生效。
        setBackMaterial(BackMaterial.DEFAULT.key)
        setGlassAlpha(0.82f)
        setGlassBlur(0)
    }

    // ---- layout ----
    fun setGroup(v: Boolean) = launch { settingsStore.setGroupSessions(v) }

    fun setDefaultCwd(v: String) = launch { settingsStore.setDefaultCwd(v.trim()) }
    fun setShowAgentStatus(v: Boolean) = launch { settingsStore.setShowAgentStatus(v) }
    fun setShowTokens(v: Boolean) = launch { settingsStore.setShowTokenUsage(v) }
    fun setShowThoughts(v: Boolean) = launch { settingsStore.setShowThoughts(v) }

    // ---- behavior ----
    fun setNotifications(v: Boolean) = launch { settingsStore.setNotifications(v) }
    fun setDnd(v: Boolean) = launch { settingsStore.setDnd(v) }
    fun setAutoApprove(v: Boolean) = launch { settingsStore.setAutoApprove(v) }
    /** 设置 → 拓展：拓展插件 UI 配置。 */
    fun setPluginUiExtend(v: Boolean) = launch { settingsStore.setPluginUiExtend(v) }

    // ---- 通知（开关即时生效：NotificationCenter 每次发送前重建 channel） ----
    fun setNotifyOnComplete(v: Boolean) = launch { settingsStore.setNotifyOnComplete(v) }
    fun setNotifyOnPermission(v: Boolean) = launch { settingsStore.setNotifyOnPermission(v) }
    fun setNotifyVibrate(v: Boolean) = launch { settingsStore.setNotifyVibrate(v) }
    fun setNotifyHeadsUp(v: Boolean) = launch { settingsStore.setNotifyHeadsUp(v) }
    fun setCwdTreeUri(v: String) = launch { settingsStore.setCwdTreeUri(v) }
    fun setHeartbeat(ms: Long) = launch { settingsStore.setHeartbeat(ms) }

    // ---- 常驻聚合通知（n 个会话 + 思维链 + 通知内选择） ----
    fun setNotifyOngoing(v: Boolean) = launch { settingsStore.setNotifyOngoing(v) }
    fun setNotifyShowThought(v: Boolean) = launch { settingsStore.setNotifyShowThought(v) }
    fun setNotifyThoughtLines(v: Int) = launch { settingsStore.setNotifyThoughtLines(v) }
    fun setNotifySuppressForeground(v: Boolean) = launch { settingsStore.setNotifySuppressForeground(v) }
    fun setNotifyInlineActions(v: Boolean) = launch { settingsStore.setNotifyInlineActions(v) }
    fun setNotifyReplySnippet(v: Boolean) = launch { settingsStore.setNotifyReplySnippet(v) }
    fun setNotifyKeepAfterDone(v: Boolean) = launch { settingsStore.setNotifyKeepAfterDone(v) }
    fun setNotifyVibrateStrength(v: Int) = launch { settingsStore.setNotifyVibrateStrength(v) }

    // ---- 对话与发送 ----
    fun setSendMode(v: SendMode) = launch { settingsStore.setSendMode(v) }
    fun setQueueLimit(v: Int) = launch { settingsStore.setQueueLimit(v) }
    fun setAutoScroll(v: Boolean) = launch { settingsStore.setAutoScroll(v) }
    fun setShowDuration(v: Boolean) = launch { settingsStore.setShowDuration(v) }
    fun setColorizeAuthors(v: Boolean) = launch { settingsStore.setColorizeAuthors(v) }
    fun setThoughtsExpanded(v: Boolean) = launch { settingsStore.setThoughtsExpanded(v) }
    fun setMaxOutputTokens(v: Int) = launch { settingsStore.setMaxOutputTokens(v) }

    /** 全局默认：新会话是否富渲染特殊格式（HTML / LaTeX / Markdown / 代码块）。 */
    fun setRenderSpecialFormats(v: Boolean) = launch { settingsStore.setRenderSpecialFormats(v) }

    // ---- 群组编排 ----
    fun setGroupMaxParallel(v: Int) = launch { settingsStore.setGroupMaxParallel(v) }
    fun setGroupAllowTools(v: Boolean) = launch { settingsStore.setGroupAllowTools(v) }
    fun setGroupMode(v: String) = launch { settingsStore.setGroupMode(v) }
    fun setGroupScheduler(v: String) = launch { settingsStore.setGroupScheduler(v) }
    fun setSubagentMaxDepth(v: Int) = launch { settingsStore.setSubagentMaxDepth(v) }

    /**
     * T4.5：改一类系统能力的档位。整表写回（读旧表 → 改一项 → 编码），
     * 解析/编码都在 CapabilityTiers 里，坏值只会回落 Ask。
     */
    fun setCapabilityTier(capability: DeviceCapability, tier: CapabilityTier) = launch {
        val current = CapabilityTiers.parse(settingsStore.settings.first().capabilityTiers)
        settingsStore.setCapabilityTiers(CapabilityTiers.encode(CapabilityTiers.with(current, capability, tier)))
    }
    fun setGroupOfficePrompt(v: Boolean) = launch { settingsStore.setGroupOfficePrompt(v) }
    fun setGroupContextBudget(v: Int) = launch { settingsStore.setGroupContextBudget(v) }
    fun setGroupIsolateMembers(v: Boolean) = launch { settingsStore.setGroupIsolateMembers(v) }
    fun setShellChannel(v: String) = launch { settingsStore.setShellChannel(v) }
    fun setQuickCommands(text: String) = launch {
        settingsStore.setQuickCommands(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }
    fun setTemplates(text: String) = launch {
        settingsStore.setPromptTemplates(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    // ---- runtime ----
    fun setRuntimeVersion(v: String) = launch {
        settingsStore.setRuntimeVersion(v)
        onReconnect()
    }
    fun reconnect() = launch {
        busy.value = true
        onReconnect()
        busy.value = false
        message.value = UiText.Res(DesignR.string.settings_reconnect_requested)
    }
    fun clearLogs() = launch {
        runtime.clearLogs()
        refreshStorage()
        message.value = UiText.Res(DesignR.string.settings_logs_cleared)
    }

    // ---- endpoints ----
    fun saveEndpoint(draft: EndpointDraft) = launch {
        val id = draft.id.ifBlank { "ep-" + System.currentTimeMillis().toString(36) }
        endpoints.save(
            Endpoint(
                id = id,
                name = draft.name.ifBlank { draft.url },
                kind = draft.kind,
                url = draft.url.trim(),
                token = draft.token.trim(),
                cwd = draft.cwd.trim(),
                heartbeatMs = draft.heartbeatMs,
            ),
        )
        message.value = UiText.Res(DesignR.string.settings_saved_item, listOf(draft.name.ifBlank { draft.url }))
    }

    fun deleteEndpoint(id: String) = launch {
        endpoints.delete(id)
        message.value = UiText.Res(DesignR.string.settings_deleted)
    }

    fun activate(id: String) = launch {
        endpoints.setActive(id)
        message.value = UiText.Res(DesignR.string.settings_connection_switched)
        onReconnect()
    }

    fun dismissMessage() { message.value = null }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            runCatching { block() }.onFailure { message.value = UiText.Raw(it.message ?: it.toString()) }
            busy.value = false
        }
    }

    private companion object {
        /** 历史设置里 runtimeVersion 的占位值，见 init 中的归一化。 */
        const val LEGACY_BUNDLED = "bundled"

        /** 与设置页 ACCENTS 的第一个蓝一致，恢复默认外观时用它。 */
        const val DEFAULT_ACCENT = 0xFF0099FF
    }
}
