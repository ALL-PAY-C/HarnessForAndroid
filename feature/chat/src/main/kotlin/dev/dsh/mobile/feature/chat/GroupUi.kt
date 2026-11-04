// 群聊 UI 契约（设计文档 §8 的 5 组件里属于 T5.4 的 4 块）：
// MemberCard / 正在发言指示 / TurnStream 的可折叠 notice / ModeratorBar 的「指定」。
// 只做展示与交互，数据全部从 ChatUiState（messages / participants / agents / models）与
// ChatViewModel.pinnedSpeaker 读；不新造全局状态、不碰 core:protocol 调度。
package dev.dsh.mobile.feature.chat

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.protocol.group.LedgerSnapshot
import dev.dsh.mobile.core.protocol.group.LedgerState
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.SectionChevron
import dev.dsh.mobile.design.qqFontBody

// ── 数据派生：全部从现有 state 算，不新增状态源 ───────────────────────────────

/** 某个成员「本轮」的产出：是否已发言 + 本轮 token / 字符数。 */
internal data class MemberRoundStat(val spoke: Boolean, val chars: Int, val tokens: Long)

/**
 * 本轮 = 用户最后一条消息之后（这条消息触发的这一趟群聊）。
 *
 * 调度层的 round 计数留在 core:protocol 里，UI 拿不到也不该拿；
 * 「用户问了以后谁答过」是同一件事的可观测投影，口径写在注释里，不做第二套真值。
 */
internal fun memberRoundStats(messages: List<ChatMessage>): Map<String, MemberRoundStat> {
    if (messages.isEmpty()) return emptyMap()
    val boundary = messages.indexOfLast { it.role == ChatMessage.Role.USER }
    val scope = if (boundary >= 0) messages.subList(boundary + 1, messages.size) else messages
    val out = LinkedHashMap<String, MemberRoundStat>()
    scope.forEach { msg ->
        val authorId = msg.authorId ?: return@forEach
        if (msg.role != ChatMessage.Role.ASSISTANT) return@forEach
        if (authorId == GROUP_NOTICE_AUTHOR) return@forEach
        val prev = out[authorId] ?: MemberRoundStat(false, 0, 0L)
        out[authorId] = MemberRoundStat(
            spoke = true,
            chars = prev.chars + msg.text.length,
            tokens = prev.tokens + msg.inputTokens + msg.outputTokens,
        )
    }
    return out
}

/**
 * 一段连续的群聊系统 notice（同一轮里往往是「主持人：为什么」+「[群聊已散会] 为什么停」）。
 *
 * 判定看 authorId 不看 role：emitGroupNotice（LocalEngineTransport.kt:1841）是全工程唯一
 * 往消息流写系统说明的出口，用的是 authorId = "system"；落库时 role 一律是 ASSISTANT。
 */
internal fun isGroupNotice(msg: ChatMessage): Boolean =
    (msg.authorId == GROUP_NOTICE_AUTHOR || msg.role == ChatMessage.Role.SYSTEM) && msg.text.isNotBlank()

/**
 * emitGroupNotice 固定用 authorId = "system"（见 [GROUP_NOTICE_AUTHOR]）。
 *
 * 注意：这条消息落库时 role 是 ASSISTANT（ChatRepository.persistAssistant），
 * 所以判定只能看 authorId —— 看 role 会一条都匹配不上，notice 继续当气泡渲染。
 */
private const val GROUP_NOTICE_AUTHOR = "system"

/**
 * 一段连续的群聊 notice。
 *
 * 为什么不按「一条消息一行」：同一轮里 emitGroupNotice 发几次，都会拼进同一个 author 的
 * 同一个 part（TurnParts.applyText），最后只落成一条消息 —— 实测就是
 * 「主持人：首轮简报…[群聊已散会]…」挤在一行。所以先按已知前缀把正文切回成多条。
 */
