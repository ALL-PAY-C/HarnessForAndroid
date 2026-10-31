package dev.dsh.mobile.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.ChatMessage.Role
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.mobile.core.model.ToolCall
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

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
                    .clip(RoundedCornerShape(50))
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
                .padding(horizontal = 8.dp),
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
            .clip(RoundedCornerShape(50))
            .background(p.accent.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(p.accent))
        Text(
            formatTokens(total),
            color = p.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** Runtime state pill: green ready / amber working / red failed / grey unconfigured. */
@Composable
fun StatusCapsule(state: RuntimeState, onClick: () -> Unit = {}) {
    val p = LocalQq.current
    val (label, color) = when (state) {
        is RuntimeState.Ready -> "就绪" to p.green
        is RuntimeState.Starting -> "启动中" to p.amber
        is RuntimeState.Reconnecting -> "重连 ${state.attempt}" to p.amber
        is RuntimeState.Failed -> "离线" to p.red
        RuntimeState.NotConfigured -> "未配置" to p.t3
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One conversation row inside a grouped card. */
@Composable
fun ConvRow(
    session: Session,
    onClick: () -> Unit,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    timeText: String = "",
    showDivider: Boolean = true,
    extras: List<@Composable (Session) -> Unit> = emptyList(),
) {
    val p = LocalQq.current
    val tokens = LocalThemeTokens.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) p.accent.copy(alpha = 0.07f) else p.card)
                .clickable(onClick = onClick)
                // 「列表密度」滑块（tokens.density）以前只在设置里改数值、没有任何地方读它。
                .padding(
                    horizontal = if (tokens.compact) 10.dp else 13.dp,
                    vertical = (if (tokens.compact) 8.dp else 10.dp) * tokens.density,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) p.accent else Color.Transparent)
                        .then(if (selected) Modifier else Modifier.background(p.card)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (selected) "✓" else "", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
            }
            Ava(
                seed = session.id,
                size = if (tokens.compact) 40.dp else 46.dp,
                label = session.title,
                online = session.status == SessionStatus.Running,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 四色状态点跟着标题走：蓝=运行中 / 橙=待选择 / 绿=做完 / 红=意外中断。
                    // 放在预览行末尾时会被右下角挤成一个孤立的小圆点，看不出属于哪一行。
                    SessionStatusDot(session.status, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        session.title.ifBlank { "未命名会话" },
                        color = p.t1,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (session.isGroup) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text("群 ${session.memberCount}", color = p.accent, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (session.pinned) {
                        Spacer(Modifier.width(5.dp))
                        Text("置顶", color = p.t3, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (timeText.isNotBlank()) {
                        Text(timeText, color = p.t3, fontSize = 11.5.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.preview.ifBlank { "开始对话…" },
                        color = p.t2,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        extras.forEach { it(session) }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().padding(start = 71.dp).height(1.dp).background(p.line))
        }
    }
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
 * 会话运行态圆点：蓝=运行中（呼吸）、橙=待选择、绿=做完、红=意外中断。
 * 列表和聊天头部共用，颜色语义只在这里定义一次。
 */
@Composable
fun SessionStatusDot(status: SessionStatus, size: Dp = 8.dp) {
    val p = LocalQq.current
    val color = when (status) {
        SessionStatus.Running -> p.accent
        SessionStatus.WaitingPermission -> Color(0xFFF0A020)
        SessionStatus.Failed -> p.red
        SessionStatus.Idle -> p.green
    }
    val alpha = if (status == SessionStatus.Running) {
        rememberInfiniteTransition(label = "statusDot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "statusDotAlpha",
        ).value
    } else {
        1f
    }
    // 用 Canvas 画而不是 Box+background：状态点只有 8dp，夹在文字行里时
    // 各种父布局的 clip / 圆角裁剪会把它整块裁掉（以前就是这么消失的）。
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawCircle(color = color, radius = this.size.minDimension / 2f, alpha = alpha)
    }
}

@Composable
fun RunPill(text: String) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(p.accent.copy(alpha = 0.1f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(p.accent))
        Text(text, color = p.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Compatibility alias: older screens pass a trailing slot. */
@Composable
fun SessionRow(
    session: Session,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    ConvRow(session = session, onClick = onClick, extras = if (trailing == null) emptyList() else listOf { trailing() })
}

/** Rounded card used for grouped lists and settings blocks. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalQq.current
    val style = LocalSurfaceStyle.current
    val border = style.borderWidth
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, shape, clip = false)
            .clip(shape)
            .background(p.card)
            // 材质叠层（液态高光 / 亚克力细噪点）画在底色之上、内容之下；无材质时是空操作。
            .panelSheen()
            // 外观设置里的描边：0dp 时这根 Modifier 根本不进链，旧外观一个像素都不变。
            // 液态材质用渐变描边模拟边缘折射感，其余材质仍是纯色分割线。
            .then(
                when {
                    border <= 0.dp -> Modifier
                    style.edge != null -> Modifier.border(border, style.edge, shape)
                    else -> Modifier.border(border, p.line, shape)
                },
            ),
    ) { content() }
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
                        dev.dsh.mobile.core.common.TimeFmt.clock(message.at),
                        color = p.t3,
                        fontSize = 11.sp,
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
                            Text(message.text, color = Color.White, fontSize = 15.5.sp, lineHeight = 22.sp)
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
                fontSize = 13.sp,
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
                    Text("· " + formatTokens(used) + " tok", color = p.t3, fontSize = 11.sp)
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
            Spacer(Modifier.height(4.dp))
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
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("复制", color = p.t3, fontSize = 11.sp)
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
        else -> dev.dsh.mobile.core.common.TimeFmt.clock(startAt)
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
fun EmptyState(title: String, subtitle: String) {
    val p = LocalQq.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = p.t2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = p.t3, fontSize = 13.sp)
    }
}

/** Bottom tab bar: icon + label, accent when active. */
data class TabSpec(val label: String, val icon: ImageVector)

@Composable
fun QqTabBar(
    tabs: List<TabSpec>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onEditPlus: (() -> Unit)? = null,
) {
    val p = LocalQq.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    // UI 修改模式：底部栏上方多一条可横滑的位置条 + 「+」，挑组件放到底部栏。
    if (onEditPlus != null && UiEditMode.plusVisible(UiEditMode.Slot.BottomBar)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.card)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                UiEditMode.Slot.BottomBar.label,
                color = p.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 6.dp),
            )
            SlotPlusButton(label = "编辑底部 tab 栏", onClick = onEditPlus)
        }
    }
    Column(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding()) {
        // 4 个 tab 单行等宽（插件 tab 多了就一起挤压，不再分第二行）。
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            tabs.forEachIndexed { index, tab -> TabItem(tab, index == selected) { onSelect(index) } }
        }
    }
}

/** 底部栏一格：图标 + 文字，宽度由外层 Row 的 weight(1f) 决定。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(tab: TabSpec, active: Boolean, onClick: () -> Unit) {
    val p = LocalQq.current
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(top = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            tab.icon,
            tab.label,
            tint = if (active) p.accent else p.t3,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            color = if (active) p.accent else p.t3,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Settings/agent row: 30dp tinted icon square + title/subtitle + trailing slot. */
@Composable
fun QqRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalQq.current
    // 行级不画描边：QqRow 通常嵌在 SectionCard 里，行框会落在卡片描边内侧变成双层框。
    // 描边由 SectionCard / SurfaceCard 这类卡片级容器统一消费 LocalSurfaceStyle。
    val tokens = LocalThemeTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            // 行高走 min：大字号下不裁字；纵向内边距跟「列表密度」滑块。
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp * tokens.density),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint ?: p.t2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.t1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = p.t3, fontSize = 11.5.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun QqCardDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(LocalQq.current.line))
}

