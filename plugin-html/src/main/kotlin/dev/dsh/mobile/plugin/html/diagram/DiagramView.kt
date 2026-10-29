package dev.dsh.mobile.plugin.html.diagram

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.mobile.plugin.html.HtmlDocument

/**
 * 图形/图表卡片（宿主通过 DiagramRenderers.install 注册到这里）。
 *
 * 结构：头部（kind 名 + 源码/预览 + 复制 + 全屏）→ 预览（WebView，手势由 Compose 管）或源码卡。
 * 手势为什么不用 WebView 自带的：卡片在列表里，WebView 一吃竖滑列表就滚不动了 ——
 * 只有「放大状态」或「内容高于卡片」时 Compose 才消费拖动，其余情况把手势让给外层列表。
 */
@Composable
fun DiagramView(kind: String, source: String, modifier: Modifier = Modifier) {
    val k = remember(kind) { kind.trim().lowercase() }
    val mode = remember(k) { DiagramHtml.modeOf(k) }
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val clipboard = LocalClipboardManager.current
    var showSource by remember(k, source) { mutableStateOf(false) }
    var fullscreen by remember(k, source) { mutableStateOf(false) }
    var remote by remember(k, source) { mutableStateOf<DiagramRemote>(DiagramRemote.Idle) }

    LaunchedEffect(k, source) {
        if (mode == DiagramHtml.Mode.PLANTUML) {
            remote = DiagramRemote.Pending
            remote = PlantUml.load(source)
        }
    }

    // 渲染什么在这里定死：html 类复用同模块的 HtmlDocument，plantuml 拿到 SVG 后按 svg 渲染
    val doc: String? = remember(k, source, dark, remote) {
        when (mode) {
            DiagramHtml.Mode.HTML -> HtmlDocument.build(context, source)
            DiagramHtml.Mode.PLANTUML ->
                (remote as? DiagramRemote.Ok)?.let { DiagramHtml.build("svg", it.svg, dark) }
            DiagramHtml.Mode.SOURCE -> null
            else -> DiagramHtml.build(k, source, dark)
        }
    }
    val note = when (val r = remote) {
        is DiagramRemote.Pending -> "正在联网渲染 PlantUML…"
        is DiagramRemote.Err -> "联网渲染失败：" + r.reason
        else -> DiagramHtml.noteOf(k)
    }

    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, line, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(k.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            CardAction(if (showSource) "预览" else "源码") { showSource = !showSource }
            CardAction("复制") { clipboard.setText(AnnotatedString(source)) }
            CardAction("⛶", enabled = doc != null && !showSource) { fullscreen = true }
        }
        HorizontalDivider(color = line)
        val shown = doc
        if (shown != null && !showSource) {
            PreviewBox(shown)
        } else {
            SourceCard(source, note)
        }
    }

    if (fullscreen && doc != null) {
        FullscreenDiagram(kind = k, doc = doc, onClose = { fullscreen = false })
    }
}

@Composable
private fun CardAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 源码卡：等宽、长行不折（配合横向滚动），高度上限与预览卡一致。 */
@Composable
private fun SourceCard(source: String, note: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            note,
            Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_CARD_HEIGHT)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                source,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * 预览区：高度由页面回报（首次量完才定高，最少 96dp、最多 360dp）。
 * 缩放在 graphicsLayer 上做，深看靠全屏的原生缩放（那里是矢量重排，不糊）。
 */
@Composable
private fun PreviewBox(doc: String) {
    val density = LocalDensity.current
    var contentPx by remember(doc) { mutableFloatStateOf(0f) }
    val zoom = remember(doc) { ZoomState() }
    val contentDp = with(density) { contentPx.toDp() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(contentDp.coerceIn(MIN_CARD_HEIGHT, MAX_CARD_HEIGHT))
            .clipToBounds(),
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                .pinchPan(zoom, viewW = { viewW }, viewH = { viewH }, contentH = { contentPx }),
        ) {
            DiagramWeb(
                doc = doc,
                modifier = Modifier.fillMaxSize(),
                // 高度只增不减：库是异步画的，先量到的小值不能被后来的中间态压回去
                onHeight = { px -> if (px > contentPx) contentPx = px },
            )
        }
    }
}

/**
 * 双指缩放 + 拖动。engage 条件：两根以上手指（捏合）或当前已放大。
 * 不 engage 时一个事件都不消费 —— 单指竖滑原样交给外层会话列表滚动。
 */
private fun Modifier.pinchPan(
    zoom: ZoomState,
    viewW: () -> Float,
    viewH: () -> Float,
    contentH: () -> Float,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var engaged = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            val zoomChange = event.calculateZoom()
            val pan = event.calculatePan()
            if (!engaged && (pressed > 1 || zoom.scale > 1.01f)) engaged = true
            if (!engaged) continue
            if (zoomChange > 0f && zoomChange != 1f) {
                zoom.scale = (zoom.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            }
            if (pan != Offset.Zero) {
                zoom.offset = clampOffset(zoom.offset + pan, zoom.scale, viewW(), viewH(), contentH())
            }
            event.changes.forEach { if (it.pressed) it.consume() }
        }
        // 缩回 1 倍后归位，否则会留一个偏移看着像"卡住"
        if (zoom.scale <= 1.01f) zoom.offset = Offset.Zero
    }
}

/** 平移边界：横向按放大倍数算，纵向还要把"内容比卡片高"的那部分算进去，否则看不到底。 */
private fun clampOffset(offset: Offset, scale: Float, viewW: Float, viewH: Float, contentH: Float): Offset {
    val maxX = ((scale - 1f) * viewW / 2f).coerceAtLeast(0f)
    val scaledH = if (contentH > 0f) contentH * scale else viewH * scale
    val maxY = ((scaledH - viewH) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 全屏：同一个文档，换成可交互的 WebView（原生双指缩放 + 滚动）。 */
@Composable
private fun FullscreenDiagram(kind: String, doc: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF0B0B0D), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(kind.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF9AA0A6))
                    Spacer(Modifier.weight(1f))
                    FullscreenAction("重置缩放") { resetZoom(web) }
                    FullscreenAction("关闭") { onClose() }
                }
                DiagramWeb(doc = doc, modifier = Modifier.fillMaxSize(), interactive = true, onCreated = { web = it })
            }
        }
    }
}

@Composable
private fun FullscreenAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Color(0xFFDADCE0),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * 重置缩放：原生捏合缩放没有"设回 1"的 API，只能按当前倍数反推一次 zoomBy。
 * 拿不到 scale（极少数实现返回 0）时退化成滚到顶，至少位置归位。
 */
private fun resetZoom(web: WebView?) {
    val view = web ?: return
    runCatching {
        val scale = view.scale
        if (scale > 0f && scale != 1f) view.zoomBy(1f / scale)
        view.scrollTo(0, 0)
    }
}

private val MIN_CARD_HEIGHT = 96.dp
private val MAX_CARD_HEIGHT = 360.dp
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 4f