internal data class NoticeRun(
    /** 段首那条消息在 messages 里的下标。 */
    val start: Int,
    /** 段首消息 id，用作列表 key 与折叠记忆键。 */
    val firstId: String,
    /** 这一段里被合并进来的消息条数（其余下标见 [groupNoticeHidden]）。 */
    val messages: Int,
    /** 切开后的一条条结论 / reason，按原顺序。 */
    val segments: List<String>,
) {
    /** 结论行：停会 / 停下 / 升级 / 提问 / 预算，都不命中就取最后一段（主持人决策）。 */
    private val conclusionIndex: Int
        get() {
            val hit = segments.indexOfLast { s -> CONCLUSION_PREFIXES.any { s.startsWith(it) } }
            return if (hit >= 0) hit else segments.lastIndex
        }

    val headline: String get() = segments[conclusionIndex]

    /** 展开后才看的 reason / 过程行，结论行本身不重复出现。 */
    val details: List<String>
        get() = segments.filterIndexed { index, _ -> index != conclusionIndex }
}

private val CONCLUSION_PREFIXES =
    listOf("[群聊已散会]", "[群聊已停下]", "[群聊已升级]", "[主持人提问]", "[预算告急]")

/** 切段锚点：每个 notice 的开头。调度层发出来的文案只有这几种起手式。 */
private const val HOST_MARKER = "主持人："

private val NOTICE_MARKERS = CONCLUSION_PREFIXES + listOf(HOST_MARKER, "自动机连续打回")

/** 把一条 notice 消息的正文切回成多条（换行也算边界；一个锚点都没命中就整段当一条）。 */
internal fun noticeSegments(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val cuts = sortedSetOf<Int>()
    NOTICE_MARKERS.forEach { marker ->
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) break
            // 「主持人：」只有在整段开头才算锚点：后面拼着 reason 时它出现在句中
            // （如「[群聊已散会] <模型名> 主持人：要点已覆盖」），在那里切会把结论截成半句。
            if (marker == HOST_MARKER && at != 0) {
                from = at + marker.length
                continue
            }
            cuts.add(at)
            from = at + marker.length
        }
    }
    if (cuts.isEmpty()) return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val points = (listOf(0) + cuts.filter { it > 0 }).distinct().sorted()
    return points.mapIndexedNotNull { i, start ->
        val end = points.getOrNull(i + 1) ?: text.length
        text.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }
}

/** 起始下标 → 合并块。只有块首那个下标有值，块内其余下标见 [groupNoticeHidden]。 */
internal fun groupNoticeRuns(messages: List<ChatMessage>, isGroup: Boolean): Map<Int, NoticeRun> {
    if (!isGroup || messages.isEmpty()) return emptyMap()
    val runs = LinkedHashMap<Int, NoticeRun>()
    var i = 0
    while (i < messages.size) {
        if (!isGroupNotice(messages[i])) {
            i++
            continue
        }
        val start = i
        val segments = ArrayList<String>()
        var count = 0
        while (i < messages.size && isGroupNotice(messages[i])) {
            segments.addAll(noticeSegments(messages[i].text))
            count++
            i++
        }
        if (segments.isNotEmpty()) runs[start] = NoticeRun(start, messages[start].id, count, segments)
    }
    return runs
}

/** 已经被合并块吃掉、不该再单独渲染的下标。 */
internal fun groupNoticeHidden(runs: Map<Int, NoticeRun>): Set<Int> {
    if (runs.isEmpty()) return emptySet()
    val hidden = HashSet<Int>()
    runs.values.forEach { run ->
        (1 until run.messages).forEach { offset -> hidden.add(run.start + offset) }
    }
    return hidden
}

// ── 组件 1+2：成员卡片（含正在发言高亮） ─────────────────────────────────────

/** 「正在发言」呼吸动效：0.16 → 0.42 的不透明度，750ms 往返；静止态完全不占动画资源。 */
@Composable
private fun speakingPulse(label: String): Float {
    val transition = rememberInfiniteTransition(label = "speaking-" + label)
    val value by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "speaking-alpha-" + label,
    )
    return value
}

