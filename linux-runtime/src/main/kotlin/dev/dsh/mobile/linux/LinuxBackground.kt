package dev.dsh.mobile.linux

import dev.dsh.mobile.toolbridge.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import java.io.FileFilter

/** shell 里的 `$`：Kotlin 模板里要写成 \$ 才能原样进脚本。 */
private const val D = "\$"

/**
 * R2.4：单次 shell 调用有 30s 上限，长任务必须挪到后台。
 *
 * 三件事：把命令真的 detach 掉（nohup + setsid）、把 pid/日志位置持久化成
 * 私有目录里的一条记录（重启后仍能查）、把日志按增量吐（调用方只拿新内容）。
 *
 * ponytail: 日志与退出码必须落在 /workspace——proot 只把 workDir 绑进 guest，
 * app 私有目录在 guest 里看不见。记录本身（.job）才放私有目录。
 */
data class BgJob(
    val pid: Int,
    val command: String,
    /** 宿主侧路径，用于读日志。 */
    val logPath: String,
    /** 宿主侧路径，用于读退出码。 */
    val rcPath: String,
    val startedAt: Long,
    /** 已经吐给调用方的字节数；持久化后重启也不会重复吐旧内容。 */
    var offset: Long = 0,
)

/** 后台任务状态。exited 的 code 为 null 表示进程没了但没等到退出码（kill -9 / 设备重启）。 */
sealed interface BgStatus {
    object Running : BgStatus
    data class Exited(val code: Int?) : BgStatus
    object Unknown : BgStatus
}

data class BgSnapshot(
    val job: BgJob,
    val status: BgStatus,
    /** 本次新增输出（相对 job.offset），已截到 maxIncrement。 */
    val newOutput: String,
    val truncated: Boolean,
)

fun interface PidProbe {
    fun alive(pid: Int): Boolean
}

private fun signalTerm(pid: Int): Boolean = runCatching {
    ProcessBuilder("kill", "-TERM", pid.toString()).start().waitFor() == 0
}.getOrDefault(false)

