package dev.dsh.mobile.pluginapi

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

/**
 * 富内容渲染槽：markdown 块级渲染 + HTML 清洗文本。
 *
 * 分层动机：:plugin-host 里曾经直接 import :render 的 ChatContent / HtmlRenderer，
 * 形成「插件宿主 → UI 渲染层 → 设计系统」的反向依赖，把整套 Compose 拖进引擎内核链。
 * 现在宿主只认这个槽位（纯函数/纯 Composable 签名，零 UI 实现），实现由 UI 侧的
 * :render 在启动时注入 —— 与 [HtmlBlockRenderers] / [DiagramRenderers] 同一套写法。
 *
 * 未注入实现时的兜底：markdown 退化为等宽前的纯文本，HTML 退化为原始文本，
 * 都还能读，不会崩。
 */
object RichContentRenderers {

    @Volatile
    private var markdownSlot: (@Composable (text: String, style: TextStyle, modifier: Modifier) -> Unit)? = null

    @Volatile
    private var htmlSlot: ((html: String, base: TextStyle) -> AnnotatedString)? = null

    /** 注入代数：在 Composable 里读它，槽位一变就重组。 */
    var generation by mutableIntStateOf(0)
        private set

    val available: Boolean get() = markdownSlot != null

    /** UI 侧（:render 的 RichContentInstaller）在启动时调一次；传 null 的分支保持原样。 */
    fun install(
        markdown: (@Composable (text: String, style: TextStyle, modifier: Modifier) -> Unit)? = null,
        htmlToAnnotated: ((html: String, base: TextStyle) -> AnnotatedString)? = null,
    ) {
        if (markdown != null) markdownSlot = markdown
        if (htmlToAnnotated != null) htmlSlot = htmlToAnnotated
        generation++
    }

    fun clear() {
        markdownSlot = null
        htmlSlot = null
        generation++
    }

    /** markdown 块级渲染：有实现就用实现，没有就退回纯文本。 */
    @Composable
    fun Markdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
        @Suppress("UNUSED_EXPRESSION") generation
        val slot = markdownSlot
        if (slot != null) slot(text, style, modifier) else Text(text = text, style = style, modifier = modifier)
    }

    /** HTML → 清洗后的 AnnotatedString：有实现就用实现，没有就原样返回（从不把裸 HTML 当 Compose）。 */
    fun htmlToAnnotated(html: String, base: TextStyle): AnnotatedString {
        val slot = htmlSlot
        return slot?.invoke(html, base) ?: AnnotatedString(html)
    }
}
