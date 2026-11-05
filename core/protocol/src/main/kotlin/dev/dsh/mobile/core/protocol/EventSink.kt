package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.platform.AppLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件流的唯一出口（T9.14 修 1）。
 *
 * 以前每个生产者各自 `MutableSharedFlow(...).tryEmit(...)`：慢 collector 一来就静默丢事件，
 * 界面表现成「说着说着停了」「永远转圈」，日志里一个字都没有。现在：
 *
 *  * [emit] 非挂起：溢出时丢弃并计数（首次 / 每 100 次打一行日志，带事件类型与累计数）；
 *  * [emitOrAwait] 挂起：缓冲满时等消费者接走 —— 终态类事件（TurnEnd / ToolCallEvent /
 *    Compacted / Permission）丢一条就是 UI 永远转圈，必须走这条；
 *  * [droppedEventCount] / [lostEventCount] 把「丢了多少」暴露出来，丢事件从无声变成可查。
 *
 * 两类丢失分开计数，因为故障模式不同：
 *
 *  * dropped = 缓冲满（消费者太慢，事件被挤掉）；
 *  * lost = 压根没有订阅者（界面还没挂上 collector / 已经销毁）。SharedFlow 在零订阅者时
 *    tryEmit 恒返回 true 却把值直接扔掉，不单独计就完全不可见 —— 这正是「整轮事件全丢」的现场。
 *    不用 replay 兜：那会把上一轮的事件重放给新 collector。
 *
 * 缓冲有界（默认 [DEFAULT_CAPACITY]）：无界缓冲只是把「静默丢帧」换成「无上限涨内存」，
 * 长回合的流式 delta 会把进程撑死；有界 + 满则计数，至少丢了多少是可查的。
 * 需要保留「只留最新值」语义的流（例如世代流）显式传 capacity / replay / overflow。
 */
internal class EventSink<T : Any>(
    capacity: Int = DEFAULT_CAPACITY,
    replay: Int = 0,
    overflow: BufferOverflow = BufferOverflow.SUSPEND,
) {
    private val _events = MutableSharedFlow<T>(
        replay = replay,
        extraBufferCapacity = capacity,
        onBufferOverflow = overflow,
    )

    val events: SharedFlow<T> = _events.asSharedFlow()

    private val dropped = AtomicLong()
    private val lost = AtomicLong()

    /** 累计因缓冲溢出而丢弃的事件数。 */
    val droppedEventCount: Long get() = dropped.get()

    /** 累计因「没有任何订阅者」而丢弃的事件数。 */
    val lostEventCount: Long get() = lost.get()

    /** 非挂起发送，返回是否真的入队；false = 缓冲溢出，已计数并打过日志。 */
    fun emit(e: T): Boolean {
        if (_events.subscriptionCount.value == 0) {
            noteLost(e)
            // 照常走一次 tryEmit 以保留返回值语义：零订阅者时它恒为 true。
            _events.tryEmit(e)
            return true
        }
        if (_events.tryEmit(e)) return true
        val total = dropped.incrementAndGet()
        if (total == 1L || total % 100L == 0L) {
            AppLog.w("event dropped(total=" + total + "): " + e::class.java.simpleName)
        }
        return false
    }

    /** 挂起发送：缓冲满时等消费者接走，关键事件一律走这条。 */
    suspend fun emitOrAwait(e: T) {
        if (_events.subscriptionCount.value == 0) {
            // 零订阅者时 emit 不会挂起、值也没人接：计数后照常走一次，语义与 emit 对齐。
            noteLost(e)
            _events.emit(e)
            return
        }
        _events.emit(e)
    }

    private fun noteLost(e: T) {
        val total = lost.incrementAndGet()
        if (total == 1L || total % 100L == 0L) {
            AppLog.w("event lost(no subscriber, total=" + total + "): " + e::class.java.simpleName)
        }
    }

    companion object {
        /** 默认缓冲上限：约 2048 条事件，够长回合流式输出抖动，又不会无限涨。 */
        const val DEFAULT_CAPACITY: Int = 2048
    }
}
