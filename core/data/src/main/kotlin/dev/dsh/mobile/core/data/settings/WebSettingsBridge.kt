package dev.dsh.mobile.core.data.settings

import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import dev.dsh.mobile.core.model.web.WebSecretKeys
import dev.dsh.mobile.core.model.web.WebSettings

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
