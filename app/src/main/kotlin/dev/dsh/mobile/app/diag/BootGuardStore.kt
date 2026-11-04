package dev.dsh.mobile.app.diag

import android.content.Context
import android.content.SharedPreferences
import dev.dsh.mobile.core.model.diag.DiagStore

/**
 * 启动账本的 SharedPreferences 实现（`dsh_boot`，与 PluginStore 同一个文件但不同键前缀）。
 *
 * 键一律用 `boot.guard.` 前缀：`SafeMode` 自己要占 `boot.attempt` / `boot.safeMode`，
 * 撞名会让「首帧后计数归零」和「本次启动进度」互相覆盖。
 */
class BootGuardStore(private val prefs: SharedPreferences) : DiagStore {

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }

    /** 本次启动 id：崩溃记录用它把「同一次启动里的多条记录」串起来。 */
    fun bootAttemptId(): String = get(BOOT_ID).orEmpty()

    fun markBootPending(id: String, startedAt: Long, versionCode: Int) {
        put(BOOT_ID, id)
        put(BOOT_STARTED_AT, startedAt.toString())
        put(BOOT_VERSION_CODE, versionCode.toString())
        put(BOOT_REACHED_UI, "0")
        put(BOOT_ENGINE_READY, "0")
        put(BOOT_CRASH_KIND, null)
    }

    fun markReachedUi() = put(BOOT_REACHED_UI, "1")

    fun markEngineReady() = put(BOOT_ENGINE_READY, "1")

    /** CrashReporter 的唯一回填点：有它才算「这次启动是崩的」的硬物证。 */
    fun markCrashPending(kind: String) = put(BOOT_CRASH_KIND, kind)

    fun markBootOk() {
        put(BOOT_REACHED_UI, "1")
        put(BOOT_CRASH_KIND, null)
    }

    fun crashKind(): String? = get(BOOT_CRASH_KIND)

    fun reachedUi(): Boolean = get(BOOT_REACHED_UI) == "1"

    fun engineReady(): Boolean = get(BOOT_ENGINE_READY) == "1"

    fun startedAt(): Long = get(BOOT_STARTED_AT)?.toLongOrNull() ?: 0L

    fun versionCode(): Int = get(BOOT_VERSION_CODE)?.toIntOrNull() ?: 0

    companion object {
        const val PREFS_NAME = "dsh_boot"

        private const val BOOT_ID = "boot.guard.id"
        private const val BOOT_STARTED_AT = "boot.guard.startedAt"
        private const val BOOT_VERSION_CODE = "boot.guard.versionCode"
        private const val BOOT_REACHED_UI = "boot.guard.reachedUi"
        private const val BOOT_ENGINE_READY = "boot.guard.engineReady"
        private const val BOOT_CRASH_KIND = "boot.guard.crashKind"

        fun from(context: Context): BootGuardStore =
            BootGuardStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