/** Shared empty state for lists and centers: icon, primary/secondary copy, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 42.dp,
) {
    val p = LocalQq.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = p.t3, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(14.dp))
        Text(title, color = p.t1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = p.t3, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel, color = p.accent) }
        }
    }
}

/** Settings section copy uses one typography and spacing scale at both nesting levels. */
object SectionSpacing {
    val cardGap = 12.dp
    val horizontal = 14.dp
    val vertical = 12.dp
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val p = LocalQq.current
    Column(modifier.padding(vertical = 2.dp)) {
        Text(title, color = p.t1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = p.t3, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/** Small section label above a card. */
@Composable
fun SectionTitle(text: String, count: Int? = null) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = p.t2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = p.t3, fontSize = 11.sp)
        }
    }
}

/** 分组头 / 分区头共用的 14dp 下向 chevron（stroke 2.6）；收起态向左转 -90° 指向右侧。 */
@Composable
fun SectionChevron(expanded: Boolean, tint: Color = LocalQq.current.t3) {
    val angle by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Icon(
        ChevronDownGlyph,
        contentDescription = if (expanded) "收起" else "展开",
        tint = tint,
        modifier = Modifier.size(14.dp).rotate(angle),
    )
}

private val ChevronDownGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChevronDown",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }.build()
}

