package dev.dsh.mobile.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一个可接入的模型后端（DeepSeek / OpenAI 兼容 / 自定义）。 */
data class ModelProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val isDefault: Boolean = false,
    /** Context window discovered from the endpoint or entered by hand; 0 = unknown. */
    val contextWindow: Int = 0,
    /** 思考强度，取值 ["off", "low", "medium", "high"]；写进请求体的 reasoning effort。 */
    val reasoningEffort: String = "high",
    /** 最大输出 tokens；0 = 请求里不发送 max_tokens。 */
    val maxOutputTokens: Int = 0,
    /** 温度；负数 = 不发送（用 -1f 而不是 NaN，方便序列化与比较）。 */
    val temperature: Float = -1f,
    /** Top P；负数 = 不发送。 */
    val topP: Float = -1f,
    /** 支持工具调用：false 时请求里不带 tools。 */
    val supportsTools: Boolean = true,
) {
    /** 徽标文案：关 / 低 / 中 / 高。 */
    val reasoningLabel: String
        get() = reasoningLabelOf(reasoningEffort)
}

/** 思考强度取值表，UI chip 与校验共用同一份。 */
val reasoningEfforts: List<String> = listOf("off", "low", "medium", "high")

fun reasoningLabelOf(effort: String): String = when (effort) {
    "off" -> "关"
    "low" -> "低"
    "medium" -> "中"
    else -> "高"
}

/** 未知/异常取值一律落回 "high"，旧数据与手改文件都不会带出非法状态。 */
fun sanitizeReasoningEffort(raw: String): String =
    raw.trim().lowercase().takeIf { it in reasoningEfforts } ?: "high"

/** 温度/Top P 输入过滤：只留数字与一个小数点，非法字符直接吃掉。 */
fun sanitizeDecimalInput(raw: String): String {
    val out = StringBuilder()
    var dot = false
    for (ch in raw) {
        when {
            ch.isDigit() -> out.append(ch)
            (ch == '.' || ch == ',') && !dot && out.isNotEmpty() -> {
                dot = true
                out.append('.')
            }
        }
    }
    return out.toString()
}

/**
 * 模型提供方描述。新增第三方只需实现该接口并 register()，Agent 页与设置页自动出现，
 * 不需要改核心代码。
 */
interface ModelProvider {
    val id: String
    val label: String
    val defaultBaseUrl: String
    val defaultModel: String
    val hint: String
}

object ModelProviders {
    private val extra = mutableListOf<ModelProvider>()

    val builtin: List<ModelProvider> = listOf(
        object : ModelProvider {
            override val id = "deepseek"
            override val label = "DeepSeek"
            override val defaultBaseUrl = "https://api.deepseek.com/v1"
            override val defaultModel = "deepseek-chat"
            override val hint = "官方 API Key，sk- 开头"
        },
        object : ModelProvider {
            override val id = "openai-compatible"
            override val label = "OpenAI 兼容"
            override val defaultBaseUrl = "https://api.openai.com/v1"
            override val defaultModel = "gpt-4o-mini"
            override val hint = "任何兼容 /v1/chat/completions 的服务"
        },
        object : ModelProvider {
            override val id = "anthropic"
            override val label = "Anthropic"
            override val defaultBaseUrl = "https://api.anthropic.com"
            override val defaultModel = "claude-sonnet-4-20250514"
            override val hint = "官方 Key，sk-ant- 开头"
        },
        object : ModelProvider {
            override val id = "custom"
            override val label = "自定义"
            override val defaultBaseUrl = "http://192.168.1.10:11434/v1"
            override val defaultModel = "qwen2.5:7b"
            override val hint = "本地 Ollama / vLLM / 第三方中转"
        },
        // 地址填 engine://<模型 id>，请求不走网络，直接交给 :node 引擎里插件注册的 provider。
        object : ModelProvider {
            override val id = "engine"
            override val label = "引擎插件"
            override val defaultBaseUrl = "engine://"
            override val defaultModel = "deepseek-v4.1-flash"
            override val hint = "用 :node 引擎里插件注册的 provider；先在插件页打开一次引擎，" +
                "模型名填插件声明的 id（如 deepseek-v4.1-flash）"
        },
    )

    fun all(): List<ModelProvider> = builtin + extra

    fun byId(id: String): ModelProvider = all().firstOrNull { it.id == id } ?: builtin.last()

