package dev.dsh.mobile.toolbridge

import android.content.Context
import android.content.Intent
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.ProxyEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用自身沙箱内的 shell，任何设备都能用，只能访问应用私有目录。
 *
 * 内置了一份静态 busybox（jniLibs/libbusybox.so，安装时由系统解压成可执行文件）：
 * 这样 grep/sed/awk/find/tar/wget/gzip 这些命令都真的存在，不再需要用户去装 Termux
 * 或者跑到 Termux 里打开 allow-external-apps。系统 sh 仍然作为兜底。
 */
class AppChannel(private val context: Context) : ShellChannel {
    override val id = "app"
    override val label = "应用内 Shell"
    override val kind = ChannelKind.App
    override fun available() = true

    private val busybox: File? by lazy { Busybox.ensure(context) }

    override fun detail(): String =
        if (busybox != null) "内置 busybox，无需 root / 无需 Termux" else "系统 sh，作用域限于应用沙箱"

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
        val bb = busybox
        if (bb != null) {
            val r = Busybox.run(context, bb, command, timeoutMs)
            if (r.code != 127) return r
        }
        return Busybox.systemSh(context, command, timeoutMs)
    }
}

/**
 * 内置 busybox 的落地与执行。跟 Termux 无关，不依赖任何外部应用。
 *
 * 两条原则：① 不往用户能看到的目录写东西（全部在 app 私有目录）；
 * ② applet 软链只装一次，失败就退回系统 sh，不影响可用性。
 */
internal object Busybox {

    /** nativeLibraryDir 里的 libbusybox.so 在安装时已被解压成可执行文件，可直接 exec。 */
    fun ensure(context: Context): File? = runCatching {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val bb = File(dir, "libbusybox.so")
        if (!bb.isFile || bb.length() < 100_000L) return@runCatching null
        if (!bb.canExecute()) bb.setExecutable(true)
        if (!bb.canExecute()) return@runCatching null
        bb
    }.getOrNull()

    /** applet 软链目录；PATH 里放它，用户就能直接写 grep / sed / tar。 */
    private fun binDir(context: Context): File = File(context.filesDir, "bin").apply { mkdirs() }

    private fun home(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

    private fun tmp(context: Context): File = File(context.cacheDir, "tmp").apply { mkdirs() }

    suspend fun run(context: Context, bb: File, command: String, timeoutMs: Long): ShellResult {
        installApplets(bb, binDir(context))
        val home = home(context)
        val env = ProxyEnv.merge(
            mapOf(
                "PATH" to binDir(context).absolutePath + ":/system/bin:/system/xbin:/vendor/bin",
                "HOME" to home.absolutePath,
                "TMPDIR" to tmp(context).absolutePath,
                "SHELL" to "/system/bin/sh",
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
            ),
        )
        return runProcess(listOf(bb.absolutePath, "sh", "-c", command), timeoutMs, home, env)
    }

    suspend fun systemSh(context: Context, command: String, timeoutMs: Long): ShellResult {
        val home = home(context)
        val env = ProxyEnv.merge(
            mapOf(
                "PATH" to binDir(context).absolutePath + ":/system/bin:/system/xbin:/vendor/bin",
                "HOME" to home.absolutePath,
                "TMPDIR" to tmp(context).absolutePath,
            ),
        )
        return runProcess(listOf("sh", "-c", command), timeoutMs, home, env)
    }

    /**
     * 给内置 PTY 终端用：返回可用 busybox 并确保 applet 软链已装好。
     * 交互 shell 里用户会直接敲 grep/sed，PATH 里必须有真命令，所以这里同步装一次。
     */
    suspend fun prepare(context: Context): File? {
        val bb = ensure(context) ?: return null
        runCatching { installApplets(bb, binDir(context)) }
        return bb
    }

    /** 内置终端的 PATH / HOME / TMPDIR 环境（与应用内 Shell 通道保持同一套目录）。 */
    fun shellEnv(context: Context): List<String> {
        val path = listOf(
            binDir(context).absolutePath,
            "/system/bin",
            "/system/xbin",
            "/vendor/bin",
        ).joinToString(":")
        // 内置终端也带代理：用户在终端里敲 curl/git 同样是子进程。
        return ProxyEnv.envList(
            listOf(
                "PATH=" + path,
                "HOME=" + home(context).absolutePath,
                "TMPDIR=" + tmp(context).absolutePath,
                "SHELL=/system/bin/sh",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PWD=" + home(context).absolutePath,
            ),
        )
    }

    @Volatile private var installed = false

    /** busybox --install -s <dir>：把每个 applet 做成软链。只做一次，失败不重试。 */
    private suspend fun installApplets(bb: File, bin: File) {
        if (installed) return
        installed = true
        val marker = File(bin, ".installed")
        if (marker.isFile) return
        val r = runProcess(listOf(bb.absolutePath, "--install", "-s", bin.absolutePath), 15_000)
        if (r.code == 0) runCatching { marker.writeText("1") }
        AppLog.i("busybox applets: code=" + r.code + " dir=" + bin.absolutePath)
    }
}

/** root 通道：su -c。 */
class RootChannel : ShellChannel {
    override val id = "root"
    override val label = "Root (su)"
    override val kind = ChannelKind.Root

