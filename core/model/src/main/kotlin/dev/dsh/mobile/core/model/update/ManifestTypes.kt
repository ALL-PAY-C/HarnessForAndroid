package dev.dsh.mobile.core.model.update

/** 清单这一版是从哪来的。界面/日志靠它区分「刚拉的」与「回退的」。 */
enum class ManifestOrigin { Live, Memory, Disk }

/** 一个清单源的失败原因。多源同时挂掉时要能看到每一个，而不是只有第一个。 */
data class SourceFailure(val url: String, val reason: String)

/** 单个源的实测结果：耗时 + 失败原因（成功为 null）。 */
data class SourceStat(val url: String, val elapsedMs: Long, val failure: String? = null)

/** 源短名，给界面用：「源：gh-proxy.com」比整条 URL 好读。解析不出来就原样返回。 */
fun sourceLabel(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
