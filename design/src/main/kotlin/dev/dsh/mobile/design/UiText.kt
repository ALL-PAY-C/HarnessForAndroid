package dev.dsh.mobile.design

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

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
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> value
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Raw -> value
}
