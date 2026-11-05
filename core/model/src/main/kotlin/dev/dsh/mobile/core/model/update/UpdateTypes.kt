package dev.dsh.mobile.core.model.update

import java.io.File

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
    /** 清单可选提供的备用下载地址，按顺序在直连失败后尝试。 */
    val apkMirrors: List<String> = emptyList(),
    /** 清单可选的资产 sha256（hex），给了就在下载完成后校验；没给就跳过。 */
    val sha256: String = "",
)

/** sha256 校验结论。清单没给 sha256 时是 [NotProvided]：不算通过，但也不拦安装。 */
sealed class ShaStatus {
    data class Verified(val sha256: String) : ShaStatus()
    object NotProvided : ShaStatus()
}

/** 下载结果：可安装的文件 + 校验结论。失败（含校验不过）一律走 Result.failure，不在这里。 */
data class DownloadReport(val file: File, val sha: ShaStatus)

/** 下载完成但 sha256 对不上：文件已删除，调用方拿不到 File 就装不成。 */
class ShaMismatchException(message: String) : IllegalStateException(message)

/**
 * 一次检查的完整结果：清单判断 + 实际用了哪个源 + 每源耗时/失败原因。
 *
 * [info] 为 null 表示已是最新；[origin] 区分「刚拉的」与「内存/磁盘回退」——
 * 回退时 [sourceUrl] 为 null，界面那一行小字不能编一个源出来。
 */
data class UpdateCheck(
    val info: UpdateInfo?,
    val origin: ManifestOrigin,
    val sourceUrl: String?,
    val elapsedMs: Long,
    val failures: List<SourceFailure> = emptyList(),
    val stats: List<SourceStat> = emptyList(),
) {
    /** 界面那行小字。Live 给「源：gh-proxy.com · 486ms」，回退明确说不是网络来的。 */
    fun sourceLine(): String = when (origin) {
        ManifestOrigin.Live -> "源：" + sourceLabel(sourceUrl.orEmpty()) + " · " + elapsedMs + "ms"
        ManifestOrigin.Memory -> "源：本地内存副本（本轮全部源不可用）"
        ManifestOrigin.Disk -> "源：本地磁盘副本（本轮全部源不可用）"
    }
}
