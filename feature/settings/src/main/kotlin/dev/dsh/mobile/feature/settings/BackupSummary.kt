package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.core.data.backup.BackupManifest
import dev.dsh.mobile.core.data.backup.BackupSections
import dev.dsh.mobile.core.data.backup.RestoreEngine
import java.util.Locale

/**
 * 备份页的纯文案逻辑：分节中文名、包摘要、体积、恢复结果摘要。
 *
 * 单独一个文件、不碰 Android 与 Compose，是为了能在 JVM 单测里直接跑
 * （feature/settings 没有 Robolectric，界面只能手测，文案不能也只靠手测）。
 */

/** 分节 id → 用户看得懂的名字。 */
internal fun sectionLabel(id: String): String = when (id) {
    BackupSections.DB -> "会话与消息"
    BackupSections.SETTINGS -> "设置"
    BackupSections.PLUGINS -> "插件"
    BackupSections.SECRETS -> "凭证"
    BackupSections.ATTACHMENTS -> "附件文件"
    BackupSections.WORKSPACE -> "工作区"
    else -> id
}

/** 包内实际写入的分节，顺序固定与 [BackupSections.ALL] 一致，不受 manifest 里数组顺序影响。 */
internal fun includedSections(m: BackupManifest): List<String> =
    BackupSections.ALL.filter { id -> m.sections.any { it.id == id && it.included } }

/** 「会话 12 · 消息 340 · 插件 3 个 · 含凭证（需要口令）」——选中文件后立即展示的那一行。 */
internal fun countsSummary(m: BackupManifest): String = buildList {
    add("会话 " + m.counts.sessions)
    add("消息 " + m.counts.messages)
    if (m.counts.plugins > 0) add("插件 " + m.counts.plugins + " 个")
    if (m.counts.attachments > 0) add("附件 " + m.counts.attachments + " 个")
    add(if (m.secrets != null) "含凭证（需要口令）" else "不含凭证")
}.joinToString(" · ")

/** 已有备份一行的副标题：包含哪些节。 */
internal fun sectionsSummary(m: BackupManifest): String {
    val names = includedSections(m).map { sectionLabel(it) }
    return if (names.isEmpty()) "空包（没有可用分节）" else names.joinToString(" / ")
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
 * [previewFailed] 是 preview 的结果，[legacyPackage] 来自 [dev.dsh.mobile.core.data.backup.isLegacyPackage]
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
internal fun reportSummary(r: RestoreEngine.Report): String = buildList {
    add(if (r.mode == RestoreEngine.Mode.Replace) "整机还原" else "合并导入")
    add("会话 " + r.sessions)
    add("消息 " + r.messages)
    if (r.plugins > 0) add("插件 " + r.plugins + " 个")
    if (r.files > 0) add("文件 " + r.files + " 个")
}.joinToString(" · ")
