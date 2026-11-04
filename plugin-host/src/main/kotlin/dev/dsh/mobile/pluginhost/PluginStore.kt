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
 *
 * 启用位只有一份真相：
 *  - 装成包（登记在 [InstalledPlugins]，即 dsh.plugin.packages）的插件 → `values.<id>.enabled`。
 *    插件自己读的就是这一份，宿主再写 `enabled.<id>` 只会让两边各自说各自的话。
 *  - 其余插件（内置、老 .apk 外挂）→ `enabled.<id>`，它们没有第二处存储。
 */
class PluginStore(
    context: Context,
    /** 包注册表：判「这是不是包插件」，并读写它那一份启用位。 */
    private val packages: InstalledPlugins = InstalledPlugins(context),
) : PermissionGrants {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh.plugins", Context.MODE_PRIVATE)

    /** 包插件在 values 里的启用位；不是包插件 / 没写过 → null（回落到默认值与 legacy 位）。 */
    private fun packageEnabled(id: String): Boolean? {
        if (packages.get(id) == null) return null
        val raw = packages.config(id)[KEY_ENABLED] ?: return null
        return when (raw.trim().lowercase()) {
            "1", "true", "on", "yes", "enabled" -> true
            else -> false
        }
    }

    fun isEnabled(id: String, default: Boolean): Boolean =
        packageEnabled(id) ?: prefs.getBoolean("enabled." + id, default)

    fun setEnabled(id: String, enabled: Boolean) {
        if (packages.get(id) != null) {
            packages.setConfig(id, KEY_ENABLED, if (enabled) "true" else "false")
            // 旧版本写过的 legacy 位一律摘掉：留着就是第二个真相，界面说停用、插件照跑。
            if (prefs.contains("enabled." + id)) prefs.edit().remove("enabled." + id).apply()
            return
        }
        prefs.edit().putBoolean("enabled." + id, enabled).apply()
    }

    /**
     * 已授予的权限名（[dev.dsh.mobile.pluginapi.PluginPermission.name]）。
     *
     * 每次返回副本：getStringSet 给的是 prefs 内部那个实例，直接漏出去会被调用方改掉，
     * 而 SharedPreferences 只在 commit/apply 时落盘 —— 内存里「已授予」、重启后又没了。
     */
    override fun permissions(id: String): Set<String> =
        prefs.getStringSet(KEY_PERM + id, emptySet())?.toSet() ?: emptySet()

    /** 覆盖写：空集即撤销全部（键一起摘掉，别留一个空集合当「授过权」）。 */
    override fun grant(id: String, permissions: Set<String>) {
        val editor = prefs.edit()
        if (permissions.isEmpty()) editor.remove(KEY_PERM + id) else editor.putStringSet(KEY_PERM + id, HashSet(permissions))
        editor.apply()
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

        /** 与插件自己读的那个键同名，才谈得上同一份真相。 */
        const val KEY_ENABLED = "enabled"

        /** 权限授予：一个插件一个键，值是一组权限名。 */
        const val KEY_PERM = "perm."
    }
}
