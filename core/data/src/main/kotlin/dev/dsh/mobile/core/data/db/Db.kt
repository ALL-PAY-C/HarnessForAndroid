package dev.dsh.mobile.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val cwd: String = "",
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L,
    val status: String = "Idle",
    val preview: String = "",
    val messageCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val cachedTokens: Long = 0L,
    @ColumnInfo(defaultValue = "0") val lastPrompt: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    @ColumnInfo(defaultValue = "0") val pinned: Int = 0,
    @ColumnInfo(defaultValue = "0") val archived: Int = 0,
    @ColumnInfo(defaultValue = "'最近'") val grp: String = "最近",
    @ColumnInfo(defaultValue = "'Single'") val kind: String = "Single",
    val agentId: String? = null,
    @ColumnInfo(defaultValue = "''") val participantsJson: String = "",
    /** 会话级人设 / 预设（普通会话和群聊都能用）。 */
    @ColumnInfo(defaultValue = "''") val persona: String = "",
    /** ACP 远端会话 id；null = 本地引擎。用于正反向会话 id 翻译。 */
    val remoteId: String? = null,
    /** 远端会话归属的端点 id；旧端点映射不注册、旧端点 id 绝不发往新端点。 */
    val endpointId: String? = null,
)

@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String = "",
    val thought: String = "",
    val toolCallsJson: String = "[]",
    val at: Long = 0L,
    val streaming: Int = 0,
    val authorId: String? = null,
    val authorName: String? = null,
    val attachmentsJson: String = "[]",
    /** 这一轮从开始生成到落库用了多久（毫秒），思维链行尾显示「用了 3分12秒」。 */
    val durationMs: Long = 0L,
    /** 本轮唯一 id：(sessionId, turnId, authorId) 唯一决定一条助手消息，重复落库靠它拦。 */
    @ColumnInfo(defaultValue = "''") val turnId: String = "",
    /** 这条回答（含它的工具轮）消耗的输入 token；0 表示模型没回 usage。 */
    @ColumnInfo(defaultValue = "0") val inputTokens: Long = 0L,
    /** 这条回答消耗的输出 token。 */
    @ColumnInfo(defaultValue = "0") val outputTokens: Long = 0L,
)

@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val url: String = "",
    val token: String = "",
    val cwd: String = "",
    val heartbeatMs: Long = 15_000L,
    val autoConnect: Int = 1,
    val lastUsedAt: Long = 0L,
)

@Dao
interface SessionDao {
    // 归档会话也要留在结果里：分组由 SessionsViewModel 决定（归档组默认收起），
    // 在这里过滤掉会让「归档」看起来像删除。
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions")
    suspend fun all(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Int)

