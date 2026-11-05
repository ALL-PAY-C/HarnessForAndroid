package dev.dsh.mobile.core.platform.backup

import java.io.File
import java.util.zip.ZipFile

// O8-B④: 从 core:data.backup.BackupManager 上移（只读 zip 中央目录，纯 JVM 判断）。

/** 备份包内 manifest 的文件名；core:data 的 BackupWriter 也引这个常量，避免两处漂移。 */
const val BACKUP_MANIFEST_ENTRY: String = "manifest.json"

/**
 * 是不是历史版本（无 manifest.json、直接摊 shared_prefs//db//plugins/）产出的包。
 *
 * 只读 zip 中央目录，不解压 —— 2 GiB 的包也只花一次目录解析。
 * 读不出 zip（真损坏）返回 false：调用方据此报错，不能把坏包当旧格式放过。
 */
fun isLegacyPackage(file: File): Boolean = runCatching {
    ZipFile(file).use { zip ->
        var hasManifest = false
        var hasLegacyEntry = false
        val it = zip.entries()
        while (it.hasMoreElements()) {
            val entry = it.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.removePrefix("./").removePrefix("/")
            if (name == BACKUP_MANIFEST_ENTRY) hasManifest = true
            if (name.startsWith("shared_prefs/") || name.startsWith("db/") || name.startsWith("plugins/")) {
                hasLegacyEntry = true
            }
        }
        !hasManifest && hasLegacyEntry
    }
}.getOrDefault(false)
