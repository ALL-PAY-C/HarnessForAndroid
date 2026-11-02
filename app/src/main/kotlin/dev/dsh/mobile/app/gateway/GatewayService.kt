package dev.dsh.mobile.app.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.dsh.mobile.app.DshApplication
import dev.dsh.mobile.app.MainActivity
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.AppScope
import kotlinx.coroutines.launch

/**
 * 网关常驻前台服务。
 *
 * 网关是个子进程，App 进程被回收它跟着死。把它挂进 dataSync 型前台服务，
 * 用户切到别的 App 回消息时网关不会在后台被系统顺手杀掉。
 * 服务本身不持有进程：进程归 [GatewayManager]，服务只负责「让进程活着 + 给一条通知」。
 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL = "dsh_gateway"
        const val ID = 8317

        /** 用户显式点「启动网关」：起前台服务。 */
        fun start(context: Context) {
            val i = Intent(context, GatewayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }.onFailure { AppLog.w("gateway service start rejected: ${it.message}") }
        }

        /** 由 [GatewayManager.ensureRunning] 调：Android 12+ 后台起前台服务会抛，失败即降级为纯进程。 */
        fun startIfAllowed(context: Context) = start(context)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GatewayService::class.java)) }
        }
    }

    private val manager: GatewayManager get() = (application as DshApplication).container.gateway
    @Volatile private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("GatewayService onCreate")
        promote()
        AppScope.scope.launch { runCatching { manager.start() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        AppScope.scope.launch { runCatching { manager.start() } }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i("GatewayService onDestroy")
        super.onDestroy()
    }

    private fun promote() {
        if (foreground) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地模型网关", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("本地模型网关")
            .setContentText(manager.statusLine())
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
            true
        }.onFailure {
            // 通知权限被拒或系统禁止这次前台化：网关进程照跑，只是没有常驻通知。
            AppLog.w("gateway startForeground 被拒，降级为普通服务：${it.javaClass.simpleName} ${it.message}")
        }.getOrDefault(false)
    }
}
