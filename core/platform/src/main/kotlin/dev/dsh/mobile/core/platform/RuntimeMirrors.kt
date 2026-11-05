package dev.dsh.mobile.core.platform

/**
 * 运行时镜像地址的唯一入口。
 *
 * 为什么要收敛：反代/jsDelivr 域名原先硬编码在 Updater、PluginMarket、LinuxEnvBootstrap
 * 三处，换一个前缀要改三个文件，三份默认值还会各自漂移。构建期的镜像另有一套
 * （build-logic 的 `Mirrors`，管 Gradle 下载）——那套和这里互不覆盖：一套管打包机，
 * 一套管手机上运行时真正发出的请求。
 *
 * 可配：[configure] 用用户设置的列表整体替换默认值，[reset] 回到默认。默认值保持
 * 「按可达性排序、第一个是首选」的语义，调用方取 [githubProxy] 即可。
 */
object RuntimeMirrors {

    /** 默认 GitHub 反代前缀，按可达性排序。 */
    val DEFAULT_GITHUB_PROXIES: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://hk.gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** jsDelivr 的两套前缀：GitHub 仓库文件（gh）与 npm 包（npm）。 */
    const val JSDELIVR_GH = "https://cdn.jsdelivr.net/gh/"
    const val JSDELIVR_NPM = "https://cdn.jsdelivr.net/npm/"

    /** 上游原始域名。反代前缀是「拼在完整 URL 前面」的形态，所以这两条要保留原样。 */
    const val GITHUB = "https://github.com/"
    const val GITHUB_RAW = "https://raw.githubusercontent.com/"

    @Volatile
    private var override: List<String>? = null

    /** 用户配置的镜像前缀；空列表等于「用默认」。尾部斜杠统一补齐，避免拼出 https://hostraw... */
    fun configure(proxies: List<String>) {
        val clean = proxies.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith("/")) it else it + "/" }
        override = clean.ifEmpty { null }
    }

    fun reset() {
        override = null
    }

    /** 当前生效的反代前缀。 */
    fun githubProxies(): List<String> = override ?: DEFAULT_GITHUB_PROXIES

    /** 首选反代前缀；一个都没有时返回空串。 */
    fun githubProxy(): String = githubProxies().firstOrNull().orEmpty()

    /** 给完整 GitHub URL 套首选反代；没有前缀就原样返回（不要拼出半截地址）。 */
    fun proxied(url: String): String {
        if (url.isBlank()) return url
        val prefix = githubProxy()
        return if (prefix.isEmpty()) url else prefix + url
    }

    /**
     * 一个下载 URL 的全部候选地址（去重保序）：GitHub 域名先套各反代前缀，再给原地址兜底；
     * 非 GitHub 域名原样返回（反代只认 GitHub 的路径，套上去只会白白多几个 404 探测）。
     *
     * 所有下载点都从这里取候选，不许在调用方硬编码 gh-proxy 前缀。
     */
    fun downloadCandidates(url: String): List<String> {
        val text = url.trim()
        if (text.isEmpty()) return emptyList()
        return if (isGitHubHost(text)) {
            LinkedHashSet(githubProxies().map { it + text } + text).toList()
        } else {
            listOf(text)
        }
    }

    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "codeload.github.com",
        "objects.githubusercontent.com",
    )

    private fun isGitHubHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return GITHUB_HOSTS.any { host == it || host.endsWith("." + it) }
    }
}
