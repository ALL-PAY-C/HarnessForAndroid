package dev.dsh.buildlogic

/**
 * 下载镜像前缀的单一真源。
 *
 * 上游 URL 原样拼在前缀后面（`prefix + url`），直连永远排在前缀之前。
 * node-runtime 与 linux-runtime 都从这里取，不再各自维护一份列表。
 */
object Mirrors {
    val GITHUB_RELEASE_PREFIXES: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://hk.gh-proxy.com/",
    )
}
