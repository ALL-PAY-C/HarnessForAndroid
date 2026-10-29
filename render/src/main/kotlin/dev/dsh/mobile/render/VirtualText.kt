package dev.dsh.mobile.render

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * T4 性能：巨型文本不交给单个 Text。
 *
 * 根因：262k 字 / 26k 行的消息会变成一个 AnnotatedString → 一个 Text 节点，
 * Compose 的 MultiParagraph 每帧 paint 都要按行遍历、逐行裁剪绘制，帧时间随**总行数**
 * 线性增长（实测 821 帧里 240 帧掉帧、p50 32ms、p99 57ms，见 .mock/t10-t4-gfx-scroll.txt）。
 * 分块塞进定高 LazyColumn 后，只有进入视口的块才会被组合、测量、绘制。
 *
 * 全文与样式都不丢：块由 AnnotatedString.subSequence 切出，span 原样保留。
 */
internal const val TEXT_VIRTUALIZE_MIN_LINES = 300

/** 每块行数：60 行 ≈ 一个视口的量，块太大虚拟化就白做了。 */
internal const val TEXT_CHUNK_LINES = 60

/** 视口高度上限：再高也只是把「整段」换成「一大段」，页面内滚动仍然卡。 */
internal val TEXT_VIEWPORT_HEIGHT = 480.dp

internal fun lineCount(text: CharSequence): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

/**
 * 按行切成 [start, end) 偏移，块边界永远落在换行符之后，所以不会把一行劈成两块。
 * 纯函数，单测直接覆盖（VirtualTextTest）。
 */
internal fun chunkOffsets(text: AnnotatedString, linesPerChunk: Int): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val per = linesPerChunk.coerceAtLeast(1)
    val out = ArrayList<IntRange>()
    var start = 0
    var lines = 0
    var i = 0
    while (i < text.length) {
        if (text[i] == '\n') {
            lines++
            if (lines % per == 0 && i + 1 < text.length) {
                out.add(start until i + 1)
                start = i + 1
            }
        }
        i++
    }
    if (start < text.length) out.add(start until text.length)
    return out
}

/**
 * 长文本的虚拟化渲染：定高视口 + 按行分块，只绘制可见块。
 * 只有超过 [TEXT_VIRTUALIZE_MIN_LINES] 行才用，短文本走普通 Text 更省。
 */
@Composable
internal fun VirtualText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    viewportHeight: androidx.compose.ui.unit.Dp = TEXT_VIEWPORT_HEIGHT,
) {
    val chunks = remember(text, style) { chunkOffsets(text, TEXT_CHUNK_LINES) }
    LazyColumn(modifier = modifier.heightIn(max = viewportHeight)) {
        items(chunks.size) { index ->
            val range = chunks[index]
            Text(
                text = text.subSequence(range.first, range.last + 1),
                style = style,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
