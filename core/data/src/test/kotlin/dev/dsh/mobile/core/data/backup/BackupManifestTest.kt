package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupManifestTest {

    private fun sample(schemaVersion: Int = BackupManifest.SUPPORTED_SCHEMA_VERSION) = BackupManifest(
        schemaVersion = schemaVersion,
        appVersionCode = 42,
        appVersionName = "2.7.0",
        dbVersion = 12,
        createdAt = 1_700_000_000_000L,
        device = DeviceInfo(model = "Pixel 7", sdkInt = 34, abi = "arm64-v8a"),
        sections = listOf(
            SectionInfo(BackupSections.DB, entries = 3, bytes = 1024L, sha256 = "abc"),
            SectionInfo(BackupSections.SETTINGS, entries = 4, bytes = 512L, sha256 = "def"),
        ),
        counts = Counts(sessions = 3, messages = 9, plugins = 1, attachments = 2),
        secrets = SecretsInfo(saltB64 = "c2FsdA==", ivB64 = "aXY="),
        warnings = listOf("workspace 跳过 3 个 >50MB 文件"),
    )

    /** 编解码往返：所有字段（含可选的 secrets / warnings / sections）原样回来。 */
    @Test
    fun roundTripKeepsEveryField() {
        val src = sample()
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(src))
        assertEquals(src, decoded)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(12, decoded.dbVersion)
        assertEquals("arm64-v8a", decoded.device.abi)
        assertEquals(2, decoded.sections.size)
        assertEquals("c2FsdA==", decoded.secrets?.saltB64)
        assertEquals(120_000, decoded.secrets?.iterations)
        assertEquals(1, decoded.warnings.size)
    }

    /** 更高格式版本的包必须抛 ManifestTooNewException，且提示是给用户看的中文。 */
    @Test
    fun tooNewSchemaIsRejectedWithChineseHint() {
        val json = BackupManifestCodec.encode(sample(schemaVersion = 2))
        try {
            BackupManifestCodec.decode(json)
            fail("schemaVersion=2 应当被拒绝")
        } catch (e: ManifestTooNewException) {
            assertEquals(2, e.schemaVersion)
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("备份包格式版本 2"))
            assertTrue(msg, msg.contains("高于本应用支持的 1"))
            assertTrue(msg, msg.contains("请先升级 App"))
        }
    }

    /** 缺 sections 按空列表处理，其余字段照常解出来。 */
    @Test
    fun missingSectionsBecomesEmptyList() {
        val json = """
            {
              "schemaVersion": 1,
              "appVersionCode": 42,
              "appVersionName": "2.7.0",
              "dbVersion": 12,
              "createdAt": 1700000000000,
              "device": {"model": "Pixel 7", "sdkInt": 34, "abi": "arm64-v8a"},
              "counts": {"sessions": 3, "messages": 9, "plugins": 1, "attachments": 2}
            }
        """.trimIndent()
        val m = BackupManifestCodec.decode(json)
        assertTrue(m.sections.isEmpty())
        assertEquals(3, m.counts.sessions)
        assertNull(m.secrets)
        assertTrue(m.warnings.isEmpty())
    }

    /** dbVersion 三分支：包更高=拒绝，包更低=走迁移，相等=直接合并。 */
    @Test
    fun compareDbHasThreeBranches() {
        assertEquals(DbVerdict.Newer, compareDb(pack = 13, local = 12))
        assertEquals(DbVerdict.Older, compareDb(pack = 11, local = 12))
        assertEquals(DbVerdict.Same, compareDb(pack = 12, local = 12))
        assertEquals(DbVerdict.Same, compareDb(pack = 0, local = 0))
    }

    /** 未来字段不炸：ignoreUnknownKeys 生效，已知字段仍然正确。 */
    @Test
    fun unknownFieldsAreIgnored() {
        val json = """
            {
              "schemaVersion": 1,
              "appVersionCode": 43,
              "appVersionName": "2.8.0",
              "dbVersion": 12,
              "createdAt": 1700000000000,
              "device": {"model": "Pixel 7", "sdkInt": 34, "abi": "arm64-v8a", "codename": "panther"},
              "sections": [{"id": "db", "included": true, "entries": 1, "bytes": 10, "sha256": "x", "compression": "deflate"}],
              "counts": {"sessions": 1, "messages": 2, "plugins": 0, "attachments": 0, "workspaces": 0},
              "futureSection": {"anything": [1, 2, 3]}
            }
        """.trimIndent()
        val m = BackupManifestCodec.decode(json)
        assertEquals(43, m.appVersionCode)
        assertEquals("2.8.0", m.appVersionName)
        assertEquals(1, m.sections.size)
        assertEquals(10L, m.sections.first().bytes)
    }
}
