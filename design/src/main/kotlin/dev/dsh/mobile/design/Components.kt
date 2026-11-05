// 通用小件：头像、顶栏、图标按钮、Token 胶囊、上下文环、消息气泡与卡片分隔线。
package dev.dsh.mobile.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.ChatMessage.Role
import dev.dsh.mobile.core.model.TokenUsage
import kotlin.math.absoluteValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext

internal fun avatarAccent(seed: String): Color =
    Color(AVATAR_COLORS[(seed.hashCode().absoluteValue) % AVATAR_COLORS.size].second)

private val AVATAR_COLORS = listOf(
    0xFF3CB4FF to 0xFF0090F0,
    0xFF7C5CFF to 0xFF5B3BFF,
    0xFF22C55E to 0xFF12A150,
    0xFFF59E0B to 0xFFE07B00,
    0xFFFF6B6B to 0xFFE03131,
    0xFF10A37F to 0xFF0A7C61,
    0xFFD97757 to 0xFFB8532F,
    0xFF00B8D9 to 0xFF0089A8,
)

/** QQ-style rounded-square avatar: stable gradient from the id, emoji or initial inside. */
@Composable
fun Ava(
    seed: String,
    size: Dp = 46.dp,
    label: String? = null,
    emoji: String? = null,
    online: Boolean = false,
    badge: String? = null,
) {
    val pair = AVATAR_COLORS[(seed.hashCode().absoluteValue) % AVATAR_COLORS.size]
    val text = emoji ?: label?.trim()?.take(1)?.uppercase() ?: "?"
    Box {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.33f))
                .background(Brush.linearGradient(listOf(Color(pair.first), Color(pair.second)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .clip(QqShape.pill())
                    .background(LocalQq.current.green),
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(9.dp))
                    .background(LocalQq.current.t1)
                    .padding(horizontal = 4.dp),
            ) {
                Text(badge, color = LocalQq.current.card, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Header grows with system font scale; leading / centre / trailing slots. */
@Composable
fun QqTopBar(
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    leadingExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().background(p.card).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = QqSpace.s()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左右各占一份剩余空间：内容不带 weight，天然落在正中间，
            // 不会被右侧多出来的按钮挤偏（之前设置/插件页标题都偏左就是这个原因）。
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { leading?.invoke() }
            content()
            if (leadingExpanded) {
                Box(contentAlignment = Alignment.CenterEnd) { trailing?.invoke() }
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing?.invoke() }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    }
}

@Composable
fun IconBtn(icon: ImageVector, contentDescription: String?, onClick: () -> Unit) {
    val p = LocalQq.current
    // 36dp 的视觉尺寸保留，节点本身走 48dp：IconBtn 不画背景，撑大只影响水波纹与命中区。
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = p.t1, modifier = Modifier.size(21.dp))
    }
}

