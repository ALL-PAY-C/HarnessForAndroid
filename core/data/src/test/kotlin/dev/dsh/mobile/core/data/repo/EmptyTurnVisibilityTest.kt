package dev.dsh.mobile.core.data.repo


import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.dsh.mobile.core.platform.DshResult
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.EndpointDao
import dev.dsh.mobile.core.data.db.EndpointEntity
import dev.dsh.mobile.core.data.db.MessageDao
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.MessageSearchRow
import dev.dsh.mobile.core.data.db.WarmRow
import dev.dsh.mobile.core.data.db.MESSAGE_COLUMNS
import dev.dsh.mobile.core.data.db.MESSAGE_TEXT_READ_CAP
import dev.dsh.mobile.core.data.db.SessionDao
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「发送后什么都没有」的可见性回归：
 *  - 前置条件失败（运行时未连接 / 收尾屏障）：以前库里连 USER 都没有、界面全空，只剩一闪而过的横幅；
 *  - prompt 直接抛错：USER 落了、气泡没有；
 *  - 一整轮零产出：finalizeTurn 空正文不落库，转圈结束后会话里没有任何痕迹。
 * 三种都必须留下一条可见的 assistant 气泡。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptyTurnVisibilityTest {

    private class InMemorySessionDao : SessionDao {
        private val map = ConcurrentHashMap<String, SessionEntity>()
        private val state = MutableStateFlow<List<SessionEntity>>(emptyList())

        override fun observeAll(): Flow<List<SessionEntity>> = state
        override suspend fun byId(id: String): SessionEntity? = map[id]
        override suspend fun all(): List<SessionEntity> = state.value
        override suspend fun upsert(session: SessionEntity) { map[session.id] = session; publish() }
        override suspend fun upsertAll(sessions: List<SessionEntity>) { sessions.forEach { map[it.id] = it }; publish() }
        override suspend fun setPinned(id: String, pinned: Int) { map[id]?.let { map[id] = it.copy(pinned = pinned) }; publish() }
        override suspend fun setArchived(id: String, archived: Int) { map[id]?.let { map[id] = it.copy(archived = archived) }; publish() }
        override suspend fun setGroup(id: String, grp: String) { map[id]?.let { map[id] = it.copy(grp = grp) }; publish() }
        override suspend fun setCwd(id: String, cwd: String) { map[id]?.let { map[id] = it.copy(cwd = cwd) }; publish() }
        override suspend fun setTitle(id: String, title: String) { map[id]?.let { map[id] = it.copy(title = title) }; publish() }
        override suspend fun setAgent(id: String, agentId: String?) { map[id]?.let { map[id] = it.copy(agentId = agentId) }; publish() }
        override suspend fun setParticipants(id: String, json: String) { map[id]?.let { map[id] = it.copy(participantsJson = json) }; publish() }
        override suspend fun delete(id: String) { map.remove(id); publish() }
        override suspend fun count(): Int = map.size
        override suspend fun search(q: String, limit: Int): List<SessionEntity> = emptyList()

        override suspend fun resetRunning(): Int = 0

        private fun publish() { state.value = map.values.sortedBy { it.updatedAt } }
    }

    private class InMemoryMessageDao : MessageDao {
        private val map = ConcurrentHashMap<String, MessageEntity>()
        private val state = MutableStateFlow<List<MessageEntity>>(emptyList())

        /** 单行全文读取的次数：用来证明「短消息不补读、同一行不重复读」。 */
        var textByIdCalls = 0

        /** 模拟 @Query 里的 substr(text, 1, MESSAGE_TEXT_READ_CAP)：列表读只拿前缀。 */
        private fun prefix(entity: MessageEntity): MessageEntity =
            if (entity.text.length <= MESSAGE_TEXT_READ_CAP) entity
            else entity.copy(text = entity.text.take(MESSAGE_TEXT_READ_CAP))

        override fun observeBySession(sessionId: String): Flow<List<MessageEntity>> =
            state.map { list -> list.map { prefix(it) } }

        override suspend fun bySession(sessionId: String): List<MessageEntity> =
            map.values.filter { it.sessionId == sessionId }.sortedBy { it.at }.map { prefix(it) }

        /** 温层输入（T9.8-S2）：rowid 用顺序号冒充，这些用例不验证真实 rowid。 */
        override suspend fun warmRows(sessionId: String): List<WarmRow> =
            map.values.filter { it.sessionId == sessionId }.sortedBy { it.at }
                .mapIndexed { i, m -> WarmRow(i + 1L, m.role, m.text) }

        override suspend fun textById(id: String): String? {
            textByIdCalls++
            return map[id]?.text
        }
        override suspend fun countTurn(sessionId: String, turnId: String, authorId: String?): Int =
            map.values.count {
                it.sessionId == sessionId && it.turnId == turnId && it.role == "ASSISTANT" &&
                    (it.authorId ?: "") == (authorId ?: "")
            }

        override suspend fun upsert(message: MessageEntity) { map[message.id] = message; publish() }
        override suspend fun upsertAll(messages: List<MessageEntity>) { messages.forEach { map[it.id] = it }; publish() }
        override suspend fun deleteBySession(sessionId: String) {
            map.filterValues { it.sessionId == sessionId }.keys.forEach { map.remove(it) }
            publish()
        }

        override suspend fun search(q: String, limit: Int): List<MessageSearchRow> = emptyList()

        override suspend fun count(): Int = map.size

        private fun publish() { state.value = map.values.sortedBy { it.at } }
    }

    private class InMemoryEndpointDao : EndpointDao {
        private val map = ConcurrentHashMap<String, EndpointEntity>()
        private val state = MutableStateFlow<List<EndpointEntity>>(emptyList())

        override fun observeAll(): Flow<List<EndpointEntity>> = state
        override suspend fun all(): List<EndpointEntity> = map.values.toList()
        override suspend fun upsert(endpoint: EndpointEntity) { map[endpoint.id] = endpoint; publish() }
        override suspend fun delete(id: String) { map.remove(id); publish() }
        override suspend fun clearToken(id: String) { map[id]?.let { map[id] = it.copy(token = "") }; publish() }

        private fun publish() { state.value = map.values.toList() }
    }

    private class FakeDshDatabase : DshDatabase() {
        val sessions = InMemorySessionDao()
        val messages = InMemoryMessageDao()
        val endpoints = InMemoryEndpointDao()

        override fun sessionDao(): SessionDao = sessions
        override fun messageDao(): MessageDao = messages
        override fun endpointDao(): EndpointDao = endpoints
        override fun warmLayerDao(): dev.dsh.mobile.core.data.db.WarmLayerDao =
            throw UnsupportedOperationException("in-memory fixture: no warm layer dao")
        override fun clearAllTables() = Unit
        override fun createOpenHelper(configuration: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("in-memory fixture: no Room open helper")
        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap())
    }

    private class FakeDshTransport(override val events: SharedFlow<DshEvent>) : DshTransport {
        override val state: StateFlow<RuntimeState> = MutableStateFlow(RuntimeState.Ready(TransportKind.InProcess))
        var promptBody: suspend (String, String, List<String>, String?) -> String = { _, _, _, _ -> "ok" }

        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject = JsonObject(emptyMap())
        override suspend fun listSessions(cwd: String?): List<Session> = emptyList()
        override suspend fun newSession(cwd: String): Session = Session(id = "remote-" + UUID.randomUUID(), title = "新会话", cwd = cwd)
        override suspend fun resumeSession(sessionId: String): Session = Session(id = sessionId, title = "", cwd = "")
        override suspend fun closeSession(sessionId: String) = Unit
        override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String =
            promptBody(sessionId, text, images, turnId)
        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) = Unit
        override suspend fun replyPermission(sessionId: String, requestId: String, optionId: String?) = Unit
    }

    private fun <T> DshResult<T>.orThrow(): T = when (this) {
        is DshResult.Ok -> value
        is DshResult.Err -> throw AssertionError(message, cause)
    }

    private suspend fun assistantTexts(db: FakeDshDatabase, sessionId: String): List<String> =
        db.messages.bySession(sessionId).filter { it.role == "ASSISTANT" }.map { it.text }

    /** 运行时还没连上就发送：失败必须落成气泡，而不是让会话保持全空。 */
    @Test
    fun failureBeforeUserPersistLeavesVisibleBubble() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)
        val session = repo.createSession("/cwd").orThrow()
        // 故意不 attach：requireTransport() 抛「运行时未连接」，失败点在落 USER 之前。

        val r = repo.sendMessage(session.id, "hello")

        assertTrue("必须是失败返回", r is DshResult.Err)
        val texts = assistantTexts(db, session.id)
        assertEquals("失败必须落一条气泡", 1, texts.size)
        assertTrue(texts.single(), texts.single().startsWith("（发送失败"))
        assertTrue(texts.single(), texts.single().contains("运行时未连接"))
    }

    /** USER 已落库、prompt 抛错：以前只有横幅，现在会话里也要看得见。 */
    @Test
    fun promptFailureAfterUserPersistLeavesVisibleBubble() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        // prompt 失败挂在仓库作用域上：生产里是 SupervisorJob（一轮崩不带走整个仓库），
        // 夹具必须同构，否则 async 的失败会直接冲掉测试作用域。
        val repo = ChatRepository(db, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)
        transport.promptBody = { _, _, _, _ -> throw IllegalStateException("网关 401 (fixture)") }

        assertTrue("必须是失败返回", repo.sendMessage(session.id, "hello") is DshResult.Err)
        val texts = assistantTexts(db, session.id)
        assertEquals("失败必须落一条气泡", 1, texts.size)
        assertTrue(texts.single(), texts.single().contains("网关 401"))
    }

    /** 一整轮零产出（既没正文也没工具）：落占位气泡，不留空白会话。 */
    @Test
    fun zeroContentTurnLeavesPlaceholderBubble() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)
        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)
        transport.promptBody = { _, _, _, _ -> "ok" } // 什么事件都不发

        repo.sendMessage(session.id, "hello").orThrow()

        val texts = assistantTexts(db, session.id)
        assertEquals("零产出也要留一条占位气泡", 1, texts.size)
        assertTrue(texts.single(), texts.single().contains("本轮没有产生任何回复"))
    }
}