/**
 * 成员条上的一枚胶囊：点开成员卡片。
 *
 * 高亮优先级：正在发言（执行池 running，呼吸底色 + accent 字）> 被指定为下一位（描边）>
 * 本轮已发言（名字后一个小点）> 空闲。三档都只用现有颜色 token，没有新视觉语言。
 */
@Composable
internal fun GroupMemberChip(
    part: Participant,
    busy: Boolean,
    spoke: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    val pulse = if (busy) speakingPulse(part.id) else 0f
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .background(
                when {
                    busy -> p.accent.copy(alpha = pulse)
                    pinned -> p.accent.copy(alpha = 0.10f)
                    else -> p.bg
                },
            )
            .then(if (pinned && !busy) Modifier.border(1.dp, p.accent.copy(alpha = 0.6f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 3.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ava(seed = part.id, size = 20.dp, label = part.name, online = busy)
        Spacer(Modifier.width(5.dp))
        Text(
            part.name,
            color = if (busy || pinned) p.accent else p.t2,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (spoke && !busy) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(4.dp).clip(QqShape.pill()).background(p.accent.copy(alpha = 0.5f)))
        }
    }
}

/**
 * 成员卡片：点成员条上的头像/名字弹出来（设计文档 §8 MemberCard）。
 *
 * 只显示拿得到的字段：name / modelId 解析出的模型名 / persona 当角色说明 / 执行池状态 /
 * 本轮是否发言 + 本轮 token（模型没回 usage 时退回字符数）。任务数这类没有来源的一律不编。
 */
@Composable
internal fun GroupMemberCardDialog(
    part: Participant,
    modelName: String,
    busy: Boolean,
    waiting: Boolean,
    spoke: Boolean,
    tokens: Long,
    chars: Int,
    pinned: Boolean,
    canPin: Boolean,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ava(seed = part.id, size = 34.dp, label = part.name, online = busy)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(part.name, color = p.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            busy -> stringResource(DesignR.string.group_speaking)
                            waiting -> stringResource(DesignR.string.common_waiting_confirm)
                            spoke -> stringResource(DesignR.string.group_spoke)
                            else -> stringResource(DesignR.string.agents_idle)
                        },
                        color = if (busy) p.accent else p.t3,
                        fontSize = 11.5.sp,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MemberCardRow(stringResource(DesignR.string.group_field_model), modelName.ifBlank { stringResource(DesignR.string.group_unset) })
                // persona 是成员自带的角色设定；空就整栏不出现，不留「角色：—」这种空壳。
                part.persona.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { role ->
                    MemberCardRow(stringResource(DesignR.string.group_field_role), role.trim())
                }
                MemberCardRow(stringResource(DesignR.string.group_field_round), if (spoke) stringResource(DesignR.string.group_spoke_short) else stringResource(DesignR.string.group_not_yet))
                if (spoke) {
                    MemberCardRow(
                        stringResource(DesignR.string.group_round_usage),
                        if (tokens > 0) stringResource(DesignR.string.group_usage_tokens, compact(tokens), chars) else stringResource(DesignR.string.group_usage_chars, chars),
                    )
                }
            }
        },
        confirmButton = {
            if (canPin) {
                TextButton(onClick = onPin) {
                    Text(if (pinned) stringResource(DesignR.string.group_pin_cancel) else stringResource(DesignR.string.group_pin_set), color = p.accent)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close), color = p.t2) } },
    )
}

@Composable
private fun MemberCardRow(label: String, value: String) {
    val p = LocalQq.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.t3, fontSize = 12.sp, modifier = Modifier.width(62.dp))
        Text(value, color = p.t1, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

// ── 组件 2 的另一半：消息流里的「正在回复…」占位 ──────────────────────────────

/** 执行池里 running 的成员在流末尾占一行，说明「谁在跑」，不让画面看起来卡住。 */
@Composable
internal fun SpeakingPlaceholder(name: String, seed: String) {
    val p = LocalQq.current
    val pulse = speakingPulse("placeholder-" + seed)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ava(seed = seed, size = 20.dp, label = name)
        Spacer(Modifier.width(6.dp))
        Text(name, color = p.t2, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(DesignR.string.group_replying),
            color = p.accent.copy(alpha = 0.5f + pulse),
            fontSize = 11.5.sp,
        )
    }
}

