package dev.dsh.mobile.core.model.backup

import java.io.File

/** 一个本地存档文件 + 为什么会有它（manual / auto / pre-restore）。 */
data class BackupEntry(val file: File, val reason: String, val createdAt: Long)
