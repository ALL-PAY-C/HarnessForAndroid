package dev.dsh.mobile.core.common

/**
 * 进程内「待注入图像」登记队列：role=tool 的消息放不进 image_url，
 * 工具阶段截的图只能先登记，等下一次模型请求把它并进 user 消息的 content。
 *
 * 放在 core:common 是因为登记方（toolbridge 的截图分支）和消费方（core:protocol 的请求装配）
 * 互不依赖，两边都只依赖 core:common；进程内单例（manifest 里只有 :node 是独立进程）。
 */
object PendingImageQueue {

    /** 队列容量：一轮最多补 2 张，再来就顶掉最老的（更早的屏幕已经过时）。 */
    const val MAX_ITEMS = 2

    /** 单张字节上限：超过就不登记，别让一次带图请求把网关顶回去。 */
    const val MAX_BYTES = 2L * 1024 * 1024

    /** 登记后的有效期：只可能给紧随其后的那一轮，过期即丢。 */
    const val TTL_MS = 30_000L

    /** 一张待注入的图；[mime] 给没有扩展名的临时文件兜底。 */
    data class Image(val path: String, val mime: String, val bytes: Long, val enqueuedAtMs: Long)

    /**
     * 登记结果。[queued] = false 时 [reason] 是给人/模型看的未入队原因，
     * 调用方要原样写进返回体，不能装没事。
     */
    data class Enqueue(val queued: Boolean, val reason: String? = null)

    private val lock = Any()
    private val items = ArrayDeque<Image>(MAX_ITEMS)

    /**
     * 登记一张图。[nowMs] 只在单测里显式给，正常调用取当前时间。
     * 满了顶掉最老的而不是拒绝新的：截图是「用户现在要我看的」。
     */
    fun enqueue(path: String, mime: String, bytes: Long, nowMs: Long = System.currentTimeMillis()): Enqueue {
        if (path.isBlank()) return Enqueue(false, "路径为空")
        if (bytes <= 0L) return Enqueue(false, "文件为空或不可读")
        if (bytes > MAX_BYTES) return Enqueue(false, "单张 " + (bytes / 1024) + " KiB，超过 2 MiB 上限")
        synchronized(lock) {
            dropExpiredLocked(nowMs)
            while (items.size >= MAX_ITEMS) {
                AppLog.w("截图登记满员丢弃最老的：" + items.removeFirst().path)
            }
            items.addLast(Image(path, mime, bytes, nowMs))
            return Enqueue(true)
        }
    }

    /**
     * 取走这次请求要带上的图（最多 [max] 张），取走即出队。
     * 过期项在这里就地丢掉并留日志：模型看不到它，日志里必须有原因。
     */
    fun drainForNextTurn(max: Int = MAX_ITEMS, nowMs: Long = System.currentTimeMillis()): List<Image> =
        synchronized(lock) {
            dropExpiredLocked(nowMs)
            val out = ArrayList<Image>(max)
            while (out.size < max && items.isNotEmpty()) out += items.removeFirst()
            out
        }

    /** 清空：会话切换/单测用。 */
    fun clear() {
        synchronized(lock) { items.clear() }
    }

    /** 登记时间是单调递增的，队头最老：从头丢到第一个没过期的为止。 */
    private fun dropExpiredLocked(nowMs: Long) {
        while (items.isNotEmpty() && nowMs - items.first().enqueuedAtMs > TTL_MS) {
            AppLog.w("截图登记过期丢弃（超过 " + (TTL_MS / 1000) + " 秒）：" + items.removeFirst().path)
        }
    }
}
