package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BackupNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val ts = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    /** 生成的固定形状 + 能被解析回同一秒。 */
    @Test
    fun nameRoundTripsThroughParse() {
        val name = backupFileName(ts, "2.7.0", utc)
        assertEquals("hfa-backup-20231114-221320-v2.7.0.zip", name)
        val parsed = parseBackupName(name, utc)
        assertEquals(ts, parsed.ts)
        assertEquals("2.7.0", parsed.version)
    }

    /** 时间戳一律按传入时区渲染，不跟系统默认时区。 */
    @Test
    fun timezoneIsHonored() {
        val shanghai = TimeZone.getTimeZone("GMT+08:00")
        val utcName = backupFileName(ts, "2.7.0", utc)
        val shName = backupFileName(ts, "2.7.0", shanghai)
        assertEquals("hfa-backup-20231115-061320-v2.7.0.zip", shName)
        assertNotEquals(utcName, shName)
        assertEquals(ts, parseBackupName(shName, shanghai).ts)
    }

    /** 版本名里的非法字符全被清掉，产物仍是合法文件名。 */
    @Test
    fun illegalCharactersAreSanitized() {
        val name = backupFileName(ts, "2.7.0 / beta:1*?\"<>|", utc)
        assertTrue(name, name.startsWith("hfa-backup-20231114-221320-v"))
        assertTrue(name.endsWith(".zip"))
        assertTrue(name.contains("beta"))
        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' ').forEach {
            assertTrue("文件名仍含非法字符 $it: $name", !name.contains(it))
        }
        assertEquals("0", sanitizeVersionName("  "))
    }

    /** 垃圾字符串不抛异常，两个字段都是 null。 */
    @Test
    fun garbageNameReturnsNulls() {
        listOf("", "   ", "hello.zip", "hfa-backup-x-v2.zip", "hfa-backup-20231114-221320").forEach {
            val parsed = parseBackupName(it, utc)
            assertNull("ts 应为 null: $it", parsed.ts)
            assertNull("version 应为 null: $it", parsed.version)
        }
    }

    /** 其它扩展名（.hfabak）与无扩展名都能解析。 */
    @Test
    fun acceptsOtherExtensions() {
        val hfabak = parseBackupName("hfa-backup-20231114-221320-v2.7.0.hfabak", utc)
        assertEquals(ts, hfabak.ts)
        assertEquals("2.7.0", hfabak.version)
        assertNull(parseBackupName("hfa-backup-20231114-221320-v.zip", utc).version)
        val bare = parseBackupName("HFA-BACKUP-20231114-221320-v2.7.0", utc)
        assertEquals(ts, bare.ts)
        assertEquals("2.7.0", bare.version)
    }
}
