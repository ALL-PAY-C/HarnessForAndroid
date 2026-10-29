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
        // 真正的连接状态在 container.runtime（LocalEngineTransport 内置引擎时
        // runtimeHost 一直是 NotConfigured，直接读它会出现「未配置运行时」却聊得很欢）。
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
        AppLog.i("RuntimeService onDestroy")
        super.onDestroy()
    }

    /**
     * 前台服务必须在 onCreate 里同步拿到一条通知，且只能 [startForeground] 一次
     * （Android 12+ 重复调用会抛 ForegroundServiceStartNotAllowedException）。
     */
    private fun promoteToForeground() {
        val n = runCatching {
            container.ongoingCenter.onRuntime(container.runtime.value)
            container.ongoingCenter.foregroundNotification()
        }
            .getOrElse { minimalNotification() }
        foreground = push(n)
        // 前台服务那条通知只能靠 startForeground 反复刷新：
        // 同 ID 的 NotificationManager.notify() 在前台服务占用时会静默失效。
        runCatching {
            container.ongoingCenter.setForegroundUpdater { latest ->
                if (foreground) main.post { foreground = push(latest) }
            }
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
    }
}
