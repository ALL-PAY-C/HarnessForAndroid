package dev.dsh.mobile.feature.agents

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelProbe
import dev.dsh.mobile.core.data.settings.ModelProvider
import dev.dsh.mobile.core.data.settings.probeContextLengths
import dev.dsh.mobile.core.data.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.data.settings.ProbeResult
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.toolbridge.ChannelStatus
import dev.dsh.mobile.toolbridge.ShellBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentCard(
    val session: Session,
    @param:StringRes val labelRes: Int,
)

/** 一次连接测试的结论；文案在界面层按资源拼，模型层不带中文。 */
data class ProbeNotice(
    val result: ProbeResult,
    val window: Int = 0,
)

data class AgentsUiState(
    val running: List<AgentCard> = emptyList(),
    val all: List<Session> = emptyList(),
    val commands: List<String> = emptyList(),
    val runtime: RuntimeState = RuntimeState.NotConfigured,
    val settings: AppSettings = AppSettings.DEFAULT,
    val models: List<ModelProfile> = emptyList(),
    val channels: List<ChannelStatus> = emptyList(),
    val activeChannel: String = "",
    val message: UiText? = null,
    val probe: ProbeNotice? = null,
)

class AgentsViewModel(
    repo: ChatRepository,
    settingsStore: SettingsStore,
    private val modelStore: ModelStore,
    runtime: StateFlow<RuntimeState>,
    private val shell: ShellBridge,
    /**
     * engine:// 端点的模型列表：由宿主引擎通道给（AppContainer::engineModelIds）。
     * 默认空实现，既有调用点与测试不用改；纯 HTTP 端点根本不走这里。
     */
    private val engineModelIds: suspend () -> List<String> = { emptyList() },
) : ViewModel() {

    private val message = MutableStateFlow<UiText?>(null)
    private val probe = MutableStateFlow<ProbeNotice?>(null)

    init {
        // 只投递一次后台探测，绝不在这里同步跑：refresh() 内部把 available()/detail()
        // （root 起 su 等 4s、Shizuku binder、busybox stat/chmod）全放在 Dispatchers.IO 上。
        // ViewModel 构造发生在主线程，以前这一行就是 H5 的主线程 IO 入口。
        shell.refresh()
    }

    private data class Core(
        val sessions: List<Session>,
        val commands: List<String>,
        val runtime: RuntimeState,
        val settings: AppSettings,
    )

    private val core = combine(
        repo.observeSessions(),
        repo.commands,
        runtime,
        settingsStore.settings,
    ) { sessions, commands, rt, settings -> Core(sessions, commands, rt, settings) }

    private val modelsAndChannels = combine(modelStore.models, shell.status) { m, c -> m to c }

    val uiState: StateFlow<AgentsUiState> = combine(core, modelsAndChannels, message, probe) { c, mc, msg, pr ->
        val active = c.sessions.filter {
            it.status == SessionStatus.Running ||
                it.status == SessionStatus.WaitingPermission ||
                it.status == SessionStatus.Failed
        }
        AgentsUiState(
            running = active.map { s ->
                AgentCard(
                    session = s,
                    labelRes = when (s.status) {
                        SessionStatus.Running -> DesignR.string.agents_state_running
                        SessionStatus.WaitingPermission -> DesignR.string.common_waiting_confirm
                        SessionStatus.Failed -> DesignR.string.agents_state_failed
                        else -> DesignR.string.agents_idle
                    },
                )
            },
            all = c.sessions,
            commands = c.commands,
            runtime = c.runtime,
            settings = c.settings,
            models = mc.first,
            channels = mc.second,
            activeChannel = shell.active()?.id.orEmpty(),
            message = msg,
            probe = pr,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsUiState())

    /** 一次保存多条（「拉取模型列表」里勾了 N 个）：同一个 baseUrl / apiKey，只是 model 不同。 */
    fun saveModels(profiles: List<ModelProfile>) = viewModelScope.launch {
        if (profiles.isEmpty()) return@launch
        profiles.forEach { modelStore.save(it) }
        message.value = if (profiles.size == 1) {
            UiText.Res(DesignR.string.agents_saved_model, listOf(profiles.first().name))
        } else {
            UiText.Res(DesignR.string.agents_saved_models, listOf(profiles.size))
        }
    }

    fun deleteModel(id: String) = viewModelScope.launch {
        modelStore.delete(id)
        message.value = UiText.Res(DesignR.string.agents_model_deleted)
    }

    fun setDefaultModel(id: String) = viewModelScope.launch {
        modelStore.setDefault(id)
        message.value = UiText.Res(DesignR.string.agents_default_set)
    }

    /**
     * 模型模板（内置 + 插件注册）。
     *
     * 插件模型源是运行期异步注册的，必须走 StateFlow 订阅：直接读 [ModelProviders.all] 只会拿到
     * 组合那一刻的快照，插件晚一步注册就永远不出现在「添加模型」里（实测 CLIProxy 缺 chip）。
     */
    val providers: StateFlow<List<ModelProvider>> = ModelProviders.extraFlow
        .map { ModelProviders.builtin + it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelProviders.all())

    /**
     * 引擎插件上报的模型 id。宿主那边已经把「引擎没起来 / 插件没装」吞成空表，
     * 这里再兜一层：拉模型列表是可选项，不能把异常甩到对话框上。
     */
    suspend fun loadEngineModelIds(): List<String> =
        runCatching { engineModelIds() }.getOrDefault(emptyList())

    /**
     * 「测试连接」：GET /models 优先，失败退化成一条 1 token 的 chat 请求。
     * 结果走 probe -> Snackbar，文案在 AgentsScreen.probeText 里按资源拼（数据层不带中文）。
     */
    fun testModel(profile: ModelProfile) = viewModelScope.launch {
        message.value = UiText.Res(DesignR.string.agents_testing, listOf(profile.name.ifBlank { profile.model }))
        val result = try {
            ModelProbe.probe(profile.baseUrl, profile.apiKey, profile.model)
        } catch (t: Throwable) {
            ProbeResult(
                ok = false,
                elapsedMs = 0,
                error = t.message ?: t.javaClass.simpleName,
            )
        }
        message.value = null
        probe.value = ProbeNotice(result)
        // 探测成功且用户没手填窗口时，顺手把端点 /models 里的真实上下文窗口存下来：
        // 内置表只是猜测（以前 DeepSeek 被猜成 64k），能读到真值就以真值为准。
        if (result.ok && profile.contextWindow <= 0 && profile.baseUrl.isNotBlank()) {
            val windows = runCatching { probeContextLengths(profile.baseUrl, profile.apiKey) }
                .getOrDefault(emptyMap())
            val key = profile.model.trim().lowercase()
            val window = windows[key] ?: windows[key.substringAfterLast('/')]
            if (window != null && window > 0) {
                modelStore.save(profile.copy(contextWindow = window))
                probe.value = ProbeNotice(result, window)
            }
        }
    }

    fun refreshChannels() {
        shell.refresh()
        message.value = UiText.Res(DesignR.string.agents_channels_reprobed)
    }

    fun requestShizuku() {
        val ok = shell.shizuku?.requestPermission() == true
        message.value = if (ok) {
            UiText.Res(DesignR.string.agents_shizuku_requested)
        } else {
            UiText.Res(DesignR.string.agents_shizuku_not_running)
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    fun dismissProbe() {
        probe.value = null
    }
}
