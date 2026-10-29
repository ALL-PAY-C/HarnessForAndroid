package dev.dsh.mobile.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.common.TimeFmt
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.ConvRow
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SectionChevron
import dev.dsh.mobile.design.SwipeAction
import dev.dsh.mobile.design.SwipeableRow
import dev.dsh.mobile.design.TokenPill
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    sessionRowExtras: List<@Composable (Session) -> Unit> = emptyList(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val p = LocalQq.current
    var creating by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var grouping by remember { mutableStateOf<Session?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pendingDeleteGroup by remember { mutableStateOf<String?>(null) }
    var deletingFolder by remember { mutableStateOf(false) }
    // 新建文件夹：值是要建在哪一层（空串表示顶层）。
    var creatingFolder by remember { mutableStateOf<String?>(null) }
    // 长按文件夹头弹出的操作面板。
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    // 批量移动：把多选中的会话一起塞进某个分组。
    var movingSelected by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    val visibleIds = state.groups.flatMap { it.second }.map { it.id }

    LaunchedEffect(state.error) {
        state.error?.let { viewModel.dismissError() }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        QqTopBar(
            leading = { IconBtn(Icons.Filled.Menu, "侧边栏", onOpenDrawer) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.CreateNewFolder, "新建文件夹") { creatingFolder = "" }
                    Spacer(Modifier.width(2.dp))
                    IconBtn(Icons.Filled.Add, "新建会话") { creating = true }
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.selectionMode) {
                    Text(
                        "已选 ${state.selected.size} 项",
                        color = p.accent,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "全选",
                        color = p.accent,
                        fontSize = 12.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.selectAll(visibleIds) }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    Text(
                        "退出",
                        color = p.t3,
                        fontSize = 12.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.clearSelection() }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                } else if (state.settings.showTokenUsage) {
                    val usage = state.groups.flatMap { it.second }
                        .fold(TokenUsage.EMPTY) { acc, s -> acc + s.usage }
                    TokenPill(usage)
                }
            }
        }

        if (searching) {
            Row(
                Modifier.fillMaxWidth().background(p.card).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                    BasicTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = p.t1),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                IconBtn(Icons.Filled.Close, "关闭搜索") { searching = false; viewModel.setQuery("") }
            }
        }

        Box(Modifier.weight(1f)) {
            // loaded 之前一律不判空：首帧还没到就说「还没有会话」是假空态。
            if (state.loaded && state.groups.all { it.second.isEmpty() } && state.folders.isEmpty()) {
                EmptyState(
                    title = "还没有会话",
                    subtitle = when (val rt = state.runtime) {
                        is RuntimeState.Ready -> "点右上角 + 新建，或在设置里同步历史会话"
                        is RuntimeState.Failed ->
                            if (rt.reason.contains("模型")) "先在 Agent 页添加一个模型，再点右上角 + 开始对话"
                            else "连接失败：" + rt.reason
                        else -> "先在 Agent 页添加模型（填 API Key），再回到这里点 + 新建对话"
                    },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val depthOf = state.folders.associate {
                        folderGroupKey(it.path) to it.depth
                    }
                    val countOf = state.folders.associate {
                        folderGroupKey(it.path) to (it.sessions.size + it.children)
                    }
                    // 父文件夹收起时整棵子树一起收：靠路径前缀判断，不需要递归渲染。
                    fun hiddenByAncestor(path: String): Boolean {
                        var acc = ""
                        val parts = folderPathFromGroup(path).split('/')
                        for (i in 0 until parts.size - 1) {
                            acc = if (acc.isEmpty()) parts[i] else acc + "/" + parts[i]
                            val key = folderGroupKey(acc)
                            if (key in state.collapsed) return true
                        }
                        return false
                    }

                    state.groups.forEach { (group, sessions) ->
                        if (hiddenByAncestor(group)) return@forEach
                        val collapsible = state.isCollapsible(group)
                        val isCollapsed = collapsible && group in state.collapsed
                        val depth = depthOf[group] ?: 0
                        val label = folderPathFromGroup(group).substringAfterLast('/')
                        val count = countOf[group] ?: sessions.size
                        val open = !isCollapsed && sessions.isNotEmpty()

                        // 文件夹形态：一个外框里放标题条 + 缩进的子行（每行左侧一条树状引导线）。
                        item(key = "g-" + group) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = (depth * 14).dp)
                                    .shadow(1.dp, RoundedCornerShape(14.dp), clip = false)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(p.card)
                                    .border(1.dp, p.line, RoundedCornerShape(14.dp)),
                            ) {
                                // 标题条：整行可点（chevron 命中区只有 14dp，手指戳不中）。
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .background(p.hover)
                                        .clickable(enabled = collapsible) { viewModel.toggleGroup(group) }
                                        // 长按文件夹头 = 删掉这个文件夹（组内会话归档，会话本身还在）。
                                        .pointerInput(group) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(
                                                    requireUnconsumed = false,
                                                    pass = PointerEventPass.Initial,
                                                )
                                                val released = withTimeoutOrNull(
                                                    viewConfiguration.longPressTimeoutMillis,
                                                ) {
                                                    while (true) {
                                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (c.changedToUp()) break
                                                        if ((c.position - down.position).getDistance() >
                                                            viewConfiguration.touchSlop
                                                        ) break
                                                    }
                                                    true
                                                }
                                                if (released == null && collapsible) folderMenu = group
                                            }
                                        }
                                        .padding(start = 10.dp, end = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (collapsible) {
                                        SectionChevron(expanded = !isCollapsed)
                                    } else {
                                        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                                            Text("·", color = p.t3, fontSize = 12.sp)
                                        }
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
                                    Spacer(Modifier.width(7.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp)).background(p.card)
                                            .padding(horizontal = 7.dp, vertical = 1.dp),
                                    ) {
                                        Text("" + count, color = p.t3, fontSize = 11.sp)
                                    }
                                }

                                if (open) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        sessions.forEach { session ->
                                            key(session.id) {
                                                val swallow = remember { ClickSwallow() }
                                                val row: @Composable () -> Unit = {
                                                    // 长按进多选：ConvRow 自己的 clickable 会吃掉 Main pass 的按下事件，
                                                    // 所以在 Initial pass 自己等 longPressTimeout，超时还没抬手就算长按。
                                                    Box(
                                                        Modifier.pointerInput(session.id) {
                                                            awaitEachGesture {
                                                                // R1：每个新手势开始先作废上一轮未兑现的 arm。
                                                                swallow.disarm()
                                                                val down = awaitFirstDown(
                                                                    requireUnconsumed = false,
                                                                    pass = PointerEventPass.Initial,
                                                                )
                                                                var dragged = false
                                                                val releasedInTime = withTimeoutOrNull(
                                                                    viewConfiguration.longPressTimeoutMillis,
                                                                ) {
                                                                    while (true) {
                                                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                                                        if (c.changedToUp()) break
                                                                        if ((c.position - down.position).getDistance() >
                                                                            viewConfiguration.touchSlop
                                                                        ) {
                                                                            dragged = true
                                                                            break
                                                                        }
                                                                    }
                                                                    true
                                                                }
                                                                // R2：只有真的切进了多选态才需要吞掉抬手那次 click。
                                                                if (releasedInTime == null && !dragged && !state.selectionMode) {
                                                                    swallow.arm()
                                                                    viewModel.toggleSelect(session.id)
                                                                }
                                                            }
                                                        },
                                                    ) {
                                                        ConvRow(
                                                            session = session,
                                                            onClick = {
                                                                if (!swallow.consume()) {
                                                                    if (state.selectionMode) viewModel.toggleSelect(session.id)
                                                                    else onOpenSession(session.id)
                                                                }
                                                            },
                                                            selected = session.id in state.selected,
                                                            selectionMode = state.selectionMode,
                                                            timeText = TimeFmt.relative(session.updatedAt, System.currentTimeMillis()),
                                                            showDivider = false,
                                                            extras = sessionRowExtras,
                                                        )
                                                    }
                                                }
                                                // 子行：左侧树状引导线 + 行本身一个描边小卡片。
                                                val boxed: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                                                    Row(
                                                        Modifier
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
                                                        Box(
                                                            Modifier
                                                                .weight(1f)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(p.card)
                                                                .border(1.dp, p.line, RoundedCornerShape(10.dp)),
                                                        ) { inner() }
                                                    }
                                                }
                                                if (state.selectionMode) {
                                                    boxed { row() }
                                                } else {
                                                    // Archived rows get destructive actions only:
                                                    // 删除 / 重命名 / 撤回 (unarchive back to its own group).
                                                    val actions = if (session.archived) {
                                                        listOf(
                                                            SwipeAction("删除", p.red) { viewModel.delete(session.id) },
                                                            SwipeAction("重命名", p.accent) { renaming = session },
                                                            SwipeAction("撤回", p.green) { viewModel.archive(session.id, false) },
                                                        )
                                                    } else {
                                                        listOf(
                                                            SwipeAction("归档", p.amber) { viewModel.archive(session.id, true) },
                                                            SwipeAction("重命名", p.accent) { renaming = session },
                                                            SwipeAction("分组", p.green) { grouping = session },
                                                        )
                                                    }
                                                    SwipeableRow(actions = actions, content = { boxed { row() } })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.selectionMode) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Row(Modifier.fillMaxWidth().background(p.card).padding(horizontal = 6.dp, vertical = 6.dp)) {
                MultiAction("置顶") {
                    val n = state.selected.size
                    viewModel.pinSelected(true)
                    toast("已置顶 $n 项")
                }
                MultiAction("归档") {
                    val n = state.selected.size
                    viewModel.archiveSelected()
                    toast("已归档 $n 项")
                }
                MultiAction("移动") { movingSelected = true }
                MultiAction("删除", danger = true) { confirmingDelete = true }
                MultiAction("取消") { viewModel.clearSelection() }
            }
        }
        SnackbarHost(snackbar)
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除会话") },
            text = { Text("删除已选的 ${state.selected.size} 项会话？消息记录会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val n = state.selected.size
                    confirmingDelete = false
                    viewModel.deleteSelected()
                    toast("已删除 $n 项")
                }) { Text("删除", color = p.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }

    renaming?.let { session ->
        InputDialog(
            title = "重命名会话",
            initial = session.title,
            confirmLabel = "保存",
            onDismiss = { renaming = null },
        ) { value ->
            if (value.isNotBlank()) viewModel.rename(session.id, value.trim())
            renaming = null
        }
    }

    if (creating) {
        NewChatSheet(
            models = models,
            known = state.knownGroups,
            onDismiss = { creating = false },
            onCreate = { name, isGroup, agentId, participants, group, workspace ->
                creating = false
                viewModel.createChat(name, agentId, participants, group, workspace, onOpenSession)
            },
        )
    }

    grouping?.let { session ->
        GroupDialog(
            current = session.group,
            known = state.knownGroups,
            onDismiss = { grouping = null },
        ) { group ->
            viewModel.moveToGroup(session.id, group)
            grouping = null
        }
    }

    if (movingSelected) {
        GroupDialog(
            current = "",
            known = state.knownGroups,
            title = "移动 ${state.selected.size} 项到分组",
            onDismiss = { movingSelected = false },
        ) { group ->
            val n = state.selected.size
            viewModel.moveSelectedTo(group)
            movingSelected = false
            toast("已移动 $n 项到「$group」")
        }
    }

    pendingDeleteGroup?.let { name ->
        val path = folderPathFromGroup(name)
        val count = folderSessionCount(state.sessionCountByFolder, path)
        AlertDialog(
            onDismissRequest = { if (!deletingFolder) pendingDeleteGroup = null },
            title = { Text("删除文件夹「" + folderPathFromGroup(name).substringAfterLast('/') + "」") },
            text = { Text("文件夹及子文件夹内 ${count} 个会话会移入「${SessionsUiState.ARCHIVE_GROUP}」，会话本身不删除。") },
            confirmButton = {
                TextButton(enabled = !deletingFolder, onClick = {
                    deletingFolder = true
                    viewModel.deleteFolder(path) { result ->
                        deletingFolder = false
                        result.fold(
                            onSuccess = {
                                pendingDeleteGroup = null
                                toast("已删除文件夹")
                            },
                            onFailure = { toast("删除失败，部分会话可能已归档，请重试") },
                        )
                    }
                }) { Text(if (deletingFolder) "删除中…" else "删除", color = p.red) }
            },
            dismissButton = {
                TextButton(enabled = !deletingFolder, onClick = { pendingDeleteGroup = null }) { Text("取消") }
            },
        )
    }

    folderMenu?.let { path ->
        val bare = folderPathFromGroup(path)
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text(bare.substringAfterLast('/')) },
            text = {
                Column {
                    FolderAction("新建子文件夹") { folderMenu = null; creatingFolder = bare }
                    FolderAction("重命名") { folderMenu = null; renamingFolder = bare }
                    FolderAction("删除（组内会话进归档）", danger = true) {
                        folderMenu = null
                        pendingDeleteGroup = path
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { folderMenu = null }) { Text("取消") }
            },
        )
    }

    renamingFolder?.let { path ->
        InputDialog(
            title = "重命名文件夹",
            initial = path.substringAfterLast('/'),
            confirmLabel = "保存",
            onDismiss = { renamingFolder = null },
        ) { value ->
            if (value.isNotBlank()) viewModel.renameFolder(path, value.trim())
            renamingFolder = null
        }
    }

    creatingFolder?.let { parent ->
        InputDialog(
            title = if (parent.isBlank()) "新建文件夹" else "在「" + parent.substringAfterLast('/') + "」里新建",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { creatingFolder = null },
        ) { value ->
            if (value.isNotBlank()) {
                viewModel.createFolder(value, parent)
                toast("已创建文件夹「" + value.trim() + "」")
            }
            creatingFolder = null
        }
    }
}

