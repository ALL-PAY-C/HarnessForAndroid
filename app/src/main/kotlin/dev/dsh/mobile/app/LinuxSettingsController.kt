package dev.dsh.mobile.app

import android.content.Context
import java.io.File
import java.time.Instant
import dev.dsh.mobile.design.R as DesignR
import kotlinx.coroutines.CancellationException
import dev.dsh.mobile.linux.appendBounded
import dev.dsh.mobile.linux.readTail
import dev.dsh.mobile.linux.cleanupInactive
import dev.dsh.mobile.feature.settings.LinuxSettingsActions
import dev.dsh.mobile.feature.settings.LinuxSettingsState
import dev.dsh.mobile.feature.settings.linuxInstallationLabel
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
        appendLog(context.getString(DesignR.string.linux_log_start, label))
        try {
            val result = action()
            appendLog("$label " + (result.error ?: context.getString(DesignR.string.linux_log_done)) + "\n" + result.health + "\n" + result.checks.joinToString("\n"))
            return result.copy(diagnostics = logTail())
        } catch (cancelled: CancellationException) {
            appendLog(context.getString(DesignR.string.linux_log_cancelled, label))
            throw cancelled
        } catch (error: Exception) {
            appendLog(context.getString(DesignR.string.linux_log_failed_prefix, label) + (error.message ?: error.toString()))
            val snapshot = try { inspectEnvironment() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { LinuxSettingsState() }
            return snapshot.copy(error = error.message ?: error.toString(), diagnostics = logTail())
        }
    }

    override suspend fun inspect() = diagnosed(context.getString(DesignR.string.linux_op_inspect)) { inspectEnvironment() }
    override suspend fun installEnvironment(progress: (String) -> Unit) = diagnosed(context.getString(DesignR.string.linux_op_install_env)) {
        prepareEnvironment { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun repairRuntime() = diagnosed(context.getString(DesignR.string.linux_op_repair_runtime)) {
        val changed = container.linux.repairExecutables()
        inspectEnvironment().let { snapshot ->
            snapshot.copy(message = if (snapshot.error == null) context.getString(DesignR.string.linux_msg_repair_done, changed) else context.getString(DesignR.string.linux_msg_repair_failed, snapshot.error.orEmpty()))
        }
    }
    override suspend fun reinstallRuntime(progress: (String) -> Unit) = diagnosed(context.getString(DesignR.string.linux_op_reinstall_runtime)) {
        progress(context.getString(DesignR.string.linux_progress_remove_runtime))
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
            message = if (error == null) context.getString(DesignR.string.linux_msg_reinstall_done) else context.getString(DesignR.string.linux_msg_reinstall_failed, error),
        )
    }
    override suspend fun installKernel(progress: (String) -> Unit) = diagnosed(context.getString(DesignR.string.linux_op_install_kernel)) {
        prepareKernel { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun selfCheck(progress: (String) -> Unit) = diagnosed(context.getString(DesignR.string.linux_op_selfcheck)) {
        checkEnvironment { detail -> appendLog(detail); progress(detail) }
    }
    override suspend fun cleanup() = diagnosed(context.getString(DesignR.string.linux_op_cleanup)) {
        val result = cleanupInactive(container.linux)
        inspectEnvironment().copy(message = context.getString(DesignR.string.linux_msg_cleanup_done, result.versions, result.bytes))
            .also { appendLog(it.message.orEmpty()) }
    }
    override suspend fun clearDiagnostics(): LinuxSettingsState {
        logFile.parentFile?.mkdirs()
        logFile.writeText("")
        return inspectEnvironment().copy(message = context.getString(DesignR.string.linux_msg_logs_cleared), diagnostics = "")
    }

    private fun installer() = DshKernelInstaller(
        rootfs = checkNotNull(container.linux.active()) { context.getString(DesignR.string.settings_linux_install_first) }.rootfs,
        open = { context.assets.open(it) },
    )

    private suspend fun inspectEnvironment(): LinuxSettingsState {
        val active = container.linux.active()
        val installed = if (active != null) installer().installed() else null
        val channel = container.refreshProotChannel()
        val health = if (channel != null && installed != null) DshKernel(channel, installed).health() else null
        return LinuxSettingsState(
            rootfs = linuxInstallationLabel(active?.version, active?.abi),
            kernel = installed?.let { it.version + " / " + it.entry },
            health = health?.let {
                if (it.ok) context.getString(DesignR.string.settings_linux_health_ok, it.nodeVersion, it.version)
                else context.getString(
                    DesignR.string.settings_linux_health_fail,
                    it.error ?: context.getString(DesignR.string.term_exit_code, it.exitCode),
                )
            } ?: context.getString(DesignR.string.settings_linux_health_unset),
            usageBytes = container.linux.usage().bytes,
            error = health?.takeUnless { it.ok }?.let {
                it.error ?: context.getString(DesignR.string.settings_linux_health_failed_code, it.exitCode)
            },
        )
    }

    private suspend fun prepareEnvironment(progress: (String) -> Unit): LinuxSettingsState {
        progress(context.getString(DesignR.string.linux_progress_manifest))
        val abi = checkNotNull(LinuxAbis.current()) { context.getString(DesignR.string.settings_linux_abi_unsupported) }
        val manifest = checkNotNull(LinuxAssets.forAbi(context.assets, abi)) {
            context.getString(DesignR.string.settings_linux_missing_manifest, abi)
        }
        progress(context.getString(DesignR.string.linux_progress_download))
        when (val result = container.linux.prepare(manifest)) {
            is LinuxResult.Err -> throw IllegalStateException(result.reason, result.cause)
            is LinuxResult.Ok -> Unit
        }
        val channel = checkNotNull(container.refreshProotChannel()) { context.getString(DesignR.string.linux_err_proot_channel) }
        progress(context.getString(DesignR.string.linux_progress_tools))
        // 用户点安装时顺手把工具链装上（同一个 apk 锁，和后台那路串行）；已装齐时脚本秒退。
        val bootstrap = LinuxBootstrap(
            channel,
            toolchain = LinuxToolchainInstaller(
                channel,
                logFile = File(container.linux.baseDir, "toolchain.log"),
            ),
        ).ensure()
        progress(context.getString(DesignR.string.linux_progress_selfcheck))
        val report = LinuxSelfCheck(channel).run()
        val snapshot = inspectEnvironment()
        return snapshot.copy(
            checks = report.checks.map { it.name + ": " + (if (it.ok) "ok · " else context.getString(DesignR.string.linux_check_failed)) + it.detail },
            error = listOfNotNull(
                snapshot.error,
                if (bootstrap.ok) null else bootstrap.error ?: bootstrap.apkOutput.ifBlank { bootstrap.raw }.ifBlank { context.getString(DesignR.string.linux_err_tools) },
                if (report.ok) null else report.raw.ifBlank { context.getString(DesignR.string.linux_err_selfcheck_empty) },
            ).takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    override suspend fun bootstrapEnvironment(progress: (String) -> Unit) = diagnosed(context.getString(DesignR.string.linux_op_bootstrap)) {
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
            checks = report.checks.map { it.name + ": " + (if (it.ok) "ok · " else context.getString(DesignR.string.linux_check_failed)) + it.detail },
            message = report.line(),
            error = if (report.ok) null
            else report.failed().joinToString("; ") { step -> context.getString(DesignR.string.linux_exit_code, step.id, step.rc) },
        )
    }

    private suspend fun prepareKernel(progress: (String) -> Unit): LinuxSettingsState {
        val installer = installer()
        progress(context.getString(DesignR.string.linux_progress_kernel_manifest))
        val manifest = context.assets.open(DshKernelArtifacts.FILE).use {
            checkNotNull(DshKernelArtifacts.parse(it.readBytes().decodeToString())) { context.getString(DesignR.string.linux_err_kernel_manifest) }
        }
        progress(context.getString(DesignR.string.linux_progress_kernel_install))
        when (val result = installer.install(manifest)) {
            is LinuxResult.Err -> throw IllegalStateException(result.reason, result.cause)
            is LinuxResult.Ok -> Unit
        }
        progress(context.getString(DesignR.string.linux_progress_node_verify))
        return inspectEnvironment()
    }

    private suspend fun checkEnvironment(progress: (String) -> Unit): LinuxSettingsState {
        val channel = checkNotNull(container.refreshProotChannel()) { context.getString(DesignR.string.linux_err_need_env) }
        progress(context.getString(DesignR.string.linux_progress_selfcheck))
        val report = LinuxSelfCheck(channel).run()
        val snapshot = inspectEnvironment()
        return snapshot.copy(
            checks = report.checks.map { it.name + ": " + (if (it.ok) "ok · " else context.getString(DesignR.string.linux_check_failed)) + it.detail },
            error = listOfNotNull(snapshot.error, if (report.ok) null else report.raw.ifBlank { context.getString(DesignR.string.linux_err_selfcheck_empty) })
                .takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }
}
