package dev.dsh.mobile.toolbridge.phone

import dev.dsh.mobile.core.download.PendingImageQueue

/**
 * 手机截图 → 下一轮模型请求的图像登记入口。
 *
 * 真正的队列状态在 core:download 的 [PendingImageQueue]：消费方是 core:protocol 的请求装配，
 * 它不能反过来依赖 toolbridge（见 PhoneBridge 里 ACTION_TREE 那条注释），
 * 所以那边只留手机侧的这一个门面，截图分支只管往这里塞。
 */
object PhoneImageQueue {

    /** 登记一张刚截下来的图；[PendingImageQueue.Enqueue.queued] 与原因原样进 screenshot 返回体。 */
    fun enqueue(path: String, mime: String, bytes: Long): PendingImageQueue.Enqueue =
        PendingImageQueue.enqueue(path, mime, bytes)
}
