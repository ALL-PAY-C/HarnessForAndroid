package dev.dsh.mobile.core.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 同会话发送串行闸门：按 sessionId 给一把协程互斥锁。
 *
 * 三条发送路都从这里过——ChatViewModel 的直接发 / SendQueue 的排空与「立刻发」/
 * 通知栏的回复——保证同一会话同一时刻只有一条 [ChatRepository.sendMessage]（或
 * cancel→send）在飞，否则 [dev.dsh.mobile.core.data.ledger.TurnLedger.seed] 会无条件
 * 覆盖当前轮，两条并发发送互相 clobber 轮次。
 *
 * 注意：打断必须放在 [withLock] 外面调用。sendMessage 会阻塞到整轮生成结束，若把
 * [ChatRepository.cancel] 也放进锁里，会与「正持锁阻塞的那条 sendMessage」死锁。
 */
internal class SessionSendGate {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T =
        locks.computeIfAbsent(key) { Mutex() }.withLock { block() }

    /**
     * N-2/m00619：有界发送锁 —— 只给「等锁」加时限，拿到锁后 block 在超时外执行。
     * 前一轮 prompt 因 cancel 15s 超时/ACK 超时仍持有 sendGate 时，后续 send 在 timeoutMs
     * 内拿不到锁就返回 null（调用方转 Err），不永久转圈；但一旦拿到锁，整轮 30min prompt
     * 绝不被 timeoutMs 截断。
     */
    suspend fun <T> withLockOrNull(key: String, timeoutMs: Long, block: suspend () -> T): T? {
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        // 仅限 mutex.lock() 的等待时长；withTimeoutOrNull 超时取消 lock() 不会拿到锁（Mutex.lock 可取消、无泄漏）。
        val acquired = withTimeoutOrNull(timeoutMs) { mutex.lock() }
        if (acquired == null) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
