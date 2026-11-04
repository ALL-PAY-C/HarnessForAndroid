package dev.dsh.mobile.core.data.backup

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** DataStore Preferences 的一个值（对应 preferences.proto 的 Value oneof）。 */
sealed class PbValue {
    data class Bool(val value: Boolean) : PbValue()
    data class Float32(val value: Float) : PbValue()
    data class Int32(val value: Int) : PbValue()
    data class Int64(val value: Long) : PbValue()
    data class Str(val value: String) : PbValue()
    data class StrSet(val value: Set<String>) : PbValue()
    data class Float64(val value: Double) : PbValue()
    class Raw(val value: ByteArray) : PbValue()
}

/**
 * *.preferences_pb 的最小读写实现（androidx.datastore preferences 的 protobuf 线格式）。
 *
 * 为什么要自己解：合并设置必须按 key 粒度做（覆盖同名键、保留 default_cwd / cwd_tree_uri），
 * 而整个文件是 protobuf，不能像 JSON 那样挑字段；引 protobuf 运行时又要加依赖。
 * 线格式只有 map<string, Value> 一层，手写两百行就够，且能直接单测。
 *
 * 未知字段在解码时被跳过、编码时不保留 —— 本 App 自己产出的 pb 只有这些字段，
 * 兼容性由「未知字段忽略」保证不炸，但合并后会丢，所以只用于本 App 已知的 DataStore 文件。
 */
object PreferencesPb {

    private val UTF8 = StandardCharsets.UTF_8

    fun decode(bytes: ByteArray): LinkedHashMap<String, PbValue> {
        val out = LinkedHashMap<String, PbValue>()
        val r = Reader(bytes)
        while (!r.done) {
            val field = r.tag()
            if (field.first == 1 && field.second == 2) {
                val entry = r.lengthDelimited()
                val pair = decodeEntry(entry)
                if (pair.first.isNotEmpty()) out[pair.first] = pair.second
            } else {
                r.skip(field.second)
            }
        }
        return out
    }

    fun encode(map: Map<String, PbValue>): ByteArray {
        val w = Writer()
        for ((key, value) in map) {
            val entry = Writer()
            entry.string(1, key)
            entry.bytes(2, encodeValue(value))
            w.bytes(1, entry.out.toByteArray())
        }
        return w.out.toByteArray()
    }

    /**
     * 合并：以本机为底，包内同名键覆盖；keep 里的键永远保留本机值（设备绑定项）。
     * 本机没有、包内有的键照常新增。
     */
    fun merge(
        local: Map<String, PbValue>,
        incoming: Map<String, PbValue>,
        keep: Set<String>,
    ): LinkedHashMap<String, PbValue> {
        val out = LinkedHashMap(local)
        for ((key, value) in incoming) {
            if (key in keep) continue
            out[key] = value
        }
        return out
    }

    private fun decodeEntry(bytes: ByteArray): Pair<String, PbValue> {
        var key = ""
        var value: PbValue = PbValue.Str("")
        val r = Reader(bytes)
        while (!r.done) {
            val field = r.tag()
            when {
                field.first == 1 && field.second == 2 -> key = String(r.lengthDelimited(), UTF8)
                field.first == 2 && field.second == 2 -> value = decodeValue(r.lengthDelimited())
                else -> r.skip(field.second)
            }
        }
        return key to value
    }

