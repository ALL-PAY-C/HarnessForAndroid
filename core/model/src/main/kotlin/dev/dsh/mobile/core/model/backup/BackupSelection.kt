package dev.dsh.mobile.core.model.backup

/**
 * 用户在导出页勾出来的内容范围（设计见 research/T913-backup.md §2.2）。
 *
 * [sessions] 为 null 表示「全部会话」，为空集表示「一个都不选」。
 * [passphrase] 只在 [secrets] = true 时有意义，不落盘、不进日志。
 */
data class BackupSelection(
    val sessions: Set<String>? = null,
    val settings: Boolean = true,
    val plugins: Set<String> = emptySet(),
    val secrets: Boolean = false,
    val attachments: Boolean = true,
    val workspace: Boolean = false,
    val passphrase: String = "",
) {
    /** 口令最短长度。短于这个长度直接不许导出凭证。 */
    val canExport: Boolean
        get() = !(secrets && passphrase.length < MIN_PASSPHRASE_LENGTH)

    /** 面向用户的警告列表，空表示没有需要提示的。 */
    fun validate(): List<String> {
        val out = mutableListOf<String>()
        if (secrets) {
            if (passphrase.isEmpty()) {
                out += "已勾选凭证，但未设置口令：凭证必须口令加密后才能导出"
            } else if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
                out += "口令只有 ${passphrase.length} 位，至少需要 $MIN_PASSPHRASE_LENGTH 位"
            }
        }
        if (sessions != null && sessions.isEmpty()) {
            out += "已限定导出会话，但一个会话都没勾：本次只会导出其余勾选项"
        }
        if (workspace) {
            out += "工作区文件可能很大：单文件超过 50MB 会被跳过并记入包内 warnings"
        }
        return out
    }

    /** 实际会写进包的分节 id，顺序与 [BackupSections.ALL] 一致。 */
    fun sectionIds(): List<String> = buildList {
        add(BackupSections.DB)
        if (settings) add(BackupSections.SETTINGS)
        if (plugins.isNotEmpty()) add(BackupSections.PLUGINS)
        if (secrets) add(BackupSections.SECRETS)
        if (attachments) add(BackupSections.ATTACHMENTS)
        if (workspace) add(BackupSections.WORKSPACE)
    }

    companion object {
        const val MIN_PASSPHRASE_LENGTH: Int = 8
    }
}
