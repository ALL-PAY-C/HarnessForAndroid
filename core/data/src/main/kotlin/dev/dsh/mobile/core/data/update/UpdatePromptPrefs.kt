package dev.dsh.mobile.core.data.update

import android.content.Context

/** 与 Updater 共用同一个偏好文件（Updater.kt:37 的 "dsh.update"），不另立一套更新状态。 */
private const val PREFS_NAME = "dsh.update"
private const val KEY_PROMPTED_VERSION = "key_prompted_version"

/** 上次弹过「发现新版本」的 versionCode；0 表示还没弹过。 */
fun readPromptedVersion(context: Context): Long =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_PROMPTED_VERSION, 0L)

/** 记下已经打扰过的版本号：用户点「稍后」时写，同一版本不再弹。 */
fun writePromptedVersion(context: Context, versionCode: Long) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_PROMPTED_VERSION, versionCode)
        .apply()
}
