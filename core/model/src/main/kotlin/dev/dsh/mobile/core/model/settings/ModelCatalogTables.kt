package dev.dsh.mobile.core.model.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// O8-B①: 从 core:data.settings.ModelStore 上移（纯函数与目录表；无 HTTP、无 Android 类型）。
/** 思考强度取值表，UI chip 与校验共用同一份。 */
val reasoningEfforts: List<String> = listOf("off", "low", "medium", "high")

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

    /** 模板自带的 Key（端点不需要用户手填时用）；空串 = 留空由用户填。 */
    val defaultApiKey: String get() = ""

    /** 表单「名称」默认值；默认与 label 一致，需要区分显示名时单独覆盖。 */
    val defaultName: String get() = label
}

object ModelProviders {
    // 插件注册是异步的（装完插件就地热加载、Node 插件晚于首帧起来），所以「插件模型源」
    // 必须是可观察的：UI 订阅 [extraFlow]，插件注册晚一点也能立刻出现在列表里。
    // 早前用普通 MutableList，界面在注册前组合过一次就再也看不到新模型源（实测上游网关插件晚注册时缺 chip）。
    private val extra = MutableStateFlow<List<ModelProvider>>(emptyList())

    // 纯净 App 只留两家官方模板 + 自定义：其余厂商模板由插件通过 register() 注入
    // （见 plugin-api 的 ModelProvider 贡献与 PL1/PL2），宿主不再内置一堆用不到的端点。
    val builtin: List<ModelProvider> = listOf(
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
    )

    /** 插件模型源的注册快照；界面订阅它。 */
    val extraFlow: StateFlow<List<ModelProvider>> get() = extra

    fun all(): List<ModelProvider> = builtin + extra.value

    // 未知 id 回落到「自定义」：拿别的模板兜底会把它的 baseUrl/apiKey 顶到未知配置上。
    fun byId(id: String): ModelProvider = all().firstOrNull { it.id == id }
        ?: builtin.firstOrNull { it.id == "custom" }
        ?: builtin.last()

    fun register(provider: ModelProvider) {
        // 幂等 + 原子：并发注册（插件热加载与首帧同时发生）不会重复进表。
        extra.update { current -> if (current.any { it.id == provider.id }) current else current + provider }
    }
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
