package dev.dsh.mobile.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dsh.mobile.core.model.port.ModelStoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import dev.dsh.mobile.core.platform.net.HttpJson
import dev.dsh.mobile.core.model.ReasoningStyle
import dev.dsh.mobile.core.model.settings.sanitizeReasoningEffort
import dev.dsh.mobile.core.model.settings.ModelProfile

// 同 SettingsStore：坏文件退回默认值，不让首启崩在模型配置上
private val Context.modelStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    by preferencesDataStore(
        "dsh_models",
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        },
    )

/**
 * 插件自动注册条目的 id 前缀（ctx.model → ModelStore 那条路已经删掉）。
 *
 * 手填条目的 id 是 "m-<时间戳>"，插件条目是 "plugin.<pluginId>.<model>"，
 * 所以这个前缀只可能来自旧版本的自动注册。
 */
const val AUTO_REGISTERED_MODEL_PREFIX = "plugin."

/** 纯函数：把历史遗留的插件自动条目摘掉；手填条目原样保留（含用户改动过的顺序）。 */
fun pruneAutoRegistered(list: List<ModelProfile>): List<ModelProfile> =
    list.filterNot { it.id.startsWith(AUTO_REGISTERED_MODEL_PREFIX) }

/** 模型配置持久化：重启后仍在，Key 只落在应用私有目录。 */
class ModelStore(private val context: Context) : ModelStoreApi {

    private val key = stringPreferencesKey("models")

    override val models: Flow<List<ModelProfile>> = context.modelStore.data.map { prefs ->
        decode(prefs[key].orEmpty())
    }

    override suspend fun save(profile: ModelProfile) {
        context.modelStore.edit { prefs ->
            val list = decode(prefs[key].orEmpty()).toMutableList()
            val existing = list.indexOfFirst { it.id == profile.id }
            if (existing >= 0) list[existing] = profile else list += profile
            if (profile.isDefault) {
                for (i in list.indices) list[i] = list[i].copy(isDefault = list[i].id == profile.id)
            }
            prefs[key] = encode(list)
        }
    }

    /**
     * 升级自愈：删掉旧版本插件自动塞进来的模型条目。
     *
     * 自动注册路径已经移除，但这些条目还躺在 DataStore 里；留着就是「装完新包列表里还冒 11 条」。
     * 有一条被清掉的行恰好是默认模型时，把默认位让给第一条可用条目，免得聊天直接失联。
     */
    suspend fun purgeAutoRegistered() {
        context.modelStore.edit { prefs ->
            val list = decode(prefs[key].orEmpty())
            val kept = pruneAutoRegistered(list)
            if (kept.size == list.size) return@edit
            val next = if (kept.none { it.isDefault }) {
                kept.firstOrNull { it.apiKey.isNotBlank() && it.model.isNotBlank() }
                    ?.let { first -> kept.map { it.copy(isDefault = it.id == first.id) } }
                    ?: kept
            } else {
                kept
            }
            prefs[key] = encode(next)
        }
    }

    override suspend fun delete(id: String) {
        context.modelStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key].orEmpty()).filterNot { it.id == id })
        }
    }

    override suspend fun setDefault(id: String) {
        context.modelStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key].orEmpty()).map { it.copy(isDefault = it.id == id) })
        }
    }

    private fun encode(list: List<ModelProfile>): String = ModelCodec.encode(list)

    private fun decode(raw: String): List<ModelProfile> = ModelCodec.decode(raw)
}

/**
 * 「models」preferences 的字符串编解码。
 *
 * 与 [ModelStore] 拆开是为了能在 JVM 单测里直接验证旧数据兼容——ModelStore 需要 Context，
 * 而兼容性恰恰只在纯字符串层面成立。字段顺序只许往后追加：
 * 0 id,1 name,2 providerId,3 baseUrl,4 apiKey,5 model,6 isDefault,7 contextWindow,
 * 8 reasoningEffort,9 maxOutputTokens,10 temperature,11 topP,12 supportsTools,13 reasoningStyle,
 * 14 supportsImages,15 supportsOcr,16 reasoningMap（M4/M5 追加，末尾，带默认值）。
 */
internal object ModelCodec {
    const val FIELD_SEP = "\u0001"
    const val RECORD_SEP = "\u0002"

    fun encode(list: List<ModelProfile>): String = list.joinToString(RECORD_SEP) { m ->
        listOf(
            m.id, m.name, m.providerId, m.baseUrl, m.apiKey, m.model,
            if (m.isDefault) "1" else "0", m.contextWindow.toString(),
            sanitizeReasoningEffort(m.reasoningEffort),
            m.maxOutputTokens.coerceAtLeast(0).toString(),
            floatText(m.temperature),
            floatText(m.topP),
            if (m.supportsTools) "1" else "0",
            m.reasoningStyle.name,
            if (m.supportsImages) "1" else "0",
            if (m.supportsOcr) "1" else "0",
            m.reasoningMap,
        )
            .joinToString(FIELD_SEP)
    }

    fun decode(raw: String): List<ModelProfile> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { rec ->
            val f = rec.split(FIELD_SEP)
            if (f.size < 7) return@mapNotNull null
            ModelProfile(
                id = f[0],
                name = f[1],
                providerId = f[2],
                baseUrl = f[3],
                apiKey = f[4],
                model = f[5],
                isDefault = f[6] == "1",
                // 以下全部 getOrNull：旧记录只有 7/8 个字段，读不到就走字段默认值。
                contextWindow = f.getOrNull(7)?.toIntOrNull() ?: 0,
                reasoningEffort = sanitizeReasoningEffort(f.getOrNull(8).orEmpty()),
                maxOutputTokens = f.getOrNull(9)?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                temperature = floatOr(f.getOrNull(10)),
                topP = floatOr(f.getOrNull(11)),
                supportsTools = f.getOrNull(12)?.let { it == "1" } ?: true,
                reasoningStyle = ReasoningStyle.fromWire(f.getOrNull(13)),
                // 14–16 是新加的能力位与映射：旧记录读不到就用字段默认值 false/false/""。
                supportsImages = f.getOrNull(14)?.let { it == "1" } ?: false,
                supportsOcr = f.getOrNull(15)?.let { it == "1" } ?: false,
                reasoningMap = f.getOrNull(16).orEmpty(),
            )
        }
    }

    private fun floatText(v: Float): String = if (v.isNaN() || !v.isFinite()) "" else v.toString()

    private fun floatOr(raw: String?): Float =
        raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f
}

