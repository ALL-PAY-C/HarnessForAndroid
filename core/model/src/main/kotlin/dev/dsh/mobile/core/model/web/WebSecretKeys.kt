package dev.dsh.mobile.core.model.web

// O8-B①: 从 core:data.settings.WebSettingsBridge 上移（常量，与 WebSettings 同包）。

/**
 * web 工具用到的密钥 id；值落在 core:data 的 EndpointSecretStore
 * （`noBackupFilesDir/endpoint-tokens.json`，与 endpoint token 同一套存储，不新增密钥文件）。
 */
object WebSecretKeys {
    /** Exa API Key（需密钥引擎 exa）。 */
    const val EXA = "web.exa"
}
