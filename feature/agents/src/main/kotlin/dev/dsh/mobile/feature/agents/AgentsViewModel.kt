package dev.dsh.mobile.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelProbe
import dev.dsh.mobile.core.data.settings.probeContextLengths
import dev.dsh.mobile.core.data.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.data.settings.ProbeResult
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.toolbridge.ChannelStatus
import dev.dsh.mobile.toolbridge.ShellBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentCard(
    val session: Session,
    val label: String,
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
    val message: String? = null,
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

    private val message = MutableStateFlow<String?>(null)

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

    val uiState: StateFlow<AgentsUiState> = combine(core, modelsAndChannels, message) { c, mc, msg ->
        val active = c.sessions.filter {
            it.status == SessionStatus.Running ||
                it.status == SessionStatus.WaitingPermission ||
                it.status == SessionStatus.Failed
        }
        AgentsUiState(
            running = active.map { s ->
                AgentCard(
                    session = s,
                    label = when (s.status) {
                        SessionStatus.Running -> "运行中"
                        SessionStatus.WaitingPermission -> "等待确认"
                        SessionStatus.Failed -> "失败"
                        else -> "空闲"
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentsUiState())

    fun saveModel(profile: ModelProfile) = viewModelScope.launch {
        modelStore.save(profile)
        message.value = "已保存模型 ${profile.name}"
    }

    fun deleteModel(id: String) = viewModelScope.launch {
        modelStore.delete(id)
        message.value = "已删除模型"
    }

    fun setDefaultModel(id: String) = viewModelScope.launch {
        modelStore.setDefault(id)
        message.value = "已设为默认模型"
    }

    fun providers() = ModelProviders.all()

    /**
     * 引擎插件上报的模型 id。宿主那边已经把「引擎没起来 / 插件没装」吞成空表，
     * 这里再兜一层：拉模型列表是可选项，不能把异常甩到对话框上。
     */
    suspend fun loadEngineModelIds(): List<String> =
        runCatching { engineModelIds() }.getOrDefault(emptyList())

    /**
     * 「测试连接」：GET /models 优先，失败退化成一条 1 token 的 chat 请求。
     * 结果直接走 message -> Snackbar，格式化在 ModelProbe.formatResult（纯函数，有单测）。
     */
    fun testModel(profile: ModelProfile) = viewModelScope.launch {
        message.value = "测试中 · ${profile.name.ifBlank { profile.model }}"
        val result = try {
            ModelProbe.probe(profile.baseUrl, profile.apiKey, profile.model)
        } catch (t: Throwable) {
            ProbeResult(
                ok = false,
                elapsedMs = 0,
                error = t.message ?: t.javaClass.simpleName,
            )
        }
        message.value = ModelProbe.formatResult(result)
        // 探测成功且用户没手填窗口时，顺手把端点 /models 里的真实上下文窗口存下来：
        // 内置表只是猜测（以前 DeepSeek 被猜成 64k），能读到真值就以真值为准。
        if (result.ok && profile.contextWindow <= 0 && profile.baseUrl.isNotBlank()) {
            val windows = runCatching { probeContextLengths(profile.baseUrl, profile.apiKey) }
                .getOrDefault(emptyMap())
            val key = profile.model.trim().lowercase()
            val window = windows[key] ?: windows[key.substringAfterLast('/')]
            if (window != null && window > 0) {
                modelStore.save(profile.copy(contextWindow = window))
                message.value = ModelProbe.formatResult(result) + " · 上下文 " + window
            }
        }
    }

    fun refreshChannels() {
        shell.refresh()
        message.value = "已重新探测工具通道"
    }

    fun requestShizuku() {
        val ok = shell.shizuku?.requestPermission() == true
        message.value = if (ok) "已向 Shizuku 申请授权，请在弹窗中允许" else "Shizuku 未运行，请先启动 Shizuku 服务"
    }

    fun dismissMessage() {
        message.value = null
    }
}
