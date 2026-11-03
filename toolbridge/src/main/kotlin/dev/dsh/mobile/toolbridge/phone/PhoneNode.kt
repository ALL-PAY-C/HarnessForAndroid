package dev.dsh.mobile.toolbridge.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 屏幕矩形，坐标系与 AccessibilityNodeInfo.getBoundsInScreen 一致（全屏绝对像素）。 */
data class PhoneBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * 一个不可变界面节点。
 *
 * nodeId 里必须带快照代次：树变了以后同一个序号会指向别的节点，跨代使用只会静默点错地方 —
 * 所以身份是「<snapshotId>:<seq>」，代次不符由调用方回 E_STALE_SNAPSHOT。
 *
 * 用扁平 parentIndex 而不是 children 列表：截断（maxNodes/maxDepth）时不必回填父节点的子表，
 * 序列化也不用递归。
 */
data class PhoneNode(
    val snapshotId: Long,
    val seq: Int,
    /** 同一张节点表里的下标；-1 表示根节点。父节点被过滤掉时指向最近的保留祖先。 */
    val parentIndex: Int,
    val depth: Int,
    val className: String,
    val packageName: String,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val bounds: PhoneBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val visible: Boolean,
) {
    val nodeId: String get() = snapshotId.toString() + ":" + seq
    val parentId: String? get() = if (parentIndex < 0) null else snapshotId.toString() + ":" + parentIndex
}

/** 一次读屏的产物：代次 + 扁平节点表 + 截断原因。 */
data class PhoneSnapshot(
    val snapshotId: Long,
    val nodes: List<PhoneNode>,
    val truncated: Boolean = false,
    val truncatedReason: String? = null,
) {
    /** 跨代快照的 nodeId 必须判不中：否则 T4.2 的 E_STALE_SNAPSHOT 会静默命中同序号的旧节点。 */
    fun byId(nodeId: String): PhoneNode? {
        val prefix = nodeId.substringBeforeLast(':', missingDelimiterValue = "")
        if (prefix.toLongOrNull() != snapshotId) return null
        val seq = nodeId.substringAfterLast(':').toIntOrNull() ?: return null
        return nodes.firstOrNull { it.seq == seq }
    }
}

/**
 * 树代次（generation）与「等界面变化」的唯一真源。
 *
 * 为什么需要它：无障碍配置里声明了 typeWindowContentChanged|typeWindowsChanged，但事件回调只认
 * state-changed 并把其余丢弃，于是「界面变了没有」只能反复轮询整棵树。代次单调自增后，
 * 读侧能判断快照是否过期，等待方能挂起而不是空转。
 *
 * 纯 Kotlin（不碰 Android 类型），所以能在 JVM 单测里直接验证。
 */
class PhoneTreeGenerations {

    private class Waiter(val since: Long, val deferred: CompletableDeferred<Long>)

    private val lock = Any()
    private var generation = 0L
    private var lastFingerprint: Int? = null
    private val waiters = ArrayList<Waiter>()

    fun current(): Long = synchronized(lock) { generation }

    /**
     * 指纹没变就不换代：内容变化事件会以毫秒级频率重放同一棵树，
     * 每次都自增会让 awaitChange 立刻返回，「等界面变化」的语义就废了。
     * 返回 null 表示「这次事件没有产生新代次」。
     */
    fun commit(fingerprint: Int): Long? = synchronized(lock) {
        if (lastFingerprint == fingerprint) return null
        lastFingerprint = fingerprint
        bumpLocked()
    }

    fun bump(): Long = synchronized(lock) { bumpLocked() }

    /**
     * 等到代次越过 [sinceGeneration] 就返回新代次；超时返回 null。
     * 超时不是错误 —— 调用方要区分「没变化」与「等不到」，所以不抛异常。
     */
    suspend fun awaitChange(sinceGeneration: Long, timeoutMs: Long): Long? {
        val ticket: CompletableDeferred<Long>
        synchronized(lock) {
            if (generation > sinceGeneration) return generation
            ticket = CompletableDeferred()
            waiters.add(Waiter(sinceGeneration, ticket))
        }
        return try {
            withTimeoutOrNull(if (timeoutMs <= 0L) 1L else timeoutMs) { ticket.await() }
        } finally {
            synchronized(lock) { waiters.removeAll { it.deferred === ticket } }
        }
    }

    private fun bumpLocked(): Long {
        generation += 1
        val g = generation
        val pending = ArrayList(waiters)
        waiters.clear()
        for (w in pending) if (g > w.since) w.deferred.complete(g)
        return g
    }
}
