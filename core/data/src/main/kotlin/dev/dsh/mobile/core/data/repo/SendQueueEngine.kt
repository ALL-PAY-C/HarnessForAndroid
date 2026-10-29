package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.common.DshResult
import java.util.concurrent.ConcurrentHashMap

/**
 * 同会话发送引擎：把「排队 / 中断让路 / 代际失效」的核心行为从 [SendQueue] 抽出来，
 * 依赖收窄成两个函数（发一条 / 打断），以便纯 JVM 确定性测试（不依赖 ChatRepository/Room）。
 *
 * M5 Steer 优先的根因修复时序：
 * 1. Steer（[cancelAndSend] / [sendNow]）在打断前先置「让路位」[interrupting]，再 cancel。
 *    cancel 触发 running 下降沿、唤醒 drain 时，drain 看到让路位立刻退让，不会先摘队抢锁。
 * 2. drain 取条目前、取得发送锁后各重检一次让路位；cancel 很慢（ACP 等 15s）也能兜住。
 * 3. 双 Steer 用 [controlGate] 串行化「置位 + cancel」，但 cancel 不碰 [sendGate]，
 *    所以不会被「正在整轮生成的 sendMessage」阻塞（那是死锁）。
 * 4. 代际校验：drain/sendNow 摘出的条目若代际已过期（期间 clear 过）就跳过不发送。
 */
