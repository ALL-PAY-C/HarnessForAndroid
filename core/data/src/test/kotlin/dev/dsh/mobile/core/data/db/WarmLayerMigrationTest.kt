package dev.dsh.mobile.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v11 -> v12 迁移的 JVM 断言（T9.8 S1）。
 *
 * 这里只能跑到「迁移发出哪些 SQL」这一层：本模块没有 Robolectric / room-testing，
 * JVM 单测里的 SQLite 是桩，开真库做 11→12 只能在设备上跑。所以断言写成两条：
 * 1) 版本对、语句只有两条、都是 IF NOT EXISTS 的纯新增；
 * 2) 没有任何一句动 sessions / messages（没有 ALTER / DROP / DELETE / UPDATE / INSERT），
 *    即老数据不可能被这一版迁移改写。
 * 真 SQLite 上「升到 12 后旧表行数不变」由 build/verify-warm-layer-sql.py 实跑校验。
 */
class WarmLayerMigrationTest {

    private fun runMigration(): Pair<Int, List<String>> {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        val migration = DshDatabase.MIGRATION_11_12
        migration.migrate(database)
        return migration.startVersion to statements
    }

    @Test fun migrationMovesFromElevenToTwelve() {
        val (start, _) = runMigration()
        assertEquals(11, start)
        assertEquals(12, DshDatabase.MIGRATION_11_12.endVersion)
    }

    @Test fun migrationOnlyCreatesWarmLayersTableAndIndex() {
        val (_, statements) = runMigration()
        assertEquals(2, statements.size)

        val table = statements[0]
        assertTrue(table, table.startsWith("CREATE TABLE IF NOT EXISTS `warm_layers`"))
        listOf(
            "`sessionId` TEXT NOT NULL",
            "`payload` TEXT NOT NULL",
            "`updatedAt` INTEGER NOT NULL",
            "`coveredUntilMessageId` INTEGER NOT NULL",
            "`warmVersion` INTEGER NOT NULL",
            "`source` TEXT NOT NULL",
            "PRIMARY KEY(`sessionId`)",
        ).forEach { column -> assertTrue("缺列：" + column + " in " + table, table.contains(column)) }

        assertEquals("CREATE INDEX IF NOT EXISTS `index_warm_layers_updatedAt` ON `warm_layers` (`updatedAt`)", statements[1])
    }

    @Test fun migrationLeavesExistingTablesUntouched() {
        val (_, statements) = runMigration()
        val destructive = listOf("ALTER TABLE", "DROP TABLE", "DELETE FROM", "UPDATE ", "INSERT INTO", "sessions", "messages")
        statements.forEach { sql ->
            destructive.forEach { word -> assertFalse("迁移改了老表：" + word + " in " + sql, sql.contains(word)) }
        }
    }

    @Test fun warmLayersColumnsMatchEntity() {
        val (_, statements) = runMigration()
        val ddl = statements[0]
        // Entity 的六个字段一个不少地出现在 DDL 里，否则 Room 打开库时会报 schema 不匹配。
        val fields = WarmLayerEntity::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("sessionId", "payload", "updatedAt", "coveredUntilMessageId", "warmVersion", "source"), fields)
        fields.forEach { name -> assertTrue("DDL 缺字段 " + name, ddl.contains("`" + name + "`")) }
    }

    @Test fun daoExposesRecallQueries() {
        val names = WarmLayerDao::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("observe", "get", "upsert", "delete", "bySessionRange", "searchInSession", "messageCount")))
    }
}