@Composable
fun SurfaceCard(content: @Composable () -> Unit) {
    val p = LocalQq.current
    val border = LocalSurfaceStyle.current.borderWidth
    Surface(
        color = p.card,
        shape = RoundedCornerShape(16.dp),
        border = if (border > 0.dp) BorderStroke(border, p.line) else null,
    ) {
        // Surface 自己铺 color，所以叠层要放在它内部才压得住底色；无材质时是空操作。
        Box(Modifier.panelSheen()) { content() }
    }
}

/** One action revealed by swiping a row to the left. */
data class SwipeAction(
    val label: String,
    val color: Color,
    val onAction: () -> Unit,
)

/**
 * QQ-style swipe row: drag left to reveal [actions] behind the content.
 * Horizontal drags are consumed only past the touch slop, so vertical list scrolling keeps working.
 */
@Composable
fun SwipeableRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 66.dp,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    val density = LocalDensity.current
    val maxPx = with(density) { (actionWidth * actions.size).toPx() }
    var dragTarget by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offsetX by animateFloatAsState(
        targetValue = dragTarget,
        animationSpec = if (dragging) snap() else tween(200),
        label = "swipeOffset",
    )

    // 只有真的左滑时才把动作条画出来：卡片底色在玻璃 / 壁纸下是半透明的，
    // 常驻绘制会让动作色块和标签从卡片底下透出来，整屏看着像一堆彩色条带。
    val revealed = offsetX < -0.5f
    Box(modifier.fillMaxWidth()) {
        if (revealed) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            actions.forEach { action ->
                Box(
                    Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.color)
                        .clickable {
                            dragging = false
                            dragTarget = 0f
                            action.onAction()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            dragging = true
                            dragTarget = (dragTarget + dragAmount).coerceIn(-maxPx, 0f)
                        },
                        onDragEnd = {
                            dragging = false
                            dragTarget = if (dragTarget < -maxPx / 2f) -maxPx else 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragTarget = 0f
                        },
                    )
                },
        ) {
            Box(Modifier.background(p.card)) { content() }
        }
    }
}


/**
 * 气泡顶部那行「思考 / 工具」摘要：折叠时一行概括做了什么，点开看推理全文与每次工具调用。
 *
 * 折叠态分三档：
 * - 正在推理：`思考中… 1.2k 字 · 2 个工具`，字数随流式实时往上走，右侧图标放大镜在呼吸；
 * - 已有推理：`思考 · 4.2s · 1.2k 字 · 2 个工具`，耗时取落库的真实生成时间；
 * - 只有工具：`工具调用 · 2 个`。
 *
 * 展开态是一张二级底色卡：思维链给固定高度并**自动跟随最新一行**（流式时不用手动往下拖），
 * 顶部有字数与一键复制；工具调用按条折叠，入参/请求/响应分块。
 * [messageId] 用来记住展开状态（rememberSaveable），滚动出屏幕再回来不丢。
 */
internal fun thoughtExpanded(
    live: Boolean,
    hasContent: Boolean,
    manual: Boolean?,
    /** 设置里的「思考默认展开」：历史消息按它展开/收起；流式中一律展开。 */
    defaultOpen: Boolean = false,
): Boolean = manual ?: when {
    !hasContent -> false
    // 流式中不折叠：推理正在长，收起来等于看不到过程。
    live -> true
    else -> defaultOpen
}

