package dev.dsh.mobile.core.data.backup

import android.content.Context
import androidx.room.Room
import dev.dsh.mobile.core.data.DataModule
import dev.dsh.mobile.core.data.db.DshDatabase
import java.io.File

/**
 * [BackupDb] 的 Android 实现：本机库走 Room，包内库临时升到本机版本后裸读。
 *
 * 包内库升级借的是同一份 [DshDatabase] 定义和全 MIGRATION 链：把包里的文件复制成
 * databases/ 下的临时库，Room 一打开就自动跑迁移，之后读到的行结构与本机完全一致，
 * 也就不需要在恢复流程里另写一份 schema 知识。
 */
class RoomBackupDb(
    private val context: Context,
    private val roots: BackupRoots,
) : BackupDb {

    private val database: DshDatabase by lazy { DataModule.createDatabase(context) }

    private val dbFile: File get() = context.getDatabasePath(DshDatabase.NAME)

    override val version: Int
        get() = database.openHelper.readableDatabase
            .query("PRAGMA user_version").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun counts(): Counts = Counts(
        sessions = count("sessions"),
        messages = count("messages"),
    )

    private fun count(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM " + table).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun snapshotTo(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val writable = database.openHelper.writableDatabase
        try {
            writable.execSQL("VACUUM INTO '" + target.absolutePath.replace("'", "''") + "'")
        } catch (e: Exception) {
            // SQLite < 3.27（Android 8/9）没有 VACUUM INTO：先把 WAL 灌回主库再整拷。
            writable.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            dbFile.copyTo(target, overwrite = true)
        }
    }

    override fun pruneSessions(file: File, sessionIds: Set<String>) {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            db.beginTransaction()
            try {
                if (sessionIds.isEmpty()) {
                    db.execSQL("DELETE FROM messages")
                    db.execSQL("DELETE FROM sessions")
                } else {
                    val holes = sessionIds.joinToString(",") { "?" }
                    val args = sessionIds.toTypedArray()
                    db.execSQL("DELETE FROM messages WHERE sessionId NOT IN (" + holes + ")", args)
                    db.execSQL("DELETE FROM sessions WHERE id NOT IN (" + holes + ")", args)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    override fun open(file: File): BackupDump {
        roots.dbDir.mkdirs()
        val name = "restore-import-" + System.nanoTime() + ".db"
        val copy = File(roots.dbDir, name)
        file.copyTo(copy, overwrite = true)
        val imported = Room.databaseBuilder(context, DshDatabase::class.java, name).build()
        val db = imported.openHelper.readableDatabase
        val dumpVersion = db.query("PRAGMA user_version").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return object : BackupDump {
            override val version: Int = dumpVersion

            override fun rows(table: String): List<Row> = db.query("SELECT * FROM " + table).use { c ->
                val names = c.columnNames
                val out = ArrayList<Row>(c.count)
                while (c.moveToNext()) {
                    val values = LinkedHashMap<String, Any?>(names.size)
                    for (i in names.indices) {
                        values[names[i]] = when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> c.getString(i)
                        }
                    }
                    out += Row(values)
                }
                out
            }

            override fun close() {
                runCatching { imported.close() }
                runCatching { copy.delete() }
                listOf("-wal", "-shm").forEach { runCatching { File(copy.absolutePath + it).delete() } }
            }
        }
    }

    override fun titles(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        database.openHelper.readableDatabase.query("SELECT id, title FROM sessions").use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1) ?: ""
        }
        return out
    }

    override fun upsert(table: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        val writable = database.openHelper.writableDatabase
        for (row in rows) {
            val columns = row.values.keys.toList()
            val sql = buildString {
                append("INSERT OR REPLACE INTO ").append(table).append(" (")
                append(columns.joinToString(","))
                append(") VALUES (")
                append(columns.joinToString(",") { "?" })
                append(")")
            }
            writable.execSQL(sql, columns.map { row.values[it] }.toTypedArray())
        }
    }

    override fun clearContent() {
        val writable = database.openHelper.writableDatabase
        writable.execSQL("DELETE FROM messages")
        writable.execSQL("DELETE FROM sessions")
        writable.execSQL("DELETE FROM warm_layers")
    }

    override fun clearWarmLayers(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        val writable = database.openHelper.writableDatabase
        for (id in sessionIds) writable.execSQL("DELETE FROM warm_layers WHERE sessionId = ?", arrayOf(id))
    }

    override fun <T> transaction(block: () -> T): T = database.runInTransaction(block)
}
