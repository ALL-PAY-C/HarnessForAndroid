// 会话内弹窗：消息操作、用量明细、清空确认、重命名/工作区编辑、人设、Agent 选择、成员重命名与预设选择。
package dev.dsh.mobile.feature.chat

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import android.content.Intent
import dev.dsh.mobile.core.data.preset.AgentPresets
import dev.dsh.mobile.core.model.GroupRole
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.design.text
import dev.dsh.mobile.core.model.projection.SessionProjection
import kotlinx.coroutines.launch

@Composable
internal fun MessageActionDialog(
    message: ChatMessage,
    streaming: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEditResend: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignR.string.chat_msg_actions)) },
        text = {
            Column {
                TextButton(onClick = { onCopy(message.text) }) { Text(stringResource(DesignR.string.common_copy)) }
                if (message.role == ChatMessage.Role.ASSISTANT) {
                    TextButton(enabled = !streaming, onClick = onRegenerate) { Text(stringResource(DesignR.string.chat_regenerate)) }
                }
                if (message.role == ChatMessage.Role.USER) {
                    TextButton(onClick = { onEditResend(message.text) }) { Text(stringResource(DesignR.string.chat_edit_resend)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close)) } },
    )
}

@Composable
internal fun UsageDialog(
    state: ChatUiState,
    projection: SessionProjection,
    onDismiss: () -> Unit,
    onOpenAgentSheet: () -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = { Text(stringResource(DesignR.string.chat_token_usage), color = p.t1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    stringResource(DesignR.string.chat_tokens_in_miss) to compact(state.usage.input),
                    stringResource(DesignR.string.chat_tokens_in_cached) to compact(state.usage.cached),
                    stringResource(DesignR.string.chat_tokens_out) to compact(state.usage.output),
                    stringResource(DesignR.string.chat_tokens_total) to compact(state.sessionTotalTokens),
                    stringResource(DesignR.string.chat_cache_hit_rate) to "${state.usage.cachePercent}%",
                    stringResource(DesignR.string.chat_context_usage) to state.contextUsageText.text(),
                ).forEach { (label, value) ->
                    Column {
                        Text(label, color = p.t2, fontSize = 12.sp)
                        Text(value, color = p.t1, fontSize = 14.sp)
                    }
                }
                // 压缩明细也挂在同一个弹窗里：没压过就不加这两行。
                if (projection.compaction.count > 0) Column {
                    Text(stringResource(DesignR.string.chat_context_compaction), color = p.t2, fontSize = 12.sp)
                    Text(compactionDetail(projection.compaction).text(), color = p.t1, fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close), color = p.accent) } },
        dismissButton = {
            TextButton(onClick = onOpenAgentSheet) {
                Text(state.agentName.ifBlank { stringResource(DesignR.string.chat_no_agent_selected) }, color = p.accent)
            }
        },
    )
}

@Composable
internal fun ClearConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignR.string.chat_clear_title)) },
        text = { Text(stringResource(DesignR.string.chat_clear_body)) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                    onDismiss()
            }) { Text(stringResource(DesignR.string.chat_clear_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel)) } },
    )
}

@Composable
internal fun EditDialog(
    title: String,
    initial: String,
    hint: String,
    pickFolder: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    // 路径类输入框右侧挂一个文件夹选择器：选完直接把真实路径写回同一输入框。
    val resolver = LocalContext.current.contentResolver
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            treeUriToPath(uri)?.let { value = it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().clip(QqShape.chip()).background(p.bg)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = p.t1),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = if (pickFolder) 40.dp else 12.dp, top = 10.dp, bottom = 10.dp),
                )
                if (value.isBlank()) {
                    Text(hint, color = p.t3, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                if (pickFolder) Box(Modifier.align(Alignment.CenterEnd)) {
                    IconBtn(Icons.Filled.Description, stringResource(DesignR.string.common_pick_folder)) { folderPicker.launch(null) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(DesignR.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel)) } },
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
internal fun PersonaDialog(
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
            Text(stringResource(DesignR.string.chat_agent_config_title), color = p.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (agentName.isBlank()) stringResource(DesignR.string.chat_agent_config_hint) else stringResource(DesignR.string.chat_agent_current, agentName),
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
                Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(QqShape.chip()).background(p.bg),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
                if (value.isBlank()) {
                    Text(
                        stringResource(DesignR.string.chat_agent_example_hint),
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
                TextButton(onClick = { value = "" }) { Text(stringResource(DesignR.string.chat_clear_action)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel)) }
                TextButton(onClick = { onConfirm(value) }) { Text(stringResource(DesignR.string.common_save)) }
            }
        }
    }
}

