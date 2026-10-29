package dev.dsh.mobile.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.dsh.mobile.app.MainActivity
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 系统通知出口：完成 / 待确认两类事件。
 *
 * 开关来自 [AppSettings]（Flow 注入），每次发送前按 notifyHeadsUp 重建 channel——
 * channel 的 IMPORTANCE 建好之后改不动，删了重建是唯一能让「横幅」开关即时生效的办法。
 */
class NotificationCenter(
    private val context: Context,
    scope: CoroutineScope,
    settings: Flow<AppSettings>,
) {

    @Volatile
    private var current: AppSettings = AppSettings.DEFAULT

    init {
        scope.launch {
            settings.collect { current = it }
        }
    }

    fun setForegroundSession(sessionId: String?) {
        activeSessionId = sessionId
        // 聚合通知里「正在看的会话」不占行（策略开关见 notifySuppressForeground）。
        context.getApplicationContext()
            .let { it as? dev.dsh.mobile.app.DshApplication }
            ?.container?.ongoingCenter?.refresh()
    }

    fun notifyTurnComplete(sessionId: String, title: String, body: String) {
        if (!enabled(current.notifyOnComplete)) return
        // 「回复摘要」关掉后只报状态，不把回复正文搬到通知栏上（会议室/地铁上很实用）。
        val text = if (current.notifyReplySnippet) body else "本轮回复已完成"
        post(CHANNEL_COMPLETE, sessionId, null, title, text)
    }

    fun notifyPermission(sessionId: String, requestId: String, agentName: String, title: String) {
        if (!enabled(current.notifyOnPermission)) return
        post(CHANNEL_PERMISSION, sessionId, requestId, title.ifBlank { "${agentName} 请求确认" }, agentName)
    }

    fun cancelPermission(requestId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel("permission:$requestId", 0)
    }

    /** 总开关 + 免打扰（当前会话的闸门在 post 里按 sessionId 判断）。 */
    private fun enabled(kindOn: Boolean): Boolean =
        kindOn && current.notificationsEnabled && !current.dnd

    private fun post(channelId: String, sessionId: String, requestId: String?, title: String, body: String) {
        if (foregroundSessionId == sessionId) return
        // Android 13+ 无 POST_NOTIFICATIONS 时 notify 会抛 SecurityException，整段兜住。
        runCatching {
            ensureChannels()
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_SESSION_ID, sessionId)
                if (requestId != null) {
                    putExtra(EXTRA_REQUEST_ID, requestId)
                    data = Uri.Builder().scheme("dsh-permission").authority("open")
                        .appendPath(sessionId).appendPath(requestId).build()
                }
            }
            val pi = PendingIntent.getActivity(
                context,
                (requestId ?: sessionId).hashCode() xor channelId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(if (requestId != null) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
            // channel 上已按开关设了振动，setDefaults 只是老设备/渠道被系统改写时的兜底。
            if (current.notifyVibrate) builder.setDefaults(Notification.DEFAULT_VIBRATE)
            if (requestId == null) nm.notify(sessionId.hashCode(), builder.build())
            else nm.notify("permission:$requestId", 0, builder.build())
            vibrate()
        }.onFailure { AppLog.w("notify failed: ${it.message}", it) }
    }

    private fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val importance = if (current.notifyHeadsUp) {
            NotificationManager.IMPORTANCE_HIGH
        } else {
            NotificationManager.IMPORTANCE_DEFAULT
        }
        listOf(
            CHANNEL_COMPLETE to "对话完成",
            CHANNEL_PERMISSION to "待确认",
        ).forEach { (id, name) ->
            if (id != CHANNEL_PERMISSION || nm.getNotificationChannel(id)?.importance != importance) {
                nm.deleteNotificationChannel(id)
            }
            nm.createNotificationChannel(
                NotificationChannel(id, name, importance).apply {
                    enableVibration(current.notifyVibrate)
                    setShowBadge(true)
                },
            )
        }
    }

    private fun vibrate() {
        if (!current.notifyVibrate) return
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            // 震动力度三档：轻/标准/强，决定单次时长与次数。
            val wave = when (current.notifyVibrateStrength.coerceIn(1, 3)) {
                1 -> longArrayOf(0, 40)
                2 -> longArrayOf(0, 80, 60, 120)
                else -> longArrayOf(0, 140, 70, 220)
            }
            vib?.takeIf { it.hasVibrator() }?.vibrate(VibrationEffect.createWaveform(wave, -1))
        }
    }

    companion object {
        /**
         * 用户正在看的会话：它自己的完成/待确认不打扰。
         * 放 companion 是因为 [OngoingCenter] 也要按同一个值过滤聚合通知。
         */
        /** 用户最后停留的会话（进聊天页设置，离开清空）。 */
        @Volatile
        private var activeSessionId: String? = null

        /**
         * 应用是否在前台。按 Home 键后 Activity 走 onStop，这里置 false：
         * 否则 [activeSessionId] 会一直挂着，用户切出去等回复时完成通知与
         * 待确认通知全被当成「他正看着这个会话」而静默丢掉——最常见的
         * 「跑完了却没响」就是这么来的。
         */
        @Volatile
        var appInForeground: Boolean = true
            internal set

        /** 真正在看的会话：只有应用在前台时才算数。 */
        val foregroundSessionId: String?
            get() = if (appInForeground) activeSessionId else null

        const val CHANNEL_COMPLETE = "dsh_complete"
        const val CHANNEL_PERMISSION = "dsh_permission"
        /** 点通知进 MainActivity 后按这个 extra 打开会话。 */
        const val EXTRA_SESSION_ID = "dsh.extra.sessionId"
        const val EXTRA_REQUEST_ID = "dsh.extra.requestId"
    }
}