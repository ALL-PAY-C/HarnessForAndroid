package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.core.data.backup.BackupManifest
import dev.dsh.mobile.core.data.backup.BackupSections
import dev.dsh.mobile.core.data.backup.Counts
import dev.dsh.mobile.core.data.backup.DeviceInfo
import dev.dsh.mobile.core.data.backup.RestoreEngine
import dev.dsh.mobile.core.data.backup.SecretsInfo
import dev.dsh.mobile.core.data.backup.SectionInfo
import dev.dsh.mobile.core.data.backup.isLegacyPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.dsh.mobile.core.model.backup.RestoreReport

/** 备份页文案纯函数的单测：这些字符串直接上屏，错一个字就是用户看不懂状态。 */
class BackupSummaryTest {

    /** 假资源：把 id 与参数原样拼出来，断言只验接线，不锁具体文案。 */
    private val copy: CopyResolver = { id, args -> "res:" + id + ":" + args.joinToString(",") }

    private fun manifest(
        sections: List<SectionInfo> = listOf(SectionInfo(BackupSections.DB, true, 1, 10L)),
        counts: Counts = Counts(sessions = 2, messages = 30),
        secrets: SecretsInfo? = null,
    ) = BackupManifest(
        appVersionCode = 7,
        appVersionName = "2.1.0",
        dbVersion = 12,
        createdAt = 1_700_000_000_000L,
        device = DeviceInfo("Pixel", 34, "arm64-v8a"),
        sections = sections,
        counts = counts,
        secrets = secrets,
    )

    @Test
    fun sectionLabel_coversEveryKnownSection() {
        assertEquals(copy(DesignR.string.backup_section_db, emptyList()), sectionLabel(BackupSections.DB, copy))
        assertEquals(copy(DesignR.string.backup_section_settings, emptyList()), sectionLabel(BackupSections.SETTINGS, copy))
        assertEquals(copy(DesignR.string.backup_section_plugins, emptyList()), sectionLabel(BackupSections.PLUGINS, copy))
        assertEquals(copy(DesignR.string.backup_section_secrets, emptyList()), sectionLabel(BackupSections.SECRETS, copy))
        assertEquals(copy(DesignR.string.backup_section_attachments, emptyList()), sectionLabel(BackupSections.ATTACHMENTS, copy))
        assertEquals(copy(DesignR.string.backup_section_workspace, emptyList()), sectionLabel(BackupSections.WORKSPACE, copy))
    }

    @Test
    fun sectionLabel_fallsBackToRawId() {
        assertEquals("future", sectionLabel("future", copy))
    }

    @Test
    fun includedSections_skipsExcludedAndFollowsFixedOrder() {
        val m = manifest(
            sections = listOf(
                SectionInfo(BackupSections.ATTACHMENTS, true),
                SectionInfo(BackupSections.DB, true),
                SectionInfo(BackupSections.SETTINGS, false),
                SectionInfo(BackupSections.PLUGINS, true),
            ),
        )
        assertEquals(
            listOf(BackupSections.DB, BackupSections.PLUGINS, BackupSections.ATTACHMENTS),
            includedSections(m),
        )
    }

    @Test
    fun countsSummary_mentionsSecretsAndSkipsZeroCounts() {
        val plain = manifest(counts = Counts(sessions = 3, messages = 12))
        assertEquals(copy(DesignR.string.backup_counts_sessions, listOf(3)) + " · " + copy(DesignR.string.backup_counts_messages, listOf(12)) + " · " + copy(DesignR.string.backup_no_secrets, emptyList()), countsSummary(plain, copy))

        val rich = manifest(
            counts = Counts(sessions = 3, messages = 12, plugins = 2, attachments = 5),
            secrets = SecretsInfo(saltB64 = "c2FsdA==", ivB64 = "aXY="),
        )
        assertEquals(copy(DesignR.string.backup_counts_sessions, listOf(3)) + " · " + copy(DesignR.string.backup_counts_messages, listOf(12)) + " · " + copy(DesignR.string.backup_counts_plugins, listOf(2)) + " · " + copy(DesignR.string.backup_counts_attachments, listOf(5)) + " · " + copy(DesignR.string.backup_has_secrets, emptyList()), countsSummary(rich, copy))
    }

