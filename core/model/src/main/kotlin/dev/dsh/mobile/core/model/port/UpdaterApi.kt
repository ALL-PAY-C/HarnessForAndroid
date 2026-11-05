package dev.dsh.mobile.core.model.port

import android.content.Intent
import dev.dsh.mobile.core.model.update.DownloadReport
import dev.dsh.mobile.core.model.update.UpdateCheck
import dev.dsh.mobile.core.model.update.UpdateInfo
import java.io.File

/**
 * 自更新端口：由 core:data 的 Updater 实现。
 *
 * 只留界面要用的 6 个入口；候选地址、清单预算、缓存下载等实现细节不进接口。
 */
interface UpdaterApi {
    fun sourceUrl(): String
    fun setSourceUrl(url: String)
    fun installedVersion(): Pair<Long, String>

    /** 该地址是不是内置清单源（界面据此把「内置/自定义」翻成一句话）。 */
    fun isBuiltinSource(url: String): Boolean

    suspend fun checkDetailed(): Result<UpdateCheck>
    suspend fun downloadChecked(info: UpdateInfo, onProgress: (Int) -> Unit = {}): Result<DownloadReport>
    fun installIntent(file: File): Intent
}
