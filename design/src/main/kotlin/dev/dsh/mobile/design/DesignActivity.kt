// 思考与工具调用展示：活动摘要、思考折叠、工具卡片与 JSON 块。
package dev.dsh.mobile.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.ToolCall
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString

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
    val head = if (live) {
        stringResource(R.string.design_activity_thinking_live)
    } else {
        stringResource(R.string.design_activity_thought_steps, steps)
    }
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
                contentDescription = if (open) {
                    stringResource(R.string.design_activity_thought_collapse)
                } else {
                    stringResource(R.string.design_activity_thought_expand)
                },
                tint = p.t3.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp)) {
                // 工具动作先折成一句话，像 DSH 那样「› 执行了命令，已写入文件」。
                // 每个工具仍然可以单独点开看入参/响应，只是默认不再把整块 JSON 摊开占屏。
                if (tools.isNotEmpty()) {
                    val sep = stringResource(R.string.design_activity_phrase_sep)
                    val phrase = tools.map { toolPhrase(it).text() }.distinct().joinToString(sep)
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
                            if (toolsOpen) {
                                stringResource(R.string.common_collapse)
                            } else {
                                stringResource(R.string.design_activity_tool_count, tools.size)
                            },
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
                        CopyChip(stringResource(R.string.design_activity_copy_thought)) {
                            clipboard.setText(AnnotatedString(thought))
                        }
                    }
                } else if (!live && tools.isEmpty()) {
                    Text(
                        stringResource(R.string.design_activity_no_reasoning),
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
internal fun toolPhrase(tool: ToolCall): UiText {
    val k = (tool.kind + " " + tool.title).lowercase()
    return when {
        "shell" in k || "bash" in k || "exec" in k || "terminal" in k -> UiText.Res(R.string.design_tool_phrase_shell)
        "write" in k || "edit" in k || "patch" in k || "create" in k -> UiText.Res(R.string.design_tool_phrase_write)
        "read" in k || "cat" in k || "view" in k -> UiText.Res(R.string.design_tool_phrase_read)
        "grep" in k || "search" in k || "find" in k || "glob" in k -> UiText.Res(R.string.design_tool_phrase_search)
        "fetch" in k || "http" in k || "web" in k || "download" in k -> UiText.Res(R.string.design_tool_phrase_fetch)
        "delete" in k || "remove" in k || "rm" in k -> UiText.Res(R.string.design_tool_phrase_delete)
        tool.title.isNotBlank() -> UiText.Raw(tool.title)
        else -> UiText.Res(R.string.design_tool_phrase_other)
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
                tool.title.ifBlank { tool.args }.ifBlank { stringResource(R.string.design_tool_call_fallback) },
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
                    if (tool.attempts > 1) add(stringResource(R.string.design_tool_retry, tool.attempts - 1))
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
                JsonBlock(stringResource(R.string.design_json_args), tool.args)
                JsonBlock(stringResource(R.string.design_json_request), tool.requestJson)
                JsonBlock(stringResource(R.string.design_json_response), tool.responseJson)
                if (tool.detail.isNotBlank() && tool.status == ToolCall.ToolStatus.Failed) {
                    JsonBlock(stringResource(R.string.design_json_error), tool.detail)
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
                    stringResource(R.string.common_copy),
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
