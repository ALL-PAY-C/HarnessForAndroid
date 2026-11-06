package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启第三方插件全掉的根因：安全模式把「本进程这次自增」也算成一次失败。
 *
 * 真机 07:43:48（dsh.plugins.xml boot.failures=2）：beginBoot 自增成 3 → [PluginHost.safeMode]
 * 为真 → [PluginHost.isEnabled] 只放行 dsh. 前缀 → ENGINE_NODE_START plugins=2、MODEL_SOURCES 空；
 * 首帧时 markBootOk 归零，于是第二次启动 plugins=4。判据必须只用「之前的启动」。
 */
class SafeModeBootCountTest {

    @Test
    fun twoHistoricalFailuresPlusThisBootIsNotSafeMode() {
        // 历史 2 次 + 本轮自增 1 次 = 3：本轮还没失败过，不能算崩溃循环。
        assertEquals(2, previousBootFailures(failures = 3, countedInThisProcess = 1))
        assertFalse(safeModeFrom(failures = 3, countedInThisProcess = 1))
    }

    @Test
    fun threeRealPreviousFailuresStillEnterSafeMode() {
        assertTrue(safeModeFrom(failures = 3, countedInThisProcess = 0))
        assertTrue(safeModeFrom(failures = 4, countedInThisProcess = 1))
    }

    @Test
    fun countingNeverGoesNegative() {
        assertEquals(0, previousBootFailures(failures = 0, countedInThisProcess = 1))
        assertFalse(safeModeFrom(failures = 0, countedInThisProcess = 2))
    }

    @Test
    fun bootAfterMarkBootOkStaysNormal() {
        // 首帧 markBootOk 写回 0，下一次启动自增成 1：仍然是健康的一轮。
        assertFalse(safeModeFrom(failures = 1, countedInThisProcess = 1))
    }
}
