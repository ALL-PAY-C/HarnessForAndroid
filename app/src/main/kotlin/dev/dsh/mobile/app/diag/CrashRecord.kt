package dev.dsh.mobile.app.diag

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条崩溃记录。字段与 research/T912-crash-lkg.md §2.2 对齐，只是把文档里的嵌套对象压平成
 * 单层字符串 —— 落盘只为诊断与去重，嵌套结构既没有查询价值又得多写解析代码。
 *
 * appVersion / dbVersion / device 是「哪台机器、哪个版本」的三元组：没有它，同一条 signature
 * 在不同版本上的复现次数分不开。
 */
data class CrashRecord(
    val ts: Long,
    val kind: String,
    val thread: String,
    val throwable: String,
    val message: String,
    val frames: List<String>,
    val appLog: List<String>,
    val process: String,
    val bootAttempt: String,
    val appVersion: String,
    val dbVersion: Int,
    val device: String,
    /** CrashClassifier.keyOf 的 sha256 前 8 位：同因去重与「同签名连续 N 次」判定用。 */
    val signature: String,
    val schema: Int = SCHEMA,
) {

    fun toJson(): String {
        val o = JSONObject()
        o.put("schema", schema)
        o.put("ts", ts)
        o.put("kind", kind)
        o.put("signature", signature)
        o.put("thread", thread)
        o.put("throwable", throwable)
        o.put("message", message)
        o.put("frames", JSONArray(frames))
        o.put("appLog", JSONArray(appLog))
        o.put("process", process)
        o.put("bootAttempt", bootAttempt)
        o.put("appVersion", appVersion)
        o.put("dbVersion", dbVersion)
        o.put("device", device)
        return o.toString()
    }

    companion object {
        const val SCHEMA = 1

        /** 不认识/损坏的文件返回 null：一条坏记录不该让崩溃列表整页打不开。 */
        fun fromJson(raw: String): CrashRecord? = runCatching {
            val o = JSONObject(raw)
            CrashRecord(
                ts = o.optLong("ts"),
                kind = o.optString("kind"),
                thread = o.optString("thread"),
                throwable = o.optString("throwable"),
                message = o.optString("message"),
                frames = o.optJSONArray("frames").toStringList(),
                appLog = o.optJSONArray("appLog").toStringList(),
                process = o.optString("process"),
                bootAttempt = o.optString("bootAttempt"),
                appVersion = o.optString("appVersion"),
                dbVersion = o.optInt("dbVersion"),
                device = o.optString("device"),
                signature = o.optString("signature"),
                schema = o.optInt("schema", SCHEMA),
            )
        }.getOrNull()

        private fun JSONArray?.toStringList(): List<String> {
            val array = this ?: return emptyList()
            return buildList(array.length()) {
                for (i in 0 until array.length()) add(array.optString(i))
            }
        }
    }
}
