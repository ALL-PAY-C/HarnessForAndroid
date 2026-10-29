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
import dev.dsh.mobile.core.data.db.SessionDao
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.model.TransportKind
import dev.dsh.mobile.core.protocol.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 真实 [ChatRepository]（fakeDb + 内存 DAO）+ fake [DshTransport] 的集成行为回归。
 *
 * 不依赖 Room / Robolectric：直接子类化 [DshDatabase]，把三个 DAO 换成内存实现；
 * RoomDatabase 的三个抽象成员（open helper / invalidation tracker / clearAllTables）
 * 在 ChatRepository 的公开行为路径上从不被调用，fixture 里直接抛错即可。
 *
 * 已知纯 JVM 风险（如实记录，不加依赖）：
 *  [ChatRepository.sendMessage] 内部用 `org.json.JSONArray()` 组附件 JSON。android.jar 在
 *  本地单测里对 org.json 只有桩实现（本仓库 plugin-host / runtime-host / feature-plugins
 *  都在 build.gradle.kts 里用 `testImplementation("org.json:json:20240303")` 换真实现）。
 *  因此下面走 sendMessage 的用例在纯 JVM 上会撞 org.json 桩；createSession 路径不含 org.json，
 *  可独立跑通。是否给 core:data 补 org.json 测试依赖，由 1391 的 runner 决定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryIntegrationTest {

    // ---------------------------------------------------------------- fixtures

    private class InMemorySessionDao : SessionDao {
        private val map = ConcurrentHashMap<String, SessionEntity>()
        private val state = MutableStateFlow<List<SessionEntity>>(emptyList())

        override fun observeAll(): Flow<List<SessionEntity>> = state
        override suspend fun byId(id: String): SessionEntity? = map[id]
        override suspend fun all(): List<SessionEntity> = snapshot()

        /** 下一次 upsert 直接抛错：模拟 DB 抖动，验证 collector 不会因此整条死掉。 */
        var throwNextUpsert = false

        override suspend fun upsert(session: SessionEntity) {
            if (throwNextUpsert) {
                throwNextUpsert = false
                throw IllegalStateException("db hiccup (fixture)")
            }
            map[session.id] = session
            publish()
        }

        override suspend fun upsertAll(sessions: List<SessionEntity>) {
            sessions.forEach { map[it.id] = it }
            publish()
        }

        override suspend fun setPinned(id: String, pinned: Int) {
            map[id]?.let { map[id] = it.copy(pinned = pinned) }
            publish()
        }

        override suspend fun setArchived(id: String, archived: Int) {
            map[id]?.let { map[id] = it.copy(archived = archived) }
            publish()
        }

        override suspend fun setGroup(id: String, grp: String) {
            map[id]?.let { map[id] = it.copy(grp = grp) }
            publish()
        }

        override suspend fun setCwd(id: String, cwd: String) {
            map[id]?.let { map[id] = it.copy(cwd = cwd) }
            publish()
        }

        override suspend fun setTitle(id: String, title: String) {
            map[id]?.let { map[id] = it.copy(title = title) }
            publish()
        }

        override suspend fun setAgent(id: String, agentId: String?) {
            map[id]?.let { map[id] = it.copy(agentId = agentId) }
            publish()
        }

        override suspend fun setParticipants(id: String, json: String) {
            map[id]?.let { map[id] = it.copy(participantsJson = json) }
            publish()
        }

        override suspend fun delete(id: String) {
            map.remove(id)
            publish()
        }

        override suspend fun count(): Int = map.size

        // 抽屉搜索不在本 fixture 的断言范围内：Db.kt 新增的 DAO 方法必须实现，空结果即可。
        override suspend fun search(q: String, limit: Int): List<SessionEntity> = emptyList()

        override suspend fun resetRunning(): Int {
            val touched = map.filterValues { it.status == "Running" || it.status == "WaitingPermission" }
            touched.forEach { (k, v) -> map[k] = v.copy(status = "Idle") }
            if (touched.isNotEmpty()) publish()
            return touched.size
        }

        private fun snapshot(): List<SessionEntity> =
            map.values.sortedWith(compareByDescending<SessionEntity> { it.pinned }.thenByDescending { it.updatedAt })

        private fun publish() {
            state.value = snapshot()
        }
    }

    private class InMemoryMessageDao : MessageDao {
        private val map = ConcurrentHashMap<String, MessageEntity>()
        private val state = MutableStateFlow<List<MessageEntity>>(emptyList())

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

        override suspend fun upsert(message: MessageEntity) {
            map[message.id] = message
            publish()
        }

        override suspend fun upsertAll(messages: List<MessageEntity>) {
            messages.forEach { map[it.id] = it }
            publish()
        }

        override suspend fun deleteBySession(sessionId: String) {
            val ids = map.filterValues { it.sessionId == sessionId }.keys
            ids.forEach { map.remove(it) }
            publish()
        }

        override suspend fun search(q: String, limit: Int): List<MessageSearchRow> = emptyList()

        override suspend fun count(): Int = map.size

        private fun publish() {
            state.value = map.values.sortedBy { it.at }
        }
    }

    private class InMemoryEndpointDao : EndpointDao {
        private val map = ConcurrentHashMap<String, EndpointEntity>()
        private val state = MutableStateFlow<List<EndpointEntity>>(emptyList())

        override fun observeAll(): Flow<List<EndpointEntity>> = state
        override suspend fun all(): List<EndpointEntity> = map.values.sortedByDescending { it.lastUsedAt }
        override suspend fun upsert(endpoint: EndpointEntity) {
            map[endpoint.id] = endpoint
            publish()
        }

        override suspend fun delete(id: String) {
            map.remove(id)
            publish()
        }

        override suspend fun clearToken(id: String) {
            map[id]?.let { map[id] = it.copy(token = "") }
            publish()
        }

        private fun publish() {
            state.value = map.values.sortedByDescending { it.lastUsedAt }
        }
    }

    private class FakeDshDatabase : DshDatabase() {
        val sessions = InMemorySessionDao()
        val messages = InMemoryMessageDao()
        val endpoints = InMemoryEndpointDao()

        override fun sessionDao(): SessionDao = sessions
        override fun messageDao(): MessageDao = messages
        override fun endpointDao(): EndpointDao = endpoints

        // RoomDatabase 抽象成员：ChatRepository 只走 DAO，不碰 Room 内部。
        // createInvalidationTracker 不是「从不调用」——RoomDatabase 构造函数（RoomDatabase.kt:108）
        // 在 <init> 里就会调用它，所以必须返回真实实例；空表名即可，本 fixture 不订阅任何表。
        override fun clearAllTables() = Unit
        override fun createOpenHelper(configuration: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("in-memory fixture: no Room open helper")

        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap())
    }

    private class FakeDshTransport(
        override val events: SharedFlow<DshEvent>,
    ) : DshTransport {
        override val state: StateFlow<RuntimeState> = MutableStateFlow(RuntimeState.Ready(TransportKind.InProcess))

        var promptBody: suspend (sessionId: String, text: String, images: List<String>, turnId: String?) -> String =
            { _, _, _, _ -> "ok" }
        var lastPromptText: String? = null

        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject = JsonObject(emptyMap())
        override suspend fun listSessions(cwd: String?): List<Session> = emptyList()
        override suspend fun newSession(cwd: String): Session =
            Session(id = "remote-" + UUID.randomUUID().toString(), title = "新会话", cwd = cwd)

        override suspend fun resumeSession(sessionId: String): Session = Session(id = sessionId, title = "", cwd = "")
        override suspend fun closeSession(sessionId: String) = Unit

        override suspend fun prompt(sessionId: String, text: String, images: List<String>, turnId: String?): String {
            lastPromptText = text
            return promptBody(sessionId, text, images, turnId)
        }

        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) = Unit
        override suspend fun replyPermission(sessionId: String, requestId: String, optionId: String?) = Unit
    }

    // ---------------------------------------------------------------- tests

    private fun <T> DshResult<T>.orThrow(): T = when (this) {
        is DshResult.Ok -> value
        is DshResult.Err -> throw AssertionError(message, cause)
    }

    /**
     * 夹具自证：createSession 路径不含 org.json，纯 JVM 可跑通，验证 FakeDshDatabase
     * （DshDatabase 子类 + 内存 DAO + RoomDatabase 抽象成员抛错）在 JVM 上可构造、可落库。
     */
    @Test
    fun createSession_persistsLocalIdleSession() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)

        val session = repo.createSession("/cwd").orThrow()

        val row = db.sessions.byId(session.id)
        assertEquals(SessionStatus.Idle.name, row?.status)
        assertEquals("/cwd", row?.cwd)
        assertEquals("新会话", row?.title)
    }

    /**
     * 稳定行为（第一步）：真实 [ChatRepository] 走 createSession → attach → sendMessage，
     * fake prompt 发一条带 turnId 的 MessageChunk（经 collector 的 handleEvent 落 ledger），
     * 成功后库里应有 USER + ASSISTANT 各一条，会话回到 Idle。
     */
    @Test
    fun createSessionThenSendMessage_persistsUserAndAssistantAndIdle() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        // collector 挂 backgroundScope：不阻塞 runTest 结束，也不触发 UncompletedCoroutinesError。
        val repo = ChatRepository(db, backgroundScope)

        // 先建会话（未 attach，走纯本地建号），拿到本地 id。
        val session = repo.createSession("/cwd").orThrow()

        // 挂上 fake transport：attach 启动 collector；本地模式 isRemote=false。
        repo.attach(transport)

        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "助手回复", turnId = turnId))
            "ok"
        }

        repo.sendMessage(session.id, "你好").orThrow()

        val messages = db.messages.bySession(session.id)
        assertEquals(2, messages.size)
        assertEquals(setOf("USER", "ASSISTANT"), messages.map { it.role }.toSet())
        assertEquals("你好", messages.first { it.role == "USER" }.text)
        assertEquals("助手回复", messages.first { it.role == "ASSISTANT" }.text)
        assertEquals(SessionStatus.Idle.name, db.sessions.byId(session.id)?.status)
    }

    /**
     * 连续两次本地 send 的公开行为回归：LocalEngine 一轮流式结束会发合成 TurnEnd marker（带 turnId），
     * collector 消费后解封 N-1 终态屏障（markerConsumed && promptFinished），第二次 send 不应被
     * 「上一轮回复仍在收尾」挡住。fake prompt 按 LocalEngineTransport 的真实时序发 chunk + TurnEnd。
     */
    @Test
    fun sendTwice_persistsTwoTurnsAndStaysIdle() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)

        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        var n = 0
        transport.promptBody = { sid, _, _, turnId ->
            n += 1
            val body = "回复$n"
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = body, turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }

        repo.sendMessage(session.id, "你好1").orThrow()
        repo.sendMessage(session.id, "你好2").orThrow()

        val messages = db.messages.bySession(session.id)
        assertEquals(4, messages.size)
        assertEquals(setOf("你好1", "你好2"), messages.filter { it.role == "USER" }.map { it.text }.toSet())
        assertEquals(setOf("回复1", "回复2"), messages.filter { it.role == "ASSISTANT" }.map { it.text }.toSet())
        assertEquals(SessionStatus.Idle.name, db.sessions.byId(session.id)?.status)
    }

    /**
     * T9：调用方（页面 / VM）被取消，不得取消本轮任务。
     * 真实场景是 ChatViewModel 挂在 NavBackStackEntry 上，pop 即 viewModelScope 取消；
     * 这里用一个「调用方协程」模拟：它被 cancel 之后，仓库作用域里的 prompt job 仍要跑完，
     * 并由它自己把完整正文落库、把会话收回 Idle —— 而不是把半截正文定格成完整回答。
     */
    @Test
    fun callerCancellation_doesNotAbortTurnAndPersistsFullText() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        // 应用级作用域（AppContainer 的 AppScope）在这里由 backgroundScope 代替：
        // 它不属于任何「调用方」，所以调用方被取消不会连带取消这一轮。
        val repo = ChatRepository(db, backgroundScope)

        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val promptStarted = CompletableDeferred<Unit>()
        val releasePrompt = CompletableDeferred<Unit>()
        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "第一段", turnId = turnId))
            promptStarted.complete(Unit)
            // 挂住：慢 LLM 还在推流，此时调用方会被取消。
            releasePrompt.await()
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "第二段", turnId = turnId))
            "ok"
        }

        val caller = launch { repo.sendMessage(session.id, "你好") }
        promptStarted.await()
        caller.cancelAndJoin()

        // 调用方已消失，但这一轮还在跑：没有半截正文落库，会话仍是 Running。
        assertEquals(0, db.messages.bySession(session.id).count { it.role == "ASSISTANT" })
        assertEquals(SessionStatus.Running.name, db.sessions.byId(session.id)?.status)

        // 慢 LLM 收尾：本轮由仓库作用域收口，正文必须完整。
        releasePrompt.complete(Unit)
        advanceUntilIdle()

        val messages = db.messages.bySession(session.id)
        assertEquals(2, messages.size)
        assertEquals("第一段第二段", messages.first { it.role == "ASSISTANT" }.text)
        assertEquals(SessionStatus.Idle.name, db.sessions.byId(session.id)?.status)
    }

    /**
     * T9 窄竞态（确定性复现）：prompt 已经返回、本轮 job 已经完成，但调用方的 await 还没恢复就被取消。
     * 两个 dispatcher 把顺序钉死：
     * - 仓库作用域用 Unconfined → job 一完成，invokeOnCompletion 同步跑，读到 detached=false 跳过收口；
     * - 调用方用 StandardTestDispatcher → await 的恢复进队列，job 完成与恢复之间留出可插入取消的窗口。
     * 修复前：catch 分支只 detached.set(true) 就重抛，完整正文永远不定稿（无 ASSISTANT 行、会话卡 Running）。
     */
    @Test
    fun callerCancelAfterJobCompleted_stillFinalizesFullTurn() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        // 应用级作用域（AppScope 的替身）：Unconfined，job 完成即同步收尾。
        val repo = ChatRepository(db, CoroutineScope(Dispatchers.Unconfined + Job()))

        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val promptStarted = CompletableDeferred<Unit>()
        val releasePrompt = CompletableDeferred<Unit>()
        transport.promptBody = { sid, _, _, turnId ->
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "第一段", turnId = turnId))
            promptStarted.complete(Unit)
            releasePrompt.await()
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "第二段", turnId = turnId))
            "ok"
        }

        val caller = launch(StandardTestDispatcher(testScheduler)) { repo.sendMessage(session.id, "你好") }
        advanceUntilIdle()
        promptStarted.await()

        // 竞态时刻：prompt 返回 → job 完成（detached 仍是 false，兜底被跳过）；
        // 调用方的 await 恢复还在队列里，此时取消调用方。
        releasePrompt.complete(Unit)
        caller.cancel()
        assertEquals(0, db.messages.bySession(session.id).count { it.role == "ASSISTANT" })
        assertEquals(SessionStatus.Running.name, db.sessions.byId(session.id)?.status)

        advanceUntilIdle()

        val messages = db.messages.bySession(session.id)
        assertEquals(1, messages.count { it.role == "ASSISTANT" })
        val assistant = messages.first { it.role == "ASSISTANT" }
        assertEquals("第一段第二段", assistant.text)
        assertEquals(0, assistant.streaming)
        assertEquals(SessionStatus.Idle.name, db.sessions.byId(session.id)?.status)
    }

    /**
     * 需求 2：走过 subagent 的轮次，最终正文必须和普通轮一样落一条 ASSISTANT 行。
     *
     * fake prompt 按线上真实时序发事件：子代理进度（InProgress）→ 结算（Completed）→ 最终正文 → TurnEnd。
     * 以前这类轮次在库里只剩一行 USER：助手气泡与它的正文一起消失。
     */
    @Test
    fun subagentTurn_persistsAssistantMessage() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>()
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)

        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        val finalText = "子代理已经返回了：PONG。汇总完毕。"
        transport.promptBody = { sid, _, _, turnId ->
            val call = ToolCall(
                id = "call_subagent",
                title = "子代理 · 查PONG",
                kind = "subagent",
                status = ToolCall.ToolStatus.InProgress,
                args = """{"prompt":"只回 PONG","name":"查PONG"}""",
            )
            events.emit(DshEvent.ToolCallEvent(sessionId = sid, call = call, turnId = turnId))
            events.emit(
                DshEvent.ToolCallEvent(
                    sessionId = sid,
                    call = call.copy(status = ToolCall.ToolStatus.Completed, detail = "PONG"),
                    turnId = turnId,
                ),
            )
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = finalText, turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }

        repo.sendMessage(session.id, "把它交给子代理").orThrow()

        val messages = db.messages.bySession(session.id)
        assertEquals(2, messages.size)
        assertEquals(setOf("USER", "ASSISTANT"), messages.map { it.role }.toSet())
        assertEquals(finalText, messages.first { it.role == "ASSISTANT" }.text)
        assertEquals(SessionStatus.Idle.name, db.sessions.byId(session.id)?.status)
    }

    /**
     * 回归：单条事件抛错不能带走整个事件消费链。
     *
     * 以前 attach 的 collector 里 handleEvent 没有隔离，一次 DB 异常就让 collector 退出 ——
     * 这一轮之后的 chunk/TurnEnd 全部丢失，界面上只留 USER 行（正是 subagent 轮的现场）。
     */
    @Test
    fun usageEventFailureDoesNotBlindTheCollector() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<DshEvent>(extraBufferCapacity = 64)
        val transport = FakeDshTransport(events)
        val db = FakeDshDatabase()
        val repo = ChatRepository(db, backgroundScope)

        val session = repo.createSession("/cwd").orThrow()
        repo.attach(transport)

        transport.promptBody = { sid, _, _, turnId ->
            // 先让一次 DB 写在事件链里炸掉（Usage 的落库），再看后面的正文还能不能进来。
            db.sessions.throwNextUpsert = true
            events.emit(DshEvent.Usage(sessionId = sid, usage = dev.dsh.mobile.core.model.TokenUsage(input = 1, output = 1), turnId = turnId))
            events.emit(DshEvent.MessageChunk(sessionId = sid, text = "抖动之后的正文", turnId = turnId))
            events.emit(DshEvent.TurnEnd(sessionId = sid, reason = "stop", turnId = turnId))
            "ok"
        }

        repo.sendMessage(session.id, "你好").orThrow()

        val messages = db.messages.bySession(session.id)
        assertEquals(1, messages.count { it.role == "ASSISTANT" })
        assertEquals("抖动之后的正文", messages.first { it.role == "ASSISTANT" }.text)
    }

    // 下一步在此文件继续补：
    // - cancel partial preserved（finalizeTurn keepPartial=true → PARTIAL_MARK 落库）；
    // - caller cancel 不遗留；ensure 远端补建失败（prompt 未发送）应仍可重试（需子类化 AcpTransport）。
    // 复用上面的 FakeDshDatabase / FakeDshTransport 与 in-memory DAO。
}