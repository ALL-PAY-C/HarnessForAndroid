package dev.dsh.mobile.app

import dev.dsh.mobile.linux.BgStatus
import dev.dsh.mobile.linux.LinuxBackground
import dev.dsh.mobile.linux.LinuxToolchain
import dev.dsh.mobile.linux.LinuxToolchainInstaller
import dev.dsh.mobile.linux.ProotChannel
import kotlinx.coroutines.delay
import java.io.File

/**
 * 工具链（bash/jq/ffmpeg/...）后台预装。
 *
 * 为什么不在启动路径上同步装：provisionScript 要下几十 MB 的 apk 包，前台跑会把冷启动拖到
 * 分钟级。这里只把命令交给 [LinuxBackground]，pid/日志落 channel.workDir/.dsh-bg，
 * 然后按固定间隔轮询 status（增量读日志）。进程重启后 bg.load(pid) 仍能接着查。
 *
 * 幂等：脚本自己用 apk info -e 判断，包齐了直接退出、不联网；所以每次冷启动都可以放心跑一遍。
 */
class ToolchainProvisioner(
    private val channel: ProotChannel,
    private val jobsDir: File,
    private val logFile: File,
    private val pollEveryMs: Long = 5_000,
    private val maxWaitMs: Long = 20 * 60_000,
) {

    /** 起后台预装并等到结束（或超时）。返回一句人话，供 AppLog / 诊断里看。 */
    suspend fun provision(): String {
        // 先选镜像：顺带把 etc/apk/repositories 写好，任一步失败都回落官方源，不影响下面的安装。
        runCatching { LinuxToolchainInstaller(channel, logFile = logFile).applyMirror() }
        val background = LinuxBackground.inWorkspace(channel, jobsDir)
        val job = background.run(
            exec = { channel.exec(it, ProotBackgroundJobs.BG_TIMEOUT_MS) },
            command = LinuxToolchain.provisionScript(),
            name = "toolchain",
        )
        if (job.pid <= 0) return "工具链后台任务没返回 pid（provision 没能启动）"

        val deadline = System.currentTimeMillis() + maxWaitMs
        var snapshot = background.status(job)
        while (snapshot.status == BgStatus.Running && System.currentTimeMillis() < deadline) {
            delay(pollEveryMs)
            snapshot = background.status(job)
        }

        // 后台日志落在 channel.workDir/.dsh-bg；留一份到私有目录，设置页「清日志」不会碰它，
        // 但排查「为什么没装上」时能直接读。
        runCatching {
            val full = File(job.logPath).takeIf { it.isFile }?.readText().orEmpty()
            logFile.parentFile?.mkdirs()
            // parse 内部已把 apk 输出过 LinuxApkLog：落盘的文本不含那两条已知无害的 rename 行，
            // 真实错误原样保留。
            logFile.writeText(LinuxToolchain.parse(full).raw)
        }

        return "工具链预装 " + when (val status = snapshot.status) {
            is BgStatus.Exited -> status.code?.let { "结束，退出码 $it" } ?: "结束，没拿到退出码"
            BgStatus.Running -> "仍在后台跑（等满 " + maxWaitMs / 60_000 + " 分钟），pid=" + job.pid
            BgStatus.Unknown -> "状态未知（进程已不在且没有退出码）"
        }
    }
}