// ── 组件 3：notice 合并块（默认一行结论，点开看 reason） ──────────────────────

/**
 * 一条（或多条合并的）系统 notice：居中、灰底、小字，跟 [SystemNoteRow] 同族但多一层折叠。
 *
 * 默认只显示结论那一行；主持人给的 reason 折在下面。「新增信息默认折叠」是设计文档 §8 的原则。
 */
@Composable
internal fun GroupNoticeBlock(run: NoticeRun) {
    val p = LocalQq.current
    var open by rememberSaveable(run.firstId) { mutableStateOf(false) }
    val details = run.details
    val foldable = details.isNotEmpty()
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(p.card)
                .then(if (foldable) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (foldable) {
                    SectionChevron(expanded = open, tint = p.t3)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    run.headline,
                    color = p.t2,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (foldable) {
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        details.forEach { line ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                line,
                                color = p.t3,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 组件 4：手动指定发言人 ───────────────────────────────────────────────────

/**
 * 「指定下一位发言人」选择器：一次有效。
 *
 * 选完只写 [ChatViewModel.pinSpeaker]，落库/持久化都没有 —— 关页即失效，符合「状态落在
 * 现有会话内、不新增持久化配置」。
 */
@Composable
internal fun SpeakerPickerDialog(
    members: List<Participant>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = { Text(stringResource(DesignR.string.group_pick_next_title), color = p.t1) },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(DesignR.string.group_pick_next_hint),
                    color = p.t3,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                if (members.isEmpty()) {
                    Text(stringResource(DesignR.string.group_no_agents), color = p.t3, fontSize = 12.sp)
                }
                members.forEach { part ->
                    val selected = part.id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(QqShape.chip())
                            .background(if (selected) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onPick(part.id) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ava(seed = part.id, size = 20.dp, label = part.name)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            part.name,
                            color = if (selected) p.accent else p.t1,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Text(stringResource(DesignR.string.group_next_badge), color = p.accent, fontSize = 11.sp)
                    }
                }
                if (current != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(DesignR.string.group_pin_cancel),
                        color = p.red,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onPick(null) }.padding(vertical = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close), color = p.accent) } },
    )
}

// ── 组件 5：房间账本（待办胶囊 + 账本面板，T5.3）──────────────────────────────

/**
 * 成员条右侧的「待办 N」胶囊：未完成任务 + 未解问题的条数。
 *
 * 0 条时整个组件不渲染 —— 没有账本的群聊零视觉变化。
 * 点开是 [LedgerSheet]；面板开关留在这一层，不新增全局状态。
 */
@Composable
internal fun GroupLedgerChip(
    pending: Int,
    snapshot: LedgerSnapshot,
    memberNames: Map<String, String>,
) {
    if (pending <= 0) return
    val p = LocalQq.current
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .clip(QqShape.chip())
            .background(p.warn.copy(alpha = 0.14f))
            .clickable { open = true }
            .padding(horizontal = QqSpace.s(), vertical = QqSpace.xs()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(QqShape.pill()).background(p.warn))
        Spacer(Modifier.width(QqSpace.xs()))
        Text(stringResource(DesignR.string.group_todo_count, pending), color = p.warn, fontSize = qqFontBody(11.5.sp), maxLines = 1)
    }
    if (open) {
        LedgerSheet(snapshot = snapshot, memberNames = memberNames) { open = false }
    }
}

/**
 * 账本面板：未完成（含负责人）/ 未解问题 / 已完成（折叠，点开看最近 5 条）。
 *
 * 状态用行首色点表达：Open 蓝、Reworked 琥珀、Done 绿 —— 全是既有 palette token，
 * 不新造颜色；圆角与间距走 QqShape / QqSpace，字号走 qqFontBody。
 */
