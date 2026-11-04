package dev.dsh.mobile.design

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.core.common.DividerLabel
import dev.dsh.mobile.core.common.RelativeTime

/**
 * 一条还没定语言的界面文案。
 *
 * ViewModel / 仓库层只产出 [Res]（资源 id + 参数）或 [Raw]（运行时数据，比如引擎原样回的错误串），
 * Context 只在 UI 层取：Compose 用 [text]，非 Compose 用 [resolve]。这样状态层不绑死当前语言，
 * 切语言后重新渲染即生效，也让 JVM 单测不需要 Context。
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText
}

@Composable
fun UiText.text(): String = when (this) {
    // 参数里允许再嵌一层 [UiText]（比如「目标 · 进行中」里的 phase 标签），先递归解成字符串再格式化。
    is UiText.Res -> stringResource(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    is UiText.Raw -> value
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Raw -> value
}

/** 相对时间（会话列表右侧）：结构化档位 → 资源文案；时间戳无效（null）渲染空串。 */
@Composable
fun relativeTimeText(value: RelativeTime?): String = when (value) {
    null -> ""
    RelativeTime.JustNow -> stringResource(R.string.time_just_now)
    is RelativeTime.Minutes -> stringResource(R.string.time_minutes_ago, value.n.toInt())
    is RelativeTime.Clock -> value.hhmm
    is RelativeTime.Days -> stringResource(R.string.time_days_ago, value.n.toInt())
    is RelativeTime.Absolute -> value.text
}

/** 消息分隔条：今天 / 昨天带时刻，更远的日期原样展示。 */
@Composable
fun dividerText(value: DividerLabel): String = when (value) {
    DividerLabel.None -> ""
    is DividerLabel.Today -> stringResource(R.string.time_today, value.hhmm)
    is DividerLabel.Yesterday -> stringResource(R.string.time_yesterday, value.hhmm)
    is DividerLabel.Text -> value.text
}
