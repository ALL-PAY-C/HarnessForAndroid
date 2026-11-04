package dev.dsh.mobile.core.data.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 文件名时间戳部分：yyyyMMdd-HHmmss，秒级精度。 */
private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

private val ILLEGAL_IN_NAME = Regex("[\\\\/:*?\"<>|\\s]")

private val BACKUP_NAME = Regex(
    "^hfa-backup-(\\d{8})-(\\d{6})-v(.+)$",
    RegexOption.IGNORE_CASE,
)

/** 版本名后面挂的归档扩展名，解析时剥掉；不认识的后缀一律留在版本里。 */
private val ARCHIVE_EXT = Regex("\\.(zip|hfabak|bak)$", RegexOption.IGNORE_CASE)

/** 把版本名里会破坏文件名的字符换成 `_`；洗完为空则退回 `0`。 */
fun sanitizeVersionName(name: String): String {
    val cleaned = name.replace(ILLEGAL_IN_NAME, "_").trim('_')
    return cleaned.ifEmpty { "0" }
}

/**
 * 生成备份包文件名：`hfa-backup-YYYYMMDD-HHmmss-v<ver>.zip`。
 * 时间一律按 [tz] 渲染（不跟系统默认时区走），保证测试与跨设备展示一致。
 */
fun backupFileName(
    nowMillis: Long,
    appVersionName: String,
    tz: TimeZone = TimeZone.getDefault(),
): String {
    val fmt = SimpleDateFormat(STAMP_PATTERN, Locale.US).apply { timeZone = tz }
    return "hfa-backup-${fmt.format(Date(nowMillis))}-v${sanitizeVersionName(appVersionName)}.zip"
}

/** [ts] / [version] 解析不出来就是 null，调用方自行决定怎么提示。 */
data class ParsedBackupName(val ts: Long?, val version: String?)

/** 解析 [backupFileName] 产出的名字（也接受 .hfabak 等其它扩展名与缺失扩展名）。永不抛异常。 */
fun parseBackupName(name: String, tz: TimeZone = TimeZone.getDefault()): ParsedBackupName {
    val trimmed = name.trim()
    val match = BACKUP_NAME.find(trimmed)
        ?: return ParsedBackupName(null, null)
    val (datePart, timePart, versionPart) = match.destructured
    val fmt = SimpleDateFormat(STAMP_PATTERN, Locale.US).apply {
        timeZone = tz
        isLenient = false
    }
    val ts = runCatching { fmt.parse("$datePart-$timePart")?.time }.getOrNull()
    val version = versionPart.replace(ARCHIVE_EXT, "").takeIf { it.isNotBlank() }
    return ParsedBackupName(ts, version)
}
