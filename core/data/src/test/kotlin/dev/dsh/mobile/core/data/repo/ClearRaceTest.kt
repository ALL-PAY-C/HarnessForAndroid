package dev.dsh.mobile.core.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N2：clear/delete 与 in-flight 结算的竞态。
 *
 * 用真实的 [TurnLedger.close] / [TurnLedger.isClosed]（封口代际）+ Mutex（sessionCritical 用的锁），
 * 配一个可控暂停的 fake 持久化，锁死两种交错都「clear 后空」。
 * 关键不在时间戳，而在「锁 + 代际」：迟到写回要么被锁挡住、要么被 isClosed 丢弃。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClearRaceTest {

    /** 极简 fake 持久化：按会话存文本，clearSession 只清本会话。 */
    private class FakeStore {
        val rows = mutableListOf<String>()
        fun upsert(sessionId: String, text: String) { rows.add("$sessionId:$text") }
        fun clearSession(sessionId: String) { rows.removeAll { it.startsWith("$sessionId:") } }
        fun forSession(sessionId: String) = rows.filter { it.startsWith("$sessionId:") }
    }

    /** 交错 A：结算先拿到锁并落库，clear 等锁；最终 clear 仍把库清空。 */
    @Test
    fun persistFirstClearSecond_endsEmpty() = runTest {
        val ledger = TurnLedger()
        val lock = Mutex()
        val store = FakeStore()
        val gate = CompletableDeferred<Unit>()

        suspend fun persist(sessionId: String, text: String) {
            lock.withLock {
                if (ledger.isClosed(sessionId)) return
                gate.await() // 可控暂停：卡在临界段里，模拟 countTurn/upsert 的挂起
                store.upsert(sessionId, text)
            }
        }
        suspend fun clear(sessionId: String) {
            lock.withLock {
                ledger.close(sessionId)
                store.clearSession(sessionId)
            }
        }

        launch { persist("s", "半截") }
        runCurrent() // persist 拿到锁，停在 gate
        launch { clear("s") }
        runCurrent() // clear 等锁
        gate.complete(Unit)
        advanceUntilIdle() // persist 落库→释放锁；clear 封口→清库
        assertEquals(emptyList<String>(), store.forSession("s"))
    }

    /** 交错 B：clear 先封口清库，之后才到的结算看到 isClosed 直接弃写。 */
    @Test
    fun clearFirstPersistSecond_isSkipped() = runTest {
        val ledger = TurnLedger()
        val lock = Mutex()
        val store = FakeStore()

        suspend fun persist(sessionId: String, text: String) {
            lock.withLock {
                if (ledger.isClosed(sessionId)) return
                store.upsert(sessionId, text)
            }
        }
        suspend fun clear(sessionId: String) {
            lock.withLock {
                ledger.close(sessionId)
                store.clearSession(sessionId)
            }
        }

        clear("s")
        persist("s", "迟到写回")
        assertEquals(emptyList<String>(), store.forSession("s"))
    }
}
