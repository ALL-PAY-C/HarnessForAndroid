package dev.dsh.mobile.app

import android.app.Application
import android.os.Build
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.noderuntime.NodeRuntime
import dev.dsh.mobile.render.RichContentInstaller

class DshApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 引擎跑在 :node 子进程里（NodeEngineService），那边只需要 Service 自己的 Context。
        // Application 是进程无关的，不在主进程就早退，免得 :node 里白建一份 AppContainer
        // （DB、工具链、网络全都会重复初始化一遍）。
        if (processName(this) != packageName) {
            AppLog.i("DshApplication skip, process=" + processName(this))
            return
        }
        // 富内容渲染槽：:render 的实现注入 plugin-api，plugin-host 只持接口（T1.2 分层）。
        // 必须在任何插件 UI 上屏之前装好，否则 markdown 会先画成纯文本再重组。
        RichContentInstaller.install()
        container = AppContainer(this)
        // abi 以前由已删除的进程内运行时宿主提供：直接用系统 ABI，只用于日志。
        AppLog.i("DshApplication start, abi=" + (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"))
        // Native UI must never wait on the runtime: kick the connection off in the background.
        container.connectActive()
        // 工具链（bash/jq/ffmpeg）在后台预装：冷启动不等它，幂等脚本已装齐就不联网。
        container.kickOffToolchain()
        runNodeSelfTest()
    }

    /** 当前进程名。API 28 才有 Application.getProcessName()，26/27 读 /proc/self/cmdline。 */
    private fun processName(context: Application): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching {
            java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ')
        }.getOrDefault(context.packageName)

    /**
     * 仅 debug：验证进程内 libnode.so 真能跑起来。
     * node 的 stdout/stderr 在 node_starter.cpp 里被重定向进 logcat(tag=DshMobile)，
     * 所以 adb logcat -s DshMobile:* 能直接看到 node 自己 console.log 的内容。
     */
    private fun runNodeSelfTest() {
        if (!BuildConfig.DEBUG) return
        // libnode 同进程只能启动一次：自检会把这唯一一次启动用掉，而技能中心/插件页才是
        // 真正要跑 node 的地方。所以自检改成 opt-in —— 只有显式放了 node-selftest.enable
        // 才跑（adb shell run-as <pkg> touch files/node-selftest.enable）。
        if (!java.io.File(filesDir, "node-selftest.enable").exists()) return
        NodeRuntime.install(this)
        AppLog.i("node self-test: available=${NodeRuntime.isAvailable()}")
        // 除了 NODE_OK，还验一遍 fs / path / process.chdir —— 插件真正落地执行时要靠这些。
        val dir = filesDir.absolutePath
        val script = "const fs=require('fs'),path=require('path');" +
            "console.log('NODE_OK '+process.version);" +
            "process.chdir('" + dir + "');" +
            "const p=path.join('" + dir + "','node-selftest.txt');" +
            "fs.writeFileSync(p,'ok '+process.version);" +
            "console.log('NODE_FS_OK '+fs.readFileSync(p,'utf8')+' cwd='+process.cwd());"
        Thread({
            NodeRuntime.start(listOf("node", "-e", script))
                .onSuccess { AppLog.i("node self-test exit=$it") }
                .onFailure { AppLog.e("node self-test failed: ${it.message}", it) }
        }, "node-selftest").start()
    }
}
