package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSelectionTest {

    /** 默认勾选：不带凭证，只读设置 + 附件 + DB。 */
    @Test
    fun defaultSelectionExcludesSecrets() {
        val s = BackupSelection()
        assertFalse(s.secrets)
        assertTrue(s.canExport)
        assertEquals(listOf(BackupSections.DB, BackupSections.SETTINGS, BackupSections.ATTACHMENTS), s.sectionIds())
        assertTrue(s.validate().none { it.contains("凭证") })
        assertEquals(null, s.sessions)
    }

    /** 勾了凭证却没口令：不许导出，并给一条中文警告。 */
    @Test
    fun secretsWithoutPassphraseBlocksExport() {
        val s = BackupSelection(secrets = true)
        assertFalse(s.canExport)
        val warnings = s.validate()
        assertEquals(1, warnings.size)
        assertTrue(warnings.first(), warnings.first().contains("口令"))
        assertTrue(warnings.first().contains("凭证"))
        assertTrue(s.sectionIds().contains(BackupSections.SECRETS))
    }

    /** 口令不足 8 位同样拦住，并报出实际位数。 */
    @Test
    fun shortPassphraseBlocksExport() {
        val s = BackupSelection(secrets = true, passphrase = "1234567")
        assertFalse(s.canExport)
        val msg = s.validate().first { it.contains("口令") }
        assertTrue(msg, msg.contains("7 位"))
        assertTrue(msg.contains("8"))
        assertTrue(BackupSelection(secrets = true, passphrase = "12345678").canExport)
    }

    /** 口令够长即放行，且不再有凭证相关警告。 */
    @Test
    fun secretsWithLongPassphraseCanExport() {
        val s = BackupSelection(secrets = true, passphrase = "correct horse")
        assertTrue(s.canExport)
        assertTrue(s.validate().isEmpty())
        assertTrue(s.sectionIds().contains(BackupSections.SECRETS))
    }

    /** sectionIds 严格跟随勾选，db 恒定在内。 */
    @Test
    fun sectionIdsFollowSelection() {
        val s = BackupSelection(
            settings = false,
            plugins = setOf("plugin.a", "plugin.b"),
            secrets = false,
            attachments = false,
            workspace = true,
        )
        assertEquals(listOf(BackupSections.DB, BackupSections.PLUGINS, BackupSections.WORKSPACE), s.sectionIds())
        // 只勾会话列表时仍导出 db
        assertEquals(listOf(BackupSections.DB), BackupSelection(
            sessions = setOf("s1"),
            settings = false,
            attachments = false,
        ).sectionIds())
        // 空插件集合不算勾选 plugins
        assertFalse(BackupSelection(plugins = emptySet()).sectionIds().contains(BackupSections.PLUGINS))
        // 全关时 db 仍在（会话与消息是备份的最小单位），且不算阻断
        val minimal = BackupSelection(settings = false, attachments = false)
        assertEquals(listOf(BackupSections.DB), minimal.sectionIds())
        assertTrue(minimal.canExport)
        assertTrue(minimal.validate().isEmpty())
    }
}
