package dev.dsh.mobile.core.model.backup

import kotlinx.serialization.Serializable

/**
 * 备份包内的 `manifest.json`（设计见 research/T913-backup.md §2.1）。
 *
 * [schemaVersion] 是**包格式**版本，与 Room 的 [dbVersion] 正交：前者决定本 App 能否读懂这个包，
 * 后者决定包里的库要不要走 MIGRATION 链升级。
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val appVersionCode: Int,
    val appVersionName: String,
    val dbVersion: Int,
    val createdAt: Long,
    val device: DeviceInfo,
    val sections: List<SectionInfo> = emptyList(),
    val counts: Counts,
    val secrets: SecretsInfo? = null,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** 本应用能读的最高包格式版本。读到的包更高即拒绝，不做「尽力解析」。 */
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/** 产包设备，仅用于诊断展示，不参与任何裁决。 */
@Serializable
data class DeviceInfo(
    val model: String,
    val sdkInt: Int,
    val abi: String,
)

/** 单个分节的产包统计。[id] 取值见 [BackupSections]。 */
@Serializable
data class SectionInfo(
    val id: String,
    val included: Boolean = true,
    val entries: Int = 0,
    val bytes: Long = 0L,
    val sha256: String = "",
)

/** 恢复前预检用的行数统计。 */
@Serializable
data class Counts(
    val sessions: Int = 0,
    val messages: Int = 0,
    val plugins: Int = 0,
    val attachments: Int = 0,
)

/** 凭证节的加密参数：口令派生密钥，包内只留 salt/iv，不绑设备。 */
@Serializable
data class SecretsInfo(
    val cipher: String = "AES-256-GCM",
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = 120_000,
    val saltB64: String,
    val ivB64: String,
)

/** 包内六类分节的 id。 */
object BackupSections {
    const val DB = "db"
    const val SETTINGS = "settings"
    const val PLUGINS = "plugins"
    const val SECRETS = "secrets"
    const val ATTACHMENTS = "attachments"
    const val WORKSPACE = "workspace"

    val ALL: List<String> = listOf(DB, SETTINGS, PLUGINS, SECRETS, ATTACHMENTS, WORKSPACE)
}
