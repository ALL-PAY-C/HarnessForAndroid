// 会话底部槽 + 折叠面板（面板三段默认收起，槽位渲染走 design/SlotEditUi）。
// 改动后需重新产出 ChatScreenKt 门面类，勿只 touch。
package dev.dsh.mobile.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.data.repo.SendQueue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.core.content.ContextCompat
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
import dev.dsh.mobile.design.SlotEditFrame
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotPlusButton
import dev.dsh.mobile.design.SlotStrip
import dev.dsh.mobile.design.UiEditSlotPlusRow
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens
import dev.dsh.mobile.design.MessageBubble
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.render.ChatContent
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.ContextRing
import dev.dsh.mobile.design.SessionStatusDot
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.protocol.ProjectedTodo
import dev.dsh.mobile.core.protocol.group.GROUP_SCHEDULER_MODERATED
import dev.dsh.mobile.core.protocol.SessionProjection
import androidx.compose.ui.text.style.TextDecoration
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
    /** 打开整页的会话设置（原右侧边栏）。 */
    onOpenSettings: () -> Unit = {},
    messageSlot: @Composable (ChatMessage) -> Boolean = { false },
    composerExtras: List<Pair<String, (String) -> String>> = emptyList(),
    /** Plugin buttons for the "+" panel and the "…" menu, translated from their manifests. */
    composerSlots: List<SlotContribution> = emptyList(),
    overflowSlots: List<SlotContribution> = emptyList(),
    /** 插件贡献的智能体预设（装好插件即出现在「配置智能体」里）。 */
    presetSlots: List<SlotContribution> = emptyList(),
    onSlot: (SlotContribution) -> Unit = {},
    /** 会话底部槽（SlotId.ChatBottom）已放置的插件组件，由 AppNav 绑定好后传进来。 */
    slotPlaced: List<SlotContribution> = emptyList(),
    /** 会话底部槽的可添加候选（编辑态点「+」时列出）。 */
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
    /** 选中智能体预设：AppNav 转成 setSessionPresetId，页面本身不碰存储。 */
    onPickPreset: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 只读投影：待办 / 目标 / 上下文用量 / 压缩统计，全部来自 runtime，页面只订阅不写。
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()
    val systemNotes by viewModel.systemNotes.collectAsStateWithLifecycle()
    // 手动指定的下一位发言人（一次有效，只在本会话内存里）。
    val pinnedSpeaker by viewModel.pinnedSpeaker.collectAsStateWithLifecycle()
    // 预设选择状态机：发出第一条消息前可选，之后锁定只读（与桌面 DSH 一致）。
    val presetSelection = presetSelectionOf(state.presetId, state.messages.isNotEmpty())
    val listState = rememberLazyListState()
    val p = LocalQq.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var picking by remember { mutableStateOf(false) }
    var usageOpen by remember(state.sessionId) { mutableStateOf(false) }
    // todo/goal 面板默认折叠（只露一行抬头），点开才铺开清单。
    var projectionOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var personaOpen by remember { mutableStateOf(false) }
    // 智能体预设选择弹窗（锁定后点不开）。
    var presetOpen by remember { mutableStateOf(false) }
    // 底部菜单（快捷指令/模板/插件槽/会话底部槽）默认收起，按会话重置。
    var panelOpen by remember(state.sessionId) { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var editingWorkspace by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // 待发图片：先挂在这里，点发送才真正发出去（QQ 的手感）。
    var drafts by remember { mutableStateOf<List<String>>(emptyList()) }
    // 群聊 T5.4：点开的成员卡片、以及「指定下一位发言人」选择器（都按会话重置）。
    var cardMember by remember(state.sessionId) { mutableStateOf<Participant?>(null) }
    var speakerPicker by remember(state.sessionId) { mutableStateOf(false) }

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
    // 相机是运行时权限：manifest 声明了 CAMERA 之后，没授权就 launch IMAGE_CAPTURE
    // 会被系统抛 SecurityException 打死进程（"DSH keeps stopping"）。先问权限再拍照。
    val startCapture: () -> Unit = {
        val file = File(context.cacheDir, "cam-" + System.currentTimeMillis() + ".jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        cameraUri = uri
        runCatching { takePhoto.launch(uri) }
            .onFailure { notice = "打不开相机：" + it.javaClass.simpleName }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            notice = "需要相机权限：系统设置 → 应用 → DSH Mobile → 权限 → 相机"
        }
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
        MessageActionDialog(
            message = message,
            streaming = state.streaming,
            onDismiss = { actionMessage = null },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                actionMessage = null
            },
            onRegenerate = {
                follow = true
                viewModel.regenerate(message.id)
                actionMessage = null
            },
            onEditResend = { text ->
                input = TextFieldValue(text, TextRange(text.length))
                actionMessage = null
                composerFocus.requestFocus()
            },
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
            pickFolder = true,
            initial = current,
            hint = "例如 /sdcard/DSH 或 D:/work",
            onDismiss = { editingWorkspace = null },
        ) { value ->
            viewModel.setWorkspace(value.trim())
            editingWorkspace = null
        }
    }

    if (presetOpen) {
        PresetPickDialog(
            currentId = state.presetId,
            onDismiss = { presetOpen = false },
        ) { id ->
            onPickPreset(id)
            presetOpen = false
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

    if (confirmingClear) {
        ClearConfirmDialog(
            onDismiss = { confirmingClear = false },
            onConfirm = { viewModel.clear() },
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
                fontSize = qqFontBody(12.5.sp),
                modifier = Modifier
                    .padding(bottom = 120.dp)
                    .clip(QqShape.chip())
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }

    // 主持人档（设置 → 群聊编排）：只有它才会有房间级的「下一位」概念，其余档一律不露入口。
    val moderatorMode = state.isGroup && state.settings.groupScheduler == GROUP_SCHEDULER_MODERATED
    // 成员卡片要的「本轮说了没 / 本轮用量」全部从消息流现算，不新造状态源。
    val roundStats = remember(state.messages) { memberRoundStats(state.messages) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {

        ChatHeader(
            state = state,
            projection = projection,
            pinnedSpeaker = pinnedSpeaker,
            moderatorMode = moderatorMode,
            presetSelection = presetSelection,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onOpenUsage = { usageOpen = true },
            onOpenSpeakerPicker = { speakerPicker = true },
            onOpenPreset = { presetOpen = true },
            onOpenAgentSheet = { picking = true },
        )

        if (usageOpen) UsageDialog(
            state = state,
            projection = projection,
            onDismiss = { usageOpen = false },
            onOpenAgentSheet = { usageOpen = false; picking = true },
        )

        ChatErrorBar(error = state.error, onDismiss = { viewModel.dismissError() })

        ChatMemberStrip(
            state = state,
            roundStats = roundStats,
            pinnedSpeaker = pinnedSpeaker,
            onOpenMember = { cardMember = it },
        )

        // 成员卡片（设计文档 §8 MemberCard）：点成员胶囊弹出，缺的字段不显示、不编造。
        cardMember?.let { member ->
            GroupMemberCardDialog(
                part = member,
                modelName = state.models.firstOrNull { it.id == member.modelId }?.name ?: member.modelId,
                busy = state.busyAgents.any {
                    it.agentId == member.id &&
                        (it.state == AgentRunState.Thinking || it.state == AgentRunState.ToolRunning)
                },
                waiting = state.busyAgents.any {
                    it.agentId == member.id && it.state == AgentRunState.WaitingPermission
                },
                spoke = roundStats[member.id]?.spoke == true,
                tokens = roundStats[member.id]?.tokens ?: 0L,
                chars = roundStats[member.id]?.chars ?: 0,
                pinned = pinnedSpeaker == member.id,
                canPin = moderatorMode,
                onPin = {
                    viewModel.pinSpeaker(if (pinnedSpeaker == member.id) null else member.id)
                    cardMember = null
                },
                onDismiss = { cardMember = null },
            )
        }

        if (speakerPicker) SpeakerPickerDialog(
            members = state.participants,
            current = pinnedSpeaker,
            onPick = { picked ->
                viewModel.pinSpeaker(picked)
                speakerPicker = false
            },
            onDismiss = { speakerPicker = false },
        )

        // 执行状态条：谁在跑、谁在等确认。拿不到执行池（busyAgents 为空）就整条不渲染。
        ChatBusyStrip(state = state)

        ChatPlanStrip(state = state)

        // 模型侧 todo / goal 只读面板：两样都空时整块不渲染（老会话一个像素都不变）。
        if (projection.hasTodos || projection.goal != null) {
            ProjectionPanel(
                projection = projection,
                expanded = projectionOpen,
                onToggle = { projectionOpen = !projectionOpen },
            )
        }

        ChatMessageList(
            state = state,
            listState = listState,
            compacting = compacting,
            systemNotes = systemNotes,
            messageSlot = messageSlot,
            composerFocus = composerFocus,
            onActionMessage = { actionMessage = it },
            onSend = { text -> viewModel.send(text) },
            onAnswerPermission = { requestId, optionId, owner ->
                viewModel.answerPermission(requestId, optionId, owner)
            },
            modifier = Modifier.weight(1f),
        )

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
                // 真入口：走 repo.compact → transport，结果回一条系统回执（没超预算就如实说）。
                "/compact", "/压缩" -> {
                    viewModel.compactContext()
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
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startCapture() else cameraPermission.launch(Manifest.permission.CAMERA)
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
            onSlot = onSlot,
            slotPlaced = slotPlaced,
            slotCandidates = slotCandidates,
            onSlotAdd = onSlotAdd,
            onSlotRemove = onSlotRemove,
        )
        }

    }
}
/** 导出会话长图到相册；成功/失败都用一句可直接展示的话返回。 */
internal suspend fun exportChatToGallery(
    context: android.content.Context,
    title: String,
    messages: List<ChatMessage>,
    agentName: String,
): String = try {
    withContext(Dispatchers.IO) {
        exportChatToPng(context, title, messages.map { msg ->
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
}
