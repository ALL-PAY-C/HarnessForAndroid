package dev.dsh.mobile.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.dsh.mobile.app.service.RuntimeService
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.AppRestart
import dev.dsh.mobile.core.platform.RestartHook
import dev.dsh.mobile.core.platform.RestartReason
import java.lang.ref.WeakReference

/**
 * 「重启 App」的宿主实现，装进 [AppRestart.hook]。
 *
 * 两条路，先软后硬：
 *  · 软重启：container.softRestart 在进程内重建插件注册表和引擎连接，然后让当前 Activity
 *    recreate()。不杀进程 → 不碰 BAL，任务也不会被 AMS 移除，用户视线不离开 App。
 *  · 硬重启：只有备份恢复这种「磁盘上的 DataStore 文件被整体替换」的场景才用。杀之前先在
 *    常驻服务里挂一条可点击通知，否则进程一死，用户只剩一个空桌面，没有任何入口回来。
 */
object RestartController {

    /**
     * 硬重启给通知留的时间。NotificationManager 是跨进程调用，杀早了这条提示就没了，
     * 用户又回到「桌面 + 什么都不发生」的状态——正是这次要修的那个 bug。
     */
    private const val HARD_RESTART_DELAY_MS = 400L

    /**
     * 最近一次 resume 的 Activity，软重启时直接拿它 recreate。
     *
     * 为什么用弱引用：Activity 的生命周期远短于进程（旋转、返回栈回收、退到后台被系统回收都会
     * 销毁它），单例里持强引用会把整棵 View 树、Window、Drawable 一起钉死在进程堆上直到进程
     * 退出——标准 Activity 泄漏。
     *
     * 清理点在 [ActivityLifecycleCallbacks] 的 onActivityPaused / onActivityDestroyed（且只清
     * 「当前引用的就是这个实例」的那些，避免 A→B 切换时迟到的 onActivityPaused(A) 抹掉刚记下
     * 的 B）；写入点是 onActivityResumed。
     */
    private var resumed: WeakReference<Activity>? = null

    fun install(app: DshApplication) {
        // 不再靠 context 链推导 Activity：界面侧的 LocalContext 已被 DshTheme 换成
        // UiLocale.wrap 的配置 Context（design/Theme.kt:425），那条链上根本没有 Activity。
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        AppRestart.hook = RestartHook { context, reason ->
            val soft = app.container.softRestart(reason)
            if (soft) {
                recreateUi(context)
            } else {
                RuntimeService.postRelaunchNotice(context, noticeText(reason))
                AppLog.w("RESTART hard scheduled reason=" + reason.label + " pid=" + Process.myPid())
                Handler(Looper.getMainLooper()).postDelayed(
                    { Process.killProcess(Process.myPid()) },
                    HARD_RESTART_DELAY_MS,
                )
            }
            soft
        }
    }

    private fun noticeText(reason: RestartReason): String = when (reason) {
        RestartReason.BackupRestore -> "恢复完成，点此回到应用"
        else -> "已重启，点此回到应用"
    }

    /**
     * 软重启不换进程，界面得自己重建。
     *
     * 先取生命周期回调记下的前台 Activity；取不到再退回沿 baseContext 找（这条只对
     * Activity 自己的 Context 或普通 ContextWrapper 有效，配置 Context 是链断的）。
     * 两条都空才算真的没有界面可重建。
     */
    private fun recreateUi(context: Context) {
        val activity = resumed?.get() ?: context.findActivity()
        if (activity == null) {
            AppLog.w("RESTART soft: context 链上没有 Activity，界面未重建")
            return
        }
        // 必须在主线程下一帧再做：此刻调用方（Compose 点击回调 / 协程）还压在旧界面的栈上，
        // 当场 recreate() 会和正在进行的重组撞车。
        Handler(Looper.getMainLooper()).post {
            AppLog.i("RESTART soft: recreate " + activity.javaClass.simpleName)
            runCatching { activity.recreate() }
                .onFailure { AppLog.e("RESTART soft: recreate failed", it) }
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }
}
