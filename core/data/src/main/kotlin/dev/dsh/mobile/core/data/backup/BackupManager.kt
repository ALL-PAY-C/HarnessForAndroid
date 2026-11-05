package dev.dsh.mobile.core.data.backup

import android.content.Context
import dev.dsh.mobile.core.download.archive.ZipExtract
import dev.dsh.mobile.core.data.EndpointTokenMigration
import dev.dsh.mobile.core.model.port.BackupApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import dev.dsh.mobile.core.model.backup.BackupManifest
import dev.dsh.mobile.core.model.backup.BackupEntry
import dev.dsh.mobile.core.model.backup.RestoreMode
import dev.dsh.mobile.core.model.backup.RestoreReport
import dev.dsh.mobile.core.model.backup.BackupSelection

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
class BackupManager(private val context: Context) : BackupApi {

    private val dir: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val prefs by lazy { context.getSharedPreferences("dsh.backup", Context.MODE_PRIVATE) }

    private fun rawFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(".zip") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /**
     * 存档按队列管理：只留最近 [MAX_BACKUPS] 个，多出来的最旧的直接删。
     * 读列表时顺手清一次，老版本留下的 20 个也会在这一刻收敿到 8 个。
     */
    override fun list(): List<BackupEntry> {
        val files = rawFiles()
        if (files.size > MAX_BACKUPS) {
            files.drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
        }
        return files.take(MAX_BACKUPS).map {
            BackupEntry(it, prefs.getString("reason.${it.name}", "").orEmpty(), it.lastModified())
        }
    }

    override fun lastBackupAt(): Long = prefs.getLong(KEY_LAST, 0L)

    /** Times of day to back up at, "HH:mm", at least one. */
    override fun times(): List<String> {
        val raw = prefs.getString(KEY_TIMES, "")?.takeIf { it.isNotBlank() } ?: return DEFAULT_TIMES
        return raw.split(",").map { it.trim() }.filter { it.matches(Regex("\\d{1,2}:\\d{2}")) }
            .ifEmpty { DEFAULT_TIMES }
    }

    override fun setTimes(times: List<String>) {
        prefs.edit().putString(KEY_TIMES, times.joinToString(",")).apply()
    }

