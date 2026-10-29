package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * S9b「卸载失败保留配置」的无设备窄测。
 *
 * 覆盖 [uninstallDir] 的删除/remove 顺序不变量：
 *  1. 删除成功 → remove 恰好调用一次；
 *  2. 删除失败 → remove 不被调用（配置保留）；
 *  3. 目录不存在 → 视为成功，remove 仍被调用（记录照常清）。
 *
 * 测试边界（诚实声明）：第 2 例的失败是**真实 JVM 文件删除失败**，且**仅 Windows 生效**——
 * Windows 上 [FileOutputStream] 打开的文件不带 FILE_SHARE_DELETE，句柄未关时
 * `File.delete()`/`File.deleteRecursively()` 返回 false（本机 LockTest 实测）；
 * POSIX/Linux 允许删除已打开的文件，无法用文件占用注入删除失败，故该用例用
 * [assumeTrue] 在非 Windows 平台跳过（诚实标注，不做跨平台删除函数 seam，避免过度重构）。
 * 这不是 Android 设备上的真实卸载失败，也未跑任何 Android 运行时。
 */
class PluginInstallerUninstallTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun successfulDeleteCallsRemoveExactlyOnce() {
        val dir = Files.createTempDirectory("plug").toFile()
        try {
            var calls = 0
            val result = uninstallDir(dir, "p") { calls++ }
            assertTrue(result.isSuccess)
            assertEquals(1, calls)
            assertFalse(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteFailureSkipsRemove_windowsFileLock() {
        // 仅 Windows 可注入：POSIX/Linux 允许删除已打开文件，文件占用不会让删除失败。
        assumeTrue(isWindows)
        val dir = Files.createTempDirectory("plug").toFile()
        try {
            var calls = 0
            val lock = File(dir, "lock.txt")
            lock.writeText("x")
            // 句柄保持打开期间执行，让 deleteRecursively() 因共享违规返回 false。
            FileOutputStream(lock).use {
                val result = uninstallDir(dir, "p") { calls++ }
                assertTrue(result.isFailure)
                assertEquals(0, calls) // remove 未被调用 = 配置保留
            }
        } finally {
            dir.deleteRecursively() // use{} 已关句柄，可正常清理；断言失败也不泄漏
        }
    }

    @Test
    fun missingDirStillClearsRecords() {
        val root = Files.createTempDirectory("plug-root").toFile()
        try {
            var calls = 0
            val dir = File(root, "does-not-exist")
            val result = uninstallDir(dir, "p") { calls++ }
            assertTrue(result.isSuccess)
            assertEquals(1, calls)
        } finally {
            root.deleteRecursively()
        }
    }
}
