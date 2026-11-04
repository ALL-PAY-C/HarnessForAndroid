package dev.dsh.mobile.core.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 内存版 [BackupDb]：把库状态序列化成 JSON 落盘，[open] 再读回来。
 *
 * 这样「写包 → 解包 → 合并」整条链路能在纯 JVM 上跑通，而不只是验证参数传递。
 * 事务语义按 SQLite 模拟：块内抛异常则整批回滚。
 */
class FakeBackupDb(override val version: Int = 12) : BackupDb {

    val sessions = LinkedHashMap<String, Row>()
    val messages = LinkedHashMap<String, Row>()

    /** 记录被清过暖层的会话 id，供断言。 */
    val warmCleared = ArrayList<String>()

    /** 让某张表的第 n 次 upsert 抛异常，用来验证回滚。 */
    var failTable: String? = null
    var failAtRow: Int = Int.MAX_VALUE

    override fun counts(): Counts = Counts(sessions.size, messages.size)

    override fun snapshotTo(target: File) {
        target.parentFile?.mkdirs()
        target.writeText(serialize())
    }

    override fun pruneSessions(file: File, sessionIds: Set<String>) {
        val dump = decode(file.readText())
        dump.sessions.filterKeys { it !in sessionIds }.keys.forEach { dump.sessions.remove(it) }
        dump.messages.entries.filter { it.value.str("sessionId") !in sessionIds }
            .forEach { dump.messages.remove(it.key) }
        file.writeText(dump.serialize())
    }

    override fun open(file: File): BackupDump {
        val dump = decode(file.readText())
        return object : BackupDump {
            override val version: Int = dump.version

            override fun rows(table: String): List<Row> = when (table) {
                "sessions" -> dump.sessions.values.toList()
                "messages" -> dump.messages.values.toList()
                else -> emptyList()
            }

            override fun close() = Unit
        }
    }

    override fun titles(): Map<String, String> = sessions.mapValues { it.value.str("title").orEmpty() }

    override fun upsert(table: String, rows: List<Row>) {
        var seen = 0
        for (row in rows) {
            seen++
            if (table == failTable && seen >= failAtRow) throw IllegalStateException("模拟写库失败：" + table)
            val id = row.str("id") ?: continue
            when (table) {
                "sessions" -> sessions[id] = row
                "messages" -> messages[id] = row
            }
        }
    }

    override fun clearContent() {
        sessions.clear()
        messages.clear()
    }

    override fun clearWarmLayers(sessionIds: Collection<String>) {
        warmCleared += sessionIds
    }

    override fun <T> transaction(block: () -> T): T {
        val sessionBackup = LinkedHashMap(sessions)
        val messageBackup = LinkedHashMap(messages)
        return try {
            block()
        } catch (e: Throwable) {
            sessions.clear()
            sessions.putAll(sessionBackup)
            messages.clear()
            messages.putAll(messageBackup)
            throw e
        }
    }

    private fun serialize(): String = JSONObject()
        .put("version", version)
        .put("sessions", rowsToJson(sessions.values))
        .put("messages", rowsToJson(messages.values))
        .toString()

    private fun decode(text: String): Snapshot {
        val root = JSONObject(text)
        return Snapshot(
            version = root.optInt("version", version),
            sessions = rowsFromJson(root.optJSONArray("sessions")),
            messages = rowsFromJson(root.optJSONArray("messages")),
        )
    }

    private class Snapshot(
        val version: Int,
        val sessions: LinkedHashMap<String, Row>,
        val messages: LinkedHashMap<String, Row>,
    ) {
        fun serialize(): String = JSONObject()
            .put("version", version)
            .put("sessions", rowsToJson(sessions.values))
            .put("messages", rowsToJson(messages.values))
            .toString()
    }

    companion object {
        fun sessionRow(id: String, title: String, endpointId: String = "ep-1"): Row = Row(
            linkedMapOf(
                "id" to id,
                "title" to title,
                "cwd" to "/tmp",
                "updatedAt" to 1L,
                "createdAt" to 1L,
                "status" to "active",
                "preview" to title,
                "messageCount" to 1,
                "pinned" to 0,
                "archived" to 0,
                "grp" to null,
                "kind" to "chat",
                "agentId" to null,
                "participantsJson" to null,
                "persona" to null,
                "remoteId" to null,
                "endpointId" to endpointId,
            ),
        )

        fun messageRow(id: String, sessionId: String, text: String, at: Long = 1L): Row = Row(
            linkedMapOf(
                "id" to id,
                "sessionId" to sessionId,
                "role" to "user",
                "text" to text,
                "thought" to null,
                "toolCallsJson" to null,
                "at" to at,
                "streaming" to 0,
                "authorId" to null,
                "authorName" to null,
                "attachmentsJson" to null,
                "durationMs" to null,
                "turnId" to null,
                "inputTokens" to null,
                "outputTokens" to null,
            ),
        )

        private fun rowsToJson(rows: Collection<Row>): JSONArray {
            val array = JSONArray()
            for (row in rows) {
                val obj = JSONObject()
                for ((key, value) in row.values) obj.put(key, value ?: JSONObject.NULL)
                array.put(obj)
            }
            return array
        }

        private fun rowsFromJson(array: JSONArray?): LinkedHashMap<String, Row> {
            val out = LinkedHashMap<String, Row>()
            if (array == null) return out
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val values = LinkedHashMap<String, Any?>()
                for (key in obj.keys()) {
                    values[key] = when (val v = obj.opt(key)) {
                        JSONObject.NULL, null -> null
                        is Number -> v.toLong()
                        else -> v
                    }
                }
                val id = values["id"] as? String ?: continue
                out[id] = Row(values)
            }
            return out
        }
    }
}