    override fun setAutoEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO, enabled).apply()

    override fun autoEnabled(): Boolean = prefs.getBoolean(KEY_AUTO, true)

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
    override fun reschedule() {
        BackupScheduler.schedule(context, this)
    }

    override fun cancelSchedule() {
        BackupScheduler.cancel(context)
    }

    override fun closeBatch() = prefs.edit().putLong(KEY_BATCH, 0L).apply()

    /** Performs a scheduled backup if any configured time has passed since the last one. */
    fun catchUpIfDue(): File? {
        if (!autoEnabled()) return null
        val now = System.currentTimeMillis()
        val due = times().mapNotNull { timeOfDayToday(it, now) }.filter { it <= now }.maxOrNull() ?: return null
        if (lastBackupAt() >= due) return null
        return snapshot("scheduled")
    }

    /**
     * 自动 / 插件安装前的存档：委托 [BackupWriter] 产标准包（带 manifest、带分节校验和）。
     *
     * 存档是运维手段，不导出凭证（secrets=false）、不带工作区（太大），
     * 只保证「会话 + 设置 + 插件 + 附件」这四样能回到安装前的状态。
     */
    override fun snapshot(reason: String): File? = runCatching<File> {
        // 迁移屏障：读 DB 前先把遗留 endpoint token 迁走，否则首次迁移前的快照会导出明文 token。
        EndpointTokenMigration.ensureMigrated(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "backup-$stamp.zip")
        writer().write(
            out,
            BackupSelection(
                sessions = null,
                settings = true,
                plugins = installedPluginIds(),
                secrets = false,
                attachments = true,
                workspace = false,
            ),
        )
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putString("reason.${out.name}", reason)
            .apply()
        trim()
        out
    }.getOrNull()

    /**
     * 恢复一个备份包（默认 merge）。调用方负责在成功后重启运行时。
     *
     * 新包（含 manifest.json）走 [RestoreEngine]：staging + 校验 + 事务式落地。
     * 老包（本类历史版本产出的三前缀 zip）仍走 [legacyRestore]，否则用户手里
     * 上一版 App 拍的存档会突然打不开。
     */
    override fun restorePackage(
        file: File,
        mode: RestoreMode,
        passphrase: String,
    ): Result<RestoreReport> = runCatching {
        val engine = engine()
        val manifest = runCatching { engine.preview(file) }.getOrNull()
        if (manifest == null) {
            legacyRestore(file).getOrThrow()
            RestoreReport(mode, 0, 0, 0, 0, listOf("旧版存档：按文件覆盖恢复，未做校验"))
        } else {
            val report = engine.restore(file, mode, passphrase).getOrThrow()
            // 库被整包换掉后可能带回迁移前的明文 token：重置屏障，让下次快照重新迁移。
            EndpointTokenMigration.reset()
            report
        }
    }

    /** Restore a snapshot. The caller is expected to restart the app afterwards. */
    override fun restore(file: File): Result<Unit> = restorePackage(file).map { }

    private fun legacyRestore(file: File): Result<Unit> = runCatching {
        val dbDir = context.getDatabasePath(dev.dsh.mobile.core.data.db.DshDatabase.NAME).parentFile
        ZipExtract.forEachEntry(file.inputStream().buffered(), ZipExtract.Options.pathOnly()) { name, isDirectory, data ->
            if (!isDirectory) {
                val clean = ZipExtract.cleanName(name) ?: throw ZipExtract.ZipExtractException.IllegalEntry(name)
                val target = when {
                    clean.startsWith("shared_prefs/") -> File(context.dataDir, clean)
                    clean.startsWith("db/") -> File(dbDir, clean.removePrefix("db/"))
                    clean.startsWith("plugins/") -> File(context.filesDir, clean)
                    else -> null
                }
                // 流式落盘：存档里的 db 可能有几十 MB，别为了路径校验先读进内存。
                if (target != null) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> data.copyTo(out) }
                }
            }
        }
        prefs.edit().putLong(KEY_BATCH, 0L).apply()
        // DB 被覆盖回写后可能带回 legacy token（旧备份是迁移前拍的）：重置迁移屏障，
        // 让下次快照重新迁移，避免把明文 token 再次打进 zip。
        EndpointTokenMigration.reset()
    }

    /**
     * 手动导出（设置 → 备份与恢复 → 创建备份）：按 [selection] 在应用私有 backups 目录产一个标准包。
     *
     * 与 [snapshot] 的区别只有一个：勾选范围由用户给，而不是固定的四节。
     * 文件名走 [backupFileName]，带版本名，用户在不同版本拍的包之间能区分。
     */
    override fun export(selection: BackupSelection): Result<Pair<File, BackupManifest>> = runCatching {
        EndpointTokenMigration.ensureMigrated(context)
        val out = File(
            dir,
            backupFileName(
                nowMillis = System.currentTimeMillis(),
                appVersionName = AppInfo.of(context).versionName,
            ),
        )
        val manifest = writer().write(out, selection)
        prefs.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putString("reason.${out.name}", if (selection.secrets) "manual:secrets" else "manual")
            .apply()
        trim()
        out to manifest
    }

    /** 只读包内 manifest（不动磁盘）：给「已有备份」列表与 SAF 选中后的预览用。 */
    override fun previewPackage(file: File): Result<BackupManifest> = runCatching { engine().preview(file) }

    /** 手动删掉一个存档（界面上要能删，否则只能等它自己滚出去）。 */
    override fun delete(file: File): Boolean = runCatching { file.exists() && file.delete() }.getOrDefault(false)

    /**
     * 双重配额：最多 [MAX_BACKUPS] 个，且总量不超过 [MAX_BYTES]。
     *
     * 只按个数淘汰是不够的 —— 8 个带附件的包能有几个 GB，等到写满磁盘时
     * 连备份本身都写不出来。至少保留最新一个，哪怕它自己就超了配额。
     */
    private fun trim() {
        val files = rawFiles()
        var keep = files.take(MAX_BACKUPS)
        var total = keep.sumOf { it.length() }
        while (keep.size > 1 && total > MAX_BYTES) {
            total -= keep.last().length()
            keep = keep.dropLast(1)
        }
        files.drop(keep.size).forEach { runCatching { it.delete() } }
    }

    private fun installedPluginIds(): Set<String> =
        File(context.filesDir, "plugins").listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    private val roots: BackupRoots by lazy { BackupRoots.of(context) }

    private fun writer(): BackupWriter = BackupWriter(
        roots = roots,
        db = RoomBackupDb(context, roots),
        prefs = AndroidPrefsSource(context),
        app = AppInfo.of(context),
        device = AppInfo.device(),
    )

    private fun engine(): RestoreEngine = RestoreEngine(
        roots = roots,
        db = RoomBackupDb(context, roots),
        prefs = AndroidPrefsSource(context),
        prefsSink = AndroidPrefsSink(context),
        snapshotBeforeReplace = { snapshot("pre-restore") },
    )

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
        /** 存档目录总量上限：2 GiB，超过就从最旧的开始删。 */
        const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024
        private const val KEY_LAST = "last_at"
        private const val KEY_TIMES = "times"
        private const val KEY_AUTO = "auto"
        private const val KEY_BATCH = "batch_at"
    }
}