@Composable
fun ActivitySummary(
    thought: String,
    tools: List<ToolCall>,
    at: Long,
    modifier: Modifier = Modifier,
    /** 正在流式输出：默认展开，思维链跟着涨。 */
    live: Boolean = false,
    /** 已生成耗时（毫秒）；live 时以 at 为起点自己往上走。 */
    durationMs: Long = 0L,
    /** 记住展开状态用的键；空串时退化成跟随 live。 */
    messageId: String = "",
    /** 历史消息的默认展开态（设置项「思考默认展开」）；[live] 时忽略，始终展开。 */
    expandDefault: Boolean = false,
) {
    val p = LocalQq.current
    val clipboard = LocalClipboardManager.current
    var manualOpen by rememberSaveable(messageId) { mutableStateOf<Boolean?>(null) }
    var toolsOpen by rememberSaveable(messageId + "#tools") { mutableStateOf(false) }
    val chars = thought.trim().length
    val open = thoughtExpanded(live, chars > 0 || tools.isNotEmpty(), manualOpen, defaultOpen = expandDefault)
    // 折叠行只说「在干什么 / 干完了、用了多久 / 动了几个工具」。
    // 不再塞「1.2k 字」这种计数：字数对用户没有决策价值，还占掉一行里最显眼的位置。
    val steps = tools.size + if (chars > 0) 1 else 0
    val head = if (live) "正在思考…" else "思考过程 · $steps 步"
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { manualOpen = !open }
                .padding(vertical = 4.dp, horizontal = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingGlyph(live = live, active = open)
            Spacer(Modifier.width(6.dp))
            Text(
                head,
                color = if (live) p.accent.copy(alpha = 0.9f) else p.t3,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "收起思考" else "展开思考",
                tint = p.t3.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp)) {
                // 工具动作先折成一句话，像 DSH 那样「› 执行了命令，已写入文件」。
                // 每个工具仍然可以单独点开看入参/响应，只是默认不再把整块 JSON 摊开占屏。
                if (tools.isNotEmpty()) {
                    val phrase = tools.map { toolPhrase(it) }.distinct().joinToString("，")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { toolsOpen = !toolsOpen }
                            .padding(vertical = 5.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("›", color = p.accent.copy(alpha = 0.85f), fontSize = 13.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            phrase,
                            color = p.t2,
                            fontSize = 12.5.sp,
                            maxLines = if (toolsOpen) 4 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (toolsOpen) "收起" else tools.size.toString() + " 项",
                            color = p.t3,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (toolsOpen) {
                        // 工具详情用「等次色块」：比正文低一层的面 + hairline，和思维链的竖线引用块区分开。
                        val blockShape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 4.dp, bottom = 6.dp)
                                .clip(blockShape)
                                .background(if (p.dark) p.card else p.hover)
                                .border(1.dp, p.line, blockShape)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            tools.forEach { tool -> ToolCallCard(tool) }
                        }
                    }
                }
                if (chars > 0) {
                    // 正文块：左侧一条细竖线做缩进，不用卡片不用边框，读起来像引用块。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (tools.isNotEmpty()) 6.dp else 4.dp, bottom = 2.dp),
                    ) {
                        Box(
                            Modifier
                                .padding(top = 2.dp, bottom = 2.dp)
                                .width(2.dp)
                                .heightIn(min = 14.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(p.accent.copy(alpha = 0.25f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        ThoughtScroll(text = thought, live = live, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        CopyChip("复制思考") { clipboard.setText(AnnotatedString(thought)) }
                    }
                } else if (!live && tools.isEmpty()) {
                    Text(
                        "这一轮没有返回推理内容。换用带 reasoning 的模型（如 deepseek-reasoner）就能在这里看到完整思维链。",
                        color = p.t3,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/** 思维链正文：限高滚动，流式时自动贴住最新一行（不然长推理每次都得手动往下拖）。 */
@Composable
private fun ThoughtScroll(text: String, live: Boolean, modifier: Modifier = Modifier) {
    val p = LocalQq.current
    val scroll = rememberScrollState()
    LaunchedEffect(text.length, live) {
        if (live) runCatching { scroll.scrollTo(scroll.maxValue) }
    }
    SelectionContainer {
        Text(
            text,
            color = p.t2,
            fontSize = 12.5.sp,
            lineHeight = 19.5.sp,
            modifier = modifier
                .heightIn(max = 320.dp)
                .verticalScroll(scroll),
        )
    }
}

/** 把一个工具调用压成一句人话，拼进「› 执行了命令，写入了文件」那一行。 */
internal fun toolPhrase(tool: ToolCall): String {
    val k = (tool.kind + " " + tool.title).lowercase()
    return when {
        "shell" in k || "bash" in k || "exec" in k || "terminal" in k -> "执行了命令"
        "write" in k || "edit" in k || "patch" in k || "create" in k -> "写入了文件"
        "read" in k || "cat" in k || "view" in k -> "读取了文件"
        "grep" in k || "search" in k || "find" in k || "glob" in k -> "搜索了内容"
        "fetch" in k || "http" in k || "web" in k || "download" in k -> "访问了网络"
        "delete" in k || "remove" in k || "rm" in k -> "删除了内容"
        tool.title.isNotBlank() -> tool.title
        else -> "调用了工具"
    }
}

/** 折叠行左侧那枚图标：静默时是描边大脑，流式时换成实心并在透明度上呼吸。 */
@Composable
private fun ThinkingGlyph(live: Boolean, active: Boolean) {
    val p = LocalQq.current
    if (!live) {
        Icon(
            if (active) Icons.Filled.Psychology else Icons.Outlined.Psychology,
            contentDescription = null,
            tint = if (active) p.accent.copy(alpha = 0.85f) else p.t3,
            modifier = Modifier.size(15.dp),
        )
        return
    }
    val alpha = rememberInfiniteTransition(label = "think")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(680), RepeatMode.Reverse),
            label = "thinkAlpha",
        )
    Icon(
        Icons.Filled.Psychology,
        contentDescription = null,
        tint = p.accent.copy(alpha = alpha.value),
        modifier = Modifier.size(15.dp),
    )
}

/** 面板右上角的小按钮（复制/展开之类），统一观感。 */
@Composable
private fun CopyChip(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Text(
        label,
        color = p.accent,
        fontSize = 10.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
/**
 * 一次工具调用，DSH 那种紧凑形态：默认只有一行（状态点 + 工具名 + 命令 + 耗时），
 * 点开才铺入参 / 请求 / 响应三块原文。以前三块 JSON 无条件展开，一屏只能放一条。
 */
@Composable
private fun ToolCallCard(tool: ToolCall) {
    val p = LocalQq.current
    var open by rememberSaveable(tool.id) { mutableStateOf(false) }
    val color = when (tool.status) {
        ToolCall.ToolStatus.Pending, ToolCall.ToolStatus.InProgress -> p.accent
        ToolCall.ToolStatus.Completed -> p.green
        ToolCall.ToolStatus.Failed -> p.red
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(
                tool.kind.ifBlank { "tool" },
                color = p.t3,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tool.title.ifBlank { tool.args }.ifBlank { "工具调用" },
                color = if (tool.status == ToolCall.ToolStatus.Failed) p.red else p.t2,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                buildList {
                    if (tool.elapsedMs > 0L) add(shortDuration(tool.elapsedMs))
                    if (tool.attempts > 1) add("重试 ${tool.attempts - 1}")
                }.joinToString(" · "),
                color = p.t3,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = p.t3.copy(alpha = 0.55f),
                modifier = Modifier.size(13.dp),
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 13.dp, bottom = 4.dp)) {
                JsonBlock("入参", tool.args)
                JsonBlock("请求", tool.requestJson)
                JsonBlock("响应", tool.responseJson)
                if (tool.detail.isNotBlank() && tool.status == ToolCall.ToolStatus.Failed) {
                    JsonBlock("错误", tool.detail)
                }
            }
        }
    }
}

/** 等宽 JSON 块：横向滚动、纵向限高 240dp，右上角一键复制；空字段显示「—」。 */
@Composable
private fun JsonBlock(label: String, value: String) {
    val p = LocalQq.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = p.t3,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    "复制",
                    color = p.accent,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { clipboard.setText(AnnotatedString(value)) }
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            value.ifBlank { "—" },
            color = if (value.isBlank()) p.t3 else p.t2,
            fontSize = 10.5.sp * LocalFontRoles.current.code,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(p.card)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 7.dp, vertical = 5.dp),
        )
    }
}


/**
 * 短耗时，三段式：
 * - 不到 1 分钟 → `12.3s`
 * - 不到 1 小时 → `3m12s`
 * - 更长 → `1h05m` / `1h05m09s`
 *
 * 以前超过 60 秒就退回「3 分 12 秒」，气泡头一行里中英文混排很乱。
 */
internal fun shortDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val total = ms / 1000
    if (total < 60) return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> if (s > 0) h.toString() + "h" + two(m) + "m" + two(s) + "s" else h.toString() + "h" + two(m) + "m"
        else -> m.toString() + "m" + two(s) + "s"
    }
}

internal fun two(n: Long): String = if (n < 10) "0" + n else n.toString()

/**
 * 图片消息气泡：本地文件直接解码。
 * 点开全屏预览、长按出菜单、下面一行「文件名 · 宽×高」点一下复制路径；
 * 文件被删/没权限时显示「图片已失效 + 路径」，不留白块。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentImage(path: String, mine: Boolean) {
    val p = LocalQq.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val file = remember(path) { File(path) }
    val bmpState = remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val failed = remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        // 缩略图真实显示高度 220.dp（heightIn(max=220.dp)），2x 过采样兼顾预览清晰度。
        // 不能用整屏尺寸作目标：高分辨率屏上 4032x3024 图会算出 sample=1 仍整图解码（~49MB ARGB，OOM 风险）。
        val density = context.resources.displayMetrics.density
        val targetPx = (220f * density).roundToInt() * 2
        val decoded = try {
            withContext(Dispatchers.IO) {
                decodeImageSampled(path, targetPx, targetPx)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // OOM / 损坏图片：标记失效，不让异常抛穿 LaunchedEffect 打崩协程。
            null
        }
        if (decoded != null) bmpState.value = decoded else failed.value = true
    }
    val bmp = bmpState.value
    var preview by remember(path) { mutableStateOf(false) }
    var menu by remember(path) { mutableStateOf(false) }
    val copyPath: () -> Unit = { clipboard.setText(AnnotatedString(path)) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Box {
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { preview = true },
                                onLongClick = { menu = true },
                            ),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("复制本地路径", fontSize = 13.sp) },
                            onClick = { menu = false; copyPath() },
                        )
                        DropdownMenuItem(
                            text = { Text("打开方式", fontSize = 13.sp) },
                            onClick = { menu = false; openWith(context, file) },
                        )
                        DropdownMenuItem(
                            text = { Text("另存到相册", fontSize = 13.sp) },
                            onClick = { menu = false; saveToGallery(context, file) },
                        )
                    }
                }
                failed.value -> {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.bg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "图片已失效 · $path",
                            color = p.t3,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("加载中…", color = p.t3, fontSize = 12.sp)
                    }
                }
            }
        }
        val caption = buildList {
            add(file.name)
            if (bmp != null) add("${bmp.width}×${bmp.height}")
        }.joinToString(" · ")
        Text(
            caption,
            color = if (mine) Color.White.copy(alpha = 0.75f) else p.t3,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { copyPath() }
                .padding(horizontal = 2.dp, vertical = 1.dp),
        )
    }
    if (preview && bmp != null) {
        Dialog(
            onDismissRequest = { preview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { preview = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "×",
                    color = Color.White,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .clickable { preview = false },
                )
            }
        }
    }
}

/** 目标尺寸降采样采样率：2 的幂，保证解码后不小于 [reqWidth]×[reqHeight]，避免整图载入。 */
internal fun computeInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / sample) >= reqHeight && (halfWidth / sample) >= reqWidth) {
            sample *= 2
        }
    }
    return sample
}

