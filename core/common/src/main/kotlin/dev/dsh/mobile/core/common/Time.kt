package dev.dsh.mobile.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val md = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val ymd = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val mdOnly = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /**
     * Label for a separator between messages, QQ style: the more distant the
     * day, the more of the date it carries.
     */
    fun divider(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val startOfToday = now - (now % 86_400_000L)
        val dayDiff = (startOfToday - (ts - (ts % 86_400_000L))) / 86_400_000L
        return when {
            dayDiff <= 0L -> "今天 " + hm.format(Date(ts))
            dayDiff == 1L -> "昨天 " + hm.format(Date(ts))
            dayDiff < 365L -> mdOnly.format(Date(ts))
            else -> ymd.format(Date(ts))
        }
    }

    /** 只要时刻（HH:mm），用在气泡抬头。 */
    fun clock(ts: Long): String = if (ts <= 0L) "" else hm.format(Date(ts))

    /** 绝对时间（yyyy-MM-dd HH:mm）：存档这类东西不该让人去猜「几分钟前」是几点。 */
    fun absolute(ts: Long): String = if (ts <= 0L) "" else ymd.format(Date(ts))

    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return ""
        val d = now - ts
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000L} 分钟前"
            d < 86_400_000L -> hm.format(Date(ts))
            d < 7 * 86_400_000L -> "${d / 86_400_000L} 天前"
            else -> md.format(Date(ts))
        }
    }
}
