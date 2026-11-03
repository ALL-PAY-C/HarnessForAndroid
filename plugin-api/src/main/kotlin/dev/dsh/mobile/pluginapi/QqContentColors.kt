package dev.dsh.mobile.pluginapi

import androidx.compose.ui.graphics.Color

/**
 * 内容渲染层的内置配色。
 *
 * 为什么在 :plugin-api 而不在 :design 的 QqPalette：:render / :plugin-html / :plugin-host
 * 都只依赖 :plugin-api（render/build.gradle.kts:13 明文「渲染层不反向依赖设计系统」，
 * plugin-html 只有 compileOnly(plugin-api)，plugin-host 只有 api(plugin-api)），三个模块
 * 都看不到 :design，而构建脚本本轮冻结。放在内容契约层 = 一处定义、三处共用，值逐位不变。
 *
 * 需要跟 QqPalette 联动的界面色仍走 :design（见 QqPalette.terminalBg / QqPalette.scrim）。
 */
object QqContentColors {
    /** 正文链接文字。 */
    val link = Color(0xFF3B82F6)

    /** 行内代码底色（10% 灰）。 */
    val codeBg = Color(0x1A808080)

    /** 徽标「成功」语气色。 */
    val badgeSuccess = Color(0xFF2E7D32)

    /** 徽标「警示」语气色。 */
    val badgeWarning = Color(0xFFB26A00)

    /** 全屏图表底。 */
    val diagramBg = Color(0xFF0B0B0D)

    /** 全屏图表左上角类型标签。 */
    val diagramLabel = Color(0xFF9AA0A6)

    /** 全屏图表操作按钮文字。 */
    val diagramAction = Color(0xFFDADCE0)
}
