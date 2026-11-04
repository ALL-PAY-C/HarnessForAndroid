package dev.dsh.mobile.core.data.backup

import java.io.Closeable
import java.io.File

/**
 * 一行数据：列名 → 值，保留列顺序（拼 INSERT 时列顺序必须稳定）。
 *
 * 用泛型行而不是 Room 实体：恢复要在「包里的库」和「本机的库」之间搬数据，
 * 两边列集合必须完全一致，而实体类只能表达本机版本。泛型行让迁移链升级后的
 * 旧库也能读，且让引擎的合并策略能在纯 JVM 测试里跑。
 */
class Row(val values: LinkedHashMap<String, Any?>) {
    operator fun get(column: String): Any? = values[column]

    fun str(column: String): String? = values[column] as? String

    fun int(column: String): Int? = (values[column] as? Number)?.toInt()

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): Row = Row(linkedMapOf(*pairs))
    }
}

/** 包内数据库的只读视图（已经过迁移链升级到本机版本）。 */
interface BackupDump : Closeable {
    val version: Int

    fun rows(table: String): List<Row>
}

/**
 * 恢复引擎要的数据库能力，全部走这一层。
 *
 * 纯 JVM 单测里 android.jar 是桩，Room 建不起来；把库操作收成接口后，
 * 写包 / 恢复 / 合并策略都能在临时目录 + 内存 Fake 上验证。
 */
interface BackupDb {

    /** 本机 Room 版本（= PRAGMA user_version）。 */
    val version: Int

    /** 当前库的行数统计（plugins / attachments 由 Writer 自己数）。 */
    fun counts(): Counts

    /**
     * 把当前库写成一个能独立打开的 SQLite 文件。
     *
     * 优先 VACUUM INTO：单文件、不含 -wal/-shm、天然一致。minSdk 26 上没有
     * VACUUM INTO（SQLite 3.27 / API 29），回退到 checkpoint + 拷主库。
     */
    fun snapshotTo(target: File)

    /** 只保留这些会话（消息一并删）。空集表示一个都不留。 */
    fun pruneSessions(file: File, sessionIds: Set<String>)

    /** 打开一个包内数据库文件（会先升到本机版本）。调用方负责 close。 */
    fun open(file: File): BackupDump

    /** 本机所有会话 id → 标题，用于「同名不同 id 加（导入）」判定。 */
    fun titles(): Map<String, String>

    /** 按主键 INSERT OR REPLACE。 */
    fun upsert(table: String, rows: List<Row>)

    /** 清空会话与消息（Replace 用；warm_layers 一起清，否则回填的是旧摘要）。 */
    fun clearContent()

    /** 导入后让受影响会话的暖层失效，走懒回填。 */
    fun clearWarmLayers(sessionIds: Collection<String>)

    fun <T> transaction(block: () -> T): T

    object Empty : BackupDb {
        override val version: Int = 0

        override fun counts(): Counts = Counts()

        override fun snapshotTo(target: File) = Unit

        override fun pruneSessions(file: File, sessionIds: Set<String>) = Unit

        override fun open(file: File): BackupDump = object : BackupDump {
            override val version: Int = 0
            override fun rows(table: String): List<Row> = emptyList()
            override fun close() = Unit
        }

        override fun titles(): Map<String, String> = emptyMap()

        override fun upsert(table: String, rows: List<Row>) = Unit

        override fun clearContent() = Unit

        override fun clearWarmLayers(sessionIds: Collection<String>) = Unit

        override fun <T> transaction(block: () -> T): T = block()
    }
}
