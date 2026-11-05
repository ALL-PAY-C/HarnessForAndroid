package dev.dsh.mobile.pluginhost.builtin

import dev.dsh.mobile.pluginapi.ConfigField
import dev.dsh.mobile.pluginapi.ConfigFieldType

/**
 * 两个网关插件共用的设置项与解析规则。
 *
 * 上游身份全在这里由用户填：base、令牌、代理、模型、额外头。代码里只有示例 base
 * （[dev.dsh.mobile.pluginhost.builtin.gateway.stream.UpstreamSpec.EXAMPLE_UPSTREAM]），
 * 不出现任何具体域名或客户端指纹。
 */
internal object GatewaySettings {

    const val KEY_BASE_URL = "baseUrl"
    const val KEY_TOKEN = "token"
    const val KEY_MODEL = "model"
    const val KEY_PROXY = "proxy"
    const val KEY_EXTRA_HEADERS = "extraHeaders"

    /** 上游对话路径：接真实上游时填它自己的路径（含查询串）。 */
    const val KEY_PATH = "path"

    /** 上游项目标识：部分上游要求带，空则不写。 */
    const val KEY_PROJECT_ID = "projectId"

    /** 出站 UA：部分上游校验客户端标识，空则用示例 UA。 */
    const val KEY_USER_AGENT = "userAgent"

    /** 设置项：两个插件同形，只有 base 的提示语按网关类型区分。 */
    fun fields(baseHint: String): List<ConfigField> = listOf(
        ConfigField(
            key = KEY_BASE_URL,
            label = "上游 Base URL",
            type = ConfigFieldType.Text,
            hint = baseHint,
        ),
        ConfigField(
            key = KEY_TOKEN,
            label = "鉴权令牌",
            type = ConfigFieldType.Secret,
            hint = "上游的 API Key / Bearer 令牌，只存在本机配置里",
        ),
        ConfigField(
            key = KEY_MODEL,
            label = "模型名（可选）",
            type = ConfigFieldType.Text,
            hint = "填了就覆盖请求里的 model；留空用客户端传来的",
        ),
        ConfigField(
            key = KEY_PROXY,
            label = "上游代理（可选）",
            type = ConfigFieldType.Text,
            hint = "如 http://127.0.0.1:7890，留空直连",
        ),
        ConfigField(
            key = KEY_EXTRA_HEADERS,
            label = "额外请求头（可选）",
            type = ConfigFieldType.Text,
            hint = "一行一个 Name: Value，只加在发往上游的请求上",
        ),
    )

    /**
     * 流式网关的设置项：在通用五项之后追加「上游形状」三项（路径 / 项目 ID / UA）。
     * 直通网关用不上它们，所以单独开一个入口，不动 [fields] 的既有清单。
     */
    fun streamFields(baseHint: String): List<ConfigField> = fields(baseHint) + listOf(
        ConfigField(
            key = KEY_PATH,
            label = "上游路径",
            type = ConfigFieldType.Text,
            hint = "示例路径；接真实上游时填它自己的对话路径",
        ),
        ConfigField(
            key = KEY_PROJECT_ID,
            label = "项目 ID",
            type = ConfigFieldType.Text,
            hint = "示例留空；部分上游要求带项目标识",
        ),
        ConfigField(
            key = KEY_USER_AGENT,
            label = "客户端 UA",
            type = ConfigFieldType.Text,
            hint = "示例留空；部分上游校验客户端标识",
        ),
    )

    /** 一行一个 `Name: Value`；空行、缺冒号的行跳过。 */
    fun headers(raw: String?): Map<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val at = trimmed.indexOf(':')
            if (at <= 0) continue
            val name = trimmed.substring(0, at).trim()
            val value = trimmed.substring(at + 1).trim()
            if (name.isNotEmpty()) out[name] = value
        }
        return out
    }
}
