package dev.dsh.mobile.core.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 AppContainer.connect/disconnect 实际使用的协调逻辑（不是独立假 helper）。
 * 用 CompletableDeferred 屏障，不用 delay 猜时间。
 */
class ConnectionCoordinatorTest {

    @Test
    fun replaceAwaitsTeardownBeforeStart() = runBlocking {
        // B3：旧 stop 阻塞时，新 start 尚未发生——teardown 必须先完成。
        val coordinator = ConnectionCoordinator()
        val teardownStarted = CompletableDeferred<Unit>()
        val releaseTeardown = CompletableDeferred<Unit>()
        var startRan = false

        val job = launch {
            coordinator.replace(
                teardown = {
                    teardownStarted.complete(Unit)
                    releaseTeardown.await()
                },
                start = { startRan = true },
            )
        }

        teardownStarted.await()
        assertFalse("teardown 阻塞时 start 不应提前发生", startRan)
        releaseTeardown.complete(Unit)
        job.join()
        assertTrue("teardown 完成后 start 应发生", startRan)
    }

    @Test
    fun teardownAndReplaceShareMutex() = runBlocking {
        // 外部 disconnect 占住互斥时，connect(replace) 必须等它释放，不能并发。
        val coordinator = ConnectionCoordinator()
        val teardownEntered = CompletableDeferred<Unit>()
        val releaseTeardown = CompletableDeferred<Unit>()

        val teardownJob = launch {
            coordinator.teardown {
                teardownEntered.complete(Unit)
                releaseTeardown.await()
            }
        }
        teardownEntered.await()

        val replaceEntered = CompletableDeferred<Unit>()
        val replaceJob = launch {
            coordinator.replace(teardown = {}, start = { replaceEntered.complete(Unit) })
        }

        assertFalse("disconnect 未释放互斥时 connect 不应进入", replaceEntered.isCompleted)
        releaseTeardown.complete(Unit)
        teardownJob.join()
        replaceJob.join()
        assertTrue("互斥释放后 connect 应进入", replaceEntered.isCompleted)
    }

    @Test
    fun concurrentReplacesAreSerialized() = runBlocking {
        // 两个 connect 并发：第二个必须等第一个的 teardown+start 全完成。
        val coordinator = ConnectionCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRan = false

        val first = launch {
            coordinator.replace(
                teardown = {},
                start = {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                },
            )
        }
        firstStarted.await()

        val second = launch {
            coordinator.replace(teardown = {}, start = { secondRan = true })
        }

        assertFalse("第一个 connect 未完成时第二个不应进入", secondRan)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondRan)
    }
}
