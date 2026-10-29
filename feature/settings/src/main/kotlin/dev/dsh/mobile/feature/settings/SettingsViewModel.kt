package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.core.common.ProxyEnv
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
import dev.dsh.mobile.core.data.update.UpdateInfo
import dev.dsh.mobile.core.data.update.Updater
import java.io.File
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
    val message: String? = null,
    // ---- 自更新 ----
    val appVersion: String = "",
    val updateSource: String = "",
    val updateInfo: UpdateInfo? = null,
    val updateStatus: String? = null,
    val updateProgress: Int = 0,
    val updateReady: Boolean = false,
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
    private val message = MutableStateFlow<String?>(null)
    private val storage = MutableStateFlow(0L)
    private val updateSource = MutableStateFlow(updater.sourceUrl())
    private val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    private val updateStatus = MutableStateFlow<String?>(null)
    private val updateProgress = MutableStateFlow(0)
    private val updateReady = MutableStateFlow(false)
    private var downloaded: File? = null

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        endpoints.observeEndpoints(),
        runtime.state,
        combine(busy, message, storage) { b, m, s -> Triple(b, m, s) },
        combine(updateSource, updateInfo, updateStatus, updateProgress, updateReady) { src, info, st, pr, ready ->
            UpdateSlice(src, info, st, pr, ready)
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
            updateReady = upd.ready,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private data class UpdateSlice(
        val source: String,
        val info: UpdateInfo?,
        val status: String?,
        val progress: Int,
        val ready: Boolean,
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
                .onFailure { message.value = it.message ?: it.toString() }
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
        val bytes = requireNotNull(downloadLimitBytes(value)) { "单次下载上限须为 1–4096 MB 的整数" }
        withContext(Dispatchers.IO) { settingsStore.setMaxDownloadBytes(bytes) }
    }

    fun setProxyUrl(raw: String) = launch {
        val invalid = proxyError(raw)
        if (invalid != null) {
            message.value = invalid
            return@launch
        }
        val value = ProxyEnv.normalize(raw)
        withContext(Dispatchers.IO) { settingsStore.setProxyUrl(value) }
        message.value = if (value.isEmpty()) "已切换为直连" else "代理已保存：" + value
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
        _linuxState.value = _linuxState.value.copy(busy = true, progress = "读取环境状态", error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    action { detail -> _linuxState.value = _linuxState.value.copy(progress = detail) }
                }
                _linuxState.value = result
                message.value = result.error ?: result.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: error.toString()
                _linuxState.value = _linuxState.value.copy(error = detail)
                message.value = detail
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
        updateStatus.value = "检查中…"
        updateReady.value = false
        updater.check()
            .onSuccess { info ->
                updateInfo.value = info
                updateStatus.value = if (info == null) {
                    "已经是最新版本（${updater.installedVersion().second}）"
                } else {
                    "发现新版本 ${info.versionName}"
                }
            }
            .onFailure { updateStatus.value = "检查失败：${it.message}" }
    }

    fun downloadUpdate() {
        val info = updateInfo.value ?: return
        viewModelScope.launch {
            updateStatus.value = "下载中…"
            updateProgress.value = 0
            updater.download(info) { pct -> updateProgress.value = pct }
                .onSuccess { file ->
                    downloaded = file
                    updateReady.value = true
                    updateStatus.value = "下载完成，可以安装"
                }
                .onFailure { updateStatus.value = "下载失败：${it.message}" }
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
        message.value = "已请求重连"
    }
    fun clearLogs() = launch {
        runtime.clearLogs()
        refreshStorage()
        message.value = "日志已清理"
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
        message.value = "已保存 ${draft.name.ifBlank { draft.url }}"
    }

    fun deleteEndpoint(id: String) = launch {
        endpoints.delete(id)
        message.value = "已删除"
    }

    fun activate(id: String) = launch {
        endpoints.setActive(id)
        message.value = "已切换连接"
        onReconnect()
    }

    fun dismissMessage() { message.value = null }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            runCatching { block() }.onFailure { message.value = it.message }
            busy.value = false
        }
    }
}
