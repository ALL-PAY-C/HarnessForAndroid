package dev.dsh.mobile.core.data.settings

import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import dev.dsh.mobile.core.protocol.web.WebSettings

/**
 * web 工具用到的密钥 id；值落在 [EndpointSecretStore]
 * （`noBackupFilesDir/endpoint-tokens.json`，与 endpoint token 同一套存储，不新增密钥文件）。
 */
object WebSecretKeys {
    /** Exa API Key（需密钥引擎 exa）。 */
    const val EXA = "web.exa"
}

/**
 * 把设置页里的引擎选择 + 独立密钥存储拼成 [WebSettings]。
 * AppContainer 只需要把 [WebSettings] 交给 `LocalEngineTransport(web = ...)`，不必自己拼字段。
 */
fun AppSettings.toWebSettings(secrets: EndpointSecretStore): WebSettings = WebSettings(
    engine = webSearchEngine,
    exaApiKey = secrets.get(WebSecretKeys.EXA).orEmpty(),
    searxngInstance = searxngInstance,
    maxDownloadBytes = maxDownloadBytes,
)
