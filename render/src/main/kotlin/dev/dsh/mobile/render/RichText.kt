package dev.dsh.mobile.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import dev.dsh.mobile.pluginapi.HtmlBlockRenderers
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

// ------------------------------------------------------------------ inline

/** Inline-only Markdown: one flowing paragraph, no block layout. */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val spans = remember(text) { MarkdownParser.parseInline(text) }
    InlineSpans(spans = spans, modifier = modifier, style = style, onLinkClick = null)
}

/**
 * Renders already-parsed inline spans. Kept internal so [ChatContent] can reuse
 * the block parser's output instead of re-parsing each paragraph as a string.
 */
@Composable
internal fun InlineSpans(
    spans: List<MdInline>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    onLinkClick: ((String) -> Unit)? = null,
    /** 表格单元格要按列的 align 对齐；正文不传，沿用 Text 的默认值。 */
    textAlign: TextAlign? = null,
) {
    val contentColor = if (color.isSpecified) color else LocalContentColor.current
    val linkColor = MaterialTheme.colorScheme.primary
    val fontSize = style.fontSize.takeOrElse { 16.sp }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val basePx = with(density) { fontSize.toPx() }

    val rendered = remember(spans, contentColor, linkColor, fontSize, basePx) {
        buildAnnotated(spans, contentColor, linkColor, fontSize) { latex ->
            // 行内公式的占位尺寸必须等于真实排版尺寸，否则正文里会空出一大段。
            val (wPx, hPx) = mathInlineSizePx(measurer, density, latex, basePx)
            with(density) { wPx.toSp() to hPx.toSp() }
        }
    }
    val annotated = rendered.first
    val inlineContent = rendered.second

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val clickModifier = if (onLinkClick == null) {
        Modifier
    } else {
        Modifier.pointerInput(annotated) {
            detectTapGestures { pos: Offset ->
                val lr = layout
                if (lr != null) {
                    val at = lr.getOffsetForPosition(pos).coerceIn(0, annotated.length)
                    val end = (at + 1).coerceAtMost(annotated.length)
                    if (end > at) {
                        val hit = annotated.getStringAnnotations(LinkAnnotationTag, at, end).firstOrNull()
                        if (hit != null) onLinkClick(hit.item)
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier.then(clickModifier),
        color = color,
        style = style,
        textAlign = textAlign,
        inlineContent = inlineContent,
        onTextLayout = { layout = it },
    )
}

private fun buildAnnotated(
    spans: List<MdInline>,
    color: Color,
    linkColor: Color,
    fontSize: TextUnit,
    mathSize: (String) -> Pair<TextUnit, TextUnit>,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val builder = AnnotatedString.Builder()
    val inline = LinkedHashMap<String, InlineTextContent>()
    var mathIndex = 0

    fun emphasis(bold: Boolean, italic: Boolean, strike: Boolean): SpanStyle? {
        if (!bold && !italic && !strike) return null
        return SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        )
    }

    fun append(list: List<MdInline>, bold: Boolean, italic: Boolean, strike: Boolean) {
        for (span in list) {
            when (span) {
                is MdInline.Text -> {
                    val st = emphasis(bold, italic, strike)
                    if (st == null) builder.append(span.text) else builder.withStyle(st) { append(span.text) }
                }
                is MdInline.Code -> builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x1A808080),
                        fontSize = fontSize * 0.94f,
                    )
                ) { append(span.text) }
                is MdInline.Bold -> append(span.children, true, italic, strike)
                is MdInline.Italic -> append(span.children, bold, true, strike)
                is MdInline.Strike -> append(span.children, bold, italic, true)
                is MdInline.Link -> {
                    val start = builder.length
                    builder.append(span.label)
                    builder.addStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        start,
                        builder.length,
                    )
                    builder.addStringAnnotation(LinkAnnotationTag, span.href, start, builder.length)
                }
                is MdInline.Math -> {
                    val id = "math:" + mathIndex
                    mathIndex++
                    builder.appendInlineContent(id, span.latex)
                    val (w, h) = mathSize(span.latex)
                    inline[id] = InlineTextContent(
                        Placeholder(w, h, PlaceholderVerticalAlign.Center)
                    ) { _ -> MathFormula(span.latex, display = false, color = color, fontSize = fontSize) }
                }
            }
        }
    }

    append(spans, false, false, false)
    return builder.toAnnotatedString() to inline
}

