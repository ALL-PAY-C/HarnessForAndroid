package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.settings.ModelProfile
import kotlinx.coroutines.flow.Flow

/** 模型配置端口：由 core:data 的 ModelStore 实现。目录/探测/内置 Provider 表不在接口内。 */
interface ModelStoreApi {
    val models: Flow<List<ModelProfile>>

    suspend fun save(profile: ModelProfile)
    suspend fun delete(id: String)
    suspend fun setDefault(id: String)
}
