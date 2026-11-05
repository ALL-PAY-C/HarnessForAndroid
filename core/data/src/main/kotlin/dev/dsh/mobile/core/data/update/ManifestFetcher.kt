package dev.dsh.mobile.core.data.update

import dev.dsh.mobile.core.model.web.HttpRequest
import dev.dsh.mobile.core.protocol.web.HttpTransport
import dev.dsh.mobile.core.protocol.web.defaultHttpTransport

/**
 * 清单出网口。抽成接口只为一件事：200 / 404 / 超时三条路径都能用 fake 或本地服务器测到，
 * 不必真的发一次公网请求。
 */
fun interface ManifestFetcher {
    suspend fun fetch(url: String): String
}

/**
 * 复用仓库既有 HTTP 能力：:core:protocol 的 [HttpTransport]（:core:data 早已依赖该模块），
 * 不再自己开裸连接拼超时与 UA。
 *
 * 语义与原来的 `Updater.httpGet` 一致：跟随重定向、UA `dsh-mobile-updater`、
 * `Cache-Control: no-cache`、非 2xx 抛 `HTTP <code>：<正文前 120 字>`。
 * 超时取原 readTimeout 20s（原来 connect 12s / read 20s 两档，[HttpRequest.timeoutMs]
 * 只有一个值，取大的那一档；真正的止损线是 [ManifestRace] 的每源超时）。
 */
class DefaultManifestFetcher(
    private val transport: HttpTransport = defaultHttpTransport(),
    private val timeoutMs: Long = 20_000L,
) : ManifestFetcher {

    override suspend fun fetch(url: String): String {
        val response = transport.execute(
            HttpRequest(
                url = url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Cache-Control" to "no-cache",
                ),
                followRedirects = true,
                timeoutMs = timeoutMs,
            ),
        )
        val text = response.body.toString(Charsets.UTF_8)
        require(response.code in 200..299) { "HTTP " + response.code + "：" + text.take(120) }
        return text
    }

    private companion object {
        const val USER_AGENT = "dsh-mobile-updater"
    }
}
