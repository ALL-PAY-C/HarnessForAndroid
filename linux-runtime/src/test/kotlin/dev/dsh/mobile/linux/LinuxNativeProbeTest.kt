package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T2 单元侧：proot 原生探测的判定逻辑。
 *
 * 真机侧（androidTest）负责证明 ELF 能真的跑起来；这里只证明判定本身不会撒谎：
 * 缺 loader、ABI 不符、环境变量没设 —— 都必须判失败，而不是「看起来有文件就算过」。
 */
class LinuxNativeProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun elf(machine: Int, size: Int = 64): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x7F
        b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte()
        b[3] = 'F'.code.toByte()
        b[4] = 2 // ELFCLASS64
        b[5] = 1 // little endian
        b[16] = 2 // ET_EXEC
        b[18] = (machine and 0xFF).toByte()
        b[19] = ((machine shr 8) and 0xFF).toByte()
        return b
    }

    private fun nativeDir(withLoader: Boolean = true, withProot: Boolean = true, prootMachine: Int = 62, loaderMachine: Int = 62): File {
        val dir = tmp.newFolder("native")
        if (withProot) File(dir, "libproot.so").writeBytes(elf(prootMachine))
        if (withLoader) File(dir, "libproot-loader.so").writeBytes(elf(loaderMachine))
        return dir
    }

    private fun probe(
        nativeDir: File,
        abi: String = "x86_64",
        tmpDir: File = tmp.newFolder("linux-tmp"),
        rootfs: File? = null,
        run: (List<String>, Map<String, String>) -> NativeRun = { _, _ -> NativeRun(0, "proot 5.4.0", "") },
    ) = LinuxNativeProbe(
        nativeDir = nativeDir,
        tmpDir = tmpDir,
        abi = abi,
        runner = NativeRunner { argv, env, _ -> run(argv, env) },
        rootfs = rootfs,
    )

    private fun rootfsDir(): File = tmp.newFolder("rootfs").also { File(it, "bin/sh").apply { parentFile.mkdirs(); writeText("") } }

    private fun LinuxProbeResult.failedNames(): List<String> = checks.filter { !it.ok }.map { it.name }

    @Test
    fun `proot 与 loader 齐全且机器类型匹配时探测通过`() {
        val result = probe(nativeDir()).probe()
        assertTrue(result.detail, result.ok)
        assertEquals("x86_64", result.abi)
        assertEquals(emptyList<String>(), result.failedNames())
    }

    @Test
    fun `缺 loader 时判失败而不是只看 proot 存在`() {
        val result = probe(nativeDir(withLoader = false)).probe()
        assertFalse(result.ok)
        assertTrue(result.failedNames().toString(), result.failedNames().contains("loader-elf"))
    }

    @Test
    fun `loader 的 ELF 机器类型与设备 ABI 不符时判失败`() {
        // loader 是 aarch64(183) 而设备是 x86_64
        val result = probe(nativeDir(loaderMachine = 183)).probe()
        assertFalse(result.ok)
        assertTrue(result.failedNames().toString(), result.failedNames().contains("abi-match"))
    }

    @Test
    fun `proot 自身跑不起来时判失败`() {
        val result = probe(nativeDir()) { _, _ -> NativeRun(1, "", "error: cannot exec") }.probe()
        assertFalse(result.ok)
        assertTrue(result.failedNames().toString(), result.failedNames().contains("exec"))
    }

    @Test
    fun `exec 探测必须显式带上 PROOT_LOADER 与 PROOT_TMP_DIR`() {
        var seen: Map<String, String>? = null
        val result = probe(nativeDir()) { _, env -> seen = env; NativeRun(0, "proot 5.4.0", "") }.probe()
        assertTrue(result.ok)
        val env = requireNonNull(seen)
        assertTrue(env["PROOT_LOADER"]!!.endsWith("libproot-loader.so"))
        assertEquals(tmp.newFolder("x").parentFile!!.let { env["PROOT_TMP_DIR"] } != null, true)
        assertTrue(env.containsKey("PROOT_TMP_DIR"))
    }

    @Test
    fun `有 rootfs 时必须真的 exec 一次 guest 才算通过（D2）`() {
        val rootfs = rootfsDir()
        val argvs = mutableListOf<List<String>>()
        val result = probe(nativeDir(), rootfs = rootfs) { argv, _ ->
            argvs += argv
            if (argv.contains("/bin/sh")) NativeRun(0, "dsh-guest-ok\n", "") else NativeRun(0, "proot 5.4.0", "")
        }.probe()
        assertTrue(result.detail, result.ok)
        assertTrue(result.checks.map { it.name }.toString(), result.checks.any { it.name == "guest-exec" && it.ok })
        val guestArgv = argvs.first { it.contains("/bin/sh") }
        assertEquals("-0", guestArgv[1])
        assertEquals(rootfs.absolutePath, guestArgv[guestArgv.indexOf("-r") + 1])
    }

    @Test
    fun `guest 里跑不起来时 guest-exec 判失败`() {
        val result = probe(nativeDir(), rootfs = rootfsDir()) { argv, _ ->
            if (argv.contains("/bin/sh")) NativeRun(1, "", "proot error: execve(\"/bin/sh\"): Permission denied")
            else NativeRun(0, "proot 5.4.0", "")
        }.probe()
        assertFalse(result.ok)
        assertTrue(result.failedNames().toString(), result.failedNames().contains("guest-exec"))
    }

    @Test
    fun `没有 rootfs 时不做 guest-exec 检查`() {
        val result = probe(nativeDir()).probe()
        assertTrue(result.ok)
        assertFalse(result.checks.map { it.name }.toString(), result.checks.any { it.name == "guest-exec" })
    }

    @Test
    fun `探测结论会带上 proot 版本行`() {
        val result = probe(nativeDir()) { _, _ -> NativeRun(0, "proot 5.4.0", "") }.probe()
        assertEquals("proot 5.4.0", result.version)
    }

    @Test
    fun `ELF 机器类型识别`() {
        assertEquals("x86_64", ElfHeader.machineOf(elf(62)))
        assertEquals("aarch64", ElfHeader.machineOf(elf(183)))
        assertNull(ElfHeader.machineOf(ByteArray(8)))
    }

    private fun requireNonNull(v: Map<String, String>?): Map<String, String> = v ?: error("runner 没被调用")
}
