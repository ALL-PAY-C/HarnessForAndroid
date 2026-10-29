package dev.dsh.mobile.app

import dev.dsh.mobile.core.protocol.bool
import dev.dsh.mobile.core.protocol.long
import dev.dsh.mobile.core.protocol.str
import dev.dsh.mobile.linux.BgJob
import dev.dsh.mobile.linux.BgStatus
import dev.dsh.mobile.linux.LinuxBackground
import dev.dsh.mobile.linux.ProotChannel
import dev.dsh.mobile.toolbridge.FileBridge
import dev.dsh.mobile.toolbridge.FsCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** shell 工具的默认超时：与 ShellBridge 默认值一致；更长的命令请走 run_bg。 */
internal const val TOOL_SHELL_TIMEOUT_MS = 30_000L

/**
 * fs_* 的四个真实操作。生产实现转发 [FileBridge]；单测换成 lambda 直接钉住参数映射，
 * 不需要 Context，也不碰真实文件系统。
 */
class FileOps(
    val read: suspend (path: String, offset: Long?, limit: Int?) -> String,
    val write: suspend (path: String, content: String, append: Boolean) -> String,
    val list: suspend (path: String, recursive: Boolean) -> String,
    val op: suspend (op: String, src: String, dst: String?) -> String,
) {
    companion object {
        /** 生产形态：FileBridge 是阻塞 io，全部挪到 IO dispatcher；它返回的已经是 JSON，原样透传。 */
        fun of(bridge: FileBridge): FileOps = FileOps(
            read = { path, offset, limit -> withContext(Dispatchers.IO) { bridge.read(path, offset, limit) } },
            write = { path, content, append -> withContext(Dispatchers.IO) { bridge.write(path, content, append) } },
            list = { path, recursive -> withContext(Dispatchers.IO) { bridge.list(path, recursive) } },
            op = { op, src, dst -> withContext(Dispatchers.IO) { bridge.op(op, src, dst) } },
        )
    }
}

/** 一次后台查询的结果：模型只需要状态串 + 增量输出，不需要认识 sealed 类型。 */
data class BgPoll(val pid: Int, val state: String, val output: String, val truncated: Boolean)

/** 后台任务的操作面。生产是 [ProotBackgroundJobs]，单测给桩。 */
interface BackgroundJobs {
    suspend fun start(command: String, name: String?): Int

    /** key 可以是 pid，也可以是启动时给的名字。 */
    fun poll(key: String): BgPoll?

    fun kill(key: String): Boolean
}

/**
 * [LinuxBackground] 的生产实现。
 *
 * 日志与退出码必须落在 channel.workDir/.dsh-bg：proot 只把 workDir 绑到 guest 的 /workspace，
 * 写到别处的文件 guest 既看不见也读不回来。所以模型给的 logfile 只当「名字」用
 * （去掉目录与扩展名），真实路径由 LinuxBackground 决定。
 */
class ProotBackgroundJobs(private val channel: ProotChannel, jobsDir: File) : BackgroundJobs {

    private val bg = LinuxBackground.inWorkspace(channel, jobsDir)

    override suspend fun start(command: String, name: String?): Int =
        bg.run(exec = { channel.exec(it, BG_TIMEOUT_MS) }, command = command, name = sanitize(name)).pid

    override fun poll(key: String): BgPoll? = job(key)?.let { found ->
        val snapshot = bg.status(found)
        BgPoll(found.pid, state(snapshot.status), snapshot.newOutput, snapshot.truncated)
    }

    override fun kill(key: String): Boolean = job(key)?.let { bg.kill(it) } == true

    private fun job(key: String): BgJob? =
        key.trim().toIntOrNull()?.takeIf { it > 0 }?.let { bg.load(it) }
            ?: sanitize(key)?.let { name -> bg.list().firstOrNull { File(it.logPath).nameWithoutExtension == name } }

    private fun state(status: BgStatus): String = when (status) {
        BgStatus.Running -> "running"
        is BgStatus.Exited -> status.code?.let { "exited($it)" } ?: "exited"
        BgStatus.Unknown -> "unknown"
    }

    companion object {
        /**
         * proot 的 exec 会话不会及时 EOF（loader ptrace 着 guest 子孙，nohup+setsid detach 也一样），
         * 超时属正常：任务已经起来，pid 由 pid 文件取回，这里只要一个短窗口。
         */
        const val BG_TIMEOUT_MS = 5_000L

        /** logfile 只取末段名字：guest 里没有宿主绝对路径这回事。 */
        fun sanitize(name: String?): String? =
            name?.trim()?.substringAfterLast('/')?.substringBeforeLast('.')
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.take(40)?.takeIf { it.isNotBlank() }
    }
}

