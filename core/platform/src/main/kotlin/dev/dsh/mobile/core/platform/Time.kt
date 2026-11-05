package dev.dsh.mobile.core.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话行的相对时间：只说「哪一档 + 数值」，不含任何自然语言。
 * 文案由 UI 层映射到 values 里的 strings.xml，切语言才能生效。
 */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val n: Long) : RelativeTime
    data class Clock(val hhmm: String) : RelativeTime
    data class Days(val n: Long) : RelativeTime
    data class Absolute(val text: String) : RelativeTime
}

/** 同 [RelativeTime]，用于消息之间的日期分隔条。 */
sealed interface DividerLabel {
    data class Today(val hhmm: String) : DividerLabel
    data class Yesterday(val hhmm: String) : DividerLabel

    /** 更远的日期：MM-dd HH:mm，超过一年再带上年份。 */
    data class Text(val text: String) : DividerLabel
    data object None : DividerLabel
}

object TimeFmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val md = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val ymd = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val mdOnly = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /**
     * Label for a separator between messages, QQ style: the more distant the
     * day, the more of the date it carries. 只有「今天 / 昨天」这两档需要 UI 拼前缀，
     * 其余原样展示。
     */
    fun dividerParts(ts: Long, now: Long = System.currentTimeMillis()): DividerLabel {
        if (ts <= 0L) return DividerLabel.None
        val startOfToday = now - (now % 86_400_000L)
        val dayDiff = (startOfToday - (ts - (ts % 86_400_000L))) / 86_400_000L
        return when {
            dayDiff <= 0L -> DividerLabel.Today(hm.format(Date(ts)))
            dayDiff == 1L -> DividerLabel.Yesterday(hm.format(Date(ts)))
            dayDiff < 365L -> DividerLabel.Text(mdOnly.format(Date(ts)))
            else -> DividerLabel.Text(ymd.format(Date(ts)))
        }
    }

    /** 只要时刻（HH:mm），用在气泡抬头。 */
    fun clock(ts: Long): String = if (ts <= 0L) "" else hm.format(Date(ts))

    /** 绝对时间（yyyy-MM-dd HH:mm）：存档这类东西不该让人去猜是多久之前的几点。 */
    fun absolute(ts: Long): String = if (ts <= 0L) "" else ymd.format(Date(ts))

    /**
     * 会话列表用的相对时间，五档：不足 1 分钟 / 不足 1 小时（按分钟计）/
     * 不足 1 天（给 HH:mm）/ 不足 7 天（按天计）/ 更远（MM-dd HH:mm）。
     * ts<=0 返回 null，等价于原来返回空串。
     */
    fun relativeParts(ts: Long, now: Long = System.currentTimeMillis()): RelativeTime? {
        if (ts <= 0L) return null
        val d = now - ts
        return when {
            d < 60_000L -> RelativeTime.JustNow
            d < 3_600_000L -> RelativeTime.Minutes(d / 60_000L)
            d < 86_400_000L -> RelativeTime.Clock(hm.format(Date(ts)))
            d < 7 * 86_400_000L -> RelativeTime.Days(d / 86_400_000L)
            else -> RelativeTime.Absolute(md.format(Date(ts)))
        }
    }
}