/** 文件夹长按面板里的一行。 */
@Composable
private fun FolderAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalQq.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Text(label, color = if (danger) p.red else p.t1, fontSize = 14.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MultiAction(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (danger) p.red else p.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Small QQ-styled input dialog reused for rename / new group. */
@Composable
internal fun InputDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "确定",
    hint: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text(title, color = p.t1, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(hint, color = p.t3, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                DialogButton("取消", p.t2, onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogButton(confirmLabel, p.accent) { onConfirm(value) }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = color, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Pick an existing group, or type a new one. */
@Composable
private fun GroupDialog(
    current: String,
    known: List<String>,
    title: String = "移动到分组",
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalQq.current
    val options = (listOf(Session.DEFAULT_GROUP) + known).distinct()
    var newName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text(title, color = p.t1, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                options.forEach { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(name) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = p.t1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (name == current) Text("当前", color = p.accent, fontSize = 11.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                )
                if (newName.isBlank()) {
                    Text(
                        "新建分组…",
                        color = p.t3,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                DialogButton("取消", p.t2, onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogButton("确定", p.accent) { if (newName.isNotBlank()) onPick(newName.trim()) }
            }
        }
    }
}

/** 新建聊天：名字 + 普通会话/群聊 + 选 agent（群聊可重复拉同一个）。 */
@Composable
private fun NewChatSheet(
    models: List<ModelProfile>,
    known: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, String?, List<Participant>, String, String) -> Unit,
) {
    val p = LocalQq.current
    var name by remember { mutableStateOf("") }
    var isGroup by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf("") }
    var singleAgent by remember { mutableStateOf<String?>(null) }
    val memberIds = remember { mutableStateListOf<String>() }

    /** 同名 Agent 被拉多次时按出现次序编号；返回 (下标 -> 显示名)。 */
    fun numberedLabels(): List<String> {
        val total = memberIds.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return memberIds.map { id ->
            val profile = models.firstOrNull { it.id == id }
            val base = profile?.name?.ifBlank { profile?.model ?: "" } ?: "Agent"
            if ((total[id] ?: 0) <= 1) base else {
                val n = (seen[id] ?: 0) + 1
                seen[id] = n
                "$base #$n"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text("新建聊天", color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                )
                if (name.isBlank()) {
                    Text(
                        if (isGroup) "群聊名称" else "会话名称（可留空）",
                        color = p.t3,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg)) {
                BasicTextField(
                    value = workspace,
                    onValueChange = { workspace = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (workspace.isBlank()) {
                    Text(
                        "工作区路径（留空用默认目录，产物落在这里）",
                        color = p.t3,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(3.dp),
            ) {
                listOf(false to "普通会话", true to "群聊").forEach { (value, label) ->
                    val active = isGroup == value
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) p.accent else Color.Transparent)
                            .clickable { isGroup = value }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) Color.White else p.t2,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (isGroup) "选择 Agent（可重复拉同一个）" else "选择一个 Agent",
                color = p.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))

            if (models.isEmpty()) {
                Text("还没有接入 Agent，先到 Agent 页添加 API Key", color = p.t3, fontSize = 12.5.sp)
            } else {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    models.forEach { profile ->
                        val count = memberIds.count { it == profile.id }
                        val selected = if (isGroup) count > 0 else singleAgent == profile.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (isGroup) memberIds.add(profile.id)
                                    else singleAgent = profile.id
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ava(seed = profile.id, size = 32.dp, label = profile.name.ifBlank { profile.model })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    profile.name.ifBlank { profile.model },
                                    color = p.t1,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(profile.model, color = p.t3, fontSize = 11.sp)
                            }
                            if (isGroup && count > 0) {
                                Text("×$count", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { memberIds.remove(profile.id) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text("−", color = p.red, fontSize = 14.sp)
                                }
                            } else if (!isGroup && selected) {
                                Text("✓", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (isGroup && memberIds.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已选 ${memberIds.size} 位：" + numberedLabels().joinToString("、"),
                    color = p.t3,
                    fontSize = 11.5.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("选择分组", color = p.t2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("最近") .plus(known.filter { it != "最近" }).take(3).forEach { g ->
                    val active = group == g || (group.isBlank() && g == "最近")
                    Text(
                        g,
                        color = if (active) Color.White else p.t2,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) p.accent else p.bg)
                            .clickable { group = if (g == "最近") "" else g }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg)) {
                BasicTextField(
                    value = group,
                    onValueChange = { group = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (group.isBlank()) {
                    Text("新分组名（留空=最近）", color = p.t3, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                DialogButton("取消", p.t2, onDismiss)
                Spacer(Modifier.width(8.dp))
                val ready = if (isGroup) memberIds.isNotEmpty() else singleAgent != null
                DialogButton(if (ready) "创建" else "请选择 Agent", if (ready) p.accent else p.t3) {
                    if (!ready) return@DialogButton
                    if (isGroup) {
                        val counters = mutableMapOf<String, Int>()
                        val participants = memberIds.mapIndexed { index, id ->
                            val profile = models.firstOrNull { it.id == id }
                            val n = (counters[id] ?: 0) + 1
                            counters[id] = n
                            val base = profile?.name?.ifBlank { profile.model } ?: "Agent"
                            Participant(
                                id = java.util.UUID.randomUUID().toString(),
                                modelId = id,
                                name = if (memberIds.count { it == id } > 1) "$base #$n" else base,
                                slot = index,
                            )
                        }
                        onCreate(name.trim(), true, null, participants, group.trim(), workspace.trim())
                    } else {
                        onCreate(name.trim(), false, singleAgent, emptyList(), group.trim(), workspace.trim())
                    }
                }
            }
        }
    }
}
