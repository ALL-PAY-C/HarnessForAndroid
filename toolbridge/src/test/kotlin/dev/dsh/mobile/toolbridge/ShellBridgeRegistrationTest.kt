package dev.dsh.mobile.toolbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellBridgeRegistrationTest {
    private class Channel(override val id: String, override val label: String, val spec: ShellSpec? = null, val usable: Boolean = true) : ShellChannel {
        override val kind = ChannelKind.Proot
        override fun available() = usable
        override fun terminalSpec() = spec
        override fun detail() = label
        override suspend fun exec(command: String, timeoutMs: Long) = ShellResult(0, label, "")
    }

    @Test fun terminalUsesAvailableProotBeforeBusyboxFallback() = runTest {
        val guest = ShellSpec("/native/libproot.so", listOf("/bin/sh", "-l"), "/work", emptyList(), "proot")
        val bridge = ShellBridge(listOf(Channel("proot", "guest", guest)), StandardTestDispatcher(testScheduler), backgroundScope)
        assertSame(guest, bridge.terminalSpec { error("busybox fallback should not run") })
    }

    @Test fun terminalFallsBackToBusyboxWithoutAvailableProot() = runTest {
        val busybox = ShellSpec("/native/libbusybox.so", listOf("sh"), "/home", emptyList(), "busybox")
        for (channels in listOf(emptyList(), listOf(Channel("proot", "missing", usable = false)))) {
            val bridge = ShellBridge(channels, StandardTestDispatcher(testScheduler), backgroundScope)
            assertSame(busybox, bridge.terminalSpec { busybox })
        }
    }

    @Test fun duplicateRegistrationRetainsOriginalUnlessReplaceRequested() = runTest {
        val old = Channel("proot", "old-rootfs")
        val fresh = Channel("proot", "new-rootfs")
        val bridge = ShellBridge(listOf(old), StandardTestDispatcher(testScheduler), backgroundScope)
        bridge.register(fresh, replace = false)
        assertSame(old, bridge.byId("proot"))
        assertEquals(1, bridge.all().size)
        bridge.register(fresh)
        assertSame(old, bridge.all().single())
    }

    @Test fun unregisterRemovesChannelFromLookupAndSnapshot() = runTest {
        val old = Channel("proot", "old-rootfs")
        val app = Channel("app", "app")
        val bridge = ShellBridge(listOf(old, app), StandardTestDispatcher(testScheduler), backgroundScope)
        bridge.unregister("proot")
        assertNull(bridge.byId("proot"))
        assertEquals(listOf(app), bridge.all())
        bridge.unregister("proot")
        assertEquals(listOf(app), bridge.all())
    }

    @Test fun replacingSameIdPublishesNewObjectAndPreservesPosition() = runTest {
        val old = Channel("proot", "old-rootfs")
        val fresh = Channel("proot", "new-rootfs")
        val app = Channel("app", "app")
        val bridge = ShellBridge(listOf(old, app), StandardTestDispatcher(testScheduler), backgroundScope)
        bridge.register(fresh, replace = true)
        assertSame(fresh, bridge.byId("proot"))
        assertSame(fresh, bridge.all().first())
        assertEquals(listOf("proot", "app"), bridge.all().map { it.id })
    }
}
