package dev.dsh.mobile.core.model.settings

/** 探测失败的分类。展示文案由 UI 层按资源拼接（core:data 不带中文）。 */
enum class ProbeFailure { EmptyBaseUrl, Timeout, Transport, Unknown }

/** 「测试连接」的结果。纯数据，展示文案在 UI 层按资源拼。 */
data class ProbeResult(
    val ok: Boolean,
    val elapsedMs: Long,
    /** GET /models 拿到的前几个模型名；走 chat/completions 退化成功时为空。 */
    val models: List<String> = emptyList(),
    /** 非 2xx 时的 HTTP 状态码；0 = 没拿到响应（连接失败 / 超时）。 */
    val httpCode: Int = 0,
    /** 失败原因（异常信息或响应体片段）。 */
    val error: String = "",
    /** 失败的分类；成功或只有原始错误串时为 null。 */
    val failure: ProbeFailure? = null,
    /** 设备内引擎插件（engine:// ...）不需要联网，成功时直接算通过。 */
    val engineOffline: Boolean = false,
)
