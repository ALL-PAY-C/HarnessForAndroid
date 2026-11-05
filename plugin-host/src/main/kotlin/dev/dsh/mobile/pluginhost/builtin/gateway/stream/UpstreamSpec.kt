package dev.dsh.mobile.pluginhost.builtin.gateway.stream

/**
 * 上游端点与通用常量的**唯一落点**。
 *
 * 这里只剩协议形状：示例 base、OpenAI 兼容路径、默认 UA。上游身份（域名、OAuth 客户端、
 * 指纹头）全部移到插件设置项，由用户在「流式网关」设置页里填，代码里不留第二处。
 *
 * 覆盖方式：设置项 baseUrl 填任意 OpenAI 兼容 base（https:// 开头），网关就改打它；
 * 不填走下面的示例值（不可达，只用来占位与展示）。
 */
object UpstreamSpec {

    /** 示例上游，可由配置覆盖。 */
    const val EXAMPLE_UPSTREAM = "https://api.example.invalid/v1"

    /** OpenAI 兼容路径。 */
    const val CHAT_PATH = "/chat/completions"
    const val MODELS_PATH = "/models"

    /** 默认 UA：不带任何上游客户端指纹。 */
    const val DEFAULT_USER_AGENT = "DshGateway/1.0"

    /** 信封里 userAgent 字段的取值（只要通道别名，不带版本）。 */
    const val USER_AGENT_ALIAS = "DshGateway"

    /** 凭据 json 的 type 字段与能力提示子对象键名。 */
    const val CREDENTIAL_TYPE = "gateway"
    const val HINTS_KEY = "capability_hints"
}