    private fun decodeValue(bytes: ByteArray): PbValue {
        var out: PbValue = PbValue.Str("")
        val r = Reader(bytes)
        while (!r.done) {
            val field = r.tag()
            when {
                field.first == 1 && field.second == 0 -> out = PbValue.Bool(r.varint() != 0L)
                field.first == 2 && field.second == 5 -> out = PbValue.Float32(Float.fromBits(r.fixed32()))
                field.first == 3 && field.second == 0 -> out = PbValue.Int32(r.varint().toInt())
                field.first == 4 && field.second == 0 -> out = PbValue.Int64(r.varint())
                field.first == 5 && field.second == 2 -> out = PbValue.Str(String(r.lengthDelimited(), UTF8))
                field.first == 6 && field.second == 2 -> out = PbValue.StrSet(decodeStringSet(r.lengthDelimited()))
                field.first == 7 && field.second == 1 -> out = PbValue.Float64(Double.fromBits(r.fixed64()))
                field.first == 8 && field.second == 2 -> out = PbValue.Raw(r.lengthDelimited())
                else -> r.skip(field.second)
            }
        }
        return out
    }

    private fun encodeValue(value: PbValue): ByteArray {
        val w = Writer()
        when (value) {
            is PbValue.Bool -> w.varintField(1, if (value.value) 1L else 0L)
            is PbValue.Float32 -> w.fixed32(2, value.value.toRawBits())
            is PbValue.Int32 -> w.varintField(3, value.value.toLong())
            is PbValue.Int64 -> w.varintField(4, value.value)
            is PbValue.Str -> w.string(5, value.value)
            is PbValue.StrSet -> w.bytes(6, encodeStringSet(value.value))
            is PbValue.Float64 -> w.fixed64(7, value.value.toRawBits())
            is PbValue.Raw -> w.bytes(8, value.value)
        }
        return w.out.toByteArray()
    }

    private fun decodeStringSet(bytes: ByteArray): Set<String> {
        val out = LinkedHashSet<String>()
        val r = Reader(bytes)
        while (!r.done) {
            val field = r.tag()
            if (field.first == 1 && field.second == 2) out += String(r.lengthDelimited(), UTF8) else r.skip(field.second)
        }
        return out
    }

    private fun encodeStringSet(values: Set<String>): ByteArray {
        val w = Writer()
        for (v in values) w.string(1, v)
        return w.out.toByteArray()
    }

    private class Reader(private val b: ByteArray, private var i: Int = 0) {
        val done: Boolean get() = i >= b.size

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                require(i < b.size) { "protobuf 截断" }
                val byte = b[i++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift <= 63) { "protobuf varint 过长" }
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7L).toInt()
        }

        fun lengthDelimited(): ByteArray {
            val len = varint().toInt()
            require(len >= 0 && i + len <= b.size) { "protobuf 长度越界" }
            val out = b.copyOfRange(i, i + len)
            i += len
            return out
        }

        fun fixed32(): Int {
            require(i + 4 <= b.size) { "protobuf 截断" }
            val v = (b[i].toInt() and 0xFF) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return v
        }

        fun fixed64(): Long {
            var v = 0L
            for (k in 0 until 8) {
                require(i + k < b.size) { "protobuf 截断" }
                v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
            }
            i += 8
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> i += 8
                2 -> i += varint().toInt()
                5 -> i += 4
                else -> throw IllegalArgumentException("不支持的 protobuf wire type " + wire)
            }
            require(i <= b.size) { "protobuf 截断" }
        }
    }

    private class Writer {
        val out = ByteArrayOutputStream()

        fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write((v and 0x7F).toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun varintField(field: Int, value: Long) {
            varint(field.toLong() shl 3)
            varint(value)
        }

        fun fixed32(field: Int, bits: Int) {
            varint((field.toLong() shl 3) or 5L)
            out.write(bits and 0xFF)
            out.write((bits ushr 8) and 0xFF)
            out.write((bits ushr 16) and 0xFF)
            out.write((bits ushr 24) and 0xFF)
        }

        fun fixed64(field: Int, bits: Long) {
            varint((field.toLong() shl 3) or 1L)
            for (k in 0 until 8) out.write(((bits ushr (8 * k)) and 0xFF).toInt())
        }

        fun bytes(field: Int, value: ByteArray) {
            varint((field.toLong() shl 3) or 2L)
            varint(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(UTF8))
    }
}
