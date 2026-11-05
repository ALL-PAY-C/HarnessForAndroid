package dev.dsh.mobile.app

import android.app.Application
import android.content.Context
import android.os.Build
import dev.dsh.mobile.app.diag.AppBootGuard
import dev.dsh.mobile.app.diag.BootGuardStore
import dev.dsh.mobile.app.diag.CrashReporter
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.AppScope
import dev.dsh.mobile.core.platform.UiLocale
import dev.dsh.mobile.core.data.DataModule
import dev.dsh.mobile.noderuntime.NodeRuntime
import dev.dsh.mobile.render.RichContentInstaller

class DshApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 语言必须在这里锁存并包装（T9.11 §2.2 第一段）：SettingsStore 的 DataStore 是异步读，
     * 等它回来再设置语言 = 冷启动第一帧先画中文再跳英文。prefs 镜像由 SettingsStore.setUiLanguage 维护。
     * :node 子进程也会走一次，只读一个 SharedPreferences，无副作用。
     */
    override fun attachBaseContext(base: Context) {
        UiLocale.latch(base)
        super.attachBaseContext(UiLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 引擎跑在 :node 子进程里（NodeEngineService），那边只需要 Service 自己的 Context。
        // Application 是进程无关的，不在主进程就早退，免得 :node 里白建一份 AppContainer
        // （DB、工具链、网络全都会重复初始化一遍）。
        if (processName(this) != packageName) {
            AppLog.i("DshApplication skip, process=" + processName(this))
            return
        }
        // 崩溃落盘必须早于任何可能抛异常的初始化（T9.12 §2.1）：AppContainer 构造、DB 打开、
        // 插件注册都会炸，handler 那时还没装的话，那次崩溃只剩 logcat，下次启动拿不到任何物证。
        // 也必须在进程闸门之后装：:node 里再装一份只会让同一个崩溃被记两次或记成别的进程。
        val bootStore = BootGuardStore.from(this)
        val crashReporter = CrashReporter.install(this, bootStore, AppScope.scope)
        // 步骤 3：守卫要读写设置层（快照 / 回滚 / 上传开关），这两样必须在 onApplicationCreate 之前就位，
        // 否则 t0 的裁决只能打日志，回滚与上传永远没有落地对象。
        val bootGuard = AppBootGuard(
            store = bootStore,
            crashStore = crashReporter.store,
            scope = AppScope.scope,
            versionCode = versionCode(),
            settings = DataModule.createSettings(this),
            lkgDir = java.io.File(filesDir, "lkg"),
            // container 在下一行才建：首帧时它一定已就位，不需要为了这个回调去提前构造。
            onBootOk = { if (this::container.isInitialized) container.pluginHost.markBootOk() },
        )
        bootGuard.onApplicationCreate()
        // 富内容渲染槽：:render 的实现注入 plugin-api，plugin-host 只持接口（T1.2 分层）。
        // 必须在任何插件 UI 上屏之前装好，否则 markdown 会先画成纯文本再重组。
        val t0 = android.os.SystemClock.uptimeMillis()
        RichContentInstaller.install()
        container = AppContainer(this, crashReporter, bootGuard)
        // engineReady 只认 RuntimeState.Ready：不订阅 runtime 的话 onBootOk 之后永远写不出 LKG 快照。
        bootGuard.bindRuntime(container.runtime)
        // 重启入口的宿主实现：core:platform 的 restartApp 只是一个槽位，真正的软/硬重启在这里接上。
        // 装得越早越好——安全模式横幅上的「退出并重启」首帧就能被点到。
        RestartController.install(this)
        AppLog.i("AppContainer built in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms")
        // abi 以前由已删除的进程内运行时宿主提供：直接用系统 ABI，只用于日志。
        AppLog.i("DshApplication start, abi=" + (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"))
        // Native UI must never wait on the runtime: kick the connection off in the background.
        // 安全模式下不自动连引擎（坏配置多半就死在这一步），UI 里仍可手动启动。
        if (!bootGuard.safeModeController.flags().disableAutoEngine) container.connectActive()
        // 工具链（bash/jq/ffmpeg）在后台预装：冷启动不等它，幂等脚本已装齐就不联网。
        container.kickOffToolchain()
        runNodeSelfTest()
        triggerCrashSelfTest()
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

    /** 版本号只进崩溃记录与启动账本，取不到就 0（不能因为读包信息抛异常就把启动拖下水）。 */
    @Suppress("DEPRECATION")
    private fun versionCode(): Int = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    /**
     * 仅 debug：崩溃落盘自检。
     * `adb shell am crash <pkg>` 是 AMS 直接杀进程，根本不进 Java 未捕获异常处理器，
     * 拿它验落盘只会得到「目录为空」的假阴性。所以改走文件开关（同 node-selftest.enable）：
     * adb shell run-as <pkg> touch files/crash-self-test.trigger → 下次启动在主线程抛一次。
     * message 里故意带假 sk- token 与绝对路径，顺带验证脱敏。
     */
    private fun triggerCrashSelfTest() {
        if (!BuildConfig.DEBUG) return
        val flag = java.io.File(filesDir, "crash-self-test.trigger")
        if (!flag.exists()) return
        // 先删再抛：只崩一次，重测要重新 touch。
        flag.delete()
        throw IllegalStateException(
            "crash self-test token=sk-abcdefghijklmnopqrstuvwxyz endpoint=/data/user/0/" +
                packageName + "/files/self-test.json",
        )
    }
}
