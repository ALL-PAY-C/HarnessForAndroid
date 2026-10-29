package dev.dsh.mobile.core.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Runs the scheduled backup, then re-arms the next one.
 *
 * Backup is file I/O only, so a plain worker thread is enough — no coroutine
 * scope needed and nothing to leak if the process dies right after.
 */
class BackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BACKUP) return
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "dsh-backup") {
            try {
                val manager = BackupManager(appContext)
                manager.snapshot("scheduled")
                BackupScheduler.schedule(appContext, manager)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_BACKUP = "dev.dsh.mobile.action.BACKUP"
    }
}
