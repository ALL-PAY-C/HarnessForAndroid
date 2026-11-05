package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.chat.TurnState
import dev.dsh.mobile.core.model.compaction.CompactResult
import dev.dsh.mobile.core.model.compaction.RecallReport
import dev.dsh.mobile.core.model.context.WarmRefreshReport
import dev.dsh.mobile.core.model.projection.SessionProjection
import dev.dsh.mobile.core.platform.DshResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话仓库端口：feature 只读会话/消息/投影，写会话元信息与轮次控制。
 *
 * 由 core:data 的 ChatRepository 实现。26 个成员是 feature 实际调用的子集；
 * attach / sendMessage / messageText / searchMessages / setConfigOption 等
 * 编排层内部用的成员不进接口。
 */
interface ChatApi {
    val transport: StateFlow<DshTransport?>
    val turns: StateFlow<Map<String, TurnState>>
    val commands: StateFlow<List<String>>

    fun observeSessions(): Flow<List<Session>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    fun projection(sessionId: String): StateFlow<SessionProjection>

    suspend fun session(id: String): Session?
    suspend fun createSession(cwd: String, agentId: String? = null, title: String = ""): DshResult<Session>
    suspend fun createGroup(cwd: String, title: String, participants: List<Participant>): DshResult<Session>
    suspend fun refreshSessions(cwd: String?): DshResult<Int>

    suspend fun rename(id: String, title: String)
    suspend fun setAgent(id: String, agentId: String?)
    suspend fun setGroup(id: String, group: String)
    suspend fun setParticipants(id: String, participants: List<Participant>)
    suspend fun setCwd(id: String, cwd: String)
    suspend fun setPersona(id: String, persona: String)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun clearMessages(id: String)
    suspend fun delete(id: String)

    suspend fun compact(sessionId: String, budgetTokens: Int = 0): CompactResult
    suspend fun refreshWarm(sessionId: String): WarmRefreshReport?
    suspend fun recall(sessionId: String, query: String): RecallReport?

    suspend fun interject(sessionId: String, text: String): DshResult<Unit>
    suspend fun cancel(sessionId: String): DshResult<Unit>
    suspend fun answerPermission(sessionId: String, requestId: String, optionId: String?, ownerAgentId: String? = null): DshResult<Unit>
}
