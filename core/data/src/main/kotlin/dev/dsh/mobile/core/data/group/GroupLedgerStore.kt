package dev.dsh.mobile.core.data.group

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.groupDataStore by preferencesDataStore(
    name = "dsh_group",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 规划书仓库（T9.7a）：一个会话一本，存在 DataStore 的一条 JSON 里，key = sessionId。
 *
 * 为什么不建 Room 表：规划书是「最多 50 条、整体读写、按会话隔离」的小文档，
 * 与预设覆盖层（preset_cards）同一种形态；建表要多一次 schema 迁移 + 一套 DAO/Entity，
 * 换不来查询能力（读侧永远是「整本取出来按状态分组」）。所以走 key-value/JSON，
 * 零迁移、零新表，坏数据被 ReplaceFileCorruptionHandler 兜住。
 *
 * 时钟由构造参数注入：单测里传固定时间就能验排序，不用 sleep。
 */
class GroupLedgerStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.groupDataStore)

    private val key = stringPreferencesKey("group_ledgers")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 观察通道：UI 直接 collect 这一本，任何写入都会推一帧。 */
    fun ledger(sessionId: String): Flow<GroupLedger> =
        dataStore.data.map { decode(it[key].orEmpty())[sessionId] ?: GroupLedger.EMPTY }

    /** 一次取全量（updatedAt 倒序）；写后要读快照时用它。 */
    suspend fun list(sessionId: String): List<LedgerEntry> = ledger(sessionId).first().sorted()

    /** 新增或整条覆盖（同 id 覆盖）。 */
    suspend fun upsert(sessionId: String, entry: LedgerEntry): GroupLedger = write(sessionId) { it.upsert(entry, clock()) }

    /** 只改状态；条目不存在时不动。 */
    suspend fun updateStatus(sessionId: String, id: String, status: LedgerStatus): GroupLedger =
        write(sessionId) { it.updateStatus(id, status, clock()) }

    /** 追加一条备注（换行拼接，空串忽略）。 */
    suspend fun appendNote(sessionId: String, id: String, note: String): GroupLedger =
        write(sessionId) { it.appendNote(id, note, clock()) }

    suspend fun remove(sessionId: String, id: String): GroupLedger = write(sessionId) { it.remove(id) }

    /** 读改写一次完成，返回写后的那一本（UI 拿到就能直接渲染，不必再 first() 一次）。 */
    private suspend fun write(sessionId: String, block: (GroupLedger) -> GroupLedger): GroupLedger {
        require(sessionId.isNotBlank()) { "会话 id 不能为空" }
        var result = GroupLedger.EMPTY
        dataStore.edit { prefs ->
            val map = decode(prefs[key].orEmpty()).toMutableMap()
            val next = block(map[sessionId] ?: GroupLedger.EMPTY)
            if (next.isEmpty) map.remove(sessionId) else map[sessionId] = next
            result = next
            prefs[key] = json.encodeToString(map)
        }
        return result
    }

    private fun decode(raw: String): Map<String, GroupLedger> =
        runCatching { json.decodeFromString<Map<String, GroupLedger>>(raw) }.getOrDefault(emptyMap())
}
