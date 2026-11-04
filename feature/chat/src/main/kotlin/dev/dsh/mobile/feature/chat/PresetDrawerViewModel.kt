package dev.dsh.mobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.preset.AgentPreset
import dev.dsh.mobile.core.data.preset.AgentPresetImporter
import dev.dsh.mobile.core.data.preset.AgentPresetStore
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.model.IdentityCard
import dev.dsh.mobile.core.data.preset.IdentityCards
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
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
    /** 面板顶部那行红字；[UiText] 让语言切换后重渲染即生效。 */
    val error: UiText? = null,
    /** presetId -> 身份卡（覆盖表；内置预设的卡也在这里）。 */
    val cards: Map<String, IdentityCard> = emptyMap(),
    /** 当前会话还留着的旧自由文本人设；只在「没有卡」时合成只读卡展示。 */
    val legacyPersona: String = "",
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
    /** 取会话旧人设文本（会话实体上那个 persona 字段）；默认空实现 = 没有旧数据。 */
    private val personaOf: suspend (String) -> String = { "" },
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)

    val uiState: StateFlow<PresetDrawerState> = combine(
        settingsStore.settings,
        presetStore.presets,
        presetStore.cards,
        ActiveChatSession.id,
        busy,
    ) { settings, imported, cards, sessionId, b ->
        PresetDrawerState(
            // 内置在前、导入的在后；同 id 的旧副本已经被 Store 过滤掉，这里再兜一次底。
            presets = AgentPresets.builtins + imported.filterNot { it.id in AgentPresets.builtinIds },
            defaultPresetId = settings.presetFor(sessionId.ifBlank { null }),
            sessionScoped = sessionId.isNotBlank(),
            busy = b,
            cards = cards,
            // 旧人设只在「当前生效的那张预设没有卡」时才有意义，所以读的是当前会话的人设。
            legacyPersona = if (sessionId.isBlank()) "" else runCatching { personaOf(sessionId) }.getOrDefault(""),
        )
    }.combine(error) { s, e -> s.copy(error = e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresetDrawerState())

    fun import(input: String) {
        if (busy.value) return
        val value = input.trim()
        if (value.isEmpty()) {
            error.value = UiText.Res(DesignR.string.preset_err_input)
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
                error.value = e.message?.let { UiText.Raw(it) } ?: UiText.Res(DesignR.string.preset_err_import)
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
                require(presetStore.get(id) != null) { "preset not found" }
                applySelection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message?.let { UiText.Raw(it) } ?: UiText.Res(DesignR.string.preset_err_default)
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
                error.value = e.message?.let { UiText.Raw(it) } ?: UiText.Res(DesignR.string.preset_err_delete)
            }
        }
    }

    /** 写 / 清空一张身份卡（null = 清空）。内置预设也能挂卡，卡存在覆盖表里，不碰预设本体。 */
    fun saveCard(id: String, card: IdentityCard?) {
        error.value = null
        viewModelScope.launch {
            try {
                presetStore.setCard(id, card)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message?.let { UiText.Raw(it) } ?: UiText.Res(DesignR.string.preset_err_card)
            }
        }
    }

    /**
     * 「转为身份卡」：把会话里那坨旧自由文本人设落成一张真卡，之后再改字段就不用改数据库了。
     * 原 persona 保持不动（回退用），只是从此读侧优先看卡。
     */
    fun adoptLegacyPersona(preset: AgentPreset, persona: String) {
        val text = persona.trim()
        if (text.isEmpty()) return
        saveCard(preset.id, IdentityCard(name = preset.name.ifBlank { "Identity card" }, freeform = text))
    }

    /** 某预设当前生效的卡：覆盖表 > 预设自带 > 旧人设合成的只读卡。 */
    fun cardFor(preset: AgentPreset): IdentityCard? =
        IdentityCards.ofCard(
            uiState.value.cards[preset.id] ?: preset.card,
            if (preset.id == uiState.value.defaultPresetId) uiState.value.legacyPersona else "",
        )

    fun clearError() {
        error.value = null
    }

    private suspend fun applySelection(id: String) {
        val session = ActiveChatSession.id.value
        if (session.isNotBlank()) settingsStore.setSessionPresetId(session, id) else settingsStore.setDefaultPresetId(id)
    }
}
