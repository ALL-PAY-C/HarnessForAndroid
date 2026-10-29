package dev.dsh.mobile.app

import android.content.Context
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import dev.dsh.mobile.linux.appendBounded
import dev.dsh.mobile.linux.readTail
import dev.dsh.mobile.linux.cleanupInactive
import dev.dsh.mobile.feature.settings.LinuxSettingsActions
import dev.dsh.mobile.feature.settings.LinuxSettingsState
import dev.dsh.mobile.feature.settings.linuxInstallationLabel
import dev.dsh.mobile.feature.settings.missingLinuxManifest
import dev.dsh.mobile.linux.LinuxAbis
import dev.dsh.mobile.linux.LinuxAssets
import dev.dsh.mobile.linux.LinuxBootstrap
import dev.dsh.mobile.linux.LinuxEnvBootstrap
import dev.dsh.mobile.linux.LinuxEnvBootstrapRunner
import dev.dsh.mobile.linux.LinuxResult
import dev.dsh.mobile.linux.LinuxSelfCheck
import dev.dsh.mobile.linux.LinuxToolchainInstaller
import dev.dsh.mobile.linux.kernel.DshKernel
import dev.dsh.mobile.linux.kernel.DshKernelArtifacts
import dev.dsh.mobile.linux.kernel.DshKernelInstaller

