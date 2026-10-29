package dev.dsh.mobile.core.common

/**
 * T6：全局代理（HTTP(S)_PROXY）。设置里填一次，之后每个子进程 env 都跟着变。
 *
 * 只做两件事：解析用户输入、生成 env / export 前缀。空值一律不注入——
 * 写出 `HTTP_PROXY=` 这种空值会把 curl/pip/git 直接带偏（它们认为是「显式无代理」）。
 */
object ProxyEnv {

    /** 单例状态：设置页写入，各通道读取。进程内共享，不落盘（落盘在 SettingsStore）。 */
    @Volatile
    private var current: String = ""

    val url: String get() = current

    fun set(raw: String?) {
        current = normalize(raw)
    }

    /**
     * 只接受 `scheme://host[:port][/path]`。
     * 没写 scheme 的补 http://（用户习惯只填 127.0.0.1:7890）；
     * 带空白或换行的直接判无效——它会被拼进 shell，坏输入不该落进子进程。
     */
    fun normalize(raw: String?): String {
        val text = raw?.trim()?.trim('"', '\'').orEmpty()
        if (text.isEmpty()) return ""
        if (text.any { it.isWhitespace() }) return ""
        val url = if (text.contains("://")) text else "http://" + text
        val scheme = url.substringBefore("://").lowercase()
        if (scheme !in SCHEMES) return ""
        val host = url.substringAfter("://")
        if (host.isEmpty() || host.startsWith("/")) return ""
        return url
    }

    /** 大写 + 小写都给：curl 只认小写 http_proxy，pip / Java 认大写。 */
    fun vars(url: String = current): Map<String, String> =
        if (url.isBlank()) emptyMap() else linkedMapOf(
            "HTTP_PROXY" to url,
            "HTTPS_PROXY" to url,
            "http_proxy" to url,
            "https_proxy" to url,
        )

    /** 并进已有 env；url 为空时原样返回（空值不注入）。 */
    fun merge(base: Map<String, String>, url: String = current): Map<String, String> =
        if (url.isBlank()) base else base + vars(url)

    /** 给只能传命令字符串的通道用（su -c / Shizuku / Termux 广播）：先 export 再跑命令。 */
    fun prefix(command: String, url: String = current): String {
        if (url.isBlank()) return command
        val quoted = "'" + url.replace("'", "'\\''") + "'"
        val exports = vars(url).keys.joinToString("\n") { "export " + it + "=" + quoted }
        return exports + "\n" + command
    }

    /** 给内置 PTY 终端用（env 是 "K=V" 列表）。 */
    fun envList(base: List<String>, url: String = current): List<String> =
        if (url.isBlank()) base else base + vars(url).map { (k, v) -> k + "=" + v }

    private val SCHEMES = setOf("http", "https", "socks5", "socks5h")
}
