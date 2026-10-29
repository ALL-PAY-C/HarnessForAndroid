package dev.dsh.mobile.linux

import dev.dsh.mobile.core.common.ProxyEnv
import java.io.File

/** 一次原生调用（proot 主进程）的结果。 */
data class NativeRun(val code: Int, val stdout: String, val stderr: String)

fun interface NativeRunner {
    fun run(argv: List<String>, env: Map<String, String>, timeoutMs: Long): NativeRun
}

data class ProbeCheck(val name: String, val ok: Boolean, val detail: String)

/**
 * 探测结论。刻意不返回 toolbridge 的类型：本模块依赖 toolbridge，反向不许。
 * ok 只在所有检查都通过时为 true —— 缺一件就整体失败，不做「大致可用」判断。
 */
data class LinuxProbeResult(
    val abi: String,
    val checks: List<ProbeCheck>,
    val version: String = "",
) {
    val ok: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }
    val detail: String get() = if (ok) "ok" else failed().joinToString("; ") { "${it.name}: ${it.detail}" }
    fun failed(): List<ProbeCheck> = checks.filterNot { it.ok }
}

/**
 * T2 的原生侧探测：proot 二进制、PROOT_LOADER、PROOT_TMP_DIR、ABI 一致性，以及能不能真的执行。
 *
 * A4 的要求落在这里：PROOT_LOADER / PROOT_TMP_DIR 是每次调用显式注入的环境变量，
 * 不是「有文件就算」，缺任何一个都判失败。
 */
class LinuxNativeProbe(
    val nativeDir: File,
    val tmpDir: File,
    val abi: String,
    private val runner: NativeRunner,
    private val timeoutMs: Long = 10_000,
    /** 给了 rootfs 才做 guest-exec：证明 guest ELF 真的能通过 loader 跑起来（D2）。 */
    val rootfs: File? = null,
) {

    val prootBinary: File get() = File(nativeDir, "libproot.so")
    val loaderFile: File get() = File(nativeDir, "libproot-loader.so")

    /** 与 [ProotChannel.env] 同一份形状（探测和真跑不能有两套 env），代理也在这里并。 */
    fun prootEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = ProxyEnv.merge(buildMap {
        put("PROOT_LOADER", loaderFile.absolutePath)
        put("PROOT_TMP_DIR", tmpDir.absolutePath)
        put("HOME", "/root")
        put("TMPDIR", "/tmp")
        put("TERM", "dumb")
        put("LANG", "C.UTF-8")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        putAll(extra)
    })

    fun probe(): LinuxProbeResult {
        val checks = mutableListOf<ProbeCheck>()
        val env = prootEnv()

        checks += ProbeCheck("proot-elf", ElfHeader.machineOf(prootBinary) == abi,
            "${prootBinary.absolutePath} machine=${ElfHeader.machineOf(prootBinary) ?: "missing/not-elf"} expect=$abi")
        val loaderMachine = ElfHeader.machineOf(loaderFile)
        checks += ProbeCheck("loader-elf", loaderMachine == abi,
            "${loaderFile.absolutePath} machine=${loaderMachine ?: "missing/not-elf"} expect=$abi")

        // 两台 ELF 的机器类型必须同时等于设备 ABI：proot 与 loader 是一对，
        // 混装（比如把 arm64 的 loader 塞进 x86_64 包）必须在这里就炸掉。
        checks += ProbeCheck("abi-match",
            ElfHeader.machineOf(prootBinary) == abi && loaderMachine == abi,
            "proot=${ElfHeader.machineOf(prootBinary)} loader=$loaderMachine expect=$abi")

        val loaderEnv = env["PROOT_LOADER"].orEmpty()
        checks += ProbeCheck("loader-env",
            loaderEnv.isNotBlank() && File(loaderEnv).isFile,
            "PROOT_LOADER=${loaderEnv.ifBlank { "<unset>" }}")

        val tmpEnv = env["PROOT_TMP_DIR"].orEmpty()
        val tmpOk = tmpEnv.isNotBlank() && (File(tmpEnv).isDirectory || File(tmpEnv).mkdirs())
        checks += ProbeCheck("tmp-env", tmpOk, "PROOT_TMP_DIR=${tmpEnv.ifBlank { "<unset>" }}")

        var version = ""
        val run = try {
            runner.run(listOf(prootBinary.absolutePath, "--version"), env, timeoutMs)
        } catch (t: Throwable) {
            NativeRun(-1, "", t.toString())
        }
        version = run.stdout.lineSequence().firstOrNull { it.contains("proot") }?.trim().orEmpty()
        checks += ProbeCheck("exec", run.code == 0 && version.isNotEmpty(),
            "code=${run.code} stdout=${run.stdout.take(120).trim()} stderr=${run.stderr.take(120).trim()}")

        // D2：只有 --version 不算「能跑 guest」。这里真的 exec 一次 rootfs 里的 /bin/sh。
        val rootfsDir = rootfs
        if (rootfsDir != null && rootfsDir.isDirectory) {
            val guest = try {
                runner.run(
                    listOf(
                        prootBinary.absolutePath, "-0", "-r", rootfsDir.absolutePath,
                        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
                        "/bin/sh", "-c", "echo dsh-guest-ok",
                    ),
                    env, timeoutMs,
                )
            } catch (t: Throwable) {
                NativeRun(-1, "", t.toString())
            }
            checks += ProbeCheck("guest-exec",
                guest.code == 0 && guest.stdout.contains("dsh-guest-ok"),
                "code=${guest.code} stdout=${guest.stdout.take(120).trim()} stderr=${guest.stderr.take(120).trim()}")
        }

        return LinuxProbeResult(abi = abi, checks = checks, version = version)
    }
}
