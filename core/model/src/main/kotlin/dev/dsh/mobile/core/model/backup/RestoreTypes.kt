package dev.dsh.mobile.core.model.backup

import java.io.File

/** 恢复策略：Merge 逐节合并，Replace 先整库替换（旧版存档只有这一种）。 */
enum class RestoreMode { Merge, Replace }

/** 一次恢复的结果统计；[warnings] 里是「跳过 / 降级」的原因，界面原样展示。 */
data class RestoreReport(
    val mode: RestoreMode,
    val sessions: Int,
    val messages: Int,
    val plugins: Int,
    val files: Int,
    val warnings: List<String>,
    val preRestore: File? = null,
)
