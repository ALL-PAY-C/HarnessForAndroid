package dev.dsh.mobile.core.common

import android.content.Context
import android.os.Process

/**
 * 为什么要重启。决定这次能不能在进程内做完。
 *
 * [SafeModeExit] / [User] 只改了磁盘上的标志位，进程内可以完整重建；
 * [BackupRestore] 改写了 DataStore 的 .preferences_pb 原始文件，进程内那份缓存作废不了，
 * 只能换进程（原因见 AppContainer.softRestart）。
 */
enum class RestartReason(val label: String) {
    /** 诊断页「退出安全模式并重启」。 */
    SafeModeExit("safe-mode-exit"),

    /** 备份恢复完成后重启。 */
    BackupRestore("backup-restore"),

    /** 其它手动重启，按软重启处理。 */
    User("user"),
}

/**
 * 宿主（:app）装进来的重启实现。core:common 不持有 AppContainer，只能留一个槽位。
 *
 * 返回值语义：true = 已在进程内重启完，调用方什么都不用再做；
 * false = 这条场景必须换进程，hook 已经自己挂好通知并安排了 killProcess。
 */
fun interface RestartHook {
    fun restart(context: Context, reason: RestartReason): Boolean
}

/** 重启实现的槽位；由 DshApplication 在 container 建好后填。 */
object AppRestart {
    @Volatile
    var hook: RestartHook? = null
}

/**
 * 重启 App，并把界面自己拉回来。
 *
 * 为什么不再是「AlarmManager 的 PendingIntent + 杀进程」那一版：
 * Android 14+ 的 BAL 硬化会把它拦下来（实测 logcat：
 * Background activity launch blocked! callingUidProcState: FOREGROUND_SERVICE;
 * isPendingIntent: true; balAllowedByPiCreator: BSP.NONE; resultIfPiCreatorAllowsBal: BAL_BLOCK，
 * START ... result code=102）。而且进程一死，AMS 就把任务移除了
 * （Force removing ActivityRecord ... app died, no saved state），粘性服务把进程重新拉起来时
 * Activity 也不会回到前台——这条路在当前系统上根本回不来。
 *
 * 所以主路径改成「不杀进程」：持久化已经在调用方做完，这里只负责在进程内重建可变单例
 * （插件注册表、引擎连接）并让 Activity 自己 recreate()。软重启对恢复场景不成立时才退到
 * 硬重启，且硬重启前必须先挂一条「点此回到应用」的通知——进程死了没人替用户点。
 */
fun restartApp(context: Context, reason: RestartReason = RestartReason.User) {
    val hook = AppRestart.hook
    if (hook == null) {
        // 只有 :node 子进程 / 纯 JVM 单测会走到这里：没有 container，也没有界面可回。
        AppLog.w("RESTART no hook installed, killing pid=" + Process.myPid() + " reason=" + reason.label)
        Process.killProcess(Process.myPid())
        return
    }
    val done = runCatching { hook.restart(context, reason) }
    val soft = done.getOrNull()
    if (soft != null) {
        AppLog.i("RESTART handed to host, soft=" + soft + " reason=" + reason.label)
        return
    }
    // hook 自己炸了：既没软重启也没排上硬重启，那就至少把进程收掉，别让用户对着半死的界面。
    AppLog.e("RESTART hook failed, killing pid=" + Process.myPid(), done.exceptionOrNull())
    Process.killProcess(Process.myPid())
}
