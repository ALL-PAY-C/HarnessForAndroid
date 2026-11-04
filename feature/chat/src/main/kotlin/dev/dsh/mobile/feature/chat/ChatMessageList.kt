// 消息列表：气泡渲染、时间分隔、权限卡、选项气泡、系统回执与自动跟随滚动。
package dev.dsh.mobile.feature.chat

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.dsh.mobile.core.common.TimeFmt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.protocol.RecallReport
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.MessageBubble
import dev.dsh.mobile.design.dividerText
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.render.ChatContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyListState

/** Five minutes of silence earns a timestamp, matching QQ. */
private const val TIME_DIVIDER_GAP_MS = 5 * 60 * 1000L

/** 装了 HTML 渲染插件（dsh.html，注册 messageRenderer "html.web"）时用它渲染整块 HTML 文档。 */
internal const val HTML_RENDERER_ID: String = "html.web"

/**
 * 选项解析器搬到了 core:common —— 通知栏也要把同一批选项挂成按钮，
 * 两处必须用同一套规则（否则界面有卡片、通知里没按钮）。这里只留兼容别名，
 * 别在本文件重新实现一遍解析。
 */
internal typealias Option = dev.dsh.mobile.core.common.DshOption

internal fun splitOptions(text: String): Pair<String, List<Option>> =
    dev.dsh.mobile.core.common.splitOptions(text)

/**
 * 判断这段正文是不是一整块 HTML 文档（该交给 HTML 渲染插件用 WebView 画）。
 *
 * 只看文档级标记：<html>/<!doctype>/<body>。片段级标签（<b>、<br>）仍走内置富文本，
 * 否则一句「用 <br> 换行」也会被塞进 WebView。
 */
/**
 * 模型常把整份 HTML 包在 ```html 围栏里。判文档类型前先剥掉这层壳，否则围栏里的
 * <!doctype html> 永远匹配不到，用户看到的是被转义的源码。
 */
internal fun stripDocFence(text: String): String {
    val t = text.trim()
    if (t.length < 8 || !t.startsWith("```") || !t.endsWith("```")) return text
    val firstLine = t.indexOf('\n')
    if (firstLine < 0) return text
    val lang = t.substring(3, firstLine).trim().lowercase()
    if (lang.isNotEmpty() && lang != "html" && lang != "htm") return text
    return t.substring(firstLine + 1, t.length - 3).trim()
}

/**
 * 去掉 markdown 围栏代码块，只留围栏外的正文。
 *
 * 用途：判断「这条消息是不是一份 HTML 文档」时不能被代码块里的示例 HTML 骗到。
 */
internal fun stripFencedBlocks(text: String): String {
    if (!text.contains("```")) return text
    val out = StringBuilder(text.length)
    var inFence = false
    text.lineSequence().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            out.append('\n')
        } else if (!inFence) {
            out.append(line).append('\n')
        }
    }
    return out.toString()
}

/**
 * 这条消息是否整段就是一份 HTML 文档（要交给网页渲染插件画）。
 *
 * 两种合法形态：
 * 1. 整条消息被一个 `html` 围栏（或空语言围栏）包住，且剥壳后是完整文档；
 * 2. 正文（围栏外）本身就以 doctype / <html 开头。
 *
 * 其余情况一律按 Markdown 渲染：模型解释代码时贴的 ```bash 里可能含
 * <!DOCTYPE html>，不能凭「出现了 <html>」就把整条 5000 字回复当成网页画。
 */
internal fun looksLikeHtmlDocument(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val unwrapped = stripDocFence(trimmed)
    val wholeDoc = unwrapped.length != trimmed.length
    val t = (if (wholeDoc) unwrapped else stripFencedBlocks(trimmed)).lowercase()
    if (t.isEmpty() || !t.contains("<")) return false
    if (t.startsWith("<!doctype html")) return true
    if (t.startsWith("<html")) return true
    if (!wholeDoc) return false
    if (t.contains("</html>")) return true
    return t.contains("<body") && t.contains("</body>")
}

/** /compact 等待期的占位文案（压缩要调模型，别让点击看起来没反应）。 */

/**
 * 一条系统回执：居中、灰底小字。
 *
 * 不套 [dev.dsh.mobile.design.MessageBubble]：系统回执没有作者、不该占一整张白卡，
 * 排法跟上面的时间分割线同族（居中窄条），一眼能看出「这不是谁说的话」。
 */
