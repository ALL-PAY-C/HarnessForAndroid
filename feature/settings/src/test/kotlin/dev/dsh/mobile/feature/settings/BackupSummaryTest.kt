package dev.dsh.mobile.feature.settings

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

/** 备份页文案纯函数的单测：这些字符串直接上屏，错一个字就是用户看不懂状态。 */
class BackupSummaryTest {

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
        assertEquals("会话与消息", sectionLabel(BackupSections.DB))
        assertEquals("设置", sectionLabel(BackupSections.SETTINGS))
        assertEquals("插件", sectionLabel(BackupSections.PLUGINS))
        assertEquals("凭证", sectionLabel(BackupSections.SECRETS))
        assertEquals("附件文件", sectionLabel(BackupSections.ATTACHMENTS))
        assertEquals("工作区", sectionLabel(BackupSections.WORKSPACE))
    }

    @Test
    fun sectionLabel_fallsBackToRawId() {
        assertEquals("future", sectionLabel("future"))
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
        assertEquals("会话 3 · 消息 12 · 不含凭证", countsSummary(plain))

        val rich = manifest(
            counts = Counts(sessions = 3, messages = 12, plugins = 2, attachments = 5),
            secrets = SecretsInfo(saltB64 = "c2FsdA==", ivB64 = "aXY="),
        )
        assertEquals("会话 3 · 消息 12 · 插件 2 个 · 附件 5 个 · 含凭证（需要口令）", countsSummary(rich))
    }

    @Test
    fun sectionsSummary_readsAsSlashListOrEmpty() {
        assertEquals(
            "会话与消息 / 设置",
            sectionsSummary(manifest(sections = listOf(SectionInfo(BackupSections.DB), SectionInfo(BackupSections.SETTINGS)))),
        )
        assertEquals("空包（没有可用分节）", sectionsSummary(manifest(sections = emptyList())))
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
        val merge = RestoreEngine.Report(
            mode = RestoreEngine.Mode.Merge,
            sessions = 1,
            messages = 8,
            plugins = 0,
            files = 0,
            warnings = emptyList(),
        )
        assertEquals("合并导入 · 会话 1 · 消息 8", reportSummary(merge))

        val replace = RestoreEngine.Report(
            mode = RestoreEngine.Mode.Replace,
            sessions = 4,
            messages = 40,
            plugins = 2,
            files = 9,
            warnings = listOf("w"),
        )
        assertEquals("整机还原 · 会话 4 · 消息 40 · 插件 2 个 · 文件 9 个", reportSummary(replace))
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
