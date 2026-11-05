package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRoundTripTest {

    /** 超过 Db.MESSAGE_TEXT_READ_CAP（256KB）的正文：写包 / 恢复都不许截断。 */
    private val longText = "长消息正文".repeat(60_000)

    @Test
    fun `写包再恢复到另一个库后消息逐字相等`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "中文会话标题")
        source.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", longText)
        source.messages["m2"] = FakeBackupDb.messageRow("m2", "s1", "短消息", at = 2L)

        val roots = tempRoots("roundtrip")
        val pkg = File(roots.filesDir, "out.hfabak")
        val manifest = BackupWriter.Companion.newWriter(roots, source).write(pkg, FULL_SELECTION)

        assertEquals(1, manifest.counts.sessions)
        assertEquals(2, manifest.counts.messages)
        assertEquals(12, manifest.dbVersion)
        assertTrue(pkg.length() > 0)

        val target = FakeBackupDb()
        val targetRoots = tempRoots("roundtrip-target")
        val report = RestoreEngine(targetRoots, target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)
            .getOrThrow()

        assertEquals(1, report.sessions)
        assertEquals(2, report.messages)
        assertEquals("中文会话标题", target.sessions["s1"]?.str("title"))
        val restored = target.messages["m1"]?.str("text")
        assertNotNull(restored)
        assertEquals(longText.length, restored!!.length)
        assertEquals(longText, restored)
    }

    @Test
    fun `preview 的 counts 与 manifest 一致且分节带校验和`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "标题")
        source.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", "正文")

        val roots = tempRoots("preview")
        val pkg = File(roots.filesDir, "preview.hfabak")
        val written = BackupWriter.Companion.newWriter(roots, source).write(pkg, FULL_SELECTION)

        val engine = RestoreEngine(roots, FakeBackupDb(), PrefsSource.Empty, PrefsSink.Noop)
        val preview = engine.preview(pkg)

        assertEquals(written.counts, preview.counts)
        assertEquals(written.dbVersion, preview.dbVersion)
        val dbSection = preview.sections.first { it.id == BackupSections.DB }
        assertEquals(1, dbSection.entries)
        assertEquals(64, dbSection.sha256.length)
        assertTrue(dbSection.bytes > 0)
        assertEquals(BackupManifest.SUPPORTED_SCHEMA_VERSION, preview.schemaVersion)
    }

    @Test
    fun `恢复后会话的 endpointId 被置空`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "标题", endpointId = "ep-of-other-phone")

        val roots = tempRoots("endpoint")
        val pkg = File(roots.filesDir, "ep.hfabak")
        BackupWriter.Companion.newWriter(roots, source).write(pkg, FULL_SELECTION)

        val target = FakeBackupDb()
        RestoreEngine(tempRoots("endpoint-target"), target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)
            .getOrThrow()

        assertEquals("", target.sessions["s1"]?.str("endpointId"))
        assertEquals("标题", target.sessions["s1"]?.str("title"))
        assertEquals(1, target.sessions.size)
    }
}