    @Test
    fun sectionsSummary_readsAsSlashListOrEmpty() {
        assertEquals(
            copy(DesignR.string.backup_section_db, emptyList()) + " / " + copy(DesignR.string.backup_section_settings, emptyList()),
            sectionsSummary(manifest(sections = listOf(SectionInfo(BackupSections.DB), SectionInfo(BackupSections.SETTINGS))), copy),
        )
        assertEquals(copy(DesignR.string.backup_sections_empty, emptyList()), sectionsSummary(manifest(sections = emptyList()), copy))
    }

    @Test
    fun formatBytes_usesBinaryUnitsWithOneDecimal() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-5L))
        assertEquals("896 B", formatBytes(896L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("2.0 GB", formatBytes(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun reportSummary_namesModeAndSkipsZeroCounts() {
        val merge = RestoreReport(
            mode = RestoreMode.Merge,
            sessions = 1,
            messages = 8,
            plugins = 0,
            files = 0,
            warnings = emptyList(),
        )
        assertEquals(copy(DesignR.string.backup_mode_merge, emptyList()) + " · " + copy(DesignR.string.backup_counts_sessions, listOf(1)) + " · " + copy(DesignR.string.backup_counts_messages, listOf(8)), reportSummary(merge, copy))

        val replace = RestoreReport(
            mode = RestoreMode.Replace,
            sessions = 4,
            messages = 40,
            plugins = 2,
            files = 9,
            warnings = listOf("w"),
        )
        assertEquals(copy(DesignR.string.backup_mode_replace, emptyList()) + " · " + copy(DesignR.string.backup_counts_sessions, listOf(4)) + " · " + copy(DesignR.string.backup_counts_messages, listOf(40)) + " · " + copy(DesignR.string.backup_counts_plugins, listOf(2)) + " · " + copy(DesignR.string.backup_counts_files, listOf(9)), reportSummary(replace, copy))
    }

    @Test
    fun classifyBackup_missingManifestWithLegacyLayoutIsLegacy() {
        // 老包：preview 抛「缺少 manifest.json」，结构判定为三前缀包 → 允许整机还原。
        assertEquals(BackupKind.Legacy, classifyBackup(previewFailed = true, legacyPackage = true))
    }

    @Test
    fun classifyBackup_manifestParseFailureIsBrokenNotLegacy() {
        // 有 manifest.json 但内容坏了：结构判定 false，必须报错，不能当旧格式放过。
        assertEquals(BackupKind.Broken, classifyBackup(previewFailed = true, legacyPackage = false))
    }

    @Test
    fun classifyBackup_readablePackageIsCurrent() {
        assertEquals(BackupKind.Current, classifyBackup(previewFailed = false, legacyPackage = false))
        // 防御：结构上带三前缀但 manifest 正常（现代包也有 db/ 条目），仍算正常包。
        assertEquals(BackupKind.Current, classifyBackup(previewFailed = false, legacyPackage = true))
    }

    @Test
    fun isStaleImport_onlyMatchesOwnPrefixAndAge() {
        val now = 10_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        assertTrue(isStaleImport("import-123.hfabak", now - day - 1L, now))
        assertFalse(isStaleImport("import-123.hfabak", now - day + 1L, now))
        // 别的缓存文件再老也不归这个页面清。
        assertFalse(isStaleImport("img-cache-1.png", now - 10L * day, now))
    }

    @Test
    fun isLegacyPackage_trueForThreePrefixZipWithoutManifest() {
        val pack = zipOf(
            "shared_prefs/dsh.settings.xml" to "<map/>",
            "db/dsh-mobile.db" to "sqlite",
            "plugins/demo/manifest.json" to "{}",
        )
        assertTrue(isLegacyPackage(pack))
        pack.delete()
    }

    @Test
    fun isLegacyPackage_falseWhenManifestExists() {
        // 现代包同样有 db/ 条目，靠 manifest.json 区分。
        val pack = zipOf(
            "manifest.json" to "{}",
            "db/dsh-mobile.db" to "sqlite",
        )
        assertFalse(isLegacyPackage(pack))
        pack.delete()
    }

    @Test
    fun isLegacyPackage_falseForCorruptFile() {
        val broken = File.createTempFile("bk-broken", ".hfabak")
        broken.writeText("not a zip at all")
        assertFalse(isLegacyPackage(broken))
        broken.delete()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("bk-test", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
