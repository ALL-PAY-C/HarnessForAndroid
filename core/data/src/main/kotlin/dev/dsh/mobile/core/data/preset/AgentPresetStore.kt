package dev.dsh.mobile.core.data.preset

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.model.IdentityCard

private val Context.presetDataStore by preferencesDataStore(
    name = "dsh_presets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 预设仓库：内置预设写死在 [AgentPresets.builtins]（跟着版本走），
 * DataStore 里只存用户导入的预设，因此升级 App 时内置文本自动更新、导入的不动。
 */
class AgentPresetStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.presetDataStore)
    private val key = stringPreferencesKey("presets")
    /** 身份卡覆盖表：presetId -> IdentityCard。单独一条 key 是为了让内置预设也能挂卡（本体不可覆盖）。 */
    private val cardsKey = stringPreferencesKey("preset_cards")
    /**
     * 群聊角色卡覆盖表（T9.7a）：roleId -> IdentityCard，与 [cardsKey] 同一套范式。
     * 默认卡写死在 [GroupRole.defaultCard]，这里只存用户改过的；清空即回落到默认卡。
     */
    private val roleCardsKey = stringPreferencesKey("group_role_cards")
    private val json = Json { ignoreUnknownKeys = true }

    /** 标准预设 + 导入的预设；内置预设不在这个列表里（用 [AgentPresets.builtins]）。 */
    val presets = dataStore.data.map { decode(it[key].orEmpty()) }

    /** 覆盖表（presetId -> 身份卡），UI 直接 collect 做编辑区回显。 */
    val cards = dataStore.data.map { decodeCards(it[cardsKey].orEmpty()) }

    /** 内置优先，其次导入的；老版本存的 "default" 归一成标准模式。 */
    suspend fun get(id: String): AgentPreset? {
        val resolved = AgentPresets.resolveId(id)
        AgentPresets.builtinOf(resolved)?.let { return it }
        return presets.first().firstOrNull { it.id == resolved }
    }

    /**
     * 预设 id 对应的身份卡：覆盖表优先，其次预设本体自带的 [AgentPreset.card]。
     * 内置预设本体写死在代码里，所以用户给它们编的卡只能落在覆盖表里。
     */
    suspend fun cardOf(id: String): IdentityCard? {
        val resolved = AgentPresets.resolveId(id)
        this.cards.first()[resolved]?.let { return it }
        return get(resolved)?.card
    }

    /** 写 / 清空一张卡（null 或空卡 = 清空，读侧回落到预设自带卡）。内置预设也允许。 */
    suspend fun setCard(id: String, card: IdentityCard?) {
        val resolved = AgentPresets.resolveId(id)
        require(resolved.isNotBlank()) { "预设 id 不能为空" }
        dataStore.edit { prefs ->
            val map = decodeCards(prefs[cardsKey].orEmpty()).toMutableMap()
            if (card == null || !card.hasContent()) map.remove(resolved) else map[resolved] = card
            prefs[cardsKey] = json.encodeToString(map)
        }
    }

    /** 角色卡覆盖表（roleId -> 卡），设置页直接 collect 回显。 */
    val roleCards: Flow<Map<String, IdentityCard>> =
        dataStore.data.map { decodeCards(it[roleCardsKey].orEmpty()) }

    /** 角色当前生效的卡：覆盖表优先，没有就回落到 [GroupRole.defaultCard]。 */
    suspend fun roleCard(role: GroupRole): IdentityCard = roleCards.first()[role.id] ?: role.defaultCard()

    /** 写 / 清空一张角色卡（null 或空卡 = 清空覆盖，读侧回到默认卡）。 */
    suspend fun setRoleCard(role: GroupRole, card: IdentityCard?) {
        dataStore.edit { prefs ->
            val map = decodeCards(prefs[roleCardsKey].orEmpty()).toMutableMap()
            if (card == null || !card.hasContent()) map.remove(role.id) else map[role.id] = card
            prefs[roleCardsKey] = json.encodeToString(map)
        }
    }

    suspend fun upsert(preset: AgentPreset) {
        requireEditable(preset.id, "标准预设不可覆盖", "内置预设不可覆盖")
        require(preset.id.isNotBlank() && preset.name.isNotBlank()) { "预设 id 和名称不能为空" }
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(
                decode(prefs[key].orEmpty()).filterNot { it.id == preset.id || it.id == AgentPresets.standard.id } +
                    preset.copy(builtin = false),
            )
        }
    }

    suspend fun remove(id: String) {
        requireEditable(id, "标准预设不可删除", "内置预设不可删除")
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(
                decode(prefs[key].orEmpty())
                    .filterNot { it.id == id || it.id == AgentPresets.standard.id || it.id == "default" },
            )
        }
    }

    /** 标准模式（含老 id "default"）与其它内置预设都不许改 / 删。 */
    private fun requireEditable(id: String, standardMessage: String, builtinMessage: String) {
        require(id != "default" && id != AgentPresets.standard.id) { standardMessage }
        require(id !in AgentPresets.builtinIds) { builtinMessage }
    }

    private fun decodeCards(raw: String): Map<String, IdentityCard> =
        runCatching { json.decodeFromString<Map<String, IdentityCard>>(raw) }.getOrDefault(emptyMap())

    private fun decode(raw: String): List<AgentPreset> = listOf(AgentPresets.standard) +
        runCatching { json.decodeFromString<List<AgentPreset>>(raw) }.getOrDefault(emptyList())
            // 内置 id 写死在代码里：存量里若有一份同 id 的旧副本，丢弃它而不是让面板出现两行。
            .filter { it.id !in AgentPresets.builtinIds && it.id != "default" }.distinctBy { it.id }
}
