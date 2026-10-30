package dev.dsh.mobile.feature.chat

import android.net.Uri
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.data.repo.SendQueue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.animation.AnimatedVisibility
import dev.dsh.mobile.core.data.preset.PresetSwitch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.dsh.mobile.core.common.TimeFmt
import dev.dsh.mobile.core.data.settings.SendMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.design.MessageBubble
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.render.ChatContent
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SessionStatusDot
import dev.dsh.mobile.core.model.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    /** 打开左侧抽屉（Agent 预设面板就在里面）。 */
    onOpenDrawer: () -> Unit = {},
    messageSlot: @Composable (ChatMessage) -> Boolean = { false },
    composerExtras: List<Pair<String, (String) -> String>> = emptyList(),
    /** Plugin buttons for the "+" panel and the "…" menu, translated from their manifests. */
    composerSlots: List<SlotContribution> = emptyList(),
    overflowSlots: List<SlotContribution> = emptyList(),
    /** 插件贡献的智能体预设（装好插件即出现在「配置智能体」里）。 */
    presetSlots: List<SlotContribution> = emptyList(),
    onSlot: (SlotContribution) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val p = LocalQq.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    val exportSnackbar = remember { SnackbarHostState() }
    var exportingImage by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var picking by remember { mutableStateOf(false) }
    var usageOpen by remember(state.sessionId) { mutableStateOf(false) }
    var personaOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var grouping by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var editingWorkspace by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // 待发图片：先挂在这里，点发送才真正发出去（QQ 的手感）。
    var drafts by remember { mutableStateOf<List<String>>(emptyList()) }

    fun attach(uri: Uri, label: String) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { Attachments.copy(context, uri) }
            if (file == null) {
                notice = "读取$label 失败"
                return@launch
            }
            if (label == "图片" || label == "照片") {
                // 只挂进待发区，等用户点发送。draft 里存相对 filesDir 的路径，
                // 发送那一刻再还原成绝对路径给 Agent（Attachments.abs）。
                val rel = Attachments.rel(context, file)
                if (rel !in drafts) drafts = drafts + rel
            } else {
                viewModel.send("[$label] ${file.name}  " + file.absolutePath)
            }
            panelOpen = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attach(it, "文件") }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attach(it, "图片") }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) attach(uri, "照片") else notice = "没有拍照"
    }

    var follow by rememberSaveable(state.sessionId) { mutableStateOf(true) }
    LaunchedEffect(listState, state.sessionId) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(listState, state.sessionId) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (!scrolling && !canForward) follow = true
            }
    }
    // Layout changes cover streaming text, delayed cards and the IME shrinking the viewport.
    LaunchedEffect(listState, state.sessionId) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            listOf(layout.totalItemsCount, last?.index, last?.size, layout.viewportEndOffset,
                listState.canScrollForward, follow, state.settings.autoScroll)
        }.collect {
            if (follow && state.settings.autoScroll && listState.canScrollForward) {
                val last = listState.layoutInfo.totalItemsCount - 1
                if (last >= 0) listState.scrollToItem(last, Int.MAX_VALUE)
            }
        }
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text("消息操作") },
            text = {
                Column {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        actionMessage = null
                    }) { Text("复制") }
                    if (message.role == ChatMessage.Role.ASSISTANT) {
                        TextButton(enabled = !state.streaming, onClick = {
                            follow = true
                            viewModel.regenerate(message.id)
                            actionMessage = null
                        }) { Text("重新生成") }
                    }
                    if (message.role == ChatMessage.Role.USER) {
                        TextButton(onClick = {
                            input = TextFieldValue(message.text, TextRange(message.text.length))
                            actionMessage = null
                            composerFocus.requestFocus()
                        }) { Text("编辑重发") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionMessage = null }) { Text("关闭") } },
        )
    }

    if (picking) {
        AgentSheet(
            state = state,
            onDismiss = { picking = false },
            onPickSingle = { id -> viewModel.setAgent(id); picking = false },
            onAdd = { id -> viewModel.addParticipant(id) },
            onRemove = { pid -> viewModel.removeParticipant(pid) },
            onRename = { pid, name -> viewModel.renameParticipant(pid, name) },
        )
    }

    renaming?.let { current ->
        EditDialog(
            title = "重命名会话",
            initial = current,
            hint = "会话名称",
            onDismiss = { renaming = null },
        ) { value ->
            if (value.isNotBlank()) viewModel.rename(value.trim())
            renaming = null
        }
    }

    editingWorkspace?.let { current ->
        EditDialog(
            title = "修改工作区",
            initial = current,
            hint = "例如 /sdcard/DSH 或 D:/work",
            onDismiss = { editingWorkspace = null },
        ) { value ->
            viewModel.setWorkspace(value.trim())
            editingWorkspace = null
        }
    }

    if (personaOpen) {
        PersonaDialog(
            initial = state.persona,
            agentName = state.agentName,
            pluginPresets = presetSlots,
            onDismiss = { personaOpen = false },
        ) { value ->
            viewModel.setPersona(value)
            personaOpen = false
        }
    }

    if (grouping) {
        GroupPickDialog(
            current = state.group,
            known = state.groups,
            onDismiss = { grouping = false },
        ) { group ->
            viewModel.setGroup(group)
            grouping = false
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清空聊天记录") },
            text = { Text("只删除本地消息，运行时与 Agent 配置保持不变。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmingClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("取消") } },
        )
    }

    notice?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2_000)
            notice = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                msg,
                color = Color.White,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .padding(bottom = 120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        QqTopBar(
            leadingExpanded = true,
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.Menu, "侧边栏", onOpenDrawer)
                    IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
                    Column(Modifier.weight(1f).clickable { usageOpen = true }, horizontalAlignment = Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SessionStatusDot(
                                when {
                                    state.error != null -> SessionStatus.Failed
                                    state.permission != null -> SessionStatus.WaitingPermission
                                    state.streaming -> SessionStatus.Running
                                    else -> SessionStatus.Idle
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                state.title.ifBlank { "会话" },
                                color = p.t1,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (state.settings.showTokenUsage) Text(
                            if (state.streaming) "正在输出 · ${state.tokenText}" else state.tokenText,
                            color = p.t2,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.settings.showTokenUsage) Text(
                        state.sessionTotalText,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(p.hover).clickable { usageOpen = true }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        color = p.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.isGroup) "${state.participants.size} 位 Agent" else state.agentName,
                        modifier = Modifier.widthIn(max = 72.dp).clickable { picking = true }.padding(horizontal = 6.dp),
                        color = p.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconBtn(Icons.Filled.Menu, "右侧边栏") { menuOpen = true }
                }
            },
        ) {}

        if (usageOpen) AlertDialog(
            onDismissRequest = { usageOpen = false },
            containerColor = p.card,
            title = { Text("Token 用量", color = p.t1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "输入（未命中）" to exactTokens(state.usage.input),
                        "输入（缓存命中）" to exactTokens(state.usage.cached),
                        "输出" to exactTokens(state.usage.output),
                        "总计" to exactTokens(state.sessionTotalTokens),
                        "缓存命中率" to "${state.usage.cachePercent}%",
                        "当前上下文占用" to state.contextUsageText,
                    ).forEach { (label, value) ->
                        Column {
                            Text(label, color = p.t2, fontSize = 12.sp)
                            Text(value, color = p.t1, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { usageOpen = false }) { Text("关闭", color = p.accent) } },
            dismissButton = { TextButton(onClick = { usageOpen = false; picking = true }) { Text(state.agentName, color = p.accent) } },
        )

        // 发送失败 / 排队满 / 权限确认失败：标题红点只给一个状态，这里把可读原因铺出来。
        // state.error 是单条，成功或被 dismissError 清掉后即消失，不重复、不陈旧。
        state.error?.let { err ->
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
                        .clickable { viewModel.dismissError() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

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
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (busy) p.accent.copy(alpha = 0.14f) else p.bg)
                            .padding(start = 3.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ava(seed = part.id, size = 20.dp, label = part.name)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            part.name,
                            color = if (busy) p.accent else p.t2,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

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

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.SmartToy,
                        title = "有什么可以帮你的？",
                        subtitle = if (state.isGroup) "在下面 @ 一位 Agent 发任务；不 @ 时由最后发言的那位接着答。" else "消息会发给本地运行时，回复实时流式显示在这里。",
                    )
                }
            }
            state.messages.forEachIndexed { index, msg ->
                item(key = msg.id) {
                // QQ-style separator: only when the conversation actually pauses,
                // so a burst of messages stays a single visual block.
                val previous = state.messages.getOrNull(index - 1)
                val gap = if (previous == null) Long.MAX_VALUE else msg.at - previous.at
                if (gap >= TIME_DIVIDER_GAP_MS) {
                    Text(
                        TimeFmt.divider(msg.at),
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
                    detectTapGestures(onLongPress = { actionMessage = msg })
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
                            viewModel.send(option.value)
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
                                    viewModel.answerPermission(perm.requestId, optionId, perm.ownerAgentId)
                                },
                                onCustom = { runCatching { composerFocus.requestFocus() } },
                                onlyTo = perm.ownerAgentName,
                            )
                        }
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
                            viewModel.answerPermission(perm.requestId, optionId, perm.ownerAgentId)
                        },
                        onCustom = { runCatching { composerFocus.requestFocus() } },
                        onlyTo = if (state.isGroup) perm.ownerAgentName else null,
                    )
                }
            }
        }

        QueueStrip(
            items = state.queue,
            streaming = state.streaming,
            onPromote = { viewModel.promoteQueued(it) },
            onDrop = { viewModel.dropQueued(it) },
            onSendNow = { viewModel.sendNow(it) },
            onClear = { viewModel.clearQueue() },
        )

        // 斜杠指令留在本地执行，不发给模型。
        val handleCommand: (String) -> Boolean = { cmd ->
            when (cmd) {
                "/clear" -> { confirmingClear = true; true }
                "/model" -> { picking = true; true }
                "/compact" -> {
                    notice = "上下文压缩由运行时按预算自动完成，无需手动触发"
                    true
                }
                "/new" -> {
                    notice = "新建会话请回到会话列表点右上角 ＋"
                    true
                }
                else -> {
                    notice = "未知指令 $cmd · 可在设置里修改快捷指令"
                    true
                }
            }
        }

        Composer(
            focusRequester = composerFocus,
            input = input,
            onInput = { input = it },
            streaming = state.streaming,
            panelOpen = panelOpen,
            onTogglePanel = { panelOpen = !panelOpen },
            quickCommands = state.settings.quickCommands,
            templates = state.settings.promptTemplates,
            extras = composerExtras.map { it.first } + composerSlots.map { it.label },
            onQuickCommand = { cmd ->
                input = TextFieldValue(cmd, TextRange(cmd.length))
                panelOpen = false
            },
            onTemplate = { tpl ->
                input = TextFieldValue(tpl, TextRange(tpl.length))
                panelOpen = false
            },
            onExtra = { label ->
                val transform = composerExtras.firstOrNull { it.first == label }?.second
                val slot = composerSlots.firstOrNull { it.label == label }
                when {
                    slot != null -> {
                        panelOpen = false
                        onSlot(slot)
                    }
                    transform != null -> {
                        val next = transform(input.text)
                        input = TextFieldValue(next, TextRange(next.length))
                    }
                }
                panelOpen = false
            },            onPickFile = { pickFile.launch("*/*") },
            onPickImage = { pickImage.launch("image/*") },
            onTakePhoto = {
                val file = File(context.cacheDir, "cam-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraUri = uri
                takePhoto.launch(uri)
            },
            onSend = {
                follow = true
                val text = input.text
                // draft 是相对路径，发出去必须换回绝对路径：Agent 的 shell 工具读不到相对路径。
                val images = drafts.map { Attachments.abs(context, it) }
                input = TextFieldValue("")
                drafts = emptyList()
                panelOpen = false
                val cmd = text.trim()
                val handled = images.isEmpty() && cmd.startsWith("/") && !cmd.contains(' ') &&
                    runCatching { handleCommand(cmd) }.getOrDefault(false)
                if (!handled) {
                    // 三档策略跟设置走：默认排队，用户可以在设置里改成插入或打断。
                    viewModel.send(text, state.settings.sendMode, images)
                }
            },
            onStop = { viewModel.cancel() },
            onQueue = {
                val text = input.text
                input = TextFieldValue("")
                viewModel.send(text, SendMode.Queue)
            },
            onInsert = {
                val text = input.text
                input = TextFieldValue("")
                viewModel.send(text, SendMode.Insert)
            },
            queued = state.queued,
            drafts = drafts,
            onRemoveDraft = { path -> drafts = drafts - path },
            mentions = if (state.isGroup) state.participants.map { it.name }.distinct() else emptyList(),
            presetSwitches = state.presetSwitches,
            presetSwitchOn = state.presetSwitchOn,
            onPresetSwitch = { id, on -> viewModel.setPresetSwitch(id, on) },
        )
        }

        // 右侧边栏：代替原来那个「⋯」下拉菜单。QQ 的会话右滑就是这个手感——
        // 从右边推出来一整块，条目大字清楚，点空白处收起。
        ChatSidebar(
            open = menuOpen,
            title = state.title.ifBlank { "会话" },
            subtitle = if (state.isGroup) "群组 · " + state.participants.size + " 位 Agent" else state.agentName,
            archived = state.archived,
            workspace = state.cwd,
            extras = overflowSlots.map { it.label },
            renderSpecial = state.renderSpecial,
            onRenderSpecial = viewModel::setRenderSpecial,
            onClose = { menuOpen = false },
            onRename = { menuOpen = false; renaming = state.title },
            onWorkspace = { menuOpen = false; editingWorkspace = state.cwd },
            onAgent = { menuOpen = false; personaOpen = true },
            onMove = { menuOpen = false; grouping = true },
            onArchive = { menuOpen = false; viewModel.setArchived(!state.archived) },
            onExportImage = {
                menuOpen = false
                if (!exportingImage) {
                    exportingImage = true
                    val exportTitle = state.title
                    val exportMessages = state.messages
                    val agentName = state.agentName
                    scope.launch {
                        val result = try {
                            withContext(Dispatchers.IO) {
                                exportChatToPng(context, exportTitle, exportMessages.map { msg ->
                                    ExportMsg(msg.authorName ?: when (msg.role) {
                                        ChatMessage.Role.USER -> "我"
                                        ChatMessage.Role.ASSISTANT -> agentName.ifBlank { "助手" }
                                        ChatMessage.Role.SYSTEM -> "系统"
                                        ChatMessage.Role.TOOL -> "工具"
                                    }, msg.text)
                                }) ?: error("相册未返回文件地址")
                            }
                            "已导出到相册 Pictures/DSH"
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            "导出失败：" + (error.localizedMessage ?: error.javaClass.simpleName)
                        } finally {
                            exportingImage = false
                        }
                        exportSnackbar.showSnackbar(result)
                    }
                }
            },
            onExtra = { i -> menuOpen = false; overflowSlots.getOrNull(i)?.let(onSlot) },
            onClear = { menuOpen = false; confirmingClear = true },
        )
        SnackbarHost(exportSnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * 排队区。
 *
 * 正在跑的时候发的消息会先排在这里，不再像以前那样只显示「已排队 N」——
 * 看不到排的是什么、也改不了顺序，只能整个清空。现在每条都能单独撤下 / 插到队首 /
 * 立刻打断当前这轮发出去。
 */
internal const val QUEUE_EXPANDED_DEFAULT = false

@Composable
private fun QueueStrip(
    items: List<SendQueue.Item>,
    streaming: Boolean,
    onPromote: (String) -> Unit,
    onDrop: (String) -> Unit,
    onSendNow: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (items.isEmpty()) return
    val p = LocalQq.current
    var expanded by rememberSaveable { mutableStateOf(QUEUE_EXPANDED_DEFAULT) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.card)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = p.amber, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            val head = if (streaming) "已排队 " + items.size + " 条 · 等这一轮结束自动发"
            else "待发送 " + items.size + " 条"
            Text(head, color = p.t2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                color = p.t3,
                fontSize = 11.5.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 6.dp),
            )
            Text(
                "清空",
                color = p.red,
                fontSize = 11.5.sp,
                modifier = Modifier.clickable { onClear() }.padding(horizontal = 6.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.bg)
                            .padding(start = 9.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((index + 1).toString(), color = p.t3, fontSize = 11.sp, modifier = Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.text.ifBlank { "（" + item.images.size + " 张图片）" },
                                color = p.t1,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index > 0) {
                            Text(
                                "插队",
                                color = p.accent,
                                fontSize = 11.5.sp,
                                modifier = Modifier.clickable { onPromote(item.id) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                        Text(
                            "发",
                            color = p.accent,
                            fontSize = 11.5.sp,
                            modifier = Modifier.clickable { onSendNow(item.id) }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                        Text(
                            "✕",
                            color = p.t3,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onDrop(item.id) }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 输入区。`+` 展开后（QQ 式）输入框整体上移，下方出现工具面板：
 * 文件 / 图片 / 拍照 + 快捷指令 + 插件按钮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    focusRequester: FocusRequester,
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    streaming: Boolean,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    quickCommands: List<String>,
    templates: List<String> = emptyList(),
    extras: List<String>,
    onQuickCommand: (String) -> Unit,
    onTemplate: (String) -> Unit = {},
    onExtra: (String) -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    drafts: List<String> = emptyList(),
    onRemoveDraft: (String) -> Unit = {},
    onQueue: () -> Unit = {},
    onInsert: () -> Unit = {},
    queued: Int = 0,
    /** 群成员名字，用于 @ 自动补全。 */
    mentions: List<String> = emptyList(),
    /** 当前会话预设暴露的开关；空 = 这个预设没有开关，面板不渲染这一块。 */
    presetSwitches: List<PresetSwitch> = emptyList(),
    presetSwitchOn: Set<String> = emptySet(),
    onPresetSwitch: (String, Boolean) -> Unit = { _, _ -> },
) {
    val p = LocalQq.current
    // QQ 式 @ 补全：输入里最后一个 @ 之后还没空格，就当正在找人。
    val draft = input.text
    val atQuery = if (draft.contains('@')) draft.substringAfterLast('@') else null
    val mentionMatches = atQuery
        ?.takeIf { tail -> tail.none { it.isWhitespace() } }
        ?.let { q -> mentions.filter { it.contains(q, ignoreCase = true) }.take(8) }
        .orEmpty()
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    Column(Modifier.fillMaxWidth().background(p.card)) {
        if (mentionMatches.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                mentionMatches.forEach { name ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.bg)
                            .clickable {
                                val cut = draft.lastIndexOf('@').coerceAtLeast(0)
                                val next = draft.substring(0, cut) + "@" + name + " "
                                onInput(TextFieldValue(next, TextRange(next.length)))
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text("@$name", color = p.accent, fontSize = 13.sp)
                    }
                }
            }
        }
        // 已选中的 @ 提及挂成 chips：正文里已经有「@名字」就显示，点一下把它从正文删掉（发送文本保持原始 @ 文本）。
        val pickedMentions = remember(draft, mentions) {
            mentions
                .filter { name -> name.isNotBlank() && draft.contains("@" + name) }
                .sortedBy { draft.indexOf("@" + it) }
        }
        if (pickedMentions.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pickedMentions.forEach { name ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.accent.copy(alpha = 0.14f))
                            .clickable {
                                val next = draft.replaceFirst("@" + name, "")
                                    .replace(Regex(" {2,}"), " ")
                                    .trimStart()
                                onInput(TextFieldValue(next, TextRange(next.length)))
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("@" + name, color = p.accent, fontSize = 12.5.sp)
                        Spacer(Modifier.width(4.dp))
                        Text("×", color = p.accent, fontSize = 11.sp)
                    }
                }
            }
        }
        if (drafts.isNotEmpty()) {
            val ctx = LocalContext.current
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${drafts.size} 张", color = p.t3, fontSize = 11.sp)
                drafts.forEach { path ->
                    Box {
                        // draft 存的是相对 filesDir 的路径，解码前还原成绝对路径。
                        val bmp = remember(path) {
                            runCatching {
                                android.graphics.BitmapFactory.decodeFile(Attachments.abs(ctx, path))
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(62.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(
                                Modifier.size(62.dp).clip(RoundedCornerShape(8.dp)).background(p.bg),
                                contentAlignment = Alignment.Center,
                            ) { Text("图", color = p.t3, fontSize = 12.sp) }
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC000000))
                                .clickable { onRemoveDraft(path) },
                            contentAlignment = Alignment.Center,
                        ) { Text("×", color = Color.White, fontSize = 11.sp) }
                    }
                }
            }
        }
        // DSH composer：一整块圆角输入区，附件图标在左下、圆形发送键在右下，中间是正文。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.bg),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp)) {
                if (input.text.isBlank()) {
                    Text("有什么可以帮你的？", color = p.t3, fontSize = 15.sp)
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    textStyle = TextStyle(fontSize = 15.sp, color = p.t1, lineHeight = 21.sp),
                    maxLines = 6,
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton("文件", Icons.Filled.AttachFile, onPickFile)
                ToolButton("图片", Icons.Filled.Image, onPickImage)
                ToolButton("拍照", Icons.Filled.PhotoCamera, onTakePhoto)
                Spacer(Modifier.weight(1f))
                if (streaming && input.text.isNotBlank()) {
                    // 正在跑的时候有字：右下角仍然是「发送」，按设置里的三档策略处理
                    // （排队 / 插入 / 打断）。想明确指定时用这两个小按钮。
                    ActionChip("排队", p.accent, onQueue)
                    Spacer(Modifier.width(6.dp))
                    ActionChip("插入", p.accent, onInsert)
                    Spacer(Modifier.width(6.dp))
                }
                if (streaming && input.text.isBlank()) {
                    // 没字且有任务在跑：这个圆按钮是「停止」。
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(p.t1)
                            .clickable { onStop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "停止",
                            tint = p.card,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                IconBtn(
                    if (panelOpen) Icons.Filled.Close else Icons.Filled.Add,
                    if (panelOpen) "收起" else "更多",
                ) { onTogglePanel() }
                Spacer(Modifier.width(4.dp))
                val canSend = input.text.isNotBlank() || drafts.isNotEmpty()
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (canSend) p.accent else p.line)
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "发送",
                        tint = if (canSend) Color.White else p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (panelOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .background(p.bg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (presetSwitches.isNotEmpty()) {
                    Text("本会话预设", color = p.t2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    presetSwitches.forEach { sw ->
                        SideSwitch(
                            label = sw.label,
                            hint = sw.description,
                            checked = sw.id in presetSwitchOn,
                            onChange = { onPresetSwitch(sw.id, it) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (quickCommands.isNotEmpty()) {
                    Text("快捷指令", color = p.t2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    ChipFlow(quickCommands) { onQuickCommand(it) }
                }
                if (templates.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("提示词模板", color = p.t2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    ChipFlow(templates) { onTemplate(it) }
                }
                if (extras.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("插件", color = p.t2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    ChipFlow(extras, onExtra)
                }
            }
        }
    }
}

/** 简易自动换行 chip 容器（每行最多 4 个）。 */
@Composable
private fun ChipFlow(items: List<String>, onClick: (String) -> Unit) {
    val p = LocalQq.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    Text(
                        item,
                        color = p.t1,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.card)
                            .clickable { onClick(item) }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, label, tint = p.t2, modifier = Modifier.size(17.dp))
        Text(label, color = p.t2, fontSize = 12.5.sp)
    }
}

@Composable
private fun EditDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (value.isBlank()) {
                    Text(hint, color = p.t3, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 「配置智能体」的预设按钮与「Agent 预设」页同源：都取 [AgentPresets.builtins]。
 *
 * 以前这里是另写的一份硬编码三条（标准/简洁/工具），于是同一个应用里两个入口
 * 显示出两套完全不同的预设，用户以为装错了版本。
 */
private val BUILTIN_PRESETS: List<Pair<String, String>> =
    AgentPresets.builtins.map { it.name to (it.prefix + it.suffix).trim() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaDialog(
    initial: String,
    agentName: String,
    pluginPresets: List<SlotContribution>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    val presets = BUILTIN_PRESETS + pluginPresets.map { it.label to (it.subtitle.ifBlank { it.label }) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp),
        ) {
            Text("配置智能体", color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (agentName.isBlank()) "给这个会话写人设，或点下面的预设" else "当前：$agentName",
                color = p.t3,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { (label, body) ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (value == body && body.isNotEmpty()) p.accent else p.bg)
                            .clickable { value = body }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            color = if (value == body && body.isNotEmpty()) Color.White else p.t1,
                            fontSize = 12.5.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(10.dp)).background(p.bg),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (value.isBlank()) {
                    Text(
                        "例如：你是我的安卓助手，回答简短，先给命令再解释",
                        color = p.t3,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { value = "" }) { Text("清空") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(value) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun GroupPickDialog(
    current: String,
    known: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalQq.current
    var custom by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp),
        ) {
            Text("移动到分组", color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            (listOf("最近") + known.filter { it != "最近" }).distinct().forEach { name ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(name) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = p.t1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (name == current) Text("✓", color = p.accent, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg)) {
                BasicTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (custom.isBlank()) {
                    Text("新建分组…", color = p.t3, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { if (custom.isNotBlank()) onPick(custom.trim()) }) { Text("新建并移动") }
            }
        }
    }
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
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
            .border(1.dp, p.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Text(
                perm.title.ifBlank { "接下来的命令需要你的确认" },
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
                Text("等待确认", color = p.t3, fontSize = 11.5.sp)
                if (!owner.isNullOrBlank()) Pill("由 " + owner + " 发起", p.accent)
                if (!onlyTo.isNullOrBlank()) Pill("只回传给 @" + onlyTo, p.t3)
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
            Text("其他（点这里在下方自己输入）", color = p.t3, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** 判断某个选项是不是「放行」：kind / optionId / name 任意一处命中即可，deny 优先。 */
private fun isAllowOption(kind: String?, optionId: String?, name: String?): Boolean {
    val hay = ((kind ?: "") + " " + (optionId ?: "") + " " + (name ?: "")).lowercase()
    if (hay.contains("deny") || hay.contains("reject") || hay.contains("refuse")) return false
    if (hay.contains("取消") || hay.contains("拒绝") || hay.contains("不允许")) return false
    return hay.contains("allow") || hay.contains("approve") || hay.contains("accept") ||
        hay.contains("允许") || hay.contains("同意") || hay.contains("批准") || hay.contains("继续")
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
            Text("只回传给 @" + onlyTo, color = p.t3, fontSize = 10.5.sp)
        }
        options.forEach { option ->
            val picked = selectedId == option.id
            val dim = selectedId != null && !picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (picked) p.accent else p.card)
                    .border(1.dp, if (picked) p.accent else p.line, RoundedCornerShape(12.dp))
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
                .clip(RoundedCornerShape(12.dp))
                .clickable { onCustom() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("其他（自己输入）", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 右上角 Agent 面板：普通会话换绑 agent，群聊继续拉人 / 踢人。 */
@Composable
private fun AgentSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onPickSingle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: suspend (String, String) -> String?,
) {
    val p = LocalQq.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Participant?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }

    editing?.let { part ->
        RenameMemberDialog(
            initial = part.name,
            error = editError,
            onDismiss = {
                editing = null
                editError = null
            },
            onConfirm = { text ->
                scope.launch {
                    val failure = onRename(part.id, text)
                    if (failure == null) {
                        editing = null
                        editError = null
                    } else {
                        editError = failure
                    }
                }
            },
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text(
                if (state.isGroup) "群成员" else "选择 Agent",
                color = p.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.isGroup) "同一个 Agent 可以重复拉入，各自独立并行；点名字可以改名"
                else "这个会话使用哪个 Agent",
                color = p.t3,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (state.isGroup) {
                state.participants.forEach { part ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ava(seed = part.id, size = 30.dp, label = part.name)
                        Spacer(Modifier.width(10.dp))
                        // 点名字改名：群内靠名字区分谁在说话，所以重名会被拒绝。
                        Text(
                            part.name,
                            color = p.t1,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    editing = part
                                    editError = null
                                },
                        )
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove(part.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("移除", color = p.red, fontSize = 12.sp)
                        }
                    }
                }
                if (state.participants.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                    Spacer(Modifier.height(8.dp))
                }
            }

            Text(
                if (state.isGroup) "拉入 Agent" else "可用 Agent",
                color = p.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                state.models.forEach { profile ->
                    val count = state.participants.count { it.modelId == profile.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { if (state.isGroup) onAdd(profile.id) else onPickSingle(profile.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ava(seed = profile.id, size = 30.dp, label = profile.name.ifBlank { profile.model })
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
                        when {
                            state.isGroup && count > 0 -> Text(
                                "已加入 ×" + count,
                                color = p.accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.isGroup -> Text("＋ 拉入", color = p.accent, fontSize = 12.sp)
                            profile.id == state.agentId -> Text("✓", color = p.accent, fontSize = 14.sp)
                            else -> Unit
                        }
                    }
                }
                if (state.models.isEmpty()) {
                    EmptyState(icon = Icons.Filled.SmartToy, title = "暂无模型", subtitle = "先去 Agent 页添加 API Key")
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "关闭",
                    color = p.accent,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 改群成员名字。失败（重名/空）时把原因留在对话框里，不关窗。 */
@Composable
private fun RenameMemberDialog(
    initial: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改成员名字", color = p.t1) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = p.t1),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = p.red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("保存", color = p.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.t2) } },
        containerColor = p.card,
    )
}


/**
 * 会话右侧边栏。
 *
 * 原来是顶栏「⋯」下拉菜单，条目挤在右上角一小块、手指很容易点错。
 * 改成从右侧推出来一整块面板：每条一个整行、图标 + 标题 + 副标题，
 * 危险操作（清空记录）单独放最下面、颜色区隔。点面板外的遮罩收起。
 */
@Composable
private fun ChatSidebar(
    open: Boolean,
    title: String,
    subtitle: String,
    archived: Boolean,
    workspace: String,
    extras: List<String>,
    renderSpecial: Boolean,
    onRenderSpecial: (Boolean) -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onWorkspace: () -> Unit,
    onAgent: () -> Unit,
    onMove: () -> Unit,
    onArchive: () -> Unit,
    onExportImage: () -> Unit,
    onExtra: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val p = LocalQq.current
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(180)) { it },
        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it },
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onClose),
            )
            Column(
                Modifier
                    .width(272.dp)
                    .fillMaxHeight()
                    .background(p.bg)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    title,
                    color = p.t1,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    subtitle,
                    color = p.t3,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                // 会话级开关：默认跟随「设置 → 会话 → 发送与显示」里的全局默认，
                // 在这里切只写本会话的覆盖，不动全局、不影响别的会话。
                SideSwitch(
                    label = "特殊格式渲染",
                    hint = if (renderSpecial) "HTML / LaTeX / Markdown / 代码块" else "已关闭 · 原样显示源码",
                    checked = renderSpecial,
                    onChange = onRenderSpecial,
                )
                SideItem("重命名会话", "改标题", Icons.Filled.Create, onRename)
                SideItem("工作区", workspace.ifBlank { "默认目录" }, Icons.Filled.Folder, onWorkspace)
                SideItem("配置智能体", "人格 / 模型绑定", Icons.Filled.SmartToy, onAgent)
                SideItem("移动到文件夹", "会话分类", Icons.Filled.DriveFileMove, onMove)
                SideItem(
                    if (archived) "取消归档" else "归档会话",
                    "",
                    if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    onArchive,
                )
                SideItem("导出绘画", "会话转长图 PNG", Icons.Filled.Image, onExportImage)
                extras.forEachIndexed { i, label ->
                    SideItem(label, "", Icons.Filled.Extension, onClick = { onExtra(i) })
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.6.dp).background(p.line))
                Spacer(Modifier.height(8.dp))
                SideItem("清空聊天记录", "不可恢复", Icons.Filled.Delete, onClear, danger = true)
            }
        }
    }
}

@Composable
private fun SideItem(
    label: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) p.red else p.t2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (danger) p.red else p.t1, fontSize = 14.sp)
            if (hint.isNotBlank()) {
                Text(hint, color = p.t3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 右侧边栏里的滑块条目：文字在左，[Switch] 在右。和 [SideItem] 同样的行高与内边距。 */
@Composable
private fun SideSwitch(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.t1, fontSize = 14.sp)
            if (hint.isNotBlank()) {
                Text(hint, color = p.t3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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

