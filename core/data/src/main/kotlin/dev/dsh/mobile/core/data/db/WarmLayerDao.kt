package dev.dsh.mobile.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 温层表的读写 + 两条为冷层回捞预留的 messages 查询（T9.8 S1）。
 *
 * 后两条只读既有 messages 表、不改表结构：原文永不删除，温层指针按 rowid 区间回捞。
 * rowid 是 messages 表唯一的单调数值句柄（主键 id 是 TEXT uuid），所以区间与
 * [WarmLayerEntity.coveredUntilMessageId] 都用它。
 */
@Dao
interface WarmLayerDao {
    @Query("SELECT * FROM warm_layers WHERE sessionId = :sessionId")
    fun observe(sessionId: String): Flow<WarmLayerEntity?>

    @Query("SELECT * FROM warm_layers WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): WarmLayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WarmLayerEntity)

    @Query("DELETE FROM warm_layers WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    /** 闭区间取原文：[fromId] / [toId] 都是 rowid（含两端）。 */
    @Query("SELECT $MESSAGE_COLUMNS FROM messages WHERE sessionId = :sessionId AND rowid >= :fromId AND rowid <= :toId ORDER BY rowid ASC")
    suspend fun bySessionRange(sessionId: String, fromId: Long, toId: Long): List<MessageEntity>

    /**
     * 会话内正文 LIKE 搜索。[like] 由调用方转义（\ % _ → \\ \% \_），
     * ESCAPE 让它们回到字面字符（与 [MessageDao.search] 同口径）。
     */
    @Query("SELECT $MESSAGE_COLUMNS FROM messages WHERE sessionId = :sessionId AND text LIKE '%'||:like||'%' ESCAPE '\\' ORDER BY rowid ASC LIMIT :limit")
    suspend fun searchInSession(sessionId: String, like: String, limit: Int = 50): List<MessageEntity>

    /** 会话有没有历史消息：温层懒回填的判据（0 = 新会话，返回 null 而不是空层）。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun messageCount(sessionId: String): Int

    /**
     * 回捞投影：带 rowid 的只读行（T9.8 S4）。
     *
     * [bySessionRange] / [searchInSession] 走 $MESSAGE_COLUMNS 返回 MessageEntity，而实体本身
     * 没有 rowid（主键是 TEXT uuid）——回捞块要标 `[rowid N]`、要按 rowid 去重并倒序，
     * 所以这里另起一个纯投影：只 SELECT rowid/role/text，不碰表结构、不需要迁移。
     *
     * 正文截 4000 字：回捞块单条只留 400 字，取全量没有意义。
     */
    @Query("SELECT rowid AS rowId, role, substr(text, 1, 4000) AS text FROM messages WHERE sessionId = :sessionId AND text LIKE '%'||:like||'%' ESCAPE '\\' ORDER BY rowid DESC LIMIT :limit")
    suspend fun searchRecall(sessionId: String, like: String, limit: Int): List<RecallRow>

    /** 区间回捞（rowid 闭区间，倒序 = 新→旧；`/recall Rn` 用它按压缩区间取）。 */
    @Query("SELECT rowid AS rowId, role, substr(text, 1, 4000) AS text FROM messages WHERE sessionId = :sessionId AND rowid >= :fromId AND rowid <= :toId ORDER BY rowid DESC LIMIT :limit")
    suspend fun recallInRange(sessionId: String, fromId: Long, toId: Long, limit: Int): List<RecallRow>

    /** 最近 N 条（区间丢失时的兜底窗口，倒序）。 */
    @Query("SELECT rowid AS rowId, role, substr(text, 1, 4000) AS text FROM messages WHERE sessionId = :sessionId ORDER BY rowid DESC LIMIT :limit")
    suspend fun recentRows(sessionId: String, limit: Int): List<RecallRow>
}

/** 回捞用的一行原文指针：[rowId] 是 messages.rowid，[text] 是截断后的正文。 */
data class RecallRow(val rowId: Long, val role: String, val text: String)