class LinuxBackground(
    /** 私有目录里的记录目录：pid → 命令/日志/偏移。 */
    private val jobsDir: File,
    /** 日志与退出码的宿主目录，同时决定 guest 侧路径。 */
    private val logDir: File,
    private val guestLogDir: String = "/workspace/.dsh-bg",
    private val probe: PidProbe = PidProbe { pid -> pid > 0 && File("/proc/$pid").exists() },
    private val killer: (Int) -> Boolean = ::signalTerm,
    private val maxIncrement: Int = MAX_INCREMENT,
    /** exec 超时后轮询 pid 文件的间隔。 */
    private val pollDelayMs: Long = 250,
    /** 最多等 pid 文件这么久；超时就退回 pid=0（Unknown 语义）。 */
    private val pidWaitMs: Long = 4_000,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 用 channel 的 workDir 反推 guest 路径，避免调用方自己拼 /workspace。 */
    companion object {
        const val MAX_INCREMENT = 64 * 1024

        fun inWorkspace(channel: ProotChannel, jobsDir: File): LinuxBackground =
            LinuxBackground(jobsDir, File(channel.workDir, ".dsh-bg"))
    }

    /** 真正把进程放后台：nohup 挡住 SIGHUP，setsid 脱离控制终端，最后把 pid 交回来。 */
    fun launchScript(
        command: String,
        guestLog: String,
        guestRc: String,
        guestPid: String = guestLog.substringBeforeLast('.', guestLog) + ".pid",
    ): String {
        val quoted = command.replace("'", "'\\''")
        // 用字符串切父目录：guestLog 是 guest 侧路径，java.io.File 在宿主 Windows 上会把斜杠改向
        val guestDir = guestLog.substringBeforeLast('/', "/tmp")
        return """
            mkdir -p '$guestDir'
            : > '$guestLog'
            rm -f '$guestRc'
            rm -f '$guestPid'
            nohup setsid sh -c '$quoted; echo ${D}? > $guestRc' > '$guestLog' 2>&1 &
            echo ${D}! > '$guestPid'
            echo ${D}!
        """.trimIndent()
    }

    fun parsePid(stdout: String): Int? =
        stdout.lineSequence().map { it.trim() }.lastOrNull { it.toIntOrNull() != null }?.toInt()

    /**
     * 起一个后台任务；pid 解析不出来时返回 pid=0 的 job（status 会报 unknown）。
     *
     * ponytail: proot 的 loader 进程 ptrace 着 guest 里所有子孙，`echo $!` 那个 exec 会话
     * 不会像普通 shell 那样立刻 EOF——它会一直挂到被 detach 的任务本身退出。所以 exec 超时
     * 是正常现象（任务已经起来了），pid 改为从脚本写下的 pid 文件捡回。
     */
    suspend fun run(
        exec: suspend (String) -> ShellResult,
        command: String,
        name: String? = null,
    ): BgJob {
        jobsDir.mkdirs()
        logDir.mkdirs()
        val startedAt = now()
        val base = name ?: "bg-$startedAt"
        val hostLog = File(logDir, "$base.log")
        val hostRc = File(logDir, "$base.rc")
        val hostPid = File(logDir, "$base.pid")
        val guestLog = "$guestLogDir/$base.log"
        val guestRc = "$guestLogDir/$base.rc"
        val guestPid = "$guestLogDir/$base.pid"
        // 超时/异常都吞掉：任务已经起起来了，返回值不再是 pid 的可靠来源；取消要原样抛出去。
        val out = try {
            Result.success(exec(launchScript(command, guestLog, guestRc, guestPid)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        val pid = parsePid(out.getOrNull()?.stdout ?: "") ?: awaitPid(hostPid) ?: 0
        val job = BgJob(pid, command, hostLog.absolutePath, hostRc.absolutePath, startedAt)
        if (job.pid > 0) save(job)
        return job
    }

    /** 等 guest 侧 pid 文件落盘：exec 超时后只能从文件里捡 pid。 */
    private suspend fun awaitPid(file: File): Int? {
        var waited = 0L
        while (true) {
            val pid = runCatching { file.readText().trim().toIntOrNull() }.getOrNull()
            if (pid != null && pid > 0) return pid
            if (waited >= pidWaitMs) return null
            delay(pollDelayMs)
            waited += pollDelayMs
        }
    }

    fun status(job: BgJob): BgSnapshot {
        val status = when {
            job.pid <= 0 -> BgStatus.Unknown
            probe.alive(job.pid) -> BgStatus.Running
            else -> exitCode(job)
        }
        val before = job.offset
        val chunk = readIncrement(job)
        if (job.pid > 0 && job.offset != before) save(job)
        return BgSnapshot(job, status, chunk.first, chunk.second)
    }

    fun kill(job: BgJob): Boolean = job.pid > 0 && killer(job.pid)

    private fun exitCode(job: BgJob): BgStatus {
        val rc = File(job.rcPath)
        if (!rc.isFile) return BgStatus.Unknown
        return BgStatus.Exited(rc.readText().trim().toIntOrNull())
    }

    /** 只返回 offset 之后的新字节；日志被截断就从头读，不让调用方卡在旧偏移上。 */
    private fun readIncrement(job: BgJob): Pair<String, Boolean> {
        val file = File(job.logPath)
        if (!file.isFile) return "" to false
        val length = file.length()
        if (length < job.offset) job.offset = 0
        if (length <= job.offset) return "" to false
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(job.offset)
            val want = minOf(length - job.offset, maxIncrement.toLong()).toInt()
            val buf = ByteArray(want)
            val read = raf.read(buf)
            if (read <= 0) return "" to false
            job.offset += read
            return String(buf, 0, read, Charsets.UTF_8) to (read < length - (job.offset - read))
        }
    }

    fun save(job: BgJob) {
        jobsDir.mkdirs()
        val target = File(jobsDir, "${job.pid}.job")
        val tmp = File(jobsDir, "${job.pid}.job.tmp")
        tmp.writeText(encode(job))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun load(pid: Int): BgJob? = File(jobsDir, "$pid.job").takeIf { it.isFile }?.let { decode(it.readText()) }

    fun list(): List<BgJob> =
        jobsDir.listFiles(FileFilter { it.name.endsWith(".job") })?.mapNotNull { decode(it.readText()) }
            ?.sortedBy { it.startedAt } ?: emptyList()

    /** 一行一个字段，只转义反斜杠与换行：命令里带引号、空格都不用特殊处理。 */
    fun encode(job: BgJob): String = buildString {
        append("pid=").append(job.pid).append('\n')
        append("started=").append(job.startedAt).append('\n')
        append("offset=").append(job.offset).append('\n')
        append("log=").append(escape(job.logPath)).append('\n')
        append("rc=").append(escape(job.rcPath)).append('\n')
        append("cmd=").append(escape(job.command)).append('\n')
    }

    fun decode(text: String): BgJob? {
        val fields = text.lineSequence().filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to unescape(line.substring(at + 1))
            }.toMap()
        val pid = fields["pid"]?.toIntOrNull() ?: return null
        return BgJob(
            pid = pid,
            command = fields["cmd"] ?: "",
            logPath = fields["log"] ?: "",
            rcPath = fields["rc"] ?: "",
            startedAt = fields["started"]?.toLongOrNull() ?: 0L,
            offset = fields["offset"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> out.append('\n')
                    else -> out.append(raw[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
