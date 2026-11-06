package dev.dsh.mobile.pluginhost

import java.net.URI

/**
 * 插件自带 WebUI 的全屏地址：`http://127.0.0.1:7788/v1` + `pl.gateway.local`
 * → `http://127.0.0.1:7788/gateway.local/ui`。
 *
 * 端口来自插件加载后的回写（配置键 `baseUrl`），这里只做派生，不猜端口、不补默认值：
 * 端口没显式写出来（缺省或 0）就没有可打开的地址，调用点据此隐藏入口。
 *
 * 路径规则与 NodeBackedPlugin.gatewayRpcPath 同源：去掉 `pl.` 前缀，其余原样；
 * 不是 `pl.` 命名（或只有 `pl.`）返回 null。
 */
fun gatewayUiUrl(baseUrl: String?, pluginId: String): String? {
    val bare = NodeBackedPlugin.gatewayRpcPath(pluginId)?.removePrefix("/") ?: return null
    val raw = baseUrl?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    // 端口是唯一事实来源：插件 webServer 每次启动可能换端口，写死一个只会连到别人的门上。
    if (uri.port <= 0) return null
    return scheme + "://" + uri.host + ":" + uri.port + "/" + bare + "/ui"
}
