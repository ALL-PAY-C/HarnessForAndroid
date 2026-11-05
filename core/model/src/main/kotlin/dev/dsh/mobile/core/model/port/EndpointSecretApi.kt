package dev.dsh.mobile.core.model.port

/** 端点令牌端口：由 core:data 的 EndpointSecretStore 实现（令牌落 noBackupFilesDir，不进 Room）。 */
interface EndpointSecretApi {
    fun get(id: String): String?
    fun set(id: String, token: String)
    fun remove(id: String)
}