    private val cached: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val done = p.waitFor(4, TimeUnit.SECONDS)
            val text = if (done) p.inputStream.bufferedReader().readText() else ""
            if (!done) p.destroy()
            done && p.exitValue() == 0 && text.contains("uid=0")
        }.getOrDefault(false)
    }

    override fun available() = cached
    override fun detail() = if (cached) "已获取 root，可调用系统级命令" else "未检测到 su"
    override suspend fun exec(command: String, timeoutMs: Long) =
        // su 起的是新 shell，拿不到本进程 env，代理只能写进命令串。
        runProcess(listOf("su", "-c", ProxyEnv.prefix(command)), timeoutMs)
}

/**
 * Shizuku 通道：借 Shizuku 的 shell uid 执行命令（免 root 也能 pm/am/settings 等）。
 * 通过反射调用，Shizuku 未安装时不影响应用启动。
 */
class ShizukuChannel : ShellChannel {
    override val id = "shizuku"
    override val label = "Shizuku"
    override val kind = ChannelKind.Shizuku

    private val clazz: Class<*>? by lazy {
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
    }

    fun binderAlive(): Boolean = runCatching {
        val c = clazz ?: return false
        c.getMethod("pingBinder").invoke(null) as Boolean
    }.getOrDefault(false)

    fun permissionGranted(): Boolean = runCatching {
        val c = clazz ?: return false
        val res = c.getMethod("checkSelfPermission").invoke(null) as Int
        res == 0 // PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int = 1001): Boolean = runCatching {
        val c = clazz ?: return false
        c.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, requestCode)
        true
    }.getOrDefault(false)

    override fun available() = binderAlive() && permissionGranted()

    override fun detail() = when {
        clazz == null -> "未集成 Shizuku"
        !binderAlive() -> "Shizuku 服务未运行"
        !permissionGranted() -> "等待 Shizuku 授权"
        else -> "已授权，shell uid 执行"
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
        if (!available()) return ShellResult.unavailable(detail())
        val proc = runCatching {
            val c = clazz!!
            val m = c.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.invoke(null, arrayOf("sh", "-c", ProxyEnv.prefix(command)), null, null) as Process
        }.getOrElse { return ShellResult.unavailable("Shizuku 调用失败: ${it.message}") }
        return drain(proc, timeoutMs)
    }
}

/**
 * Termux 通道：走 RUN_COMMAND 广播，让 Termux 的完整 Linux 环境执行命令
 * （python/pip/git/ffmpeg 等）。输出重定向到共享目录再读回。
 */
class TermuxChannel(private val context: Context) : ShellChannel {
    override val id = "termux"
    override val label = "Termux"
    override val kind = ChannelKind.Termux

    // A5：三文件协议。stdout / stderr 分开落盘，退出码单独写 rc；
    // rc 文件出现即代表命令跑完，不再用「输出非空 = 成功」这种假判定。
    private val outFile = File("/sdcard/Download/dsh-termux-out.txt")
    private val errFile = File("/sdcard/Download/dsh-termux-err.txt")
    private val rcFile = File("/sdcard/Download/dsh-termux-rc.txt")

