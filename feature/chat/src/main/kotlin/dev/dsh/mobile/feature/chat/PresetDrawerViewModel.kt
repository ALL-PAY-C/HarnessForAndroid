package dev.dsh.mobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.preset.AgentPreset
import dev.dsh.mobile.core.data.preset.AgentPresetImporter
import dev.dsh.mobile.core.data.preset.AgentPresetStore
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抽屉与聊天页是两个独立 VM（AppNav 分别构造），抽屉拿不到会话 id。
 * 聊天页打开时登记自己的 id、离开时清空，抽屉据此区分「切本会话」和「改全局默认」。
 * 用 StateFlow 而不是普通 var：抽屉是 WhileSubscribed，靠一次发射才知道会话换了。
 */
internal object ActiveChatSession {
    val id = MutableStateFlow("")
}

/** 抽屉里「Agent 预设」面板的状态。 */
data class PresetDrawerState(
    val presets: List<AgentPreset> = AgentPresets.builtins,
    /** 当前生效的预设：列表里打勾的那一个（会话切过 = 会话的，否则全局默认）。 */
    val defaultPresetId: String = AgentPresets.defaultPresetId,
    /** true = 打勾的是本会话的选择；false = 当前没有打开的会话，打勾的是全局默认。 */
    val sessionScoped: Boolean = false,
    val busy: Boolean = false,
    /** 面板内常驻的红字错误；null 表示没有错误。 */
    val error: String? = null,
) {
    val defaultName: String get() = presets.firstOrNull { it.id == defaultPresetId }?.name ?: AgentPresets.standard.name
}

/**
 * 抽屉面板的 VM：列预设 / 选预设 / 导入 / 删除。
 * 六个内置预设写死在 core，这里只把它们和用户导入的拼在一起；点选时如果聊天页正开着，
 * 切的是那个会话（下一轮请求即生效），没有打开的会话就改全局默认。
 */
class PresetDrawerViewModel(
    private val presetStore: AgentPresetStore,
    private val settingsStore: SettingsStore,
    private val importer: AgentPresetImporter = AgentPresetImporter(),
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PresetDrawerState> = combine(
        settingsStore.settings,
        presetStore.presets,
        ActiveChatSession.id,
        busy,
        error,
    ) { settings, imported, sessionId, b, e ->
        PresetDrawerState(
            // 内置在前、导入的在后；同 id 的旧副本已经被 Store 过滤掉，这里再兜一次底。
            presets = AgentPresets.builtins + imported.filterNot { it.id in AgentPresets.builtinIds },
            defaultPresetId = settings.presetFor(sessionId.ifBlank { null }),
            sessionScoped = sessionId.isNotBlank(),
            busy = b,
            error = e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresetDrawerState())

    fun import(input: String) {
        if (busy.value) return
        val value = input.trim()
        if (value.isEmpty()) {
            error.value = "请输入 GitHub owner/repo、ZIP/JSON 链接、本地路径或 JSON 文本"
            return
        }
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val preset = withContext(Dispatchers.IO) { importer.importPreset(value) }
                presetStore.upsert(preset)
                applySelection(preset.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "预设导入失败"
            } finally {
                busy.value = false
            }
        }
    }

    /** 点选即生效：聊天页开着就切这个会话，否则改全局默认（下次新建会话用它）。 */
    fun setDefault(id: String) {
        error.value = null
        viewModelScope.launch {
            try {
                require(presetStore.get(id) != null) { "预设不存在" }
                applySelection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "设置默认预设失败"
            }
        }
    }

    fun delete(id: String) {
        error.value = null
        viewModelScope.launch {
            try {
                presetStore.remove(id)
                // 删掉的正好是正在用的那个：本会话和全局默认都回落到标准模式，别留悬空 id。
                val s = settingsStore.settings.first()
                val session = ActiveChatSession.id.value
                if (s.defaultPresetId == id) settingsStore.setDefaultPresetId(AgentPresets.standard.id)
                if (session.isNotBlank() && s.sessionPresetIds[session] == id) {
                    settingsStore.setSessionPresetId(session, AgentPresets.standard.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "删除预设失败"
            }
        }
    }

    fun clearError() {
        error.value = null
    }

    private suspend fun applySelection(id: String) {
        val session = ActiveChatSession.id.value
        if (session.isNotBlank()) settingsStore.setSessionPresetId(session, id) else settingsStore.setDefaultPresetId(id)
    }
}