    fun register(provider: ModelProvider) {
        if (all().none { it.id == provider.id }) extra += provider
    }
}

// 同 SettingsStore：坏文件退回默认值，不让首启崩在模型配置上
private val Context.modelStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    by preferencesDataStore(
        "dsh_models",
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        },
    )

/** 模型配置持久化：重启后仍在，Key 只落在应用私有目录。 */
class ModelStore(private val context: Context) {

    private val key = stringPreferencesKey("models")

    val models: Flow<List<ModelProfile>> = context.modelStore.data.map { prefs ->
        decode(prefs[key].orEmpty())
    }

    suspend fun save(profile: ModelProfile) {
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

    suspend fun delete(id: String) {
        context.modelStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key].orEmpty()).filterNot { it.id == id })
        }
    }

    suspend fun setDefault(id: String) {
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
 * 8 reasoningEffort,9 maxOutputTokens,10 temperature,11 topP,12 supportsTools。
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
            )
        }
    }

    private fun floatText(v: Float): String = if (v.isNaN() || !v.isFinite()) "" else v.toString()

    private fun floatOr(raw: String?): Float =
        raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f
}

/**
 * 一个模型的上下文窗口。
 *
 * 优先级：手填/端点探测到的 [hint] > [discovered]（端点 /models 里的 context_length）
 * > 内置表。内置表只能是猜测，能读到真实值就以真实值为准；都不认识时回 32k，
 * 这样「已用百分比」至少还是个有意义的数。
 */
fun contextWindowOf(model: String, hint: Int = 0, discovered: Map<String, Int> = emptyMap()): Int {
    if (hint > 0) return hint
    // 端点给了就信端点（含 "deepseek/xxx" 这类带前缀的写法，两种键都认）。
    val key = model.trim().lowercase()
    val probed = discovered[key] ?: discovered[key.substringAfterLast('/')]
    if (probed != null && probed > 0) return probed
    val m = key
    return when {
        m.isBlank() -> 32_000
        "v4" in m || "v4.1" in m || "v4-1" in m || "1m" in m -> 1_000_000
        "gemini" in m -> 1_000_000
        "claude" in m -> 200_000
        "gpt-4.1" in m || "gpt-4o" in m -> 128_000
        // DeepSeek 官方 API（deepseek-chat / deepseek-reasoner / deepseek-v3）都是 128k。
        // 以前这里写 64_000，界面把 128k 的模型显示成「已用一半」，用户以为快满了。
        "deepseek" in m -> 128_000
        "qwen" in m -> 32_000
        "llama" in m -> 8_192
        else -> 32_000
    }
}

/**
 * 端点 /models 返回里各模型的上下文窗口表（context_length / context_window）。
 *
 * 各家字段名不统一也不保证存在，所以读不到就返回空表、调用方退回内置表。
 * 纯字符串解析，单测直接喂样例 JSON。
 */
internal fun parseContextLengths(body: String): Map<String, Int> {
    if (body.isBlank()) return emptyMap()
    val json = runCatching { JSONObject(body) }.getOrNull()
    val array = json?.optJSONArray("data")
        ?: json?.optJSONArray("models")
        ?: runCatching { JSONArray(body) }.getOrNull()
        ?: return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("id").ifBlank { item.optString("name") }.trim()
        if (id.isEmpty()) continue
        val window = listOf("context_length", "context_window", "max_context_length", "max_input_tokens")
            .firstNotNullOfOrNull { field -> item.optInt(field, 0).takeIf { it > 0 } }
            ?: continue
        out[id] = window
    }
    return out
}

/**
 * 从模型端点的 GET {baseUrl}/models 读一次窗口表；失败返回空表，从不抛。
 *
 * 与「测试连接」同一条请求路径、同样只用 HttpURLConnection，不引第三方网络库。
 * 界面拿到模型列表后再调它，把 [contextWindowOf] 的 discovered 填上。
 */
suspend fun probeContextLengths(
    baseUrl: String,
    apiKey: String,
    timeoutMs: Int = 8_000,
): Map<String, Int> = withContext(Dispatchers.IO) {
    val root = baseUrl.trim().trimEnd('/')
    if (root.isEmpty()) return@withContext emptyMap()
    runCatching {
        val conn = (URL(root + "/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + apiKey.trim())
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching emptyMap()
            parseContextLengths(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyMap())
}