    private fun installed(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    private fun permitted(): Boolean = runCatching {
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 装了但没授权也算「不可用」：否则 ShellBridge 会选中 Termux，
     * 广播被系统静默丢弃，命令只能等到超时（用户看到的就是「失败 + 空响应」）。
     */
    override fun available() = installed() && permitted()

    // 应用内通道（内置 busybox）已经覆盖日常命令，Termux 只是 python/git/ffmpeg 的可选增强，
    // 界面上不再展示这条可选增强，避免被误读成「缺依赖」。
    override fun detail() = when {
        !installed() -> ""
        !permitted() -> "已安装，未授予 RUN_COMMAND"
        else -> "Termux 环境（python/git/ffmpeg）"
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult {
        if (!installed()) return ShellResult.unavailable(detail())
        if (!permitted()) return ShellResult.unavailable(detail())
        listOf(outFile, errFile, rcFile).forEach { runCatching { it.delete() } }
        // Termux 广播同样不继承 env，代理靠脚本内 export。
        val wrapped = "{ " + ProxyEnv.prefix(command) + " ; } > " + outFile.absolutePath +
            " 2> " + errFile.absolutePath + "; echo \$? > " + rcFile.absolutePath
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapped))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        val sent = runCatching { context.sendBroadcast(intent); true }.getOrDefault(false)
        if (!sent) return ShellResult.unavailable("无法唤起 Termux")
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            // 完成信号 = rc 文件出现。命令里 echo $? 是最后一步，所以 rc 一旦可读，
            // stdout/stderr 也一定已经写完。
            val rc = runCatching {
                if (rcFile.exists() && rcFile.length() > 0) rcFile.readText().trim() else null
            }.getOrNull()
            if (rc != null) {
                val out = runCatching { outFile.readText() }.getOrDefault("")
                val err = runCatching { errFile.readText() }.getOrDefault("")
                return ShellResult(rc.toIntOrNull() ?: -1, out, err)
            }
            // 3 秒连一个文件都没落盘：广播被丢（未授权 / Termux 被系统冻结），直接失败，
            // 不要拖到 30 秒超时——模型和用户都以为在慢慢执行。
            if (System.currentTimeMillis() - started > 3_000L &&
                !outFile.exists() && !errFile.exists() && !rcFile.exists()
            ) {
                return ShellResult.unavailable(
                    "Termux 没有响应 RUN_COMMAND：请在 Termux 里确认已授权，或改用应用内 Shell / Shizuku",
                )
            }
        }
        return ShellResult.unavailable("Termux 执行超时（${timeoutMs}ms），rc 文件 ${rcFile.absolutePath} 未出现")
    }
}

/** 读取进程输出，带超时。 */
internal suspend fun runProcess(
    command: List<String>,
    timeoutMs: Long,
    workdir: File? = null,
    env: Map<String, String>? = null,
): ShellResult =
    withContext(Dispatchers.IO) {
        val proc = runCatching {
            ProcessBuilder(command).redirectErrorStream(false).apply {
                workdir?.let { if (it.isDirectory) directory(it) }
                env?.let { environment().putAll(it) }
            }.start()
        }.getOrElse { return@withContext ShellResult.unavailable("启动失败: ${it.message}") }
        drain(proc, timeoutMs)
    }

internal suspend fun drain(proc: Process, timeoutMs: Long): ShellResult =
    withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val out = async { runCatching { proc.inputStream.readBytes().decodeToString() }.getOrDefault("") }
                val err = async { runCatching { proc.errorStream.readBytes().decodeToString() }.getOrDefault("") }
                val code = async { runCatching { proc.waitFor() }.getOrDefault(-1) }
                Triple(out.await(), err.await(), code.await())
            }
        }
        if (result == null) {
            runCatching { proc.destroyForcibly() }
            AppLog.w("shell timeout after ${timeoutMs}ms")
            ShellResult(-1, "", "命令超时（${timeoutMs}ms）已终止")
        } else {
            ShellResult(result.third, result.first, result.second)
        }
    }
