// 会话顶栏区：标题栏/运行态/用量环、右上菜单入口、错误条、成员条、执行状态条与 todo 投影面板。
package dev.dsh.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotStrip
import dev.dsh.mobile.design.UiEditSlotPlusRow
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.ContextRing
import dev.dsh.mobile.design.SessionStatusDot
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.protocol.ProjectedTodo
import dev.dsh.mobile.core.protocol.SessionProjection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import dev.dsh.mobile.pluginapi.SlotContribution

/**
 * todo / goal 只读面板：抬头一行永远可见（进度一眼可读），点开铺开清单。
 *
 * 只用 design 现有的颜色 token 与圆角（p.card / p.accent / p.t1..t3），没有新造视觉语言；
 * 三态用行首记号 + 已完成划线区分，不依赖颜色（弱视 / 深色下也读得出来）。
 */
@Composable
internal fun ProjectionPanel(
    projection: SessionProjection,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val p = LocalQq.current
    val goal = projection.goal
    Column(Modifier.fillMaxWidth().background(p.card)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                projectionHead(goal, projection.todos),
                color = p.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            goal?.objective?.takeIf { it.isNotBlank() }?.let { objective ->
                Spacer(Modifier.width(8.dp))
                Text(
                    objective,
                    color = p.t2,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (goal?.objective.isNullOrBlank()) Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起待办" else "展开待办",
                tint = p.t3,
                modifier = Modifier.size(16.dp),
            )
        }
        if (!expanded) return@Column
        Column(
            Modifier.padding(start = 13.dp, end = 13.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            goal?.blockedReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text("受阻：$reason", color = p.red, fontSize = 11.sp)
            }
            todoGroups(projection.todos).forEach { group ->
                Text(
                    group.title + " " + group.todos.size,
                    color = p.t3,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                group.todos.forEach { todo ->
                    val done = todo.status == ProjectedTodo.STATUS_DONE
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            todoBullet(todo.status),
                            color = if (done) p.t3 else p.accent,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            todo.content,
                            color = if (done) p.t3 else p.t1,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatHeader(
    state: ChatUiState,
    projection: SessionProjection,
    pinnedSpeaker: String?,
    moderatorMode: Boolean,
    presetSelection: PresetSelection,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSpeakerPicker: () -> Unit,
    onOpenPreset: () -> Unit,
    onOpenAgentSheet: () -> Unit,
    /** 规划书未完成条目数（T9.7a）：0 时入口只写「规划」，不显示 0。 */
    ledgerOpen: Int,
    onOpenLedger: () -> Unit,
    /**
     * 插件注册到顶栏的动作（SlotId.ChatOverflow）。
     *
     * 以前这个槽位只被当成「会话设置」页的列表，聊天顶栏里一个都不显示 ——
     * 插件作者按文档挂了 chat.topbar.menu，装上却毫无反应就是这个原因。
     */
    overflowSlots: List<SlotContribution> = emptyList(),
    onSlot: (SlotContribution) -> Unit = {},
) {
    val p = LocalQq.current
    QqTopBar(
        leadingExpanded = true,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
                // 最左侧仍是运行态圆点（思考/等待/结束/出错），呼吸动效保留。
                SessionStatusDot(
                    when {
                        state.error != null -> SessionStatus.Failed
                        state.permission != null -> SessionStatus.WaitingPermission
                        state.streaming -> SessionStatus.Running
                        else -> SessionStatus.Idle
                    },
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f).clickable { onOpenUsage() }, horizontalAlignment = Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.title.ifBlank { "新会话" },
                            color = p.t1,
                            fontSize = qqFontBody(17.sp),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 压缩可见性：没压过（count=0）就整块不渲染，顶栏保持原样。
                        compactionBadge(projection.compaction)?.let { badge ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                badge,
                                color = p.accent,
                                fontSize = qqFontBody(10.sp),
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(p.accent.copy(alpha = 0.12f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (state.settings.showTokenUsage) Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 上下文占用环：弧度即占比，挂在「上下文」文字左侧的小空里。
                        // 占比优先读投影（装配字符预算口径），投影没数据退回 usage 口径。
                        ContextRing(usagePercent(projection.usage) ?: state.contextPercent.toInt())
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (state.streaming) "正在输出 · ${usageLine(state, projection.usage)}"
                            else usageLine(state, projection.usage),
                            color = p.t2,
                            fontSize = qqFontBody(11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderSlotActions(overflowSlots, onSlot)
                // 规划书入口（T9.7a）：群聊才有；条数是未完成数，全清时不显示 0。
                if (state.isGroup) {
                    Text(
                        if (ledgerOpen > 0) "规划 " + ledgerOpen else "规划",
                        color = if (ledgerOpen > 0) p.accent else p.t2,
                        fontSize = qqFontBody(12.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (ledgerOpen > 0) p.accent.copy(alpha = 0.12f) else Color.Transparent,
                            )
                            .clickable { onOpenLedger() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                // 主持人模式（brief-then-moderated）才给「指定」：把下一位发言人钉成某个成员，
                // 一次有效；没指定时只是一枚安静的文字入口，不占位也不改观感。
                if (moderatorMode) {
                    val pinnedName = pinnedSpeaker?.let { id ->
                        state.participants.firstOrNull { it.id == id }?.name
                    }
                    Text(
                        pinnedName?.let { "下一位：" + it } ?: "指定",
                        color = p.accent,
                        fontSize = qqFontBody(12.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (pinnedName != null) p.accent.copy(alpha = 0.12f) else Color.Transparent,
                            )
                            .clickable { onOpenSpeakerPicker() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                // 智能体预设入口：未发过消息时可点（选中即生效，没有额外开关）；发过就只读展示。
                Text(
                    if (presetSelection.locked) presetNameOf(state.presetId)
                    else "预设：" + presetNameOf(state.presetId),
                    color = if (presetSelection.locked) p.t3 else p.accent,
                    fontSize = qqFontBody(12.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !presetSelection.locked) { onOpenPreset() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                IconBtn(Icons.Filled.Menu, "会话菜单") { onOpenSettings() }
            }
        },
    ) {}

    // UI 修改模式：会话顶栏旁的可编辑「+」。
    // 输入框区的「+」贴在输入区那条槽位上（见 SlotStrip），这里不再重复一个。
    UiEditSlotPlusRow(slot = SlotEditState.Slot.ChatTopBar, onClick = { onOpenAgentSheet() })
}

@Composable
internal fun ChatErrorBar(error: String?, onDismiss: () -> Unit) {
    val p = LocalQq.current
    // 发送失败 / 排队满 / 权限确认失败：标题红点只给一个状态，这里把可读原因铺出来。
    // state.error 是单条，成功或被 dismissError 清掉后即消失，不重复、不陈旧。
    error?.let { err ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.red.copy(alpha = 0.1f))
                .padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                err,
                color = p.red,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                color = p.red,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun ChatMemberStrip(
    state: ChatUiState,
    roundStats: Map<String, MemberRoundStat>,
    pinnedSpeaker: String?,
    onOpenMember: (Participant) -> Unit,
) {
    val p = LocalQq.current
    if (state.isGroup) {
        // 群成员条：一行可横滑的小胶囊（头像 + 名字），比原来竖排头像塔省 40dp 高度。
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.card)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.participants.isEmpty()) {
                Text("还没有 Agent，点右上角拉人", color = p.t3, fontSize = 11.5.sp)
            }
            state.participants.forEach { part ->
                val busy = state.settings.showAgentStatus &&
                    state.busyAgents.any { it.agentId == part.id }
                GroupMemberChip(
                    part = part,
                    busy = busy,
                    spoke = roundStats[part.id]?.spoke == true,
                    pinned = pinnedSpeaker == part.id,
                    onClick = { onOpenMember(part) },
                )
            }
            // 账本待办胶囊（T5.3）：跟在成员后面，0 条时组件自己 return，不占位。
            GroupLedgerChip(
                pending = state.ledger.pending,
                snapshot = state.ledger,
                memberNames = state.participants.associate { it.id to it.name },
            )
        }
    }
}

@Composable
internal fun ChatBusyStrip(state: ChatUiState) {
    val p = LocalQq.current
    // 执行状态条：谁在跑、谁在等确认。拿不到执行池（busyAgents 为空）就整条不渲染。
    if (state.busyAgents.isNotEmpty() && state.settings.showAgentStatus) {
        Row(
            Modifier.fillMaxWidth().background(p.card).padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.busyAgents.joinToString(" · ") { agent ->
                    val what = if (agent.state == AgentRunState.WaitingPermission) "等待确认" else "执行中"
                    agent.agentName.ifBlank { "Agent" } + " " + what
                },
                color = p.accent,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 顶栏最多挂几个插件动作：顶栏横向空间只够两个，再多就把标题挤没了。 */
internal const val HEADER_SLOT_CAP = 2

/**
 * 顶栏插件动作（SlotId.ChatOverflow）。
 *
 * 约束：最多 [HEADER_SLOT_CAP] 个、单个高 ≤32dp、宽 ≤72dp、单行省略，按 pluginId + id 稳定排序。
 * 单个按钮渲染失败只跳过它自己并弹一条 Toast —— 顶栏是主界面的必经路径，不能因为插件崩掉。
 */
@Composable
private fun HeaderSlotActions(slots: List<SlotContribution>, onSlot: (SlotContribution) -> Unit) {
    if (slots.isEmpty()) return
    val p = LocalQq.current
    val context = LocalContext.current
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(failed) {
        val msg = failed ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        failed = null
    }
    val shown = remember(slots) {
        slots.sortedWith(compareBy({ it.pluginId }, { it.id })).take(HEADER_SLOT_CAP)
    }
    shown.forEach { slot ->
        val ok = runCatching {
            Text(
                slot.label,
                color = p.accent,
                fontSize = qqFontBody(12.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 3.dp)
                    .heightIn(max = 32.dp)
                    .widthIn(max = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.accent.copy(alpha = 0.12f))
                    .clickable { onSlot(slot) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }.isSuccess
        if (!ok) failed = "顶栏插件按钮「" + slot.label + "」没能显示"
    }
}

@Composable
internal fun ChatPlanStrip(state: ChatUiState) {
    val p = LocalQq.current
    if (state.plan.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().background(p.card).padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "计划 ${state.plan.count { it.startsWith("✓") }}/${state.plan.size}",
                color = p.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(p.accent.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.plan.firstOrNull().orEmpty(),
                color = p.t2,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