/** Blue token pill from the mockup header. */
@Composable
fun TokenPill(usage: TokenUsage) {
    val p = LocalQq.current
    val total = if (usage.total > 0) usage.total else usage.input + usage.output
    Row(
        Modifier
            .clip(QqShape.pill())
            .background(p.accent.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(QqShape.pill()).background(p.accent))
        Text(
            formatTokens(total),
            color = p.accent,
            fontSize = qqFontBody(13.sp),
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

/**
 * 上下文占用环：灰底环 + 蓝色进度弧，弧长即上下文占比，0% 只留底环。
 * 挂「上下文」那行文字左侧用，状态语义仍由 SessionStatusDot 负责。
 */
@Composable
fun ContextRing(percent: Int, size: Dp = 12.dp) {
    val p = LocalQq.current
    val sweep = percent.coerceIn(0, 100) / 100f * 360f
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        val d = this.size.minDimension - stroke
        val topLeft = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
        drawArc(
            color = p.line,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(d, d),
            style = Stroke(stroke),
        )
        if (sweep > 0f) drawArc(
            color = p.accent,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = Size(d, d),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * 一条消息。照 DSH 网页端的排法：
 *
 * - 用户消息：右对齐浅灰气泡、无头像（DSH 也不给用户画头像），正文纯文本。
 * - 助手消息：**不套气泡**，走文档流 —— 顶部一行 头像 + 名字 + 时间，下面是全宽正文
 *   （markdown/公式/代码由 richBody 画），思考与工具各自是行内卡片。
 *
 * 以前两边都是 QQ 气泡 + 大圆角头像，长回答被挤成一条窄柱、思考行还被气泡包住，
 * 看起来又挤又生硬。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    showThoughts: Boolean = true,
    /** 名字后面那行耗时（11.5sp 灰字）；设置里可关，关掉后只剩作者名与流式点。 */
    showDuration: Boolean = true,
    /** 作者名跟着头像渐变上色：群里多成员时一眼分得清谁在说话。 */
    colorizeAuthors: Boolean = false,
    /** 「思考默认展开」：历史消息按它展开/收起，流式中始终展开。 */
    expandThoughts: Boolean = false,
    /**
     * 富文本正文槽：feature:chat 传 :render 的 ChatContent。design 不依赖 :render，
     * 所以这里只留槽位；为空时退回纯文本。
     */
    richBody: (@Composable (text: String, streaming: Boolean) -> Unit)? = null,
) {
    val p = LocalQq.current
    val tokens = LocalThemeTokens.current
    val mine = message.role == Role.USER
    // 圆角全走 tokens：己方 accent 气泡、对方白卡，靠头像那侧留一个尖角。
    val rMine = tokens.bubbleRadius(true)
    val tMine = tokens.tailRadius(true)
    val rOther = tokens.bubbleRadius(false)
    val tOther = tokens.tailRadius(false)
    val mineShape = RoundedCornerShape(rMine, rMine, tMine, rMine)
    val otherShape = RoundedCornerShape(rOther, rOther, rOther, tOther)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * 0.82f
        if (mine) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (message.at > 0L) {
                    Text(
                        dev.dsh.mobile.core.platform.TimeFmt.clock(message.at),
                        color = p.t3,
                        fontSize = qqFontBody(11.sp),
                        modifier = Modifier.padding(end = 4.dp, bottom = 3.dp),
                    )
                }
                Box(
                    Modifier
                        .widthIn(max = maxBubble)
                        .clip(mineShape)
                        .background(tokens.bubbleMine)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        message.attachments.forEach { path -> AttachmentImage(path, true) }
                        SelectionContainer {
                            Text(message.text, color = Color.White, fontSize = qqFontBody(15.5.sp), lineHeight = 22.sp)
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        // 对方是白卡：占满一行宽度、hairline 描边，深色下走 hover 面，不再是灰团。
                        .fillMaxWidth()
                        .clip(otherShape)
                        .background(if (p.dark) p.hover else p.card)
                        .border(1.dp, p.line, otherShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ava(
                seed = message.authorId ?: message.sessionId,
                size = 22.dp,
                label = message.authorName ?: "DSH",
            )
            Spacer(Modifier.width(7.dp))
            Text(
                message.authorName ?: "DSH",
                color = if (colorizeAuthors) avatarAccent(message.authorId ?: message.sessionId) else p.t1,
                fontSize = qqFontBody(13.sp),
                fontWeight = FontWeight.Medium,
            )
            if (showDuration && message.at > 0) {
                Spacer(Modifier.width(6.dp))
                // 名字后面挂计时：流式时每秒往上走，结束停在这次生成的真实耗时。
                MessageTimer(
                    startAt = message.at,
                    streaming = message.streaming,
                    durationMs = message.durationMs,
                )
            }
            if (message.streaming) {
                Spacer(Modifier.width(7.dp))
                StreamingDot()
            }
            // 这一条回答自己花的 token：不再区分输入/输出两段箭头，直接给一个本次消耗总数。
            if (showDuration) {
                val used = message.inputTokens + message.outputTokens
                if (used > 0L) {
                    Spacer(Modifier.width(6.dp))
                    Text("· " + formatTokens(used) + " tok", color = p.t3, fontSize = qqFontBody(11.sp))
                }
            }
            // 正文全选复制：长回答在手机上拖选很难受，给一个一键复制。流式时先不给。
            if (!message.streaming && message.text.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                CopyMessageButton(message.text)
            }
        }
        // 流式中即使还没有一个字，也要先把「深度思索中」的头露出来：
        // 以前要等第一段思维链到达才出现，用户点了发送后几秒钟屏幕上什么都没有。
        if (showThoughts && (message.streaming || message.thought.isNotBlank() || message.toolCalls.isNotEmpty())) {
            Spacer(Modifier.height(QqSpace.xs()))
            ActivitySummary(
                thought = message.thought,
                tools = message.toolCalls,
                at = message.at,
                live = message.streaming,
                durationMs = message.durationMs,
                messageId = message.id,
                expandDefault = expandThoughts,
            )
        }
                    message.attachments.forEach { path -> AttachmentImage(path, false) }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        if (richBody != null) {
                            richBody(message.text, message.streaming)
                        } else {
                            Text(
                                message.text.ifBlank { if (message.streaming) "…" else "" },
                                color = p.t1,
                                fontSize = 15.5.sp,
                                lineHeight = 24.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * 消息头部的计时器：生成中每秒自增，生成结束后停在落库的耗时上。
 * 拿不到耗时（老数据）才退回时钟。
 */
@Composable
private fun CopyMessageButton(text: String) {
    val p = LocalQq.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(stringResource(R.string.common_copy), color = p.t3, fontSize = 11.sp)
    }
}

@Composable
private fun MessageTimer(startAt: Long, streaming: Boolean, durationMs: Long) {
    val p = LocalQq.current
    val elapsed by produceState(if (durationMs > 0L) durationMs else 0L, streaming, durationMs, startAt) {
        if (!streaming) {
            value = durationMs
        } else {
            while (true) {
                value = (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    val text = when {
        streaming -> shortDuration(elapsed).ifEmpty { "0.0s" }
        durationMs > 0L -> shortDuration(durationMs)
        else -> dev.dsh.mobile.core.platform.TimeFmt.clock(startAt)
    }
    Text(text, color = p.t3, fontSize = 11.5.sp)
}

/** 流式时名字右边那个呼吸小圆点，替代以前「输出中」三个字。 */
@Composable
private fun StreamingDot() {
    val p = LocalQq.current
    val alpha = rememberInfiniteTransition(label = "dot")
        .animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dotAlpha",
        )
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(p.accent.copy(alpha = alpha.value)),
    )
}

@Composable
fun QqCardDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(LocalQq.current.line))
}
