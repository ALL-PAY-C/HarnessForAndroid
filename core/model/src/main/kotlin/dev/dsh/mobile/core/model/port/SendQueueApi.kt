package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.chat.SendQueueItem
import dev.dsh.mobile.core.platform.DshResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 发送队列端口：feature 只管入队/插队/清空与错误横幅，不认 SendQueueEngine。
 *
 * 由 core:data 的 SendQueue 实现。remove / drain / errorFlow 等内部成员不进接口。
 */
interface SendQueueApi {
    val items: StateFlow<Map<String, List<SendQueueItem>>>

    fun sendError(sessionId: String): StateFlow<String?>
    fun clearSendError(sessionId: String)

    fun submit(sessionId: String, exclusive: Boolean = true, block: suspend () -> DshResult<Unit>): Boolean
    fun enqueue(sessionId: String, text: String, images: List<String>, limit: Int): Boolean
    fun of(sessionId: String): List<SendQueueItem>
    fun clear(sessionId: String)
    fun drop(sessionId: String, id: String)
    fun promote(sessionId: String, id: String)

    suspend fun sendNow(sessionId: String, id: String)
    suspend fun sendText(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit>
    suspend fun cancelAndSend(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit>
}
