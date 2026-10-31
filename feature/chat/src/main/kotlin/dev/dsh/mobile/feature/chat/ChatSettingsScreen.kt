package dev.dsh.mobile.feature.chat

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SlotEditFrame
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotPlusButton
import dev.dsh.mobile.design.SlotStrip
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 会话设置整页：原来从右侧推出来的那块面板，改成独立路由。
 *
 * 顺序照 mockup：顶栏（返回 / 会话名 / 搜索）→ Agent、会话名、工作区三行 →
 * 特殊格式渲染卡片 → 底部 清空 / 归档 / 导出 三等分。
 * 配色只用 LocalQq 的 token，不跟 mockup 的深紫色走。
 */
@Composable
fun ChatSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    extras: List<SlotContribution> = emptyList(),
    onSlot: (SlotContribution) -> Unit = {},
    /** 抽屉那个预设 VM；AppNav 传进来时才支持「点智能体行换预设」，null 时退回原来的成员选择。 */
    presetViewModel: PresetDrawerViewModel? = null,
    /** 「会话设置」槽（SlotId.ChatSettings）已放置的插件组件。 */
    slotPlaced: List<SlotContribution> = emptyList(),
    /** 这个槽还能放的插件组件。 */
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
) {
    val p = LocalQq.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<String?>(null) }
    var editingWorkspace by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var choosingPreset by remember { mutableStateOf(false) }
    var pickingSlot by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    // 没传 presetViewModel 时用一份空状态兜底：下面所有读取都不用再判空。
    val emptyPresetState = remember { MutableStateFlow(PresetDrawerState()) }
    val presetState by (presetViewModel?.uiState ?: emptyPresetState).collectAsStateWithLifecycle()
    // 预设名优先；没有预设 VM 时保持原来的 agentName 逻辑。
    val agentLabel = (if (presetViewModel != null) presetState.defaultName else "")
        .ifBlank { state.agentName }
        .ifBlank { "未选择 Agent" }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            QqTopBar(
                leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) },
                trailing = { IconBtn(Icons.Filled.Search, "搜索", onSearch) },
            ) {
                Text(
                    state.title.ifBlank { "会话设置" },
                    color = p.t1,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.card),
                ) {
                    // 有预设 VM 时点这行是「换 Agent 预设」（选了就用），没有时退回成员选择。
                    SettingRow(
                        icon = Icons.Filled.SmartToy,
                        label = "智能体",
                        value = agentLabel,
                    ) { if (presetViewModel != null) choosingPreset = true else picking = true }
                    DividerRow()
                    SettingRow(
                        icon = Icons.Filled.Create,
                        label = "会话名",
                        value = state.title.ifBlank { "新会话" },
                    ) { renaming = state.title }
                    DividerRow()
                    SettingRow(
                        icon = Icons.Filled.Folder,
                        label = "工作区",
                        value = state.cwd.ifBlank { "默认工作区" },
                    ) { editingWorkspace = state.cwd }
                }

                // 会话级开关：默认跟随「设置 → 会话 → 发送与显示」里的全局默认，
                // 在这里切只写本会话的覆盖，不动全局、不影响别的会话。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.card)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("特殊格式渲染", color = p.t1, fontSize = 14.sp)
                            Text(
                                "HTML / LaTeX / Markdown / 代码块",
                                color = p.t3,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = state.renderSpecial, onCheckedChange = viewModel::setRenderSpecial)
                    }
                }

                // 会话设置槽（SlotId.ChatSettings）：插件组件摆在这一段。
                // 平时只渲染已放置项；「拓展插件 UI」打开时多出「+」和右上角「−」。
                if (slotPlaced.isNotEmpty() || SlotEditState.enabled) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.card)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        SlotStrip(horizontal = false, modifier = Modifier.fillMaxWidth()) {
                            slotPlaced.forEach { slot ->
                                val item = SlotEditItem(
                                    pluginId = slot.pluginId,
                                    contributionId = slot.id,
                                    label = slot.label,
                                    subtitle = slot.subtitle,
                                )
                                SlotEditFrame(onRemove = { onSlotRemove(item) }) {
                                    // 点插件项走本屏已有的 onSlot；动作也丢给它，屏内不私接插件动作。
                                    PluginSlotItem(
                                        contribution = slot,
                                        tokens = rememberHostThemeTokens(),
                                        onAction = { onSlot(slot) },
                                    )
                                }
                            }
                            if (SlotEditState.enabled) {
                                SlotPlusButton(label = "添加插件组件") { pickingSlot = true }
                            }
                        }
                    }
                }

                extras.forEach { slot ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.card),
                    ) {
                        SettingRow(Icons.Filled.Extension, slot.label, "") { onSlot(slot) }
                    }
                }
            }

            // 三等分：每格 weight(1f)，靠 spacedBy 留缝，按钮之间不会重叠。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomAction("清空", danger = true) { confirmingClear = true }
                BottomAction(if (state.archived) "取消归档" else "归档") {
                    viewModel.setArchived(!state.archived)
                }
                BottomAction(if (exporting) "导出中…" else "导出长图", enabled = !exporting) {
                    if (exporting) return@BottomAction
                    exporting = true
                    val exportTitle = state.title
                    val exportMessages = state.messages
                    val name = state.agentName
                    scope.launch {
                        val result = exportChatToGallery(context, exportTitle, exportMessages, name)
                        exporting = false
                        snackbar.showSnackbar(result)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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

    if (choosingPreset) {
        AgentPresetDialog(
            state = presetState,
            onPick = { id ->
                presetViewModel?.setDefault(id)
                choosingPreset = false
            },
            // 预设框里留一个入口去原来的多 Agent / 群成员选择，别把那条路堵死。
            onManageMembers = {
                choosingPreset = false
                picking = true
            },
            onDismiss = { choosingPreset = false },
        )
    }

    if (pickingSlot) {
        SlotPickerDialog(
            title = "添加到会话设置",
            items = slotCandidates,
            emptyHint = "还没有可放置的插件组件：先到「插件」里安装并启用插件。",
            onPick = { item ->
                onSlotAdd(item)
                pickingSlot = false
            },
            onDismiss = { pickingSlot = false },
        )
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
}

/** 设置行：图标 + 名称 + 当前值 + ›。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.t2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = p.t1, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value,
                color = p.t3,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 2.dp)) { trailing() }
        }
        if (chevron) Text("›", color = p.t3, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 目录树 Uri（primary:DSH/xxx）→ 真实路径（/sdcard/DSH/xxx）。 */
internal fun treeUriToPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = id.split(":", limit = 2)
    val volume = parts[0].ifBlank { return null }
    val relative = parts.getOrNull(1).orEmpty()
    val base = if (volume.equals("primary", ignoreCase = true)) "/sdcard" else "/storage/$volume"
    return if (relative.isBlank()) base else "$base/$relative"
}

@Composable
private fun DividerRow() {
    val p = LocalQq.current
    Box(Modifier.fillMaxWidth().padding(start = 42.dp).height(0.6.dp).background(p.line))
}

/** 底部按钮：等分一行，危险操作用红字。 */
@Composable
private fun RowScope.BottomAction(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) p.red.copy(alpha = 0.12f) else p.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                danger -> p.red
                enabled -> p.t1
                else -> p.t3
            },
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「Agent 预设」对话框：列预设、点一下就用（不额外加锁定开关）。
 * 超过 6 项时列表内部滚，不把对话框顶出屏幕；底部留「管理成员」回原成员选择。
 */
@Composable
private fun AgentPresetDialog(
    state: PresetDrawerState,
    onPick: (String) -> Unit,
    onManageMembers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalQq.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.card)
                .padding(16.dp),
        ) {
            Text("Agent 预设", color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "选中的预设从下一轮请求开始生效",
                color = p.t3,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.presets.forEach { preset ->
                    val selected = preset.id == state.defaultPresetId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .clickable { onPick(preset.id) }
                            .background(if (selected) p.accent.copy(alpha = 0.08f) else p.card)
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                preset.name,
                                color = p.t1,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 人设摘要：前后缀拼起来截 60 字，空预设（标准模式）就不占一行。
                            val summary = (preset.prefix + preset.suffix).trim().take(60)
                            if (summary.isNotEmpty()) {
                                Text(
                                    summary,
                                    color = p.t3,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Check,
                                "当前预设",
                                tint = p.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = p.red, fontSize = 11.5.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "管理成员",
                    color = p.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onManageMembers)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = p.t3,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
