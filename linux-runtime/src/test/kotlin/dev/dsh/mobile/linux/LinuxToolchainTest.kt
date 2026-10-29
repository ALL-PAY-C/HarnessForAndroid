package dev.dsh.mobile.linux

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R2.1/R2.3：工具链清单、幂等脚本、apk 串行锁、结果解析。 */
class LinuxToolchainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `预装清单含全部工具链`() {
        assertEquals(
            listOf(
                "bash", "jq", "ffmpeg", "sqlite", "unzip", "tar",
                "coreutils", "findutils", "openssh-client", "rsync", "ripgrep",
            ),
            LinuxToolchain.packages(),
        )
    }

    @Test
    fun `预装脚本先查已装 只补缺的`() {
        val s = LinuxToolchain.provisionScript()
        assertTrue(s, s.contains("REQ='bash jq ffmpeg sqlite unzip tar coreutils findutils openssh-client rsync ripgrep'"))
        assertTrue(s, s.contains("apk info -e \"\$p\""))
        assertTrue(s, s.contains("apk add --no-cache \$missing"))
        // 全装齐时直接退出，不联网
        assertTrue(s, s.contains("#T5-TOOLCHAIN-STATUS ok=1 skipped=1 apk_rc=-"))
        assertTrue(s, s.contains("#T5-TOOLCHAIN-STATUS ok=0 skipped=0 apk_rc=\$apk_rc"))
    }

    @Test
    fun `apk 原始输出被 BEGIN END 包住`() {
        val s = LinuxToolchain.provisionScript()
        assertTrue(s, s.contains("#T5-TOOLCHAIN-BEGIN"))
        assertTrue(s, s.contains("#T5-TOOLCHAIN-END rc=\$apk_rc"))
        assertTrue(s, s.indexOf("#T5-TOOLCHAIN-BEGIN") < s.indexOf("apk add --no-cache"))
    }

    @Test
    fun `apk 调用被文件锁串起来`() {
        val s = LinuxToolchain.provisionScript()
        assertTrue(s, s.contains("LOCK='/var/lock/dsh-apk.lock'"))
        assertTrue(s, s.contains("mkdir \"\$LOCK\" 2>/dev/null"))
        assertTrue(s, s.contains("kill -0 \"\$holder\""))       // 持有者已死则抢锁
        assertTrue(s, s.contains("#T5-APK-LOCK acquired pid="))
        assertTrue(s, s.contains("#T5-APK-LOCK stale pid="))
        assertTrue(s, s.contains("#T5-APK-LOCK giveup retries=60"))
        assertTrue(s, s.contains("trap 'rm -rf \"\$LOCK\"' EXIT INT TERM"))
        assertTrue(s, s.contains("sleep 0.5"))
        // 先拿锁再动 apk
        assertTrue(s, s.indexOf("apk_lock ||") < s.indexOf("apk add --no-cache"))
    }

    @Test
    fun `lockedScript 能把任意命令包进锁`() {
        val s = LinuxToolchain.lockedScript("apk update", lockDir = "/tmp/x.lock", retries = 3, sleep = "1")
        assertTrue(s, s.contains("LOCK='/tmp/x.lock'"))
        assertTrue(s, s.contains("mkdir -p '/tmp'"))
        assertTrue(s, s.contains("giveup retries=3"))
        assertTrue(s, s.contains("sleep 1"))
        assertTrue(s.trimEnd().endsWith("apk update"))
    }

    @Test
    fun `解析成功结果 并抽出 apk 输出`() {
        val stdout = """
            #T5-TOOLCHAIN-STATE installed= bash jq
            #T5-TOOLCHAIN-STATE missing=
            #T5-TOOLCHAIN-BEGIN
            (1/2) Installing bash
            OK: 12 MiB
            #T5-TOOLCHAIN-END rc=0
            #T5-TOOLCHAIN-STATUS ok=1 skipped=0 apk_rc=0
        """.trimIndent()
        val r = LinuxToolchain.parse(stdout)
        assertTrue(r.ok)
        assertFalse(r.skipped)
        assertEquals(listOf("bash", "jq"), r.installed)
        assertEquals(0, r.apkRc)
        assertTrue(r.apkOutput, r.apkOutput.contains("(1/2) Installing bash"))
        assertTrue(r.apkOutput, r.apkOutput.contains("OK: 12 MiB"))
        assertNull(r.error)
    }

    @Test
    fun `缺包判 ok=false 并带回缺失清单`() {
        val stdout = """
            #T5-TOOLCHAIN-BEGIN
            ERROR: unable to select packages
            #T5-TOOLCHAIN-END rc=1
            #T5-TOOLCHAIN-STATUS ok=0 skipped=0 apk_rc=1
            #T5-TOOLCHAIN-ERROR missing= ffmpeg ripgrep
        """.trimIndent()
        val r = LinuxToolchain.parse(stdout)
        assertFalse(r.ok)
        assertEquals(listOf("ffmpeg", "ripgrep"), r.missing)
        assertTrue(r.apkOutput, r.apkOutput.contains("unable to select packages"))
        assertTrue(r.error!!, r.error!!.contains("ffmpeg"))
    }

    @Test
    fun `脚本没跑到 STATUS 时把 stderr 当错误`() {
        val r = LinuxToolchain.parse("mkdir: can't create", "proot: exec failed")
        assertFalse(r.ok)
        assertEquals("proot: exec failed", r.error)
    }

    @Test
    fun `applyMirror 探测后写最快镜像`() = runTest {
        val installer = installer(probeOut = probeOut(aliyun = "0.640", tuna = "0.210"))
        assertEquals(LinuxApk.TUNA, installer.applyMirror())
        assertEquals(LinuxApk.TUNA, installer.mirror)
        val body = File(rootfs(), LinuxApk.REPOSITORIES_PATH).readText()
        assertEquals(LinuxApk.TUNA.base + "/main\n" + LinuxApk.TUNA.base + "/community\n", body)
        assertTrue(installer.lastProbe, installer.lastProbe.contains("#APK-MIRROR"))
    }

    @Test
    fun `探测全超时回退官方源`() = runTest {
        val installer = installer(probeOut = probeOut(aliyun = "", tuna = ""))
        assertEquals(LinuxApk.OFFICIAL, installer.applyMirror())
        val body = File(rootfs(), LinuxApk.REPOSITORIES_PATH).readText()
        assertTrue(body, body.startsWith(LinuxApk.OFFICIAL.base + "/main"))
    }

    @Test
    fun `install 跑工具链脚本并解析`() = runTest {
        val seen = mutableListOf<String>()
        val installer = installer(
            probeOut = "",
            installOut = "#T5-TOOLCHAIN-BEGIN\nOK\n#T5-TOOLCHAIN-END rc=0\n#T5-TOOLCHAIN-STATUS ok=1 skipped=0 apk_rc=0",
            seen = seen,
        )
        val r = installer.install()
        assertTrue(r.ok)
        assertEquals(1, seen.size)
        assertTrue(seen.single(), seen.single().contains("REQ='bash jq ffmpeg"))
        assertTrue(seen.single(), seen.single().contains("mkdir \"\$LOCK\""))
    }

    private fun rootfs(): File = File(tmp.root, "rootfs").also { it.mkdirs() }

    private fun probeOut(aliyun: String, tuna: String): String = buildString {
        appendLine("#APK-MIRROR-BEGIN")
        appendLine("#APK-MIRROR name=aliyun base=${LinuxApk.ALIYUN.base} time=" + aliyun)
        appendLine("#APK-MIRROR name=tuna base=${LinuxApk.TUNA.base} time=" + tuna)
        appendLine("#APK-MIRROR-END")
    }

    /** 只回放固定输出，不联网也不起进程。 */
    private fun installer(
        probeOut: String,
        installOut: String = "",
        seen: MutableList<String> = mutableListOf(),
    ): LinuxToolchainInstaller {
        val native = File(tmp.root, "native").also { it.mkdirs() }
        File(native, "libproot.so").writeText("x")
        File(native, "libproot-loader.so").writeText("x")
        val rf = rootfs()
        File(rf, "bin").mkdirs()
        File(rf, "bin/sh").writeText("x")
        val channel = ProotChannel(
            installation = LinuxInstallation("3.20.3", "aarch64", rf, File(tmp.root, "linux")),
            nativeDir = native,
            tmpDir = File(tmp.root, "tmp").also { it.mkdirs() },
            workDir = File(tmp.root, "work").also { it.mkdirs() },
            runner = { argv, _, _, _ ->
                val cmd = argv.last()
                seen.add(cmd)
                if (cmd.contains("#APK-MIRROR-BEGIN")) ProotRun(0, probeOut, "")
                else ProotRun(0, installOut, "")
            },
        )
        return LinuxToolchainInstaller(channel)
    }
}
