package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.backup.BackupEntry
import dev.dsh.mobile.core.model.backup.BackupManifest
import dev.dsh.mobile.core.model.backup.BackupSelection
import dev.dsh.mobile.core.model.backup.RestoreMode
import dev.dsh.mobile.core.model.backup.RestoreReport
import java.io.File

/**
 * 备份端口：由 core:data 的 BackupManager 实现。
 *
 * 定时策略（catchUpIfDue / snapshotBeforeInstall / trim）留在实现类，不进接口；
 * 闹钟重排必须走 [reschedule] / [cancelSchedule]，界面不碰 data 侧的 BackupScheduler。
 */
interface BackupApi {
    fun times(): List<String>
    fun setTimes(times: List<String>)
    fun autoEnabled(): Boolean
    fun setAutoEnabled(enabled: Boolean)
    fun lastBackupAt(): Long
    fun closeBatch()
    /** 按当前开关与时间点重排系统闹钟（关掉或没有时间点等价于取消）。 */
    fun reschedule()
    fun cancelSchedule()

    fun list(): List<BackupEntry>
    fun snapshot(reason: String): File?
    fun delete(file: File): Boolean

    fun restore(file: File): Result<Unit>
    fun restorePackage(file: File, mode: RestoreMode = RestoreMode.Merge, passphrase: String = ""): Result<RestoreReport>
    fun export(selection: BackupSelection): Result<Pair<File, BackupManifest>>
    fun previewPackage(file: File): Result<BackupManifest>
}
