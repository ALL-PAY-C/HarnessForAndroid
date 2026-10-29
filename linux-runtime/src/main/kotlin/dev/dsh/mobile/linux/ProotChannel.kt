package dev.dsh.mobile.linux

import dev.dsh.mobile.core.common.ProxyEnv
import dev.dsh.mobile.toolbridge.ChannelKind
import dev.dsh.mobile.toolbridge.ShellChannel
import dev.dsh.mobile.toolbridge.ShellResult
import dev.dsh.mobile.toolbridge.ShellSpec
import java.io.File
import java.util.concurrent.TimeUnit

/** proot guest 与宿主共享的存储根；验收自检的 clone 目标在它下面。 */
const val SDCARD = "/sdcard"

data class ProotRun(val code: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

fun interface ProotRunner {
    fun run(argv: List<String>, env: Map<String, String>, cwd: File?, timeoutMs: Long): ProotRun
}

/**
 * T4：把 ShellChannel.exec 翻成 proot 进程。
 *
 * 只做一件事：拼 argv/env → 跑 → 把真实退出码和分离的 stderr 交回去。
 * A4 的两条环境变量在每次 exec 都显式写入，不做「全局设置过就算」。
 */
class ProotChannel(
    val installation: LinuxInstallation,
    val nativeDir: File,
    val tmpDir: File,
    val workDir: File,
    private val runner: ProotRunner = RealProotRunner,
    /**
     * T5：guest 的 resolv.conf / hosts。默认取实测应答的 DNS（见 [LinuxDns]），
     * 每次 exec 前落盘，保证启动前两份文件都在。
     */
    val network: LinuxNetwork = LinuxNetwork.verified(),
) : ShellChannel {

    override val id: String = "proot"
    override val label: String = "Linux (proot)"
    override val kind: ChannelKind = ChannelKind.Proot

    val prootBinary: File get() = File(nativeDir, "libproot.so")
    val loaderFile: File get() = File(nativeDir, "libproot-loader.so")

    override fun available(): Boolean =
        installation.rootfs.isDirectory && prootBinary.isFile && loaderFile.isFile

    override fun detail(): String =
        "Alpine ${installation.version} (${installation.abi}) · proot ${
            runCatching { prootBinary.length() / 1024 }.getOrDefault(0)
        }KB"

    /** 代理并进来：guest 里的 apk/pip/git 全是子进程，env 带不到运行中途再加。 */
    fun env(extra: Map<String, String> = emptyMap()): Map<String, String> = ProxyEnv.merge(
        buildMap {
            put("PROOT_LOADER", loaderFile.absolutePath)
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            put("HOME", "/root")
            put("TMPDIR", "/tmp")
            put("TERM", "dumb")
            put("LANG", "C.UTF-8")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            putAll(extra)
        },
    )

    /**
     * -0：fake root（proot 的 fake_id0 扩展）。设备实测：不加 -0 时 guest 里是宿主 uid，
     * apk/包管理器写 /usr、/etc 会 EACCES；加了之后 guest 内 id=0 且安装可用。
     */
    fun argv(command: String): List<String> = listOf(
        prootBinary.absolutePath,
        "-0",
        "-r", installation.rootfs.absolutePath,
    ) + binds() + listOf(
        "-w", "/workspace",
        "/bin/sh", "-c", command,
    )

    /**
     * guest 的挂载表。/sdcard 只在宿主真有它时才挂：验收自检要把仓库 clone 到共享存储，
     * guest 默认看不到它；文件不存在还硬挂会让 proot 直接起不来。
     */
    private fun binds(): List<String> = buildList {
        addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
        if (File(SDCARD).isDirectory) addAll(listOf("-b", SDCARD))
        addAll(listOf("-b", workDir.absolutePath + ":/workspace"))
    }

    override fun terminalSpec(): ShellSpec {
        workDir.mkdirs()
        tmpDir.mkdirs()
        network.applyTo(installation.rootfs)
        return ShellSpec(
            shell = prootBinary.absolutePath,
            // JNI forwards this array directly to execvp, including argv[0].
            args = argv("").dropLast(3) + listOf("/bin/sh", "-l"),
            cwd = workDir.absolutePath,
            env = env(mapOf("TERM" to "xterm-256color")).map { (key, value) -> "$key=$value" },
            label = label,
        )
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
        workDir.mkdirs()
        tmpDir.mkdirs()
        // DNS 配置先落盘再跑命令：apk/pip/git 全依赖它；rootfs 不在时 applyTo 自己跳过。
        network.applyTo(installation.rootfs)
        val run = runner.run(argv(command), env(), workDir, timeoutMs)
        if (run.timedOut) {
            return ShellResult(-1, run.stdout, run.stderr.ifBlank { "timeout after ${timeoutMs}ms" })
        }
        return ShellResult(run.code, run.stdout, run.stderr)
    }
}

/** 真进程实现：读满 stdout/stderr，超时 destroyForcibly，返回真实退出码。 */
object RealProotRunner : ProotRunner {

    override fun run(argv: List<String>, env: Map<String, String>, cwd: File?, timeoutMs: Long): ProotRun {
        val builder = ProcessBuilder(argv)
        builder.environment().putAll(env)
        if (cwd != null) builder.directory(cwd)
        builder.redirectErrorStream(false)
        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return ProotRun(-1, "", "cannot start proot: ${t.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = Thread { drain(process.inputStream, out) }
        val errReader = Thread { drain(process.errorStream, err) }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            outReader.join(500)
            errReader.join(500)
            return ProotRun(-1, out.toString(), err.toString(), timedOut = true)
        }
        outReader.join(2_000)
        errReader.join(2_000)
        return ProotRun(process.exitValue(), out.toString(), err.toString())
    }

    /**
     * 读满一路管道。超时 destroyForcibly 时另一线程 close() 会让 read 抛
     * InterruptedIOException——那是超时的正常后果，冒到线程顶层会直接崩掉进程。
     * 已读到的内容照常保留，调用方按 timedOut 分支使用。
     */
    internal fun drain(stream: java.io.InputStream, sink: StringBuilder) {
        try {
            java.io.InputStreamReader(stream, Charsets.UTF_8).use { input ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(sink) { sink.appendRange(buffer, 0, read) }
                }
            }
        } catch (_: java.io.IOException) {
            // 另一端关闭或超时被杀：保留已读部分。
        }
    }
}
