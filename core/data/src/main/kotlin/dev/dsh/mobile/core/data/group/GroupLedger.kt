package dev.dsh.mobile.core.data.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 规划书条目的五态。名字就是落盘值（[SerialName]），改文案不改 id，存量数据不会失效。
 * 顺序即 UI 分组顺序：待办 → 进行 → 需改 → 完成 → 阻塞。
 */
@Serializable
enum class LedgerStatus(val label: String) {
    @SerialName("todo") Todo("待办"),
    @SerialName("doing") Doing("进行"),
    @SerialName("review") Review("需改"),
    @SerialName("done") Done("完成"),
    @SerialName("blocked") Blocked("阻塞"),
}

/**
 * 规划书一条。
 *
 * [owner] 存角色 id（[GroupRole.id]）或群成员 id —— 两种都是字符串，读侧自己解析，
 * 这样账本不依赖群成员表，成员被踢出群时条目照样能显示负责人。
 * [updatedAt] 每次写入由仓库盖时间戳，列表与分组都按它倒序。
 */
@Serializable
data class LedgerEntry(
    val id: String,
    val title: String,
    val status: LedgerStatus = LedgerStatus.Todo,
    val owner: String = "",
    val note: String = "",
    val updatedAt: Long = 0L,
)

/**
 * 一本规划书：条目列表 + 四个纯操作（upsert / updateStatus / appendNote / 排序分组）。
 *
 * 纯函数、无 IO、每次返回新对象 —— 仓库只负责「读 JSON → 调这里 → 写 JSON」，
 * 因此状态机与排序可以在 JVM 单测里直接验，不需要 DataStore。
 */
@Serializable
data class GroupLedger(val entries: List<LedgerEntry> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    fun entry(id: String): LedgerEntry? = entries.firstOrNull { it.id == id }

    /** 全量列表：updatedAt 倒序（同刻按写入顺序，后写的在前，保证稳定）。 */
    fun sorted(): List<LedgerEntry> = entries.withIndex()
        .sortedWith(compareByDescending<IndexedValue<LedgerEntry>> { it.value.updatedAt }.thenByDescending { it.index })
        .map { it.value }

    /** 按状态分组，组内同样 updatedAt 倒序；空组也留在 map 里，UI 好按固定顺序渲染。 */
    fun byStatus(): Map<LedgerStatus, List<LedgerEntry>> =
        LedgerStatus.entries.associateWith { s ->
            entries.withIndex()
                .filter { it.value.status == s }
                .sortedWith(compareByDescending<IndexedValue<LedgerEntry>> { it.value.updatedAt }.thenByDescending { it.index })
                .map { it.value }
        }

    /** 同 id 覆盖、没有就追加；[now] 由调用方（仓库）给，纯函数不读时钟。 */
    fun upsert(entry: LedgerEntry, now: Long): GroupLedger {
        require(entry.id.isNotBlank()) { "条目 id 不能为空" }
        val trimmed = entry.copy(
            title = entry.title.trim(),
            owner = entry.owner.trim(),
            note = entry.note.trim(),
            updatedAt = now,
        )
        require(trimmed.title.isNotEmpty()) { "条目标题不能为空" }
        val next = entries.filterNot { it.id == trimmed.id } + trimmed
        return GroupLedger(trimOldest(next))
    }

    /** 改状态；条目不存在时原样返回（不凭空造条目，免得 UI 误点长出幽灵行）。 */
    fun updateStatus(id: String, status: LedgerStatus, now: Long): GroupLedger {
        val target = entries.firstOrNull { it.id == id } ?: return this
        if (target.status == status) return this
        return GroupLedger(entries.map { if (it.id == id) it.copy(status = status, updatedAt = now) else it })
    }

    /** 追加备注：已有备注换行接在后面；空备注不写（不刷新时间戳，避免无意义的排序抖动）。 */
    fun appendNote(id: String, note: String, now: Long): GroupLedger {
        val text = note.trim()
        if (text.isEmpty()) return this
        val target = entries.firstOrNull { it.id == id } ?: return this
        val merged = if (target.note.isBlank()) text else target.note.trimEnd() + "\n" + text
        return GroupLedger(entries.map { if (it.id == id) it.copy(note = merged, updatedAt = now) else it })
    }

    fun remove(id: String): GroupLedger = GroupLedger(entries.filterNot { it.id == id })

    /** 条目上限：规划书是给人看的清单，超量先丢最旧的（与房间账本同口径）。 */
    private fun trimOldest(list: List<LedgerEntry>): List<LedgerEntry> {
        if (list.size <= MAX_ENTRIES) return list
        val keep = list.withIndex()
            .sortedWith(compareByDescending<IndexedValue<LedgerEntry>> { it.value.updatedAt }.thenByDescending { it.index })
            .take(MAX_ENTRIES)
            .map { it.value }
        return keep
    }

    companion object {
        const val MAX_ENTRIES: Int = 50
        val EMPTY: GroupLedger = GroupLedger()
    }
}
