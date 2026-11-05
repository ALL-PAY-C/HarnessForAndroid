package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.Endpoint
import kotlinx.coroutines.flow.Flow

/** 端点表端口：由 core:data 的 EndpointRepository 实现。migrateLegacyTokens / all 不进接口。 */
interface EndpointApi {
    fun observeEndpoints(): Flow<List<Endpoint>>

    suspend fun save(endpoint: Endpoint)
    suspend fun delete(id: String)
    suspend fun setActive(id: String)
}
