package dev.dsh.mobile.pluginhost

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted plugin state plus a crash guard.
 *
 * Third-party code runs inside the app process, so a plugin that throws during
 * `register()` — or takes the process down before the first frame — must not
 * cost the user a reinstall. [beginBoot] is called before any plugin loads and
 * [markBootOk] once the UI is actually up; a boot that never reaches the second
 * call increments a counter, and three of those flip the host into safe mode,
 * where everything except the built-in plugins stays off.
 */
class PluginStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh.plugins", Context.MODE_PRIVATE)

    fun isEnabled(id: String, default: Boolean): Boolean =
        prefs.getBoolean("enabled.$id", default)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled.$id", enabled).apply()
    }

    fun rememberIds(ids: Collection<String>) {
        prefs.edit().putStringSet("known", ids.toSet()).apply()
    }

    /** True when the previous boots kept dying before the UI came up. */
    val safeMode: Boolean
        get() = prefs.getInt("boot.failures", 0) >= SAFE_MODE_AFTER

    val bootFailures: Int get() = prefs.getInt("boot.failures", 0)

    fun beginBoot() {
        prefs.edit().putInt("boot.failures", bootFailures + 1).apply()
    }

    fun markBootOk() {
        if (prefs.getInt("boot.failures", 0) != 0) {
            prefs.edit().putInt("boot.failures", 0).apply()
        }
    }

    /** Safe mode is sticky until the user clears it explicitly. */
    fun clearSafeMode() {
        prefs.edit().putInt("boot.failures", 0).apply()
    }

    private companion object {
        const val SAFE_MODE_AFTER = 3
    }
}
