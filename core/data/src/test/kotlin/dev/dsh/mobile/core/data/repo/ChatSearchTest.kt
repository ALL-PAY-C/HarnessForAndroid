package dev.dsh.mobile.core.data.repo

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.EndpointDao
import dev.dsh.mobile.core.data.db.EndpointEntity
import dev.dsh.mobile.core.data.db.MessageDao
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.MessageSearchRow
import dev.dsh.mobile.core.data.db.SessionDao
import dev.dsh.mobile.core.data.db.SessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 抽屉搜索的数据层：正文命中、标题命中、通配符不误匹配。
 *
 * 不依赖 Room / Robolectric，做法与 [ChatRepositoryIntegrationTest] 一致（子类化 [DshDatabase]
 * 换成内存 DAO）。fake 的 search 按 SQLite LIKE 的语义实现（% 任意串、_ 单字符、
 * ESCAPE '\' 还原字面量），所以「仓库转义对不对」在这里是被真正验证的：
 * 仓库漏转一个 %，第 3 个用例就会把无关消息也匹配进来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchTest {

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
        override suspend fun resetRunning(): Int = 0

        /** 与 Db.kt 里那条 @Query 同语义：title 或 preview LIKE，pinned 优先、updatedAt 倒序。 */
        override suspend fun search(q: String, limit: Int): List<SessionEntity> =
            map.values.filter { likeMatch(it.title, q) || likeMatch(it.preview, q) }
                .sortedWith(compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt })
                .take(limit)

        private fun publish() { state.value = map.values.toList() }
    }

    private class InMemoryMessageDao : MessageDao {
        private val map = ConcurrentHashMap<String, MessageEntity>()
        private val state = MutableStateFlow<List<MessageEntity>>(emptyList())

        /** 被真实查询过几次：空 query 短路用例靠它证明「没有打库」。 */
        var searchCalls = 0

        override fun observeBySession(sessionId: String): Flow<List<MessageEntity>> =
            state.map { list -> list.filter { it.sessionId == sessionId } }

        override suspend fun bySession(sessionId: String): List<MessageEntity> =
            map.values.filter { it.sessionId == sessionId }.sortedBy { it.at }

        override suspend fun textById(id: String): String? = map[id]?.text

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

        override suspend fun count(): Int = map.size

        /** 与 Db.kt 里那条 @Query 同语义：正文 LIKE + LEFT JOIN 会话标题 + substr(text,1,120)。 */
        override suspend fun search(q: String, limit: Int): List<MessageSearchRow> {
            searchCalls++
            return map.values.filter { likeMatch(it.text, q) }
                .sortedByDescending { it.at }
                .take(limit)
                .map {
                    MessageSearchRow(
                        id = it.id,
                        sessionId = it.sessionId,
                        sessionTitle = sessions.byId(it.sessionId)?.title ?: "",
                        role = it.role,
                        snippet = it.text.take(120),
                        at = it.at,
                    )
                }
        }

        /** 交叉引用会话表：SQL 侧是 LEFT JOIN，这里直接查同一份内存 session 表。 */
        lateinit var sessions: InMemorySessionDao

        private fun publish() { state.value = map.values.sortedBy { it.at } }
    }

    private class InMemoryEndpointDao : EndpointDao {
        private val map = ConcurrentHashMap<String, EndpointEntity>()
        override fun observeAll(): Flow<List<EndpointEntity>> = MutableStateFlow(emptyList())
        override suspend fun all(): List<EndpointEntity> = map.values.toList()
        override suspend fun upsert(endpoint: EndpointEntity) { map[endpoint.id] = endpoint }
        override suspend fun delete(id: String) { map.remove(id) }
        override suspend fun clearToken(id: String) { map[id]?.let { map[id] = it.copy(token = "") } }
    }

    private class FakeDshDatabase : DshDatabase() {
        val sessions = InMemorySessionDao()
        val messages = InMemoryMessageDao().also { it.sessions = sessions }
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

    // ---------------------------------------------------------------- tests

    /** 用例 1：正文命中，标题随 JOIN 带出，片段 / 角色 / 时间都在。 */
    @Test
    fun searchMessages_bodyHitCarriesTitleAndSnippet() = runTest {
        val db = FakeDshDatabase()
        db.sessions.upsert(SessionEntity(id = "s1", title = "季度预算评审", updatedAt = 1_000L))
        db.sessions.upsert(SessionEntity(id = "s2", title = "随手闲聊", updatedAt = 2_000L))
        db.messages.upsert(MessageEntity(id = "m1", sessionId = "s1", role = "USER", text = "今天的会议纪要在附件里", at = 900L))
        db.messages.upsert(MessageEntity(id = "m2", sessionId = "s2", role = "ASSISTANT", text = "好的，我看下", at = 1_900L))
        val repo = ChatRepository(db, backgroundScope)

        val hits = repo.searchMessages("会议纪要")

        assertEquals(1, hits.size)
        assertEquals("s1", hits[0].sessionId)
        assertEquals("季度预算评审", hits[0].sessionTitle)
        assertEquals("USER", hits[0].role)
        assertEquals("今天的会议纪要在附件里", hits[0].snippet)
        assertEquals(900L, hits[0].at)
    }

    /** 用例 2：标题命中（pinned 优先），正文里没有这个词时也照样命中会话。 */
    @Test
    fun searchSessions_titleHitPinnedFirst() = runTest {
        val db = FakeDshDatabase()
        db.sessions.upsert(SessionEntity(id = "old", title = "季度预算评审", updatedAt = 5_000L))
        db.sessions.upsert(SessionEntity(id = "pinned", title = "预算 · 常驻", updatedAt = 1_000L, pinned = 1))
        db.sessions.upsert(SessionEntity(id = "other", title = "随手闲聊", updatedAt = 9_000L))
        val repo = ChatRepository(db, backgroundScope)

        val rows = repo.searchSessions("预算")

        assertEquals(listOf("pinned", "old"), rows.map { it.id })
    }

    /** 用例 3：% 与 _ 是字面字符。不转义时 % 会当通配符，把「100 元」也匹配进来。 */
    @Test
    fun wildcardsAreLiteral() = runTest {
        val db = FakeDshDatabase()
        db.sessions.upsert(SessionEntity(id = "s1", title = "折扣"))
        db.messages.upsert(MessageEntity(id = "m1", sessionId = "s1", role = "USER", text = "折扣 100% 到手价", at = 1L))
        db.messages.upsert(MessageEntity(id = "m2", sessionId = "s1", role = "USER", text = "折扣 100 元到手价", at = 2L))
        db.messages.upsert(MessageEntity(id = "m3", sessionId = "s1", role = "USER", text = "abc 三个字母", at = 3L))
        val repo = ChatRepository(db, backgroundScope)

        val percent = repo.searchMessages("100%")
        assertEquals(1, percent.size)
        assertEquals("折扣 100% 到手价", percent[0].snippet)

        // _ 只匹配一个字符：查 "a_c" 不该命中 "abc"。
        assertEquals(emptyList<ChatSearchHit>(), repo.searchMessages("a_c"))
        // 反斜杠本身也是字面字符：查 "\" 不该命中任何一条（库里没有反斜杠）。
        assertEquals(emptyList<ChatSearchHit>(), repo.searchMessages("\\"))
    }

    /** 用例 4：空 / 纯空白 query 直接返回空，不打库（抽屉一打开不该扫全表）。 */
    @Test
    fun blankQuerySkipsDatabase() = runTest {
        val db = FakeDshDatabase()
        db.sessions.upsert(SessionEntity(id = "s1", title = "季度预算评审"))
        val repo = ChatRepository(db, backgroundScope)

        assertEquals(emptyList<ChatSearchHit>(), repo.searchMessages("   "))
        assertEquals(emptyList<SessionEntity>(), repo.searchSessions(""))
        assertEquals(0, db.messages.searchCalls)
    }
}

/**
 * SQLite LIKE 语义：% 任意串、_ 单字符、\ 转义（ESCAPE '\'），ASCII 大小写不敏感。
 * 仓库把用户输入转义成「模式」后传给 DAO，这里按模式匹配——转义漏了就匹配过多。
 */
private fun likeMatch(value: String, pattern: String): Boolean {
    val regex = StringBuilder(".*")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> {
                regex.append(Regex.escape(pattern[i + 1].toString()))
                i += 2
            }
            c == '%' -> { regex.append(".*"); i++ }
            c == '_' -> { regex.append('.'); i++ }
            else -> { regex.append(Regex.escape(c.toString())); i++ }
        }
    }
    regex.append(".*")
    return Regex(regex.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .matches(value)
}