@Composable
internal fun LedgerSheet(
    snapshot: LedgerSnapshot,
    memberNames: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    var showDone by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(DesignR.string.group_ledger), color = p.t1, fontSize = qqFontBody(15.sp), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(QqSpace.s()))
                Text(
                    stringResource(DesignR.string.group_ledger_summary, snapshot.open.size, snapshot.questions.size),
                    color = p.t3,
                    fontSize = qqFontBody(11.sp),
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                LedgerGroupTitle(stringResource(DesignR.string.group_ledger_open))
                if (snapshot.open.isEmpty()) {
                    Text(stringResource(DesignR.string.group_ledger_empty), color = p.t3, fontSize = qqFontBody(12.sp))
                }
                snapshot.open.forEach { item ->
                    val owner = item.ownerId?.let { memberNames[it] }.orEmpty()
                    val tail = buildList {
                        if (owner.isNotEmpty()) add(owner)
                        if (item.state == LedgerState.Reworked) add(stringResource(DesignR.string.group_ledger_reworked))
                    }.joinToString(" · ")
                    LedgerLine(dot = ledgerDot(item.state), text = item.title, tail = tail)
                }
                if (snapshot.questions.isNotEmpty()) {
                    QqCardDivider()
                    LedgerGroupTitle(stringResource(DesignR.string.group_ledger_questions))
                    snapshot.questions.forEach { q -> LedgerLine(dot = p.warn, text = q, tail = "") }
                }
                if (snapshot.done.isNotEmpty()) {
                    QqCardDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showDone = !showDone }
                            .padding(vertical = QqSpace.s()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(DesignR.string.group_ledger_done, snapshot.done.size),
                            color = p.t2,
                            fontSize = qqFontBody(12.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (showDone) stringResource(DesignR.string.common_collapse) else stringResource(DesignR.string.group_ledger_show_recent),
                            color = p.accent,
                            fontSize = qqFontBody(11.sp),
                        )
                    }
                    if (showDone) {
                        snapshot.done.takeLast(5).forEach { item ->
                            LedgerLine(dot = p.green, text = item.title, tail = "")
                        }
                    }
                }
                if (snapshot.reworkCount > 0) {
                    Spacer(Modifier.height(QqSpace.s()))
                    Text(
                        stringResource(DesignR.string.group_ledger_rework_total, snapshot.reworkCount),
                        color = p.t3,
                        fontSize = qqFontBody(10.5.sp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close), color = p.accent) } },
    )
}

/** 段落小标题：色不是信息，字号与字重才是层级，所以用 t2 而不是状态色。 */
@Composable
private fun LedgerGroupTitle(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t2,
        fontSize = qqFontBody(11.5.sp),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = QqSpace.s(), bottom = QqSpace.xs()),
    )
}

/** 一条账本：状态色点 + 正文 + 尾部说明（负责人 / 已打回）。 */
@Composable
private fun LedgerLine(dot: Color, text: String, tail: String) {
    val p = LocalQq.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(5.dp).clip(QqShape.pill()).background(dot))
        Spacer(Modifier.width(QqSpace.s()))
        Column(Modifier.weight(1f)) {
            Text(text, color = p.t1, fontSize = qqFontBody(12.sp), lineHeight = qqFontBody(17.sp))
            if (tail.isNotEmpty()) {
                Text(tail, color = p.t3, fontSize = qqFontBody(10.5.sp))
            }
        }
    }
}

/** 状态色点：Open 蓝 / Reworked 琥珀 / Done 绿，全走既有 palette。 */
@Composable
private fun ledgerDot(state: LedgerState): Color {
    val p = LocalQq.current
    return when (state) {
        LedgerState.Reworked -> p.amber
        LedgerState.Done -> p.green
        LedgerState.Open, LedgerState.Dropped -> p.accent
    }
}