/**
 * 把 app 侧能力拼成 core/protocol 需要的四个 lambda（D3 一行塞进 LocalEngineTransport）。
 *
 * 只做参数映射与 JSON 透传：白名单、错误码、超时都是 toolbridge / linux-runtime 的事，
 * 这里再校验一遍就是第二份真相。所有失败都返回 `{"ok":false,...}` 而不是抛异常 ——
 * 抛异常到 UI 上只剩一个「失败」和空响应，模型没法自救。
 */
class ToolExecutors(
    private val files: FileOps,
    private val shell: suspend (command: String, channel: String?) -> String,
    private val channels: suspend () -> String,
    private val background: suspend () -> BackgroundJobs?,
) {

    /** fs_read / fs_write / fs_list / fs_op：参数名与工具描述一致，缺参数直接给可读错误。 */
    suspend fun fsExec(tool: String, args: JsonObject): String {
        return try {
            when (tool) {
                // 函数类型的值只能用位置参数调用，所以这里一路位置传参。
                "fs_read" -> files.read(args.path(), args.long("offset"), args.int("limit"))
                "fs_write" -> files.write(
                    args.path(),
                    // content 允许空串（写空文件是合法操作），所以只判 null。
                    args.str("content") ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 content 参数"),
                    args.bool("append") ?: false,
                )
                "fs_list" -> files.list(args.path(), args.bool("recursive") ?: false)
                "fs_op" -> files.op(
                    args.str("op")?.takeIf { it.isNotBlank() }
                        ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 op 参数"),
                    args.str("src")?.takeIf { it.isNotBlank() }
                        ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 src 参数"),
                    args.str("dst")?.takeIf { it.isNotBlank() },
                )
                else -> fail(FsCode.UNSUPPORTED_OP, "未知文件工具：$tool")
            }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(FsCode.INVALID_ARGUMENT, "参数无效：" + (error.message ?: error.toString()))
        }
    }

    /** shell 工具的可选通道版本；通道名字认不出来时由 ShellBridge 回落自动顺序。 */
    suspend fun shellExecChannel(command: String, channel: String?): String = shell(command, channel)

    suspend fun channelListExec(): String = channels()

    /** run_bg / bg_status / bg_kill：包 [LinuxBackground]，没有 proot 就没有后台任务。 */
    suspend fun bgExec(tool: String, args: JsonObject): String {
        val jobs = background() ?: return fail(FsCode.UNSUPPORTED_OP, "Linux 环境未安装：后台任务只能跑在 proot 里")
        return try {
            when (tool) {
                "run_bg" -> {
                    val command = args.str("command")?.takeIf { it.isNotBlank() }
                        ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 command 参数")
                    val pid = jobs.start(command, args.str("logfile"))
                    if (pid <= 0) {
                        fail(FsCode.IO_ERROR, "后台进程没有返回 pid（命令可能立刻失败了），请先在前台跑一次看错误")
                    } else {
                        buildJsonObject {
                            put("ok", true)
                            put("pid", pid)
                            put("hint", "用 bg_status 查进度，pid=" + pid)
                        }.toString()
                    }
                }
                "bg_status" -> {
                    val key = args.jobKey() ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 pid 参数")
                    val poll = jobs.poll(key) ?: return notFound(key)
                    buildJsonObject {
                        put("ok", true)
                        put("pid", poll.pid)
                        put("status", poll.state)
                        put("output", poll.output)
                        put("truncated", poll.truncated)
                    }.toString()
                }
                "bg_kill" -> {
                    val key = args.jobKey() ?: return fail(FsCode.INVALID_ARGUMENT, "缺少 pid 参数")
                    if (!jobs.kill(key)) notFound(key) else buildJsonObject {
                        put("ok", true)
                        put("killed", key)
                    }.toString()
                }
                else -> fail(FsCode.UNSUPPORTED_OP, "未知后台工具：$tool")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(FsCode.IO_ERROR, error.message ?: error.toString())
        }
    }

    private fun JsonObject.path(): String =
        str("path")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("缺少 path 参数")

    /** pid 可以是数字也可以是字符串；只给 logfile 时交给后端按名字找。 */
    private fun JsonObject.jobKey(): String? =
        str("pid")?.takeIf { it.isNotBlank() } ?: str("logfile")?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        long(key)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    private fun fail(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("code", code)
        put("error", message)
    }.toString()

    private fun notFound(key: String): String =
        fail(FsCode.NOT_FOUND, "没有这个后台任务，或它已经跑完被清理了：$key")
}