/** 右上角 Agent 面板：普通会话换绑 agent，群聊继续拉人 / 踢人。 */
@Composable
internal fun AgentSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onPickSingle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: suspend (String, String) -> UiText?,
    onRole: (String, GroupRole?) -> Unit,
) {
    val p = LocalQq.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Participant?>(null) }
    var editError by remember { mutableStateOf<UiText?>(null) }
    var roleEdit by remember { mutableStateOf<Participant?>(null) }

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

    roleEdit?.let { part ->
        RolePickerDialog(
            current = part.role.takeIf { it.isNotBlank() }?.let(GroupRole::of),
            onDismiss = { roleEdit = null },
            onPick = { picked ->
                onRole(part.id, picked)
                roleEdit = null
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
                if (state.isGroup) stringResource(DesignR.string.chat_group_members) else stringResource(DesignR.string.chat_select_agent),
                color = p.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.isGroup) stringResource(DesignR.string.chat_members_hint)
                else stringResource(DesignR.string.chat_agent_pick_hint),
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
                        // 角色 chip：点了选角色（A 监督 / B 规划 / C 检测 / D 执行 / 自定义 / 未指定）。
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { roleEdit = part }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                part.role.takeIf { it.isNotBlank() }?.let(GroupRole::of)?.label ?: stringResource(DesignR.string.chat_pick_role),
                                color = if (part.role.isBlank()) p.t3 else p.t2,
                                fontSize = 12.sp,
                            )
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove(part.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(DesignR.string.common_remove), color = p.red, fontSize = 12.sp)
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
                if (state.isGroup) stringResource(DesignR.string.chat_pull_agent) else stringResource(DesignR.string.chat_available_agents),
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
                            .clip(QqShape.chip())
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
                                stringResource(DesignR.string.chat_added_count, count),
                                color = p.accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.isGroup -> Text(stringResource(DesignR.string.chat_pull_in), color = p.accent, fontSize = 12.sp)
                            profile.id == state.agentId -> Text("✓", color = p.accent, fontSize = 14.sp)
                            else -> Unit
                        }
                    }
                }
                if (state.models.isEmpty()) {
                    EmptyState(icon = Icons.Filled.SmartToy, title = stringResource(DesignR.string.chat_no_models), subtitle = stringResource(DesignR.string.chat_no_models_hint))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    stringResource(DesignR.string.common_close),
                    color = p.accent,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(QqShape.chip()).clickable(onClick = onDismiss)
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
    error: UiText?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalQq.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignR.string.chat_rename_member), color = p.t1) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(QqShape.chip()).background(p.bg)) {
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
                    Text(error.text(), color = p.red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(stringResource(DesignR.string.common_save), color = p.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel), color = p.t2) } },
        containerColor = p.card,
    )
}

/**
 * 智能体预设的选择状态：发出第一条消息后就锁定。
 *
 * 抽成不可变值 + 纯函数，是为了让「锁定」这条规则能被单测直接盯住——
 * 界面只把 locked 翻成只读样式，不自己判 messages.size。
 */
internal data class PresetSelection(val presetId: String, val locked: Boolean) {
    /** 锁定后再选无效：原样返回，调用方据此知道没换成。 */
    fun select(id: String): PresetSelection = if (locked) this else copy(presetId = id)
}

internal fun presetSelectionOf(presetId: String, hasMessages: Boolean): PresetSelection =
    PresetSelection(presetId, hasMessages)

/** 预设 id → 展示名；未知 id 回落标准模式，不留空白。 */
internal fun presetNameOf(presetId: String): String =
    AgentPresets.builtinOf(presetId)?.name ?: AgentPresets.standard.name

/** 智能体预设弹窗：六个内置预设一行一个，当前项标「当前」；选中即回传，没有额外开关。 */
@Composable
internal fun PresetPickDialog(currentId: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        title = { Text(stringResource(DesignR.string.chat_agent_presets), color = p.t1) },
        text = {
            Column {
                Text(stringResource(DesignR.string.chat_preset_locked_hint), color = p.t3, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                AgentPresets.builtins.forEach { preset ->
                    val active = preset.id == currentId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(preset.id) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.name,
                            color = if (active) p.accent else p.t1,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) Text(stringResource(DesignR.string.chat_badge_current), color = p.accent, fontSize = 11.5.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close), color = p.accent) } },
    )
}

/** 席位角色选择：角色决定这个席位在编排循环里被叫到哪一棒；未指定 = 不参与编排。 */
@Composable
private fun RolePickerDialog(
    current: GroupRole?,
    onDismiss: () -> Unit,
    onPick: (GroupRole?) -> Unit,
) {
    val p = LocalQq.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignR.string.chat_seat_role), color = p.t1, fontSize = 16.sp) },
        text = {
            Column {
                listOf<GroupRole?>(
                    null,
                    GroupRole.Supervisor,
                    GroupRole.Planner,
                    GroupRole.Inspector,
                    GroupRole.Worker,
                    GroupRole.Custom,
                ).forEach { role ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(role) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            role?.label ?: stringResource(DesignR.string.chat_role_unset),
                            color = if (role == current) p.t1 else p.t2,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (role == current) Text(stringResource(DesignR.string.chat_badge_current), color = p.t3, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_close)) } },
    )
}
