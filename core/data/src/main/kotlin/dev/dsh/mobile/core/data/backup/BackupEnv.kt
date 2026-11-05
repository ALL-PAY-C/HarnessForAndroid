package dev.dsh.mobile.core.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.dsh.mobile.core.data.db.DshDatabase
import java.io.File

/**
 * 备份包要碰的四个目录，全部显式传进来而不是从 Context 现取。
 *
 * 纯 JVM 单测（无 Robolectric）里 android.jar 的 Context 是桩，取不到 filesDir；
 * 把路径抽成数据类后，写包 / 恢复的全部逻辑都能在临时目录上跑。
 */
data class BackupRoots(
    val filesDir: File,
    val dataDir: File,
    val dbDir: File,
    val noBackupDir: File,
) {
    val sharedPrefsDir: File get() = File(dataDir, "shared_prefs")
    val datastoreDir: File get() = File(filesDir, "datastore")
    val pluginsDir: File get() = File(filesDir, "plugins")
    val attachmentsDir: File get() = File(filesDir, "attachments")
    val workspaceDir: File get() = File(filesDir, "workspace")
    val runtimeDir: File get() = File(filesDir, "runtime")
    val backupDir: File get() = File(filesDir, "backups")
    val backupStagingDir: File get() = File(filesDir, "backup-staging")
    val restoreStagingDir: File get() = File(filesDir, "restore-staging")

    companion object {
        fun of(ctx: Context): BackupRoots = BackupRoots(
            filesDir = ctx.filesDir,
            dataDir = ctx.applicationContext.dataDir,
            dbDir = ctx.getDatabasePath(DshDatabase.NAME).parentFile ?: File(ctx.dataDir, "databases"),
            noBackupDir = ctx.noBackupFilesDir,
        )
    }
}

/** 产包 App 版本，仅写进 manifest 供诊断。 */
data class AppInfo(val versionCode: Int, val versionName: String) {
    companion object {
        fun of(ctx: Context): AppInfo = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
            AppInfo(code, info.versionName ?: "0")
        }.getOrDefault(AppInfo(0, "0"))

        fun device(): DeviceInfo = DeviceInfo(
            model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
    }
}

/** 读一个 SharedPreferences 文件的全量键值（只读）。 */
interface PrefsSource {
    fun read(name: String): Map<String, Any?>

    object Empty : PrefsSource {
        override fun read(name: String): Map<String, Any?> = emptyMap()
    }
}

/** 把包里的键值写回 SharedPreferences。 */
interface PrefsSink {
    /** [replace] = true 时先清空该文件（整机还原语义）。 */
    fun apply(name: String, values: Map<String, Any?>, replace: Boolean)

    object Noop : PrefsSink {
        override fun apply(name: String, values: Map<String, Any?>, replace: Boolean) = Unit
    }
}

/** Android 实现：白名单外的名字一律读不到（避免把 dsh.backup 之类的运维态带进包）。 */
class AndroidPrefsSource(
    private val ctx: Context,
    private val allowed: Set<String> = PREFS_WHITELIST,
) : PrefsSource {
    override fun read(name: String): Map<String, Any?> {
        if (name !in allowed) return emptyMap()
        val prefs = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        return prefs.all.mapValues { it.value }
    }
}

class AndroidPrefsSink(private val ctx: Context) : PrefsSink {
    override fun apply(name: String, values: Map<String, Any?>, replace: Boolean) {
        val prefs = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (replace) editor.clear()
        for ((key, value) in values) put(editor, key, value)
        editor.apply()
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.mapNotNull { it as? String }.toSet())
            else -> editor.putString(key, value.toString())
        }
    }
}

/**
 * 插件包登记（清单 + 配置）。
 *
 * 配置值 `values.<id>` 落盘即密文（plugin-host 的 PluginConfigVault），旧版明文在写包前由
 * [PluginConfigSeal] 换成密文，所以这个文件可以继续随设置节走：包里不含插件配置明文。
 */
const val PLUGIN_PACKAGES_PREFS: String = "dsh.plugin.packages"

/** 设置节里导出 / 合并的 SharedPreferences 白名单（dsh.mcp 含 headers，只走凭证节）。 */
val PREFS_WHITELIST: Set<String> = linkedSetOf(
    "dsh.plugins",
    PLUGIN_PACKAGES_PREFS,
    "dsh.plugin.placements",
    "dsh.ext.plugins",
    "dsh_context_summary",
)

/** 设置节里导出 / 合并的 DataStore 白名单（dsh_models 含 apiKey，只走凭证节）。 */
val DATASTORE_WHITELIST: List<String> = listOf("dsh_settings", "dsh_presets", "dsh_group")

/** 恢复时永远保留本机值的设备键（§2.2）。 */
val DEVICE_SETTING_KEYS: Set<String> = setOf("default_cwd", "cwd_tree_uri")