// ------------------------------------------------------------------- blocks

/**
 * Full block-level chat rendering with streaming support.
 *
 * Only the part of the text that is provably stable is re-parsed: [safePrefixLength]
 * cuts at the last block boundary that is not inside an open fence or an open
 * display-math block, and that prefix's block list is memoised by its own text.
 */
@Composable
fun ChatContent(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onLinkClick: ((String) -> Unit)? = null,
) {
    // T4：超长消息默认折叠，只画预览 + 「展开全文」；数据层原文不动，展开即全量。
    val plan = remember(text.length, streaming) { collapsePlan(text, streaming) }
    var expanded by rememberSaveable(plan?.totalChars ?: 0) { mutableStateOf(false) }
    val shown = if (plan != null && !expanded) {
        val raw = text.take(plan.previewChars)
        // HTML 正文折叠后给可读预览（剥标签），展开仍然还原原文。
        if (looksLikeHtml(raw)) stripTags(raw) else raw
    } else {
        text
    }
    val blocks = rememberBlocks(shown, streaming)
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            key(blockKey(index, block)) {
                BlockView(block = block, style = style, onLinkClick = onLinkClick)
            }
        }
        if (plan != null) {
            LongContentFooter(
                plan = plan,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
        }
    }
}

/** 折叠态的底部条：还剩多少字 + 展开/收起。 */
@Composable
private fun LongContentFooter(plan: CollapsePlan, expanded: Boolean, onToggle: () -> Unit) {
    val label = if (expanded) {
        "收起（共 " + plan.totalChars + " 字）"
    } else {
        "展开全文（还有 " + plan.hiddenChars + " 字）"
    }
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun rememberBlocks(text: String, streaming: Boolean): List<MdBlock> {
    if (!streaming) return remember(text) { MarkdownParser.parseBlocks(text, false) }
    val cut = remember(text.length, streaming) { safePrefixLength(text) }
    val head = text.substring(0, cut)
    val tail = text.substring(cut)
    val headBlocks = remember(head) { MarkdownParser.parseBlocks(head, false) }
    val tailBlocks = remember(tail) { MarkdownParser.parseBlocks(tail, true) }
    return remember(headBlocks, tailBlocks) { headBlocks + tailBlocks }
}

/**
 * Length of the longest prefix whose parse cannot change when more text arrives.
 * Returns the start of an unclosed fence / math block, or the last blank line.
 */
internal fun safePrefixLength(text: String): Int {
    if (text.isEmpty()) return 0
    var inFence = false
    var fenceStart = -1
    var inMath = false
    var mathStart = -1
    var boundary = 0
    var pos = 0
    while (pos <= text.length) {
        val nl = text.indexOf('\n', pos)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(pos, lineEnd)
        val t = line.trim()
        if (!inFence && !inMath) {
            if (t.startsWith("\u0060\u0060\u0060")) {
                inFence = true; fenceStart = pos
            } else if (t == "$$" || t.startsWith("\\[")) {
                inMath = true; mathStart = pos
            } else if (t.isEmpty()) {
                boundary = if (nl < 0) text.length else nl + 1
            }
        } else if (inFence) {
            if (t.startsWith("\u0060\u0060\u0060")) {
                inFence = false
                boundary = if (nl < 0) text.length else nl + 1
            }
        } else {
            if (t == "$$" || t.endsWith("\\]")) {
                inMath = false
                boundary = if (nl < 0) text.length else nl + 1
            }
        }
        if (nl < 0) break
        pos = nl + 1
    }
    if (inFence) return fenceStart.coerceAtLeast(0)
    if (inMath) return mathStart.coerceAtLeast(0)
    return boundary
}

private fun blockKey(index: Int, block: MdBlock): String {
    val head = when (block) {
        is MdBlock.Paragraph -> inlineHead(block.spans)
        is MdBlock.Heading -> inlineHead(block.spans)
        is MdBlock.Quote -> inlineHead(block.spans)
        is MdBlock.BulletList -> block.items.firstOrNull()?.let { inlineHead(it) } ?: ""
        is MdBlock.CodeBlock -> block.code
        is MdBlock.MathBlock -> block.latex
        is MdBlock.HtmlBlock -> block.raw
        is MdBlock.Table -> block.headers.joinToString("|")
        MdBlock.Divider -> "-"
    }
    val kind = block::class.simpleName ?: "Block"
    return index.toString() + ":" + kind + ":" + head.take(24)
}

private fun inlineHead(spans: List<MdInline>): String {
    val sb = StringBuilder()
    fun walk(list: List<MdInline>) {
        for (s in list) {
            if (sb.length >= 24) return
            when (s) {
                is MdInline.Text -> sb.append(s.text)
                is MdInline.Code -> sb.append(s.text)
                is MdInline.Link -> sb.append(s.label)
                is MdInline.Math -> sb.append(s.latex)
                is MdInline.Bold -> walk(s.children)
                is MdInline.Italic -> walk(s.children)
                is MdInline.Strike -> walk(s.children)
            }
        }
    }
    walk(spans)
    return sb.toString()
}

@Composable
private fun BlockView(
    block: MdBlock,
    style: TextStyle,
    onLinkClick: ((String) -> Unit)?,
) {
    val baseSize = style.fontSize.takeOrElse { 16.sp }
    when (block) {
        is MdBlock.Paragraph -> InlineSpans(
            spans = block.spans,
            modifier = Modifier.fillMaxWidth(),
            style = style,
            onLinkClick = onLinkClick,
        )

        is MdBlock.Heading -> {
            val scale = when (block.level) {
                1 -> 1.55f
                2 -> 1.3f
                3 -> 1.14f
                else -> 1.0f
            }
            InlineSpans(
                spans = block.spans,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                style = style.copy(fontSize = baseSize * scale, fontWeight = FontWeight.Bold),
                onLinkClick = onLinkClick,
            )
        }

        is MdBlock.BulletList -> Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            block.items.forEachIndexed { i, item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (block.ordered) (i + 1).toString() + "." else "\u2022",
                        style = style,
                        modifier = Modifier.width(22.dp),
                    )
                    InlineSpans(
                        spans = item,
                        modifier = Modifier.weight(1f),
                        style = style,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }

        // ```html 围栏：装了渲染插件就直接把这段 HTML 画出来（脚本/Canvas/动画都能跑），
        // 没装就退回普通代码块。用户可以一键在「预览」和「源码」之间切。
        is MdBlock.CodeBlock -> {
            // 读 generation 而不是只读 render：插件在首帧之后才注册时，
            // 这一格要能自己重组、从源码翻成预览，用户不用退出重进。
            @Suppress("UNUSED_EXPRESSION")
            HtmlBlockRenderers.generation
            val web = if (block.lang.lowercase() in HTML_LANGS) HtmlBlockRenderers.render else null
            if (web != null) {
                HtmlFenceView(block, style, web)
            } else if (block.lang.lowercase() in HTML_LANGS) {
                // 没装 HTML 插件：至少别让人对着一块空白猜。源码照常显示，
                // 并在标题行提示一句该装什么。
                CodeBlockView(block, style, hint = "装「HTML 渲染」插件可预览")
            } else {
                CodeBlockView(block, style)
            }
        }

        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            )
            Spacer(modifier = Modifier.width(8.dp))
            InlineSpans(
                spans = block.spans,
                modifier = Modifier.weight(1f),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onLinkClick = onLinkClick,
            )
        }

        MdBlock.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        is MdBlock.Table -> TableView(block, style)

        is MdBlock.MathBlock -> MathFormula(
            latex = block.latex,
            display = true,
            color = LocalContentColor.current,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            fontSize = baseSize,
        )

        is MdBlock.HtmlBlock -> {
            // 装了 HTML 渲染插件就交给 WebView 真渲染（脚本/Canvas/动画都能跑），
            // 没装才退回内置的纯文本清洗渲染。气泡与头像始终由宿主绘制。
            // 读 generation：插件在首帧之后才注册 renderer 时这里要能重组（否则一直空白）。
            @Suppress("UNUSED_EXPRESSION") HtmlBlockRenderers.generation
            val web = HtmlBlockRenderers.render
            if (web != null) {
                web(block.raw)
            } else {
                val annotated = remember(block.raw, style) { HtmlRenderer.toAnnotated(block.raw, style) }
                // T4 性能：2.6 万行的单个 Text 每帧都要遍历全部行裁剪绘制，超阈值改走分块虚拟化。
                if (lineCount(annotated) > TEXT_VIRTUALIZE_MIN_LINES) {
                    VirtualText(annotated, style, Modifier.fillMaxWidth())
                } else {
                    Text(
                        text = annotated,
                        style = style,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 会被当成页面渲染的代码块语言。 */
private val HTML_LANGS = setOf("html", "htm", "xhtml", "svg", "xml")

/**
 * ```html 围栏的渲染卡片：默认真渲染，右上角可切「源码 / 预览」。
 * 渲染本身由插件提供（HtmlBlockRenderers），宿主只负责这张卡片的框架与兜底。
 */
@Composable
private fun HtmlFenceView(
    block: MdBlock.CodeBlock,
    style: TextStyle,
    render: @Composable (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var preview by remember(block.code) { mutableStateOf(true) }
    var copied by remember(block.code) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "html",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (preview) "查看源码" else "预览效果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { preview = !preview },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(block.code))
                        copied = true
                    },
                )
            }
            if (preview) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    render(block.code)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = block.code,
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(
    block: MdBlock.CodeBlock,
    style: TextStyle,
    /** 语言标签后面的补充说明（例如「装 HTML 插件可预览」）。 */
    hint: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    var wrap by rememberSaveable(block.lang) { mutableStateOf(true) }
    val longest = remember(block.code) { block.code.lineSequence().maxOfOrNull { it.length } ?: 0 }
    val mono = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (hint != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (longest > 28) {
                    Text(
                        text = if (wrap) "不换行" else "换行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { wrap = !wrap }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 长代码块限高纵向滚动（内容不丢），横向滚动仍由「不换行」开关控制。
            val vScroll = if (codeNeedsScroll(block.code)) {
                Modifier.heightIn(max = CODE_BLOCK_MAX_HEIGHT).verticalScroll(rememberScrollState())
            } else {
                Modifier
            }
            if (wrap) {
                // T4 性能：几千行的代码块同样是单个 Text，滚动时每帧遍历全部行；超阈值改虚拟化。
                if (lineCount(block.code) > TEXT_VIRTUALIZE_MIN_LINES) {
                    VirtualText(
                        text = remember(block.code) { AnnotatedString(block.code) },
                        style = mono,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        viewportHeight = CODE_BLOCK_MAX_HEIGHT,
                    )
                } else {
                    Text(
                        text = block.code,
                        style = mono,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(vScroll)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(vScroll)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = block.code, style = mono)
                }
            }
        }
    }
}

/**
 * 表格单元格里的行内样式（**粗体** / `代码` / 公式 / 链接）要照常渲染，
 * 否则一行公式会原样吐出 `$E=mc^2$`，比不排版还难看。
 */
@Composable
private fun CellText(
    raw: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val spans = remember(raw) { MarkdownParser.parseInline(raw) }
    if (spans.size == 1 && spans[0] is MdInline.Text) {
        Text(text = (spans[0] as MdInline.Text).text, style = style, modifier = modifier, textAlign = align)
    } else {
        // 直接把单元格宽度交给 Text（以前多包一层 Box，宽度约束落不到 Text 上，
        // 对齐就只在单元格左半边生效）。
        InlineSpans(spans = spans, style = style, modifier = modifier, textAlign = align)
    }
}

/** 解析层的列对齐 → Compose 的文字对齐。Start/End 跟随布局方向，别用 Left/Right。 */
private fun TableAlign.toTextAlign(): TextAlign = when (this) {
    TableAlign.START -> TextAlign.Start
    TableAlign.CENTER -> TextAlign.Center
    TableAlign.END -> TextAlign.End
}

@Composable
private fun TableView(block: MdBlock.Table, style: TextStyle) {
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    // 列宽按「估算的排版宽度」算：CJK/emoji 一个全角，ASCII 约半个。按字符数算会把
    // 「GET」折成「GE / T」、「✅ 完成」折成两行——上一版就是这样把表格挤花的。
    val em = style.fontSize.value.takeIf { it > 0f } ?: 15f
    // 每列的单元格文本（表头 + 各行）。列宽权重与窄屏兜底宽度都由它算。
    val columnTexts = remember(block) {
        (0 until columns).map { c ->
            listOf(block.headers.getOrNull(c) ?: "") + block.rows.map { it.getOrNull(c) ?: "" }
        }
    }
    // 正常宽度：按内容长度分权重铺满（长列多占，短列少占）。
    val weights = remember(columnTexts) { tableColumnWeights(columnTexts) }
    // 窄屏兜底：退回定宽 + 横向滚动，别把每列压到只剩一个字。
    val minWidths = remember(columnTexts, em) {
        columnTexts.map { col -> (col.maxOf { textEm(it) } * em + 24f).dp.coerceIn(52.dp, 320.dp) }
    }
    // 用 onSurface 的透明叠加，而不是 surfaceVariant/outline —— 宿主的自定义主题里
    // 这两个角色常常被设成和背景同色，表格就只剩几行字、看不到表头带和网格线。
    val ink = MaterialTheme.colorScheme.onSurface
    val headColor = ink.copy(alpha = 0.07f)
    val stripeColor = ink.copy(alpha = 0.035f)
    val lineColor = ink.copy(alpha = 0.18f)
    val radius = RoundedCornerShape(10.dp)
    val cellPad = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(radius)
            .border(0.8.dp, lineColor, radius),
    ) {
        // < 400dp 才允许横向滚动：正常手机宽度下表格必须铺满、不出现横向滚动条。
        // 只有「每列都压到最小可读宽度还是塞不下」才退回横向滚动。早前用 maxWidth < 400.dp
        // 当判据，手机正文宽度普遍 340dp 左右，等于永远走兜底分支，列宽铺满和列对齐都没生效。
        val narrow = minWidths.fold(0.dp) { acc, w -> acc + w } > maxWidth
        Column(
            modifier = Modifier
                .then(
                    if (tableNeedsScroll(block.rows.size)) {
                        Modifier.heightIn(max = TABLE_MAX_HEIGHT).verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .then(if (narrow) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        ) {
            Row(
                modifier = if (narrow) {
                    Modifier.background(headColor)
                } else {
                    Modifier.fillMaxWidth().background(headColor)
                },
            ) {
                for (c in 0 until columns) {
                    CellText(
                        raw = block.headers.getOrNull(c) ?: "",
                        style = style.copy(fontWeight = FontWeight.Bold),
                        modifier = (
                            if (narrow) Modifier.width(minWidths[c])
                            else Modifier.weight(weights[c].toFloat())
                            ).then(cellPad),
                        align = block.aligns.getOrElse(c) { TableAlign.START }.toTextAlign(),
                    )
                }
            }
            block.rows.forEachIndexed { r, row ->
                HorizontalDivider(color = lineColor, thickness = 0.8.dp)
                Row(
                    modifier = when {
                        narrow && r % 2 == 1 -> Modifier.background(stripeColor)
                        narrow -> Modifier
                        r % 2 == 1 -> Modifier.fillMaxWidth().background(stripeColor)
                        else -> Modifier.fillMaxWidth()
                    },
                ) {
                    for (c in 0 until columns) {
                        CellText(
                            raw = row.getOrNull(c) ?: "",
                            style = style,
                            modifier = (
                                if (narrow) Modifier.width(minWidths[c])
                                else Modifier.weight(weights[c].toFloat())
                                ).then(cellPad),
                            align = block.aligns.getOrElse(c) { TableAlign.START }.toTextAlign(),
                        )
                    }
                }
            }
        }
    }
}
