package dev.dsh.mobile.core.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Wakes the app at the configured backup times.
 *
 * The app is not a resident daemon, so this is best-effort on purpose: an alarm
 * that never fires (device off, doze, force-stop) is covered by
 * [BackupManager.catchUpIfDue] on the next launch. The alarm exists so a running
 * app still backs up on schedule instead of only at cold start.
 */
object BackupScheduler {

    fun schedule(context: Context, manager: BackupManager = BackupManager(context)) {
        if (!manager.autoEnabled()) {
            cancel(context)
            return
        }
        val next = nextTrigger(manager.times()) ?: return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(context))
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pending(context))
    }

    /** Earliest future occurrence across all configured times. */
    fun nextTrigger(times: List<String>, now: Long = System.currentTimeMillis()): Long? =
        times.mapNotNull { occurrence(it, now) }.minOrNull()

    private fun occurrence(hhmm: String, now: Long): Long? = runCatching {
        val (h, m) = hhmm.split(":").let { it[0].toInt() to it[1].toInt() }
        require(h in 0..23 && m in 0..59)
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.timeInMillis
    }.getOrNull()

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, BackupReceiver::class.java).setAction(BackupReceiver.ACTION_BACKUP)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST = 4301
}
