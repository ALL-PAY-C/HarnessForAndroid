// 会话内弹窗：消息操作、用量明细、清空确认、重命名/工作区编辑、人设、Agent 选择、成员重命名与预设选择。
package dev.dsh.mobile.feature.chat

import android.content.Intent
import dev.dsh.mobile.core.data.preset.AgentPresets
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
import dev.dsh.mobile.core.protocol.SessionProjection
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
        title = { Text("消息操作") },
        text = {
            Column {
                TextButton(onClick = { onCopy(message.text) }) { Text("复制") }
                if (message.role == ChatMessage.Role.ASSISTANT) {
                    TextButton(enabled = !streaming, onClick = onRegenerate) { Text("重新生成") }
                }
                if (message.role == ChatMessage.Role.USER) {
                    TextButton(onClick = { onEditResend(message.text) }) { Text("编辑重发") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
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
        title = { Text("Token 用量", color = p.t1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "输入（未命中）" to compact(state.usage.input),
                    "输入（缓存命中）" to compact(state.usage.cached),
                    "输出" to compact(state.usage.output),
                    "总计" to compact(state.sessionTotalTokens),
                    "缓存命中率" to "${state.usage.cachePercent}%",
                    "当前上下文占用" to state.contextUsageText,
                ).forEach { (label, value) ->
                    Column {
                        Text(label, color = p.t2, fontSize = 12.sp)
                        Text(value, color = p.t1, fontSize = 14.sp)
                    }
                }
                // 压缩明细也挂在同一个弹窗里：没压过就不加这两行。
                if (projection.compaction.count > 0) Column {
                    Text("上下文压缩", color = p.t2, fontSize = 12.sp)
                    Text(compactionDetail(projection.compaction), color = p.t1, fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = p.accent) } },
        dismissButton = { TextButton(onClick = onOpenAgentSheet) { Text(state.agentName, color = p.accent) } },
    )
}

@Composable
internal fun ClearConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空聊天记录") },
        text = { Text("只删除本地消息，运行时与 Agent 配置保持不变。") },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                    onDismiss()
            }) { Text("清空") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
                    IconBtn(Icons.Filled.Description, "选择文件夹") { folderPicker.launch(null) }
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

/** 右上角 Agent 面板：普通会话换绑 agent，群聊继续拉人 / 踢人。 */
@Composable
internal fun AgentSheet(
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
        title = { Text("智能体预设", color = p.t1) },
        text = {
            Column {
                Text("发出第一条消息后锁定，不能再改", color = p.t3, fontSize = 11.5.sp)
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
                        if (active) Text("当前", color = p.accent, fontSize = 11.5.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = p.accent) } },
    )
}
