package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.core.model.backup.BackupManifest
import dev.dsh.mobile.core.model.backup.BackupSections
import java.util.Locale
import dev.dsh.mobile.core.model.backup.RestoreReport
import dev.dsh.mobile.core.model.backup.RestoreMode

/**
 * 备份页的纯文案逻辑：分节中文名、包摘要、体积、恢复结果摘要。
 *
 * 单独一个文件、不碰 Android 与 Compose，是为了能在 JVM 单测里直接跑
 * （feature/settings 没有 Robolectric，界面只能手测，文案不能也只靠手测）。
 */

/** 资源 id → 当前语言的文案：界面传 context.getString，单测传假实现。 */
internal typealias CopyResolver = (Int, List<Any>) -> String

/** 分节 id → 用户看得懂的名字。 */
internal fun sectionLabel(id: String, copy: CopyResolver): String {
    val res = when (id) {
        BackupSections.DB -> DesignR.string.backup_section_db
        BackupSections.SETTINGS -> DesignR.string.backup_section_settings
        BackupSections.PLUGINS -> DesignR.string.backup_section_plugins
        BackupSections.SECRETS -> DesignR.string.backup_section_secrets
        BackupSections.ATTACHMENTS -> DesignR.string.backup_section_attachments
        BackupSections.WORKSPACE -> DesignR.string.backup_section_workspace
        else -> null
    }
    return res?.let { copy(it, emptyList()) } ?: id
}

/** 包内实际写入的分节，顺序固定与 [BackupSections.ALL] 一致，不受 manifest 里数组顺序影响。 */
internal fun includedSections(m: BackupManifest): List<String> =
    BackupSections.ALL.filter { id -> m.sections.any { it.id == id && it.included } }

/** 「会话 12 · 消息 340 · 插件 3 个 · 含凭证（需要口令）」——选中文件后立即展示的那一行。 */
internal fun countsSummary(m: BackupManifest, copy: CopyResolver): String = buildList {
    add(copy(DesignR.string.backup_counts_sessions, listOf(m.counts.sessions)))
    add(copy(DesignR.string.backup_counts_messages, listOf(m.counts.messages)))
    if (m.counts.plugins > 0) add(copy(DesignR.string.backup_counts_plugins, listOf(m.counts.plugins)))
    if (m.counts.attachments > 0) add(copy(DesignR.string.backup_counts_attachments, listOf(m.counts.attachments)))
    add(copy(if (m.secrets != null) DesignR.string.backup_has_secrets else DesignR.string.backup_no_secrets, emptyList()))
}.joinToString(" · ")

/** 已有备份一行的副标题：包含哪些节。 */
internal fun sectionsSummary(m: BackupManifest, copy: CopyResolver): String {
    val names = includedSections(m).map { sectionLabel(it, copy) }
    return if (names.isEmpty()) copy(DesignR.string.backup_sections_empty, emptyList()) else names.joinToString(" / ")
}

/** 1024 进制、一位小数：896 B / 12.3 KB / 1.4 MB。负数按 0 显示。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** 一个备份包的可用性：正常 / 旧格式（只能整机还原）/ 坏包（拒绝）。 */
internal enum class BackupKind { Current, Legacy, Broken }

/**
 * 包的可用性判定。
 *
 * [previewFailed] 是 preview 的结果，[legacyPackage] 来自 [dev.dsh.mobile.core.platform.backup.isLegacyPackage]
 * 的结构判定。只有「预览失败 + 结构就是老包」才算 legacy；预览失败但不是老包一律 broken ——
 * 损坏包绝不能因为「读不出 manifest」被当成旧格式放过去整机覆盖。
 */
internal fun classifyBackup(previewFailed: Boolean, legacyPackage: Boolean): BackupKind = when {
    !previewFailed -> BackupKind.Current
    legacyPackage -> BackupKind.Legacy
    else -> BackupKind.Broken
}

/** SAF 临时包的文件名前缀（缓存里只清这个前缀，别的缓存不碰）。 */
internal const val IMPORT_FILE_PREFIX: String = "import-"

/** SAF 临时包的存活时间：进页面时清掉比这更老的那些。 */
internal const val IMPORT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

/** 缓存里的文件是否该清：只认 [IMPORT_FILE_PREFIX] 前缀，且超过 [IMPORT_TTL_MILLIS]。 */
internal fun isStaleImport(name: String, lastModified: Long, now: Long): Boolean =
    name.startsWith(IMPORT_FILE_PREFIX) && now - lastModified > IMPORT_TTL_MILLIS

/** 恢复成功后的结果摘要（成功页第一行）。 */
internal fun reportSummary(r: RestoreReport, copy: CopyResolver): String = buildList {
    add(copy(if (r.mode == RestoreMode.Replace) DesignR.string.backup_mode_replace else DesignR.string.backup_mode_merge, emptyList()))
    add(copy(DesignR.string.backup_counts_sessions, listOf(r.sessions)))
    add(copy(DesignR.string.backup_counts_messages, listOf(r.messages)))
    if (r.plugins > 0) add(copy(DesignR.string.backup_counts_plugins, listOf(r.plugins)))
    if (r.files > 0) add(copy(DesignR.string.backup_counts_files, listOf(r.files)))
}.joinToString(" · ")