    @Query("UPDATE sessions SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Int)

    @Query("UPDATE sessions SET grp = :grp WHERE id = :id")
    suspend fun setGroup(id: String, grp: String)

    @Query("UPDATE sessions SET cwd = :cwd WHERE id = :id")
    suspend fun setCwd(id: String, cwd: String)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("UPDATE sessions SET agentId = :agentId WHERE id = :id")
    suspend fun setAgent(id: String, agentId: String?)

    @Query("UPDATE sessions SET participantsJson = :json WHERE id = :id")
    suspend fun setParticipants(id: String, json: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    /** 抽屉搜索：标题或预览命中；pinned 优先、其余按最近活跃。q 已转义（见 ChatRepository.escapeLike）。 */
    @Query("SELECT * FROM sessions WHERE (title LIKE '%'||:q||'%' ESCAPE '\\' OR preview LIKE '%'||:q||'%' ESCAPE '\\') ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<SessionEntity>

    /** 冷启动自愈：把上次进程留下的 Running / WaitingPermission 抹成 Idle。 */
    @Query("UPDATE sessions SET status = 'Idle' WHERE status IN ('Running','WaitingPermission')")
    suspend fun resetRunning(): Int

}

/**
 * 列表 / 历史查询读出的正文上限（字符）。Room 单行超过 CursorWindow 默认 2MB 会抛
 * SQLiteBlobTooBigException 把进程打死；256K 字符 CJK 约 768KB，留足其余列与同批多行的余量。
 * 写入侧不再截断（原文整条落库），保护只作用在"读出多少"上；要全文走 [MessageDao.textById] 单行读。
 */
internal const val MESSAGE_TEXT_READ_CAP = 256 * 1024

/**
 * messages 表的列清单，供列表 / 历史查询复用。
 * ponytail: text 走 substr 前缀是唯一的内存保护手段（Room 不支持按行大小分页），
 * 要把上限再做大得先换存储（正文外置成文件/分块表），不在这一层加复杂度。
 */
internal const val MESSAGE_COLUMNS = "id, sessionId, role, substr(text, 1, $MESSAGE_TEXT_READ_CAP) AS text, thought, toolCallsJson, at, streaming, authorId, authorName, attachmentsJson, durationMs, turnId, inputTokens, outputTokens"

/**
 * 抽屉搜索的一行结果。会话标题随 messages LEFT JOIN sessions 带出：会话已被删时留空，
 * 结果行照旧显示（正文命中不该因为会话行没了就消失）。
 * snippet 只取正文前 120 字——结果列表逐行渲染，整段正文没必要进内存。
 */
data class MessageSearchRow(
    val id: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val snippet: String,
    val at: Long,
)

@Dao
interface MessageDao {
    @Query("SELECT $MESSAGE_COLUMNS FROM messages WHERE sessionId = :sessionId ORDER BY at ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT $MESSAGE_COLUMNS FROM messages WHERE sessionId = :sessionId ORDER BY at ASC")
    suspend fun bySession(sessionId: String): List<MessageEntity>

    /** 单条正文全文：列表只给前缀，展开 / 复制 / 导出时按 id 补读（一行远小于 2MB 窗口）。 */
    @Query("SELECT text FROM messages WHERE id = :id")
    suspend fun textById(id: String): String?

    /** 同一轮、同一作者的助手消息条数：重复落库的幂等判据（不再用文本 + 时间猜）。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND turnId = :turnId AND role = 'ASSISTANT' AND IFNULL(authorId,'') = IFNULL(:authorId,'')")
    suspend fun countTurn(sessionId: String, turnId: String, authorId: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    /**
     * 抽屉搜索：正文命中，最近的在前。q 由 [dev.dsh.mobile.core.data.repo.ChatRepository]
     * 转义过（\ % _ → \\ \% \_），这里的 ESCAPE 让它们回到字面字符：
     * 不转义时用户搜「100%」会把 % 当通配符，命中一堆无关消息。
     */
    @Query("SELECT m.id AS id, m.sessionId AS sessionId, COALESCE(s.title,'') AS sessionTitle, m.role AS role, substr(m.text,1,120) AS snippet, m.at AS at FROM messages m LEFT JOIN sessions s ON s.id = m.sessionId WHERE m.text LIKE '%'||:q||'%' ESCAPE '\\' ORDER BY m.at DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<MessageSearchRow>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}

@Dao
interface EndpointDao {
    @Query("SELECT * FROM endpoints ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints ORDER BY lastUsedAt DESC")
    suspend fun all(): List<EndpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(endpoint: EndpointEntity)

    @Query("DELETE FROM endpoints WHERE id = :id")
    suspend fun delete(id: String)

    /** 迁移遗留 token 后清空该行 token 字段（token 改存 EndpointSecretStore，Room 不再落明文）。 */
    @Query("UPDATE endpoints SET token = '' WHERE id = :id")
    suspend fun clearToken(id: String)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, EndpointEntity::class],
    version = 11,
    exportSchema = true,
)
abstract class DshDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun endpointDao(): EndpointDao

    companion object {
        const val NAME = "dsh-mobile.db"

        /**
         * v1 -> v2: conversation list gained pinning, archiving and grouping.
         * Forward-only, additive, no data loss. Registered in [dev.dsh.mobile.core.data.DataModule].
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN grp TEXT NOT NULL DEFAULT '最近'")
            }
        }

        /**
         * v2 -> v3: multi-agent group chats + per-session agent binding.
         * Additive only: existing rows stay single-agent sessions bound to the app default model.
         */
        /** v3 -> v4: per-session persona / preset. */
        /** v7 -> v8: 每条助手消息自己的 token 用量（气泡头部显示 ↑输入 ↓输出）。 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN inputTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8 -> v9: 会话的 ACP 远端 id（本地 id 独立保留，用于正反向翻译）。 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN remoteId TEXT")
            }
        }

        /** v9 -> v10: 远端会话归属端点 id（旧端点映射不注册、旧端点 id 不发新端点）。 */
        /** 缓存命中和最近 prompt 独立存储，升级保留原会话。 */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN lastPrompt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN endpointId TEXT")
            }
        }

        /** v6 -> v7: 轮次 id（消息幂等按 (sessionId, turnId, authorId) 判定）。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN turnId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN persona TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'Single'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN agentId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN participantsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN authorId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN authorName TEXT")
            }
        }
    }
}
