package dev.dsh.mobile.linux

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 跨模块接线契约：本模块 JVM 测试不实例化 Android 的 AppContainer。 */
class LinuxOfflinePreparationTest {
    private fun controller(): String {
        val relative = "app/src/main/kotlin/dev/dsh/mobile/app/LinuxSettingsController.kt"
        return listOf(File(relative), File("../$relative")).first { it.isFile }.readText()
    }

    @Test fun bootstrapFailureDoesNotGuardOfflineKernelInstallation() {
        val source = controller()
        val flow = source.substringAfter("override suspend fun reinstallRuntime").substringBefore("override suspend fun installKernel")
        val environment = flow.indexOf("val environment = prepareEnvironment")
        val kernel = flow.indexOf("prepareKernel {")
        assertTrue(environment >= 0 && kernel > environment)
        assertFalse(flow.substring(environment, kernel).contains("environment.error"))
        assertTrue(flow.contains("listOfNotNull(environment.error, kernel.error)"))
        assertTrue(flow.contains("catch (cancelled: CancellationException)"))
        val prepare = source.substringAfter("private suspend fun prepareEnvironment").substringBefore("private suspend fun prepareKernel")
        assertTrue(prepare.contains("if (bootstrap.ok) null else bootstrap.error"))
        assertFalse(prepare.contains("check(bootstrap.ok)"))
        val offline = source.substringAfter("private suspend fun prepareKernel").substringBefore("private suspend fun checkEnvironment")
        assertTrue(offline.contains("context.assets.open(DshKernelArtifacts.FILE)"))
        assertTrue(offline.contains("installer.install(manifest)"))
        assertFalse(offline.contains("LinuxBootstrap"))
    }

    @Test fun resolvSelfCheckDistinguishesDegradedContentFromEmptyFile() {
        val script = LinuxScripts.selfCheck()
        assertTrue(script.contains("degraded:"))
        assertTrue(script.contains("降级 · 未探测到应答，已写入"))
        assertTrue(script.contains("有内容 ·"))
        assertTrue(script.contains("empty or missing /etc/resolv.conf"))
    }
}
