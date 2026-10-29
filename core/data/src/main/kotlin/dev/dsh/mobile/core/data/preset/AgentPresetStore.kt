package dev.dsh.mobile.core.data.preset

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json = Json { ignoreUnknownKeys = true }

    /** 标准预设 + 导入的预设；内置预设不在这个列表里（用 [AgentPresets.builtins]）。 */
    val presets = dataStore.data.map { decode(it[key].orEmpty()) }

    /** 内置优先，其次导入的；老版本存的 "default" 归一成标准模式。 */
    suspend fun get(id: String): AgentPreset? {
        val resolved = AgentPresets.resolveId(id)
        AgentPresets.builtinOf(resolved)?.let { return it }
        return presets.first().firstOrNull { it.id == resolved }
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

    private fun decode(raw: String): List<AgentPreset> = listOf(AgentPresets.standard) +
        runCatching { json.decodeFromString<List<AgentPreset>>(raw) }.getOrDefault(emptyList())
            // 内置 id 写死在代码里：存量里若有一份同 id 的旧副本，丢弃它而不是让面板出现两行。
            .filter { it.id !in AgentPresets.builtinIds && it.id != "default" }.distinctBy { it.id }
}