@Composable
private fun SystemNoteRow(text: String) {
    val p = LocalQq.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = p.t2,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(p.card)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * /recall 回执（T9.8-S4）：一行结论 + 可折叠的命中清单（只有 rowid 与角色，没有正文）。
 *
 * 为什么可折叠：回捞是「我确实翻过冷层」的凭据，用户偶尔要看翻到了哪几条；
 * 但把 400 字原文倒进聊天流会把真正的对话顶下去 —— 正文走的是下一轮提示词，不进 UI。
 */
@Composable
private fun RecallNoteRow(report: RecallReport) {
    val p = LocalQq.current
    var open by remember(report) { mutableStateOf(false) }
    val hasRows = report.rows.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasRows) report.message + " · " + (if (open) stringResource(DesignR.string.common_collapse) else stringResource(DesignR.string.common_expand)) else report.message,
            color = p.t2,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(p.card)
                .clickable(enabled = hasRows) { open = !open }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        if (open) {
            Column(Modifier.padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                report.rows.forEach { row ->
                    Text(row, color = p.t3, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** 关掉特殊格式渲染时的正文：逐字原样、等宽，超宽横向滚动，不解析任何标记（不会变空白）。 */
@Composable
private fun SourceText(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    )
}

/** 小胶囊标签：权限卡头部的「由谁发起」「只回传给谁」。 */
@Composable
private fun Pill(text: String, tint: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = tint, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Divider1(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 判断某个选项是不是「放行」：kind / optionId / name 任意一处命中即可，deny 优先。 */
private fun isAllowOption(kind: String?, optionId: String?, name: String?): Boolean {
    val hay = ((kind ?: "") + " " + (optionId ?: "") + " " + (name ?: "")).lowercase()
    if (hay.contains("deny") || hay.contains("reject") || hay.contains("refuse")) return false
    if (hay.contains("取消") || hay.contains("拒绝") || hay.contains("不允许")) return false
    return hay.contains("allow") || hay.contains("approve") || hay.contains("accept") ||
        hay.contains("允许") || hay.contains("同意") || hay.contains("批准") || hay.contains("继续")
}

/**
 * 权限确认卡：直接长在对话流里，不再弹模态框。
 *
 * 第一段是已经发生的事（上一条气泡），这张卡说的是「接下来的命令需要你确认」；
 * A/B/C 点一下就回传，「其他」把光标送到下面的输入框，自己打字发。
 */
@Composable
private fun PermissionCard(
    perm: PermissionRequest,
    onChoose: (String?) -> Unit,
    onCustom: () -> Unit,
    /** 群聊里答案只回给发起者，气泡上明说回传给谁。 */
    onlyTo: String? = null,
) {
    val p = LocalQq.current
    val letters = "ABCDEFGH"
    val owner = perm.ownerAgentName
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
            .clip(QqShape.field())
            .background(p.card)
            .border(1.dp, p.accent.copy(alpha = 0.45f), QqShape.field()),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Text(
                perm.title.ifBlank { stringResource(DesignR.string.chat_perm_need_confirm) },
                color = p.t1,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(DesignR.string.common_waiting_confirm), color = p.t3, fontSize = 11.5.sp)
                if (!owner.isNullOrBlank()) Pill(stringResource(DesignR.string.chat_owner_pill, owner), p.accent)
                if (!onlyTo.isNullOrBlank()) Pill(stringResource(DesignR.string.chat_only_to, onlyTo), p.t3)
            }
        }
        // 每个选项一行：allow 走主色实心圆标，其余描边圆标；点一下即回传。
        perm.options.forEachIndexed { index, opt ->
            val allow = isAllowOption(opt.kind, opt.optionId, opt.name)
            Divider1(p.line)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(opt.optionId) }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val badge = letters.getOrNull(index)?.toString() ?: (index + 1).toString()
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (allow) p.accent else Color.Transparent)
                        .border(1.dp, if (allow) p.accent else p.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge,
                        color = if (allow) Color.White else p.t2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    opt.name.ifBlank { opt.optionId },
                    color = if (allow) p.t1 else p.t2,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Divider1(p.line)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onCustom() }
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).border(1.dp, p.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = p.t2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(DesignR.string.chat_perm_other_inline), color = p.t3, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 选项气泡：跟在一条回复下面，点一下就把那条选项当作用户消息发出去。
 *
 * 群聊里只回给说这句话的人，所以头上标一句「只回传给 @谁」。
 */
@Composable
private fun OptionBubbles(
    options: List<Option>,
    selectedId: String?,
    onlyTo: String?,
    onSelect: (Option) -> Unit,
    onCustom: () -> Unit,
) {
    val p = LocalQq.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!onlyTo.isNullOrBlank()) {
            Text(stringResource(DesignR.string.chat_only_to, onlyTo), color = p.t3, fontSize = 10.5.sp)
        }
        options.forEach { option ->
            val picked = selectedId == option.id
            val dim = selectedId != null && !picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(QqShape.field())
                    .background(if (picked) p.accent else p.card)
                    .border(1.dp, if (picked) p.accent else p.line, QqShape.field())
                    .clickable(enabled = selectedId == null) { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (picked) Color.White.copy(alpha = 0.25f) else p.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        color = if (picked) Color.White else p.t2,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    option.value,
                    color = if (picked) Color.White else p.t1,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f).alpha(if (dim) 0.45f else 1f),
                )
                if (picked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Check,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Row(
            Modifier
                .clip(QqShape.field())
                .clickable { onCustom() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(DesignR.string.chat_perm_other), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun ChatMessageList(
    state: ChatUiState,
    listState: LazyListState,
    compacting: Boolean,
    systemNotes: List<ChatMessage>,
    /** /recall 回执（T9.8-S4）；null = 没查过，一个像素都不多。 */
    recallNote: RecallReport? = null,
    messageSlot: @Composable (ChatMessage) -> Boolean,
    composerFocus: FocusRequester,
    onActionMessage: (ChatMessage) -> Unit,
    onSend: (String) -> Unit,
    onAnswerPermission: (String, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalQq.current
    // 系统 notice 不再进气泡（T5.4）：同一段连续 notice 合成一条可展开记录，默认只显示结论那一行。
    val noticeRuns = remember(state.messages, state.isGroup) {
        groupNoticeRuns(state.messages, state.isGroup)
    }
    val noticeFolded = remember(noticeRuns) { groupNoticeHidden(noticeRuns) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        if (state.messages.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.SmartToy,
                    iconSize = 34.dp,
                    title = stringResource(DesignR.string.chat_empty_prompt),
                    subtitle = if (state.isGroup) stringResource(DesignR.string.chat_empty_hint_group) else stringResource(DesignR.string.chat_empty_hint_single),
                )
            }
        }
        state.messages.forEachIndexed { index, msg ->
            // 段首那一条渲染合并块，段内其余整段跳过（一起并进上面那条记录里）。
            noticeRuns[index]?.let { run ->
                item(key = "notice-run-" + run.firstId) { GroupNoticeBlock(run) }
                return@forEachIndexed
            }
            if (index in noticeFolded) return@forEachIndexed
            item(key = msg.id) {
            // QQ-style separator: only when the conversation actually pauses,
            // so a burst of messages stays a single visual block.
            val previous = state.messages.getOrNull(index - 1)
            val gap = if (previous == null) Long.MAX_VALUE else msg.at - previous.at
            if (gap >= TIME_DIVIDER_GAP_MS) {
                Text(
                    dividerText(TimeFmt.dividerParts(msg.at)),
                    color = p.t3,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            // 流式中不解析选项：正文还在长，尾巴上的 A/B 行还没写完。
            val (cleanText, options) =
                if (msg.streaming) msg.text to emptyList<Option>() else splitOptions(msg.text)
            val shown = if (options.isEmpty()) msg else msg.copy(text = cleanText)
            // 三条渲染路径由 [bodyRender] 统一裁决：会话内关掉「特殊格式渲染」后一律走
            // Source（逐字原样、不解析任何标记），保证关掉不是变空白而是看到源码。
            // 开着时：装了 HTML 渲染插件（dsh.html，注册 messageRenderer "html.web"）的整块
            // HTML 文档交给它用 WebView 渲染；流式中仍走内置富文本，避免半截标签闪烁。
            val body = bodyRender(shown.text, state.renderSpecial, shown.rendererId, shown.streaming, ::looksLikeHtmlDocument)
            Box(Modifier.pointerInput(msg.id) {
                detectTapGestures(onLongPress = { onActionMessage(msg) })
            }) {
            val pluginRendered = body == BodyRender.Html &&
                messageSlot(shown.copy(rendererId = shown.rendererId ?: HTML_RENDERER_ID)) == true
            if (!pluginRendered) {
                MessageBubble(
                    shown,
                    showThoughts = state.settings.showThoughts,
                    showDuration = state.settings.showDuration,
                    colorizeAuthors = state.settings.colorizeAuthors,
                    expandThoughts = state.settings.thoughtsExpanded,
                    // assistant 正文交给 :render 的富文本（代码块/表格/公式），用户消息保持纯文本；
                    // 关掉特殊格式渲染时改走等宽原文，一个字符都不加工。
                    richBody = { text, streaming ->
                        if (body == BodyRender.Source) {
                            SourceText(text, p.t1)
                        } else {
                            ChatContent(
                                text,
                                streaming = streaming,
                                style = TextStyle(color = p.t1, fontSize = 15.5.sp, lineHeight = 24.sp),
                            )
                        }
                    },
                )
            }
            }
            if (options.isNotEmpty()) {
                // 已选态按 message.id 记住：LazyColumn 复用气泡也不会串。
                var picked by rememberSaveable(msg.id) { mutableStateOf<String?>(null) }
                OptionBubbles(
                    options = options,
                    selectedId = picked,
                    onlyTo = if (state.isGroup) msg.authorName else null,
                    onSelect = { option ->
                        picked = option.id
                        onSend(option.value)
                    },
                    onCustom = { runCatching { composerFocus.requestFocus() } },
                )
            }
        }
            if (state.isGroup && msg.authorId != null &&
                state.messages.indexOfLast { it.authorId == msg.authorId } == index
            ) {
                state.permissions.filter { it.ownerAgentId == msg.authorId }.forEach { perm ->
                    item(key = "perm-${perm.requestId}") {
                        PermissionCard(
                            perm = perm,
                            onChoose = { optionId ->
                                onAnswerPermission(perm.requestId, optionId, perm.ownerAgentId)
                            },
                            onCustom = { runCatching { composerFocus.requestFocus() } },
                            onlyTo = perm.ownerAgentName,
                        )
                    }
                }
            }
        }
        // 正在发言指示（T5.4）：执行池里真心在跑的那几位，在流尾各挂一条「正在回复…」。
        // 只读现有 busyAgents，不新造状态；等权限的已由 PermissionCard 表达，不重复占位。
        if (state.isGroup && state.settings.showAgentStatus) {
            state.busyAgents
                .filter { it.state == AgentRunState.Thinking || it.state == AgentRunState.ToolRunning }
                .distinctBy { it.agentId }
                .forEach { agent ->
                    item(key = "typing-" + agent.agentId) {
                        SpeakingPlaceholder(
                            name = agent.agentName.ifBlank { state.agentName }
                                .ifBlank { stringResource(DesignR.string.chat_agent_generic) },
                            seed = agent.agentId,
                        )
                    }
                }
        }
        // 尚未产生成员输出时，权限卡仍须可见。
        state.permissions.filter { perm ->
            !state.isGroup || perm.ownerAgentId == null || state.messages.none { it.authorId == perm.ownerAgentId }
        }.forEach { perm ->
            item(key = "perm-${perm.requestId}") {
                PermissionCard(
                    perm = perm,
                    // 群聊里答案只回给发起者：卡片带着 ownerAgentId 一起回传。
                    onChoose = { optionId ->
                        onAnswerPermission(perm.requestId, optionId, perm.ownerAgentId)
                    },
                    onCustom = { runCatching { composerFocus.requestFocus() } },
                    onlyTo = if (state.isGroup) perm.ownerAgentName else null,
                )
            }
        }
        // /compact 进行中：一条占位，别让按钮点了以后毫无反应（压缩要调模型，可能秒级）。
        if (compacting) item(key = "compact-running") { SystemNoteRow(stringResource(DesignR.string.chat_compacting)) }
        // 系统回执（/compact 结果）：居中一条灰底小字，不占气泡位置、不带作者头像。
        items(systemNotes, key = { it.id }) { note -> SystemNoteRow(note.text) }
        // /recall 回执：同样居中，但可展开看 rowid 清单（正文不进 UI）。
        if (recallNote != null) item(key = "recall-note") { RecallNoteRow(recallNote) }
    }
}
