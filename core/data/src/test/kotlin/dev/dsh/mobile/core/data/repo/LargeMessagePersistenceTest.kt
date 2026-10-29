package dev.dsh.mobile.core.data.repo

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.EndpointDao
import dev.dsh.mobile.core.data.db.EndpointEntity
import dev.dsh.mobile.core.data.db.MessageDao
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.MessageSearchRow
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * T4 数据层：26 万字原文必须整条落库，读回不丢字、不带截断提示；
 * 2MB CursorWindow 的保护改到「读」这一侧（列表/历史查询只取前缀，全文按 id 单行读）。
 *
 * 夹具沿 [ChatRepositoryIntegrationTest] 的既有做法：真实 ChatRepository + 内存 DAO，
 * 不依赖 Room/Robolectric（不加依赖）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LargeMessagePersistenceTest {

    private companion object {
        /** 设备实测那条 HTML 表格回复的字数（T4 现场值，别改成 round number）。 */
        const val BODY_CHARS = 262_173

        /** 末尾带一行可辨识的哨兵：截断会把它切掉。 */
        const val LAST_LINE = "末行哨兵-T4-完"

        const val ROW = "<tr><td>很长的表格单元内容</td></tr>"

        /** 恰好 BODY_CHARS 字：前缀行 + '.' 补齐 + 换行 + 末行哨兵。 */
        fun bigBody(): String {
            val prefixTarget = BODY_CHARS - LAST_LINE.length - 1
            val sb = StringBuilder(prefixTarget)
            sb.append("<table><tr><th>列</th></tr>")
            while (sb.length + ROW.length <= prefixTarget) sb.append(ROW)
            while (sb.length < prefixTarget) sb.append('.')
            sb.append('\n').append(LAST_LINE)
            return sb.toString()
        }
    }

    // ---------------------------------------------------------------- fixtures

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

    // ---------------------------------------------------------------- tests

    /** 红→绿：26 万字原文整条落库，读回一字不差，末行在，且不写截断提示。 */
    @Test
    fun bigAssistantBodyIsPersistedVerbatim() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)
        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val body = bigBody()
        assertEquals(BODY_CHARS, body.length)

        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = body, turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }

        repo.sendMessage(session.id, "跑一下这个表的 HTML").orThrow()

        // 列表读只给前缀（CursorWindow 保护），原文要按 id 读那一行。
        val stored = db.messages.textById(db.messages.bySession(session.id).first { it.role == "ASSISTANT" }.id)!!
        assertEquals("原文必须整条落库", BODY_CHARS, stored.length)
        assertTrue("末行不能丢", stored.endsWith(LAST_LINE))
        assertEquals("逐字一致", body, stored)
        assertFalse("不许再写截断提示", stored.contains("已截断保存"))
        assertFalse("不许带原始字数提示", stored.contains("原始 262173 字"))
    }

    /** 红→绿：列表读走前缀上限（保护 CursorWindow），全文由 messageText(id) 单行补齐。 */
    @Test
    fun fullBodyIsReadableByIdWhileListStaysBounded() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)
        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val body = bigBody()
        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = body, turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }
        repo.sendMessage(session.id, "再来一次").orThrow()

        val row = db.messages.bySession(session.id).first { it.role == "ASSISTANT" }
        val full = repo.messageText(row.id)
        assertEquals("按 id 读回全文", body, full)
    }

    /**
     * 红→绿：UI 消费的 observeMessages 必须直接带全文，而不是 256K 前缀。
     * 用例先断言「列表查询确实只有前缀」（prefix != full），否则后半段等于没测。
     */
    @Test
    fun listFlowCarriesFullBodyForLongMessages() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)
        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val body = bigBody()
        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = body, turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }
        val question = "这条短问题不该触发补读"
        repo.sendMessage(session.id, question).orThrow()

        val bigRow = db.messages.bySession(session.id).first { it.role == "ASSISTANT" }
        // 前置证据：列表查询这一层真的丢字（substr 前缀），补全文才有意义。
        assertEquals("列表查询只给前缀", MESSAGE_TEXT_READ_CAP, bigRow.text.length)
        assertTrue("前缀≠全文", bigRow.text.length < body.length)
        assertFalse("前缀里没有末行哨兵", bigRow.text.endsWith(LAST_LINE))

        db.messages.textByIdCalls = 0
        val first = repo.observeMessages(session.id).first()
        assertEquals("只补读那一条前缀行", 1, db.messages.textByIdCalls)
        val big = first.first { it.id == bigRow.id }
        assertEquals("消费方拿到原文", body, big.text)
        assertEquals(BODY_CHARS, big.text.length)
        assertTrue("末行哨兵在", big.text.endsWith(LAST_LINE))
        assertEquals("短消息原样", question, first.first { it.role == ChatMessage.Role.USER }.text)

        // 同一条再收一次：全文走缓存，不再打库（流式期间会反复发射）。
        repo.observeMessages(session.id).first()
        assertEquals("同一行只补读一次", 1, db.messages.textByIdCalls)
    }

    /**
     * 保护没被删掉：列表/历史查询的列清单必须只取 text 前缀，且上限常量还是 256K 字符。
     * @Query 与这份列清单是同一个编译期常量，改一处 DAO 两处查询一起变 —— 所以断言它等于断言 DAO。
     */
    @Test
    fun listQueryStillBoundByCursorWindowGuard() {
        assertEquals("CursorWindow 保护上限不变", 256 * 1024, MESSAGE_TEXT_READ_CAP)
        assertTrue(
            "列表查询必须用 substr 取前缀，不能 SELECT 整列 text: " + MESSAGE_COLUMNS,
            MESSAGE_COLUMNS.contains("substr(text, 1, $MESSAGE_TEXT_READ_CAP) AS text"),
        )
        assertFalse(
            "原始 text 列不能直接出现在列表列清单里: " + MESSAGE_COLUMNS,
            MESSAGE_COLUMNS.contains(", text,"),
        )
    }
}