internal class SendQueueEngine(
    private val sendItem: suspend (sessionId: String, item: SendQueue.Item) -> DshResult<Unit>,
    private val cancel: suspend (sessionId: String) -> DshResult<Unit>,
    private val onSendError: (sessionId: String, message: String) -> Unit = { _, _ -> },
    private val onChange: () -> Unit = {},
) {

    private val queues = ConcurrentHashMap<String, SessionQueueState>()
    private val draining = ConcurrentHashMap.newKeySet<String>()
    private val sendGate = SessionSendGate()
    private val controlGate = SessionSendGate()
    private val interrupting = ConcurrentHashMap<String, Int>()

    fun snapshot(): Map<String, List<SendQueue.Item>> =
        queues.mapValues { it.value.snapshot() }.filterValues { it.isNotEmpty() }

    fun of(sessionId: String): List<SendQueue.Item> = queues[sessionId]?.snapshot() ?: emptyList()

    fun enqueue(sessionId: String, text: String, images: List<String>, limit: Int): Boolean {
        val ok = queues.computeIfAbsent(sessionId) { SessionQueueState() }
            .enqueue(SendQueue.Item(text = text, images = images), limit)
        onChange()
        return ok
    }

    fun drop(sessionId: String, id: String) {
        queues[sessionId]?.drop(id)
        onChange()
    }

    fun promote(sessionId: String, id: String) {
        queues[sessionId]?.promote(id)
        onChange()
    }

    /** 清空会话队列（保留代际：已摘出未发的条目因代际失效被跳过）。 */
    fun clear(sessionId: String) {
        queues[sessionId]?.clear()
        onChange()
    }

    /** 删除会话队列条目（会话被删时用：整条目消失，代际不再存在）。 */
    fun remove(sessionId: String) {
        queues.remove(sessionId)
        onChange()
    }

    /** 直接发一条新消息（不打断）：结果交给调用方 error UI 展示。 */
    suspend fun sendText(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit> =
        sendGate.withLockOrNull(sessionId, GATE_WAIT_MS) { sendItem(sessionId, SendQueue.Item(text = text, images = images)) }
            ?: DshResult.Err("发送超时：上一轮回复仍在处理，请稍后重试")

    /** 打断当前这轮并立刻发一条新消息（Steer）。 */
    suspend fun cancelAndSend(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit> {
        val cancelResult = interrupt(sessionId)
        if (cancelResult is DshResult.Err) {
            // 打断失败（远端取消未确认）：不发这条 Steer 新 prompt，也不 drain（避免把排队项发到仍在跑的旧 remote）。
            // 队列保留，等 P5 barrier 确认旧轮真正终态后再由 running 下降沿恢复排空。
            endInterrupt(sessionId)
            return cancelResult
        }
        return try {
            sendGate.withLockOrNull(sessionId, GATE_WAIT_MS) {
                sendItem(sessionId, SendQueue.Item(text = text, images = images))
            } ?: DshResult.Err("发送超时：上一轮回复仍在处理，请稍后重试")
        } finally {
            endInterrupt(sessionId)
            drain(sessionId)
        }
    }

    /** 打断当前这轮并立刻发队列里某一条（其余仍留在队列里）。 */
    suspend fun sendNow(sessionId: String, id: String) {
        val cancelResult = interrupt(sessionId)
        if (cancelResult is DshResult.Err) {
            // 打断失败：不摘队列（保留 queued）、不 drain，Err 报给 onSendError。
            onSendError(sessionId, cancelResult.message)
            endInterrupt(sessionId)
            return
        }
        val taken = queues[sessionId]?.take(id)
        if (taken != null) onChange()
        if (taken == null) {
            endInterrupt(sessionId)
            drain(sessionId)
            return
        }
        val (item, gen) = taken
        try {
            val r = sendGate.withLockOrNull(sessionId, GATE_WAIT_MS) {
                if (!isCurrent(sessionId, gen)) DshResult.Ok(Unit) // 已作废，跳过
                else sendItem(sessionId, item)
            }
            if (r == null) onSendError(sessionId, "发送超时：上一轮回复仍在处理，请稍后重试")
            else if (r is DshResult.Err) onSendError(sessionId, r.message)
        } finally {
            endInterrupt(sessionId)
            drain(sessionId)
        }
    }

    /** 排空一个会话的队列。重入安全：同一会话同时只有一个排空循环。 */
    suspend fun drain(sessionId: String) {
        if (!draining.add(sessionId)) return
        try {
            while (true) {
                // 快路径：Steer 已置让路位，立即退让（cancel 很慢时也要先让）。
                if (isInterrupting(sessionId)) break
                val step: DrainStep = sendGate.withLock(sessionId) {
                    // 拿到锁后再重检：Steer 可能在等锁（cancel 已置位）。
                    if (isInterrupting(sessionId)) return@withLock DrainStep.Yield
                    val popped = queues[sessionId]?.pop() ?: return@withLock DrainStep.QueueEmpty
                    onChange() // 摘出后立刻让快照反映：item 从「排队」变「发送中」
                    val (item, gen) = popped
                    if (!isCurrent(sessionId, gen)) return@withLock DrainStep.Skipped
                    val r = sendItem(sessionId, item)
                    if (r is DshResult.Err) onSendError(sessionId, r.message)
                    DrainStep.Sent
                }
                when (step) {
                    DrainStep.Yield, DrainStep.QueueEmpty -> break
                    DrainStep.Skipped, DrainStep.Sent -> {}
                }
            }
        } finally {
            draining.remove(sessionId)
        }
    }

    /** 串行化「置让路位 + 打断」；cancel 在 [sendGate] 外，不与正持锁的 sendMessage 死锁。 */
    private suspend fun interrupt(sessionId: String): DshResult<Unit> =
        controlGate.withLock(sessionId) {
            interrupting.merge(sessionId, 1) { a, b -> a + b }
            try {
                cancel(sessionId)
            } catch (t: Throwable) {
                endInterrupt(sessionId)
                throw t
            }
        }

    private fun endInterrupt(sessionId: String) {
        interrupting.compute(sessionId) { _, v -> if (v == null || v <= 1) null else v - 1 }
    }

    private fun isInterrupting(sessionId: String): Boolean = interrupting.containsKey(sessionId)

    private fun isCurrent(sessionId: String, gen: Long): Boolean = queues[sessionId]?.isCurrent(gen) ?: false

    private enum class DrainStep { Yield, QueueEmpty, Skipped, Sent }

    private companion object {
        /** N-2：有界发送锁超时，略大于 cancel 的 15s 屏障，保留「等一轮结束」语义但不永久挂死。 */
        private const val GATE_WAIT_MS = 20_000L
    }
}
