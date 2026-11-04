package dev.dsh.mobile.core.common.diag

import java.security.MessageDigest

/**
 * 一份「最后可用配置」快照（T9.12 §3.3）。
 *
 * entries 是 Preferences 全量键的字符串化副本：回滚只需要值，不需要类型 —— 类型由
 * SettingsStore.applyEntries 按「当前配置里该键的实际类型」反推，快照存类型反而会分叉。
 * hash 是内容指纹，用来跳过「这次启动的配置和上次成功时一模一样」的重复写盘。
 */
data class SettingsSnapshot(
    /** 单调递增的版本号，同时是文件名 settings-v<version>.json 的后缀。 */
    val version: Int,
    val at: Long,
    val versionCode: Int,
    val entries: Map<String, String>,
    /** [LkgPlanner.hashOf] 的结果：sha256(排序后的 key=value 拼接)。 */
    val hash: String,
) {

    fun toJson(): String {
        val b = StringBuilder(64 + entries.size * 32)
        b.append("{\"version\":").append(version)
        b.append(",\"at\":").append(at)
        b.append(",\"versionCode\":").append(versionCode)
        b.append(",\"hash\":\"").append(LkgPlanner.escape(hash)).append('"')
        b.append(",\"entries\":{")
        var first = true
        // 按 key 排序落盘：同样的 entries 在任何插入顺序下都产出同样的字节，
        // 崩溃后人工 diff 两份快照时不会看到假变更。
        for ((k, v) in entries.entries.sortedBy { it.key }) {
            if (!first) b.append(',')
            first = false
            b.append('"').append(LkgPlanner.escape(k)).append("\":\"")
                .append(LkgPlanner.escape(v)).append('"')
        }
        b.append("}}")
        return b.toString()
    }

    companion object {
        /** 用 entries 现算 hash，避免调用方传错。 */
        fun of(version: Int, at: Long, versionCode: Int, entries: Map<String, String>): SettingsSnapshot =
            SettingsSnapshot(version, at, versionCode, entries, LkgPlanner.hashOf(entries))

        /** 损坏/不认识的快照返回 null：一份坏文件不该让整个回滚链断掉。 */
        fun fromJson(raw: String): SettingsSnapshot? = runCatching {
            val root = MiniJson(raw).parse() as Map<*, *>
            val entriesRaw = root["entries"] as Map<*, *>
            val entries = LinkedHashMap<String, String>(entriesRaw.size)
            for ((k, v) in entriesRaw) {
                entries[k as String] = v as String
            }
            SettingsSnapshot(
                version = (root["version"] as Number).toInt(),
                at = (root["at"] as Number).toLong(),
                versionCode = (root["versionCode"] as Number).toInt(),
                entries = entries,
                hash = root["hash"] as String,
            )
        }.getOrNull()
    }
}

/**
 * 一次回滚要写的差异（T9.12 §3.3）。
 *
 * restore 只含「值不同」与「快照有、当前没有」的键：整体覆盖会把用户崩溃前刚改好的
 * 无关设置一起抹掉。
 */
data class LkgPlan(
    val targetVersion: Int,
    /** 要写回的键 → 值。 */
    val restore: Map<String, String>,
    /** 快照里没有、当前有：用户后来新增的，保留（不写回、不删除）。 */
    val keep: List<String>,
    /** 快照里有、当前没有：恢复，同时也在 restore 里。 */
    val dropped: List<String>,
    /** 没有需要写回的键（keep 只是保留，不算写）。 */
    val isEmpty: Boolean,
)

object LkgPlanner {

    /**
     * 只回滚值不同的键。
     *
     * 三种关系分别处理：值不同 → restore；快照没有而当前有 → keep（用户后来加的，保留）；
     * 快照有而当前没有 → dropped（恢复）。顺序无关：restore 是 Map，keep/dropped 排序后返回。
     */
    fun plan(lkg: SettingsSnapshot, current: Map<String, String>): LkgPlan {
        val restore = LinkedHashMap<String, String>()
        val dropped = ArrayList<String>()
        for ((key, value) in lkg.entries) {
            val now = current[key]
            if (now == null) {
                restore[key] = value
                dropped.add(key)
            } else if (now != value) {
                restore[key] = value
            }
        }
        val keep = current.keys.filter { !lkg.entries.containsKey(it) }.sorted()
        dropped.sort()
        return LkgPlan(
            targetVersion = lkg.version,
            restore = restore,
            keep = keep,
            dropped = dropped,
            isEmpty = restore.isEmpty(),
        )
    }

    /** sha256(按 key 排序的 "key=value\n" 拼接)。排序让结果与 Map 实现顺序无关。 */
    fun hashOf(entries: Map<String, String>): String {
        val canonical = StringBuilder()
        for ((k, v) in entries.entries.sortedBy { it.key }) {
            canonical.append(k).append('=').append(v).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val n = b.toInt() and 0xff
            hex.append(HEX[n ushr 4]).append(HEX[n and 0x0f])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"

    internal fun escape(raw: String): String {
        val b = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (ch < ' ') b.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else b.append(ch)
            }
        }
        return b.toString()
    }
}

/**
 * 只服务快照的极简 JSON 解析器（core:common 是纯 JVM 模块，不能引 android 的 org.json）。
 *
 * 只实现快照用得到的子集：对象 / 数组 / 字符串 / 数字 / true / false / null。
 * 任何语法错误直接抛，由 [SettingsSnapshot.fromJson] 的 runCatching 兜成 null。
 */
private class MiniJson(private val src: String) {

    private var i = 0

    fun parse(): Any? {
        val value = value()
        skipWhitespace()
        require(i == src.length) { "trailing content at " + i }
        return value
    }

    private fun value(): Any? {
        skipWhitespace()
        require(i < src.length) { "unexpected end" }
        return when (val c = src[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else throw IllegalStateException("unexpected " + c + " at " + i)
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            i++
            return out
        }
        while (true) {
            skipWhitespace()
            val key = str()
            skipWhitespace()
            expect(':')
            out[key] = value()
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return out
                }
                else -> throw IllegalStateException("expected , or } at " + i)
            }
        }
    }

    private fun arr(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            out.add(value())
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return out
                }
                else -> throw IllegalStateException("expected , or ] at " + i)
            }
        }
    }

    private fun str(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            require(i < src.length) { "unterminated string" }
            when (val c = src[i++]) {
                '"' -> return b.toString()
                '\\' -> {
                    require(i < src.length) { "bad escape" }
                    when (val e = src[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            val hex = src.substring(i, i + 4)
                            i += 4
                            b.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalStateException("bad escape \\" + e)
                    }
                }
                else -> b.append(c)
            }
        }
    }

    private fun number(): Number {
        val start = i
        if (peek() == '-') i++
        while (i < src.length && src[i].isDigit()) i++
        var isDouble = false
        if (i < src.length && src[i] == '.') {
            isDouble = true
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            isDouble = true
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i].isDigit()) i++
        }
        val text = src.substring(start, i)
        return if (isDouble) text.toDouble() else text.toLong()
    }

    private fun literal(word: String, result: Any?): Any? {
        require(src.startsWith(word, i)) { "expected " + word + " at " + i }
        i += word.length
        return result
    }

    private fun skipWhitespace() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun peek(): Char = if (i < src.length) src[i] else '\u0000'

    private fun expect(c: Char) {
        require(peek() == c) { "expected " + c + " at " + i }
        i++
    }
}
