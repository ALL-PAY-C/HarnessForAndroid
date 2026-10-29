package dev.dsh.mobile.core.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionKeyTest {

    private val endpoint = Endpoint(
        id = "e1",
        name = "远程",
        kind = TransportKind.Remote,
        url = "wss://example.com/ws",
        token = "tok",
        heartbeatMs = 20_000L,
        cwd = "/work",
    )

    private fun key(
        endpoint: Endpoint = this.endpoint,
        runtimeVersion: String = "v1",
        bundledNode: Boolean = true,
        workspaceCwd: String = "/ws",
    ) = connectionKey(endpoint, runtimeVersion, bundledNode, workspaceCwd)

    @Test
    fun sameInputsProduceEqualKeys() {
        assertEquals(key(), key())
    }

    @Test
    fun sameIdDifferentUrlChangesKey() {
        val changed = endpoint.copy(url = "wss://other.com/ws")
        assertFalse(key(endpoint = changed) == key())
    }

    @Test
    fun tokenChangeChangesKey() {
        assertFalse(key(endpoint = endpoint.copy(token = "tok2")) == key())
    }

    @Test
    fun heartbeatCwdAndKindChangeKeys() {
        assertFalse(key(endpoint = endpoint.copy(heartbeatMs = 1L)) == key())
        assertFalse(key(endpoint = endpoint.copy(cwd = "/other")) == key())
        assertFalse(key(endpoint = endpoint.copy(kind = TransportKind.Loopback)) == key())
    }

    @Test
    fun runtimeVersionAndBundledNodeChangeKeys() {
        assertFalse(key(runtimeVersion = "v2") == key())
        assertFalse(key(bundledNode = false) == key())
    }

    @Test
    fun workspaceCwdChangeChangesKey() {
        assertFalse(key(workspaceCwd = "/other") == key())
    }

    @Test
    fun shouldReconnectIsTrueWhenForce() {
        assertTrue(shouldReconnect(key(), key(), RuntimeState.Ready(TransportKind.Remote), force = true))
    }

    @Test
    fun shouldReconnectIsTrueWhenNeverConnected() {
        assertTrue(shouldReconnect(null, key(), RuntimeState.Ready(TransportKind.Remote), force = false))
    }

    @Test
    fun shouldReconnectIsTrueWhenKeyChanged() {
        val next = key(endpoint = endpoint.copy(url = "wss://new.com/ws"))
        assertTrue(shouldReconnect(key(), next, RuntimeState.Ready(TransportKind.Remote), force = false))
    }

    @Test
    fun shouldReconnectIsTrueWhenTerminal() {
        assertTrue(shouldReconnect(key(), key(), RuntimeState.NotConfigured, force = false))
        assertTrue(shouldReconnect(key(), key(), RuntimeState.Failed("x"), force = false))
    }

    @Test
    fun shouldReconnectIsFalseWhenSameKeyAndInFlight() {
        // Ready / Starting / Reconnecting 都别打断：同 key 时只有终止态（Failed/NotConfigured）才重连。
        assertFalse(shouldReconnect(key(), key(), RuntimeState.Ready(TransportKind.Remote), force = false))
        assertFalse(shouldReconnect(key(), key(), RuntimeState.Starting("准备运行时"), force = false))
        assertFalse(shouldReconnect(key(), key(), RuntimeState.Reconnecting(1, "重连"), force = false))
    }

    @Test
    fun connectGateForceCancelsInsteadOfBypassing() {
        // 有在途连接时 force 不能直接 Proceed（那会绕过互斥并发双连），必须先 CancelThenProceed。
        assertEquals(ConnectGate.CancelThenProceed, connectGate(active = true, force = true))
    }

    @Test
    fun connectGateIdempotentWhenActive() {
        assertEquals(ConnectGate.Skip, connectGate(active = true, force = false))
    }

    @Test
    fun connectGateProceedsWhenIdle() {
        assertEquals(ConnectGate.Proceed, connectGate(active = false, force = false))
        assertEquals(ConnectGate.Proceed, connectGate(active = false, force = true))
    }

    @Test
    fun stopCompletesBeforeStartWhenReplacing() = runBlocking {
        // B3：旧 stop 未完成时 teardownConnection 不返回，start 不能发生。
        // 用 CompletableDeferred 屏障，不用 delay 猜时间。
        val stopStarted = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val job = launch {
            teardownConnection(
                disconnectTransport = { order += "disconnect" },
                stopRuntime = {
                    stopStarted.complete(Unit)
                    releaseStop.await()
                    order += "stop"
                },
            )
            order += "start" // 模拟 connect() 在 teardown 返回后才 start
        }
        stopStarted.await()
        assertEquals("stop 阻塞时 start 不应发生", listOf("disconnect"), order)
        releaseStop.complete(Unit)
        job.join()
        assertEquals(listOf("disconnect", "stop", "start"), order)
    }
}
