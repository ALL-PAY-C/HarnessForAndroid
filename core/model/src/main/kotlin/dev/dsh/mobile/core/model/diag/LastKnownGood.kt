package dev.dsh.mobile.core.model.diag

/**
 * 存储与时钟全部注入：纯逻辑层要能在 JVM 单测与 :node 进程里跑，落盘细节留给 app 层。
 * put(key, null) 表示删除，避免再开一个 delete 方法。
 */
interface DiagStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(prefix: String): List<String>
}

data class ConfigSnapshot(
    val id: String,
    val payload: String,
    val createdAt: Long,
    val crashCount: Int,
)

/**
 * 只保留最近 [maxSnapshots] 份配置。快照按序号单调递增存，不用时间排序：
 * 同一毫秒内连写两份时钟会相等，序号才是可靠顺序。
 */
class LastKnownGood(
    private val store: DiagStore,
    private val clock: () -> Long,
    private val maxSnapshots: Int = 3,
) {

    fun markGood(payload: String) {
        if (current()?.payload == payload) return
        val id = idFor(nextSeq())
        store.put(SNAP_PREFIX + id, encode(ConfigSnapshot(id, payload, clock(), 0)))
        store.put(CURSOR_KEY, id)
        prune()
    }

    fun current(): ConfigSnapshot? {
        store.get(CURSOR_KEY)?.let { id -> load(id)?.let { return it } }
        return ordered().lastOrNull()?.let { load(it) }
    }

    /** 回滚只移动游标、不删快照：用户可能还要「撤销回滚」，删掉就再也回不去了。 */
    fun rollbackOneLevel(): ConfigSnapshot? {
        val ids = ordered()
        val cursor = store.get(CURSOR_KEY) ?: ids.lastOrNull() ?: return null
        val index = ids.indexOf(cursor)
        if (index <= 0) return null
        val previousId = ids[index - 1]
        val previous = load(previousId) ?: return null
        store.put(CURSOR_KEY, previousId)
        return previous
    }

    fun recordCrash(snapshotId: String) {
        val snapshot = load(snapshotId) ?: return
        store.put(SNAP_PREFIX + snapshotId, encode(snapshot.copy(crashCount = snapshot.crashCount + 1)))
    }

    private fun prune() {
        val ids = ordered().toMutableList()
        if (ids.size <= maxSnapshots) return
        val cursor = store.get(CURSOR_KEY)
        val iterator = ids.iterator()
        while (ids.size > maxSnapshots && iterator.hasNext()) {
            val id = iterator.next()
            // 当前快照是唯一可回滚的锚点，即使它最旧也不能淘汰，否则回滚链断在这里。
            if (id == cursor) continue
            store.put(SNAP_PREFIX + id, null)
            iterator.remove()
        }
    }

    private fun ordered(): List<String> = store.keys(SNAP_PREFIX)
        .map { it.removePrefix(SNAP_PREFIX) }
        .sortedBy { it.toLongOrNull() ?: 0L }

    private fun nextSeq(): Long = (ordered().lastOrNull()?.toLongOrNull() ?: 0L) + 1L

    private fun idFor(seq: Long): String = seq.toString().padStart(10, '0')

    private fun load(id: String): ConfigSnapshot? {
        val raw = store.get(SNAP_PREFIX + id) ?: return null
        // payload 放最后一段：配置本身可能带换行，limit=4 保证只有前三个字段参与切分。
        val parts = raw.split('\n', limit = 4)
        if (parts.size < 4) return null
        return ConfigSnapshot(
            id = id,
            payload = parts[3],
            createdAt = parts[1].toLongOrNull() ?: 0L,
            crashCount = parts[2].toIntOrNull() ?: 0,
        )
    }

    private fun encode(snapshot: ConfigSnapshot): String =
        SCHEMA + "\n" + snapshot.createdAt + "\n" + snapshot.crashCount + "\n" + snapshot.payload

    private companion object {
        const val SCHEMA = "lkg1"
        const val SNAP_PREFIX = "lkg.snap."
        const val CURSOR_KEY = "lkg.cursor"
    }
}