/** Called on Dispatchers.IO by SettingsViewModel; no UI or device-global state. */
class LinuxSettingsController(
    private val context: Context,
    private val container: AppContainer,
) : LinuxSettingsActions {
    private val logFile get() = File(container.linux.baseDir, "diagnostics.log")

    private fun appendLog(detail: String) {
        logFile.parentFile?.mkdirs()
        val existing = if (logFile.isFile) logFile.readText() else ""
        val timestamp = Instant.now().toString()
        logFile.writeText(appendBounded(existing, detail.lineSequence().joinToString("\n") { "$timestamp $it" }))
    }

    private fun logTail(): String = if (logFile.isFile) readTail(logFile.readText()) else ""

    private suspend fun diagnosed(label: String, action: suspend () -> LinuxSettingsState): LinuxSettingsState {
        appendLog("$label 开始")
        try {
            val result = action()
            appendLog("$label " + (result.error ?: "完成") + "\n" + result.health + "\n" + result.checks.joinToString("\n"))
            return result.copy(diagnostics = logTail())
        } catch (cancelled: CancellationException) {
            appendLog("$label 已取消")
            throw cancelled
        } catch (error: Exception) {
            appendLog("$label 失败：" + (error.message ?: error.toString()))
            val snapshot = try { inspectEnvironment() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { LinuxSettingsState() }
            return snapshot.copy(error = error.message ?: error.toString(), diagnostics = logTail())
        }
    }

    override suspend fun inspect() = diagnosed("环境状态 / 内核健康") { inspectEnvironment() }
    override suspend fun installEnvironment(progress: (String) -> Unit) = diagnosed("安装 / 修复 Linux") {
        prepareEnvironment { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun repairRuntime() = diagnosed("修复运行时") {
        val changed = container.linux.repairExecutables()
        inspectEnvironment().let { snapshot ->
            snapshot.copy(message = if (snapshot.error == null) "运行时修复完成，已补齐 $changed 项执行权限" else "运行时修复后健康检查失败：${snapshot.error}")
        }
    }
    override suspend fun reinstallRuntime(progress: (String) -> Unit) = diagnosed("重装运行时") {
        progress("删除当前 Linux 运行时")
        container.linux.invalidateActive()
        val environment = prepareEnvironment { detail -> appendLog(detail); progress(detail) }
        // bootstrap 的失败保留在状态中；APK 内核与 Node 离线安装不依赖 apk/DNS 成功。
        val kernel = try {
            prepareKernel { detail -> appendLog(detail); progress(detail) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            environment.copy(error = error.message ?: error.toString())
        }
        val error = listOfNotNull(environment.error, kernel.error).takeIf { it.isNotEmpty() }?.joinToString("\n")
        kernel.copy(
            checks = environment.checks, error = error,
            message = if (error == null) "Linux 运行时重装完成" else "Linux 运行时重装后检查失败：$error",
        )
    }
    override suspend fun installKernel(progress: (String) -> Unit) = diagnosed("安装 / 修复 DSH 内核") {
        prepareKernel { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun selfCheck(progress: (String) -> Unit) = diagnosed("Linux 自检") {
        checkEnvironment { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun cleanup() = diagnosed("清理非激活版本") {
        val result = cleanupInactive(container.linux)
        inspectEnvironment().copy(message = "已清理 " + result.versions + " 个非激活版本，释放 " + result.bytes + " 字节")
            .also { appendLog(it.message.orEmpty()) }
    }
    override suspend fun clearDiagnostics(): LinuxSettingsState {
        logFile.parentFile?.mkdirs()
        logFile.writeText("")
        return inspectEnvironment().copy(message = "诊断日志已清空", diagnostics = "")
    }

    private fun installer() = DshKernelInstaller(
        rootfs = checkNotNull(container.linux.active()) { "请先安装 Linux 环境" }.rootfs,
        open = { context.assets.open(it) },
    )

    private suspend fun inspectEnvironment(): LinuxSettingsState {
        val active = container.linux.active()
        val installed = if (active != null) installer().installed() else null
        val channel = container.refreshProotChannel()
        val health = if (channel != null && installed != null) DshKernel(channel, installed).health() else null
        return LinuxSettingsState(
            rootfs = linuxInstallationLabel(active?.version, active?.abi),
            kernel = installed?.let { it.version + " / " + it.entry } ?: "未安装",
            health = health?.let {
                if (it.ok) "Node " + it.nodeVersion + " / 内核 " + it.version + " · 正常"
                else "健康检查失败：" + (it.error ?: "退出码 " + it.exitCode)
            } ?: "Node / 内核尚未安装或自检",
            usageBytes = container.linux.usage().bytes,
            error = health?.takeUnless { it.ok }?.let { it.error ?: "内核健康检查失败：" + it.exitCode },
        )
    }

    private suspend fun prepareEnvironment(progress: (String) -> Unit): LinuxSettingsState {
        progress("读取 Linux 制品清单")
        val abi = checkNotNull(LinuxAbis.current()) { "当前 ABI 不支持 Linux 环境" }
        val manifest = checkNotNull(LinuxAssets.forAbi(context.assets, abi)) { missingLinuxManifest(abi) }
        progress("下载、校验并解包 rootfs")
        when (val result = container.linux.prepare(manifest)) {
            is LinuxResult.Err -> throw IllegalStateException(result.reason, result.cause)
            is LinuxResult.Ok -> Unit
        }
        val channel = checkNotNull(container.refreshProotChannel()) { "rootfs 已安装，但 proot 通道注册失败" }
        progress("预置 Linux 工具（首次可能需要数分钟）")
        // 用户点安装时顺手把工具链装上（同一个 apk 锁，和后台那路串行）；已装齐时脚本秒退。
        val bootstrap = LinuxBootstrap(
            channel,
            toolchain = LinuxToolchainInstaller(
                channel,
                logFile = File(container.linux.baseDir, "toolchain.log"),
            ),
        ).ensure()
        progress("运行 Linux 自检")
        val report = LinuxSelfCheck(channel).run()
        val snapshot = inspectEnvironment()
        return snapshot.copy(
            checks = report.checks.map { it.name + "：" + (if (it.ok) "ok · " else "失败 · ") + it.detail },
            error = listOfNotNull(
                snapshot.error,
                if (bootstrap.ok) null else bootstrap.error ?: bootstrap.apkOutput.ifBlank { bootstrap.raw }.ifBlank { "Linux 工具预置失败" },
                if (report.ok) null else report.raw.ifBlank { "Linux 自检未返回检查项" },
            ).takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    override suspend fun bootstrapEnvironment(progress: (String) -> Unit) = diagnosed("补齐环境依赖") {
        val bridge = container.shell
        val runner = LinuxEnvBootstrapRunner(
            channelFor = { group ->
                when (group) {
                    // Termux 走广播执行（pkg 在 Termux 侧）；proot 侧走 proot 通道。
                    LinuxEnvBootstrap.Group.Termux -> bridge.byId("termux")
                    LinuxEnvBootstrap.Group.Proot -> container.refreshProotChannel()
                }
            },
            logFile = File(container.linux.baseDir, "env-bootstrap.log"),
        )
        val report = runner.run(progress = progress)
        val snapshot = inspectEnvironment()
        snapshot.copy(
            checks = report.checks.map { it.name + "：" + (if (it.ok) "ok · " else "失败 · ") + it.detail },
            message = report.line(),
            error = if (report.ok) null
            else report.failed().joinToString("；") { step -> step.id + " 退出码 " + step.rc },
        )
    }

    private suspend fun prepareKernel(progress: (String) -> Unit): LinuxSettingsState {
        val installer = installer()
        progress("读取 DSH 内核清单")
        val manifest = context.assets.open(DshKernelArtifacts.FILE).use {
            checkNotNull(DshKernelArtifacts.parse(it.readBytes().decodeToString())) { "DSH 内核清单缺失必填字段" }
        }
        progress("校验并安装 DSH 内核与 Node")
        when (val result = installer.install(manifest)) {
            is LinuxResult.Err -> throw IllegalStateException(result.reason, result.cause)
            is LinuxResult.Ok -> Unit
        }
        progress("验证 Node 与内核版本")
        return inspectEnvironment()
    }

    private suspend fun checkEnvironment(progress: (String) -> Unit): LinuxSettingsState {
        val channel = checkNotNull(container.refreshProotChannel()) { "请先安装 Linux 环境" }
        progress("运行 Linux 自检")
        val report = LinuxSelfCheck(channel).run()
        val snapshot = inspectEnvironment()
        return snapshot.copy(
            checks = report.checks.map { it.name + "：" + (if (it.ok) "ok · " else "失败 · ") + it.detail },
            error = listOfNotNull(snapshot.error, if (report.ok) null else report.raw.ifBlank { "Linux 自检未返回检查项" })
                .takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }
}
