package dev.dsh.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.GroupLedger
import dev.dsh.mobile.core.model.LedgerEntry
import dev.dsh.mobile.core.model.LedgerStatus
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.qqFontBody

/**
 * 项目规划书面板（T9.7a）：群聊里「人写的项目清单」，按五态分组。
 *
 * 与 [LedgerSheet]（房间账本，引擎写的运行时状态）刻意分开：这里的状态是人推进的，
 * 点开一条能改状态、追加备注、删除。所有颜色走既有 palette（t3/accent/amber/green/red），
 * 圆角与间距走 QqShape / QqSpace，不新造 token。
 *
 * 状态推进用行内按钮而不是循环点击：循环点击在「完成 → 待办」这种回退上没有语义，
 * 也看不出当前是哪一步。
 */
@Composable
internal fun GroupLedgerBoard(
    ledger: GroupLedger,
    /** 负责人 id → 显示名（角色 id 落不到这里就原样显示 id）。 */
    ownerNames: Map<String, String>,
    onDismiss: () -> Unit,
    onAdd: (title: String, owner: String) -> Unit,
    onStatus: (id: String, status: LedgerStatus) -> Unit,
    onNote: (id: String, note: String) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    val p = LocalQq.current
    var draft by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteDraft by rememberSaveable { mutableStateOf("") }

    val groups = ledger.byStatus()
    val total = ledger.entries.size
    val done = groups.getValue(LedgerStatus.Done).size

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("项目规划书", color = p.t1, fontSize = qqFontBody(15.sp), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(QqSpace.s()))
                Text(
                    if (total == 0) "空" else "完成 " + done + " / " + total,
                    color = p.t3,
                    fontSize = qqFontBody(11.sp),
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                // 新增：标题必填，负责人可选（角色 id 或成员名，纯文本，不做选择器）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("新增条目", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = draft.isNotBlank(),
                        onClick = { onAdd(draft, ""); draft = "" },
                    ) { Text("添加", color = if (draft.isNotBlank()) p.accent else p.t3, fontSize = 12.sp) }
                }

                if (total == 0) {
                    Text(
                        "还没有条目。规划者把这一轮要做的事写进来，执行者做完、检测者确认后改状态。",
                        color = p.t3,
                        fontSize = qqFontBody(11.5.sp),
                        modifier = Modifier.padding(top = QqSpace.s()),
                    )
                }

                LedgerStatus.entries.forEach { status ->
                    val items = groups.getValue(status)
                    if (items.isEmpty()) return@forEach
                    if (status != LedgerStatus.Todo) QqCardDivider()
                    BoardGroupTitle(status.label + " · " + items.size)
                    items.forEach { entry ->
                        BoardEntryLine(
                            entry = entry,
                            owner = ownerNames[entry.owner] ?: entry.owner,
                            expanded = expandedId == entry.id,
                            noteDraft = noteDraft,
                            onNoteDraft = { noteDraft = it },
                            onToggle = {
                                expandedId = if (expandedId == entry.id) null else entry.id
                                noteDraft = ""
                            },
                            onStatus = { onStatus(entry.id, it) },
                            onNote = { onNote(entry.id, noteDraft); noteDraft = ""; expandedId = null },
                            onRemove = { onRemove(entry.id); expandedId = null },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = p.accent) } },
    )
}

@Composable
private fun BoardGroupTitle(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t2,
        fontSize = qqFontBody(11.5.sp),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = QqSpace.s(), bottom = QqSpace.xs()),
    )
}

/** 一条规划书条目：色点 + 标题 + 负责人；点开是状态按钮 / 备注 / 删除。 */
@Composable
private fun BoardEntryLine(
    entry: LedgerEntry,
    owner: String,
    expanded: Boolean,
    noteDraft: String,
    onNoteDraft: (String) -> Unit,
    onToggle: () -> Unit,
    onStatus: (LedgerStatus) -> Unit,
    onNote: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 5.dp).size(5.dp).clip(QqShape.pill()).background(statusColor(entry.status)))
            Spacer(Modifier.width(QqSpace.s()))
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = p.t1, fontSize = qqFontBody(12.sp), lineHeight = qqFontBody(17.sp))
                val tail = buildList {
                    if (owner.isNotBlank()) add(owner)
                    if (entry.note.isNotBlank()) add(entry.note.lineSequence().last())
                }.joinToString(" · ")
                if (tail.isNotEmpty()) Text(tail, color = p.t3, fontSize = qqFontBody(10.5.sp), maxLines = 1)
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = QqSpace.m(), bottom = QqSpace.s())) {
                // 五态等分一行：靠 weight 让最长的「阻塞」也放得下，不换行。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LedgerStatus.entries.forEach { s ->
                        TextButton(
                            enabled = s != entry.status,
                            onClick = { onStatus(s) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                s.label,
                                color = if (s == entry.status) p.t3 else p.accent,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = onNoteDraft,
                        label = { Text("追加备注", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = noteDraft.isNotBlank(), onClick = onNote) {
                        Text("写入", color = if (noteDraft.isNotBlank()) p.accent else p.t3, fontSize = 11.sp)
                    }
                    TextButton(onClick = onRemove) { Text("删除", color = p.red, fontSize = 11.sp) }
                }
            }
        }
    }
}

/** 五态色点：待办灰、进行蓝、需改琥珀、完成绿、阻塞红 —— 全是既有 palette。 */
@Composable
private fun statusColor(status: LedgerStatus): Color {
    val p = LocalQq.current
    return when (status) {
        LedgerStatus.Todo -> p.t3
        LedgerStatus.Doing -> p.accent
        LedgerStatus.Review -> p.amber
        LedgerStatus.Done -> p.green
        LedgerStatus.Blocked -> p.red
    }
}
