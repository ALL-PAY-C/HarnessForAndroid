package dev.dsh.mobile.render

/**
 * 表格排版的两个纯函数：显示宽度估算 + 列宽权重。
 *
 * 抽在这里而不是写在 [TableView] 里，是因为它们决定表格长什么样，必须能单测
 * （Composable 里的计算只有上设备才能看出来对不对）。
 */

/** 列宽权重的下限：再短的一列也要留够「序号 / 图标」的位置。 */
internal const val TABLE_COLUMN_WEIGHT_MIN = 4
/** 列宽权重的上限：再长的列也只占这么多份，剩下的靠换行，别把别的列挤没。 */
internal const val TABLE_COLUMN_WEIGHT_MAX = 24

/**
 * 每列的宽度权重 = clamp(该列最长单元格的显示宽度(em), 4, 24)。
 *
 * 用显示宽度而不是字符数：中文 1 字符约 2 个英文宽，按字符数算会让中文列偏窄、
 * 英文列偏宽。权重越大占的横向空间越多，整表按权重铺满可用宽度。
 */
internal fun tableColumnWeights(columns: List<List<String>>): List<Int> =
    columns.map { col ->
        val longest = col.maxOfOrNull { textEm(it) } ?: 0f
        longest.toInt().coerceIn(TABLE_COLUMN_WEIGHT_MIN, TABLE_COLUMN_WEIGHT_MAX)
    }

/**
 * 估算一段文字排出来的宽度（单位 em，以字号为 1em）。
 * CJK / 全角 / emoji 约 1em，ASCII 约 0.56em。表格列宽按它算才不会把
 * 「GET」折成「GE / T」，也不会给中文列留一大截空白。
 */
internal fun textEm(input: String): Float {
    var w = 0f
    for (ch in input) {
        val c = ch.code
        w += when {
            c in 0x1100..0x115F -> 1f
            c in 0x2E80..0xA4CF -> 1f
            c in 0xAC00..0xD7A3 -> 1f
            c in 0xF900..0xFAFF -> 1f
            c in 0xFE30..0xFE6F -> 1f
            c in 0xFF00..0xFF60 -> 1f
            c in 0xFFE0..0xFFE6 -> 1f
            c in 0x2600..0x27BF -> 1.1f
            c in 0x2B00..0x2BFF -> 1.1f
            c in 0x1F000..0x1FAFF -> 1.1f
            c == 0x20 -> 0.3f
            else -> 0.56f
        }
    }
    return w
}
