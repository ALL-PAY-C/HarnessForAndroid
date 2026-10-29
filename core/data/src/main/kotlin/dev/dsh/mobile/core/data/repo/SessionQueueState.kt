package dev.dsh.mobile.core.data.repo

/**
 * 单个会话的排队状态：队列 + 代际（generation）。
 *
 * 代际解决「drain / sendNow 已摘出条目、还没拿到发送锁，此时 clear 被调用」的竞态：
 * clear 递增 generation，摘出的条目带着旧 generation，等真正要发时校验代际已过期就跳过，
 * 否则会把一条用户已经清空的消息又发出去。
 *
 * 全部方法 [Synchronized]：队列实体在多个协程（排空 / 立刻发 / UI 入队）之间共享，
 * 单会话内部必须串行。跨会话由外层 ConcurrentHashMap 分片，互不阻塞。
 */
internal class SessionQueueState {

    private val items = ArrayDeque<SendQueue.Item>()
    private var generation = 0L

    /** 入队；已到 [limit] 返回 false。 */
    @Synchronized
    fun enqueue(item: SendQueue.Item, limit: Int): Boolean {
        if (items.size >= limit) return false
        items.addLast(item)
        return true
    }

    @Synchronized
    fun drop(id: String) {
        items.removeAll { it.id == id }
    }

    /** 插到队首；返回 false 表示没找到。 */
    @Synchronized
    fun promote(id: String): Boolean {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val item = items.removeAt(idx)
        items.addFirst(item)
        return true
    }

    /** 摘出某一条（「立刻发」用），带当前代际；没找到返回 null。 */
    @Synchronized
    fun take(id: String): Pair<SendQueue.Item, Long>? {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val item = items.removeAt(idx)
        return item to generation
    }

    /** 弹队首，带当前代际；空返回 null。 */
    @Synchronized
    fun pop(): Pair<SendQueue.Item, Long>? {
        val item = items.removeFirstOrNull() ?: return null
        return item to generation
    }

    /** 清空内容并推进代际，使已摘出未发出的条目全部失效。 */
    @Synchronized
    fun clear() {
        items.clear()
        generation++
    }

    /** 代际校验：条目摘出时的代际 == 当前代际才有效。 */
    @Synchronized
    fun isCurrent(gen: Long): Boolean = generation == gen

    @Synchronized
    fun snapshot(): List<SendQueue.Item> = items.toList()

    @get:Synchronized
    val size: Int get() = items.size
}
