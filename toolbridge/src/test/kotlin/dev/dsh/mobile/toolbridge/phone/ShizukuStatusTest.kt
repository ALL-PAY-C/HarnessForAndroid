package dev.dsh.mobile.toolbridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStatusTest {
    @Test fun missingInstallOutranksRunning() {
        assertEquals(ShizukuState.NotInstalled, ShizukuStatus.of(installed = false, running = true, granted = true).state)
    }

    @Test fun installedButStoppedIsNotRunning() {
        val status = ShizukuStatus.of(installed = true, running = false, granted = true)
        assertEquals(ShizukuState.NotRunning, status.state)
        assertFalse(status.ready)
        assertTrue(status.hint().contains("启动"))
    }

    @Test fun startedWithoutGrantNeedsPermission() {
        val status = ShizukuStatus.of(installed = true, running = true, granted = false)
        assertEquals(ShizukuState.NoPermission, status.state)
        assertTrue(status.hint().contains("授权"))
    }

    @Test fun readyNeedsAllThree() {
        val status = ShizukuStatus.of(installed = true, running = true, granted = true)
        assertEquals(ShizukuState.Ready, status.state)
        assertTrue(status.ready)
        assertTrue(status.hint().isEmpty())
    }
}
