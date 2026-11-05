package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.model.IdentityCard
import dev.dsh.mobile.core.model.preset.AgentPreset
import kotlinx.coroutines.flow.Flow

/** 预设仓库端口：由 core:data 的 AgentPresetStore 实现。cardOf / roleCard 等便捷读口不进接口。 */
interface AgentPresetStoreApi {
    val presets: Flow<List<AgentPreset>>
    val cards: Flow<Map<String, IdentityCard>>
    val roleCards: Flow<Map<String, IdentityCard>>

    suspend fun get(id: String): AgentPreset?
    suspend fun upsert(preset: AgentPreset)
    suspend fun upsertAll(presets: List<AgentPreset>)
    suspend fun remove(id: String)
    suspend fun setCard(id: String, card: IdentityCard?)
    suspend fun setRoleCard(role: GroupRole, card: IdentityCard?)
}
