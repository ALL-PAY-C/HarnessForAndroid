package dev.dsh.mobile.core.data.backup

import android.content.Context
import dev.dsh.mobile.core.data.EndpointTokenMigration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Snapshot / rollback for everything a plugin install can disturb.
 *
 * Scope is deliberately "what an install touches": the installed-plugin list,
 * their config values, app settings and the session database. Rolling back is a
 * full overwrite of those — which is exactly the semantics asked for ("回到这批
 * 插件一个都没装的那一刻").
 *
 * Two entry points:
 *  - [snapshotBeforeInstall] — called once per *batch* of installs. A batch stays
 *    open for [BATCH_WINDOW_MS], so installing six plugins in a row produces one
 *    restore point, not six.
 *  - [catchUpIfDue] — scheduled backups. The app is not a daemon, so a missed
 *    05:00 is simply performed on the next launch.
 */
class BackupManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val prefs by lazy { context.getSharedPreferences("dsh.backup", Context.MODE_PRIVATE) }

    data class Entry(val file: File, val reason: String, val createdAt: Long)

    private fun rawFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(".zip") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /**
     * 存档按队列管理：只留最近 [MAX_BACKUPS] 个，多出来的最旧的直接删。
     * 读列表时顺手清一次，老版本留下的 20 个也会在这一刻收敿到 8 个。
     */
    fun list(): List<Entry> {
        val files = rawFiles()
        if (files.size > MAX_BACKUPS) {
            files.drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
        }
        return files.take(MAX_BACKUPS).map {
            Entry(it, prefs.getString("reason.${it.name}", "").orEmpty(), it.lastModified())
        }
    }

    fun lastBackupAt(): Long = prefs.getLong(KEY_LAST, 0L)

    /** Times of day to back up at, "HH:mm", at least one. */
    fun times(): List<String> {
        val raw = prefs.getString(KEY_TIMES, "")?.takeIf { it.isNotBlank() } ?: return DEFAULT_TIMES
        return raw.split(",").map { it.trim() }.filter { it.matches(Regex("\\d{1,2}:\\d{2}")) }
            .ifEmpty { DEFAULT_TIMES }
    }

    fun setTimes(times: List<String>) {
        prefs.edit().putString(KEY_TIMES, times.joinToString(",")).apply()
    }

    fun setAutoEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO, enabled).apply()

    fun autoEnabled(): Boolean = prefs.getBoolean(KEY_AUTO, true)

    /**
     * Called before an install. Returns the file created, or null when the open
     * batch already covers this install.
     */
    fun snapshotBeforeInstall(pluginId: String): File? {
        val batchAt = prefs.getLong(KEY_BATCH, 0L)
        val now = System.currentTimeMillis()
        if (batchAt > 0 && now - batchAt < BATCH_WINDOW_MS) return null
        val file = snapshot("install:$pluginId") ?: return null
        prefs.edit().putLong(KEY_BATCH, now).apply()
        return file
    }

    /** Closes the current install batch so the next install starts a new one. */
    fun closeBatch() = prefs.edit().putLong(KEY_BATCH, 0L).apply()

    /** Performs a scheduled backup if any configured time has passed since the last one. */
    fun catchUpIfDue(): File? {
        if (!autoEnabled()) return null
        val now = System.currentTimeMillis()
        val due = times().mapNotNull { timeOfDayToday(it, now) }.filter { it <= now }.maxOrNull() ?: return null
        if (lastBackupAt() >= due) return null
        return snapshot("scheduled")
    }

    fun snapshot(reason: String): File? = runCatching<File> {
        // 迁移屏障：读 DB 前先把遗留 endpoint token 迁走，否则首次迁移前的快照会导出明文 token。
        EndpointTokenMigration.ensureMigrated(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "backup-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // SharedPreferences: plugin flags, installed packages, settings.
            context.dataDir.listFiles { f -> f.name == "shared_prefs" }
                ?.flatMap { it.listFiles().orEmpty().toList() }
                ?.forEach { f -> put("shared_prefs/${f.name}", f.readBytes()) }
            // Session database (plus WAL/SHM so the copy is consistent enough to open).
            File(context.getDatabasePath(dev.dsh.mobile.core.data.db.DshDatabase.NAME).absolutePath).let { db ->
                if (db.exists()) put("db/${db.name}", db.readBytes())
                listOf("-wal", "-shm").forEach { suffix ->
                    val side = File(db.absolutePath + suffix)
                    if (side.exists()) put("db/${side.name}", side.readBytes())
                }
            }
            // Plugin payloads.
            File(context.filesDir, "plugins").walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(File(context.filesDir, "plugins")).path
                put("plugins/$rel", f.readBytes())
            }
        }
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putString("reason.${out.name}", reason)
            .apply()
        trim()
        out
    }.getOrNull()

    /** Restore a snapshot. The caller is expected to restart the app afterwards. */
    fun restore(file: File): Result<Unit> = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    when {
                        name.startsWith("shared_prefs/") -> {
                            val target = File(context.dataDir, name)
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                        }
                        name.startsWith("db/") -> {
                            val target = File(context.getDatabasePath(dev.dsh.mobile.core.data.db.DshDatabase.NAME).parentFile, name.removePrefix("db/"))
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                        }
                        name.startsWith("plugins/") -> {
                            val target = File(context.filesDir, name)
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        prefs.edit().putLong(KEY_BATCH, 0L).apply()
        // DB 被覆盖回写后可能带回 legacy token（旧备份是迁移前拍的）：重置迁移屏障，
        // 让下次快照重新迁移，避免把明文 token 再次打进 zip。
        EndpointTokenMigration.reset()
    }

    /** 手动删掉一个存档（界面上要能删，否则只能等它自己滚出去）。 */
    fun delete(file: File): Boolean = runCatching { file.exists() && file.delete() }.getOrDefault(false)

    /** Keep the newest [MAX_BACKUPS] snapshots; install batches can add up fast. */
    private fun trim() {
        rawFiles().drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
    }

    private fun timeOfDayToday(hhmm: String, now: Long): Long? = runCatching {
        val (h, m) = hhmm.split(":").let { it[0].toInt() to it[1].toInt() }
        // 与 BackupScheduler.occurrence 一致：越界的时间串直接判无效，别让 Calendar 自己滚到别的日子。
        require(h in 0..23 && m in 0..59) { "非法时间：$hhmm" }
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }.getOrNull()

    companion object {
        val DEFAULT_TIMES = listOf("05:00")
        private const val BATCH_WINDOW_MS = 10 * 60 * 1000L
        /** 只保留最近 8 个存档，超出的按队列丢最旧的。 */
        const val MAX_BACKUPS = 8
        private const val KEY_LAST = "last_at"
        private const val KEY_TIMES = "times"
        private const val KEY_AUTO = "auto"
        private const val KEY_BATCH = "batch_at"
    }
}
