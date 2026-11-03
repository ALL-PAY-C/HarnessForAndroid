package dev.dsh.mobile.pluginhost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 重试与并发策略。刻意不碰 android.*，单测直接注入假 fetch 就能验证退避次数与并发上限。
 */
object SourceRetry {

    /** 额外重试次数：总共 3 次尝试。市场源大多是 DNS/连接闪断，两次足够。 */
    const val MAX_RETRIES = 2

    /** 首次退避 400ms，第二次 800ms；再长就会被单源预算吃掉。 */
    const val BASE_DELAY_MS = 400L

    /** 第 [attempt] 次重试前等多久（attempt 从 1 开始）。 */
    fun delayMs(attempt: Int): Long =
        if (attempt <= 0) 0L else BASE_DELAY_MS shl (attempt - 1)

    /** 全部重试累计的等待时间，用来估算单源最坏耗时。 */
    fun totalDelayMs(maxRetries: Int = MAX_RETRIES): Long =
        (1..maxRetries.coerceAtLeast(0)).sumOf { delayMs(it) }
}

/**
 * 可选 token 只发给 api.github.com。
 *
 * 为什么限死这个域名：token 是用户给 GitHub 的，撒到第三方反代上就是泄密。
 * 没有 token 时返回 null，请求头里干脆不出现 Authorization。
 */
fun authHeader(url: String, token: String?): String? =
    token?.takeIf { it.isNotBlank() && url.startsWith("https://api.github.com/") }
        ?.let { "Bearer " + it.trim() }

/**
 * 跑 [block]，失败按指数退避重试，最多 [maxRetries] 次。
 *
 * 超时（[kotlinx.coroutines.TimeoutCancellationException]）属于 [CancellationException]，
 * 这里原样抛出：已经等满单源预算再重试只会把预算翻倍，没有意义。
 */
suspend fun <T> retrying(
    maxRetries: Int = SourceRetry.MAX_RETRIES,
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (attempt > maxRetries) throw e
            sleep(SourceRetry.delayMs(attempt))
            attempt++
        }
    }
}