/** 本地图片按目标尺寸降采样解码；文件不存在/损坏返回 null。须在 IO 线程调用。 */
private fun decodeImageSampled(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(w, h, reqWidth, reqHeight)
    }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

/** 用系统看图或其他应用打开本地文件（走 FileProvider，app 已注册 ${packageName}.fileprovider）。 */
private fun openWith(context: android.content.Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(view, "打开方式")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 复制进系统相册（Pictures/DSH）；API 29+ 走 MediaStore 的 RELATIVE_PATH。 */
private fun saveToGallery(context: android.content.Context, file: File) {
    runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DSH")
            }
        }
        val target = context.contentResolver
            .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
        context.contentResolver.openOutputStream(target)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }
}
/**
 * 文件夹式分组外框：一个描边框同时包住标题条与内容，视觉上是一张「文件夹」。
 * 与 SessionsScreen 里的分组同源，插件相关页面统一用它。
 */
@Composable
fun GroupFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalQq.current
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

/** 分组标题条：箭头 + 名称 + 计数，可点折叠；trailing 放按钮/复选。 */
@Composable
fun GroupHeader(
    label: String,
    count: Int? = null,
    expanded: Boolean = true,
    collapsible: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: ( @Composable RowScope.() -> Unit )? = null,
) {
    val p = LocalQq.current
    // 34dp 的固定高既戳不准也会在大字号下压字；改成 48dp 下限，命中区和字体缩放一起解决。
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(p.hover)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            SectionChevron(expanded = expanded)
        } else {
            Box(Modifier.width(14.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = p.t2,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.card)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) { Text("$count", color = p.t3, fontSize = 11.sp) }
        }
        trailing?.invoke(this)
    }
}

/** 分组子行：左侧一条树状引导线 + 描边小卡片，点开进入下一层。 */
@Composable
fun GroupChild(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalQq.current
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 11.dp.toPx()
                drawLine(
                    color = p.line,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 22.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(p.card)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .border(1.dp, p.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
