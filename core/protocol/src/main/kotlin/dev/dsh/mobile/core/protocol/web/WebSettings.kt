package dev.dsh.mobile.core.protocol.web

/**
 * 网络工具的全部可调项。由宿主（core/data 的设置 + 密钥）组装后注入，
 * core/protocol 不反向依赖 core/data。
 */
data class WebSettings(
    /** auto = 免费引擎优先并自动回退；也可指定 bing / duckduckgo / searxng / exa。 */
    val engine: String = "auto",
    val exaApiKey: String = "",
    val searxngInstance: String = "",
    /** 整次搜索的总预算（不是单个引擎的）。 */
    val searchBudgetMs: Long = 12_000L,
    val fetchBudgetMs: Long = 15_000L,
    val downloadBudgetMs: Long = 20_000L,
    val maxFetchBytes: Long = 2L * 1024 * 1024,
    val maxFetchChars: Int = 20_000,
    val maxDownloadBytes: Long = 64L * 1024 * 1024,
)
