package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** proot 下 apk 的两条 rename 噪声：无条件剔除；真实错误一行都不动。 */
class LinuxApkLogTest {

    /** 设备真实输出：terminfo/vt220 与 usr/bin/zipinfo 各一条 rename 失败 + 汇总行。 */
    private val deviceLog = """
        #T5-APK-BEGIN
        (1/22) Installing curl (8.14.1-r0)
        ERROR: ncurses-terminfo-base-6.4_p20240420-r2: failed to rename etc/terminfo/v/.apk.fa3cd to etc/terminfo/v/vt220.
        ERROR: ncurses-terminfo-base-6.4_p20240420-r2: Permission denied
        ERROR: unzip-6.0-r15: failed to rename usr/bin/.apk.9be1c to usr/bin/zipinfo.
        ERROR: unzip-6.0-r15: Permission denied
        3 errors; 88 MiB in 53 packages
        #T5-APK-END rc=3
    """.trimIndent()

    @Test
    fun `包都装好时 两条 rename 噪声与汇总行全剔除`() {
        val out = LinuxApkLog.filterBenignApkErrors(deviceLog, allPackagesInstalled = true)
        assertFalse(out, out.contains("failed to rename"))
        assertFalse(out, out.contains("Permission denied"))
        assertFalse(out, out.contains("errors;"))
        // 正常进度行留着
        assertTrue(out, out.contains("(1/22) Installing curl"))
        assertTrue(out, out.contains("#T5-APK-END rc=3"))
    }

    @Test
    fun `装失败时两条噪声同样剔除 真实错误与计数行原样保留`() {
        val log = """
            (1/3) Installing unzip (6.0-r15)
            ERROR: unzip-6.0-r15: failed to rename usr/bin/.apk.9be1c to usr/bin/zipinfo.
            ERROR: unzip-6.0-r15: Permission denied
            ERROR: unable to select packages: py3-pip (no such package)
            2 errors; 12 MiB in 3 packages
        """.trimIndent()
        val out = LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = false)
        // 噪声永远不进 UI，装失败也一样。
        assertFalse(out, out.contains("failed to rename"))
        assertFalse(out, out.contains("Permission denied"))
        // 真错误与它对应的计数行留着。
        assertTrue(out, out.contains("no such package"))
        assertTrue(out, out.contains("2 errors;"))
    }

    @Test
    fun `无关错误在成功时保留`() {
        val log = """
            (1/3) Installing git (2.45.2-r0)
            ERROR: git-2.45.2-r0: failed to rename usr/share/doc/git/README to usr/share/doc/git/README.new.
            ERROR: curl-8.14.1-r0: could not create /usr/bin/curl
            2 errors; 40 MiB in 12 packages
        """.trimIndent()
        val out = LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = true)
        assertTrue(out, out.contains("failed to rename usr/share/doc/git/README"))
        assertTrue(out, out.contains("could not create /usr/bin/curl"))
        assertFalse(out, out.contains("errors;"))
    }

    @Test
    fun `CRLF 与裸 CR 换行下两条噪声同样剔除`() {
        val log = "#T5-APK-BEGIN\r\n" +
            "(1/22) Installing curl (8.14.1-r0)\r\n" +
            "ERROR: ncurses-terminfo-base-6.4_p20240420-r2: failed to rename etc/terminfo/v/.apk.fa3cd to etc/terminfo/v/vt220.\r\n" +
            "ERROR: ncurses-terminfo-base-6.4_p20240420-r2: Permission denied\r\n" +
            "ERROR: unzip-6.0-r15: failed to rename usr/bin/.apk.9be1c to usr/bin/zipinfo.\r" +
            "3 errors; 88 MiB in 53 packages\r\n" +
            "#T5-APK-END rc=3\r\n"
        val out = LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = true)
        assertFalse(out, out.contains("failed to rename"))
        assertFalse(out, out.contains("Permission denied"))
        assertFalse(out, out.contains("errors;"))
        assertTrue(out, out.contains("(1/22) Installing curl"))
        assertTrue(out, out.contains("#T5-APK-END rc=3"))
    }

    @Test
    fun `失败原因缀在 rename 行尾时仍按目标判定为噪声`() {
        val log = """
            ERROR: unzip-6.0-r15: failed to rename usr/bin/.apk.9be1c to usr/bin/zipinfo: Permission denied
            ERROR: ncurses-terminfo-base-6.4_p20240420-r2: failed to rename etc/terminfo/v/.apk.fa3cd to /etc/terminfo/v/vt220:
        """.trimIndent()
        assertEquals("", LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = true))
        assertEquals("", LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = false))
    }

    @Test
    fun `校验失败与磁盘写满不被误吞`() {
        val log = """
            (1/2) Installing openssl (3.3.2-r0)
            ERROR: openssl-3.3.2-r0: BAD signature
            ERROR: openssl-3.3.2-r0: failed to rename usr/lib/.apk.77aa to usr/lib/libssl.so.3: No space left on device
            ERROR: git-2.45.2-r0: checksum mismatch
            ERROR: busybox-1.36.1-r7: Permission denied
            3 errors; 40 MiB in 12 packages
        """.trimIndent()
        val out = LinuxApkLog.filterBenignApkErrors(log, allPackagesInstalled = true)
        assertTrue(out, out.contains("BAD signature"))
        assertTrue(out, out.contains("No space left on device"))
        assertTrue(out, out.contains("checksum mismatch"))
        // 不跟在已知 rename 失败后面的 Permission denied 是真错误，不能一起吞。
        assertTrue(out, out.contains("busybox-1.36.1-r7: Permission denied"))
        assertTrue(out, out.contains("(1/2) Installing openssl"))
    }

    @Test
    fun `空输入返回空`() {
        assertEquals("", LinuxApkLog.filterBenignApkErrors("", allPackagesInstalled = true))
        assertEquals("", LinuxApkLog.filterBenignApkErrors("", allPackagesInstalled = false))
    }
}
