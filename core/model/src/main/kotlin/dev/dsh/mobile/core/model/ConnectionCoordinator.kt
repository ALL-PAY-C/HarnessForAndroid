package dev.dsh.mobile.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 连接生命周期协调器：AppContainer.connect / disconnect 共用的唯一闸门。
 *
 * 修 B3 的两条不变量都从这里来：
 *  1. 外部 disconnect 与 connect 共享同一 [Mutex]，串行——force 重连不能绕过它并发改
 *     _runtime/_transport/repo/stateJob。
 *  2. [replace] 先 await teardown 完成才 start——旧 stop 阻塞时新 start 一定还没发生，
 *     杜绝「迟到的旧 stop() 关掉刚 start 的 current」。
 *
 * 纯 Kotlin + 协程，无 Android 依赖，JVM 可直接用 CompletableDeferred 屏障做确定性回归。
 */
class ConnectionCoordinator {
    private val mutex = Mutex()

    /**
     * connect 走这里：teardown（await 停完旧连接）→ start（建新连接）。
     * 返回 start 的结果。
     */
    suspend fun <T> replace(teardown: suspend () -> Unit, start: suspend () -> T): T =
        mutex.withLock {
            teardown()
            start()
        }

    /** 外部 disconnect 走这里：与 [replace] 共享同一互斥。 */
    suspend fun teardown(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }
}
