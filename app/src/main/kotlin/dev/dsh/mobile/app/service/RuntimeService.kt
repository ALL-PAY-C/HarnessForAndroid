package dev.dsh.mobile.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.app.DshApplication
import dev.dsh.mobile.app.MainActivity
import dev.dsh.mobile.app.notify.OngoingCenter
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.AppScope
import dev.dsh.mobile.core.model.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the runtime + transport alive while the UI is backgrounded.
 *
 * 常驻通知只有一条，内容由 [OngoingCenter] 统一生成（运行时状态 + n 个会话 +
 * 各自思维链 + 通知内选项）。服务只负责：① 把它变成前台服务通知；
 * ② 把运行时的状态变化喂给 [OngoingCenter]；③ 保持连接。
 *
 * 崩溃史：上一版 onCreate 里无条件 startForeground，且 BootReceiver 从 BOOT_COMPLETED
 * 直接 startForegroundService —— Android 14 对 dataSync 型前台服务禁止这条路径，
 * 系统抛 ForegroundServiceStartNotAllowedException 把进程打死（"DSH keeps stopping"）。
 * 现在 startForeground 全程兜异常并降级成普通服务；开机路径只发静默通知。
 */
class RuntimeService : Service() {

    private lateinit var container: AppContainer
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as DshApplication).container
        AppLog.i("RuntimeService onCreate")
        promoteToForeground()
        // 真正的连接状态在 container.runtime：内置引擎走 LocalEngineTransport，
        // 那个 transport 自己的 state 才是 UI 该看的（否则会出现「未配置运行时」却聊得很欢）。
        job = AppScope.scope.launch {
            container.runtime.collect { rt ->
                runCatching { container.ongoingCenter.onRuntime(rt) }
            }
        }
        runCatching { container.connectActive() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) promoteToForeground()
        runCatching { container.connectActive() }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        runCatching { container.ongoingCenter.setForegroundUpdater(null) }
        // 不主动降级会被系统判成「前台服务未在超时内停止」的致命异常
        // （ForegroundServiceDidNotStopInTimeException，表现为 "DSH keeps stopping"）。
        demote()
        AppLog.i("RuntimeService onDestroy")
        super.onDestroy()
    }

    /**
     * Android 15+ 对前台服务类型有运行时长上限，到点先回调这里再杀进程。
     * 主动降级成普通服务 + 停自己，比被系统打死温和得多。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("前台服务超时（type=" + fgsType + "），降级并停止")
        demote()
        stopSelf(startId)
    }

    private fun demote() {
        if (!foreground) return
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /**
     * 前台服务必须在 onCreate 里同步拿到一条通知，且只能 [startForeground] 一次
     * （Android 12+ 重复调用会抛 ForegroundServiceStartNotAllowedException）。
     */
    private fun promoteToForeground() {
        // 冷启动时 service 创建正好落在首帧的 draw 窗口里：富内容通知要同步构造
        // OngoingCenter（lazy）+ tracesOf + Notification.Builder + 若干 PendingIntent，
        // 和首次 composition 抢同一段主线程。这里先同步推最简通知满足
        // 「onCreate 里必须 startForeground」的时序要求，富内容挪到本轮消息之后再做。
        foreground = push(minimalNotification())
        // 前台服务那条通知只能靠 startForeground 反复刷新：
        // 同 ID 的 NotificationManager.notify() 在前台服务占用时会静默失效。
        runCatching {
            container.ongoingCenter.setForegroundUpdater { latest ->
                if (foreground) main.post { foreground = push(latest) }
            }
        }
        main.post {
            if (!foreground) return@post
            val n = runCatching {
                container.ongoingCenter.onRuntime(container.runtime.value)
                container.ongoingCenter.foregroundNotification()
            }.getOrNull() ?: return@post
            foreground = push(n)
        }
    }

    /** 把一条通知推成前台服务通知。必须在主线程调用。 */
    private fun push(n: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(OngoingCenter.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(OngoingCenter.ID_ONGOING, n)
        }
        true
    }.onFailure {
        AppLog.w("startForeground 被系统拒绝，降级为普通服务：${it.javaClass.simpleName} ${it.message}")
    }.getOrDefault(false)

    /** OngoingCenter 还没就绪时的兜底通知（同一条 ID，随后会被真内容覆盖）。 */
    private fun minimalNotification(): Notification = Notification.Builder(this, OngoingCenter.CHANNEL_ONGOING)
        .setContentTitle("DSH")
        .setContentText("运行时启动中")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { AppLog.w("RuntimeService.start 失败：${it.message}") }
        }

        /**
         * 开机路径：不能开 dataSync 前台服务，只把常驻通知挂上去，
         * 用户点开应用时 MainActivity 会正常 start([RuntimeService])。
         */
        fun startQuiet(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(OngoingCenter.CHANNEL_ONGOING) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        OngoingCenter.CHANNEL_ONGOING,
                        "进行中的会话",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) },
                )
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, OngoingCenter.CHANNEL_ONGOING)
                .setContentTitle("DSH")
                .setContentText("点开继续上次的会话")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(OngoingCenter.ID_ONGOING, n) }
        }

        /**
         * 硬重启前的最后一条出口。进程马上要被杀，没人能替用户点界面，
         * 所以挂一条高优先级、可点击、可清除的通知（不是常驻通知，用户随时能划掉）。
         *
         * 为什么必须有这条：killProcess 之后 AMS 会把任务移除（app died, no saved state），
         * 粘性服务把进程拉回来时 Activity 不会自己回到前台，BAL 也不允许后台启动 Activity。
         * 没有通知，用户面前只剩一个空桌面——正是这次要修的那个现象。
         */
        fun postRelaunchNotice(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 单独一个渠道：IMPORTANCE_HIGH 才会抬头提示，也不会把常驻通知那个 LOW 渠道一起抬起来。
            if (nm.getNotificationChannel(CHANNEL_RELAUNCH) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_RELAUNCH,
                        "重启提示",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setShowBadge(false)
                        // 不响铃不震动：这是一条「你回来了」的提示，不是告警。
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
            val pi = PendingIntent.getActivity(
                context,
                RELAUNCH_REQUEST_CODE,
                // SINGLE_TOP|CLEAR_TOP：进程还活着时点它回栈顶，进程已死时就是一次冷启动。
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CHANNEL_RELAUNCH)
                .setContentTitle("恢复完成，点此回到应用")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                // 可清除：这条提示的寿命只到用户回到 App 为止，不留垃圾、不常驻。
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(ID_RELAUNCH, n) }
                .onFailure { AppLog.w("postRelaunchNotice 失败：${it.message}") }
        }

        /** 重启提示走独立 ID，别和常驻通知抢同一个槽位。 */
        private const val ID_RELAUNCH = 4712
        private const val CHANNEL_RELAUNCH = "dsh.relaunch"
        private const val RELAUNCH_REQUEST_CODE = 4713
    }
}
