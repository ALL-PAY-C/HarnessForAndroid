package dev.dsh.mobile.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.dsh.mobile.core.common.AppLog

/**
 * Restores the runtime connection after a reboot.
 *
 * Android 14+ 起 BOOT_COMPLETED 里不允许直接 startForegroundService（dataSync 型会被系统
 * 以 ForegroundServiceStartNotAllowedException 打死，表现就是开机后应用反复崩溃）。
 * 这里只重建「开机常驻」的静默通知，真正的前台服务等用户打开应用时再拉起来。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.i("BootReceiver: $action")
        runCatching { RuntimeService.startQuiet(context) }
            .onFailure { AppLog.w("BootReceiver start failed: ${it.message}") }
    }
}
