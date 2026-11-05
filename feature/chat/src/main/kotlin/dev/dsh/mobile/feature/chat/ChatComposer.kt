// 输入区：输入框、附件待发条、发送/停止按钮、工具面板与排队条。
package dev.dsh.mobile.feature.chat

import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.core.data.repo.SendQueue
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.design.SlotEditFrame
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotPlusButton
import dev.dsh.mobile.design.SlotStrip
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens
import dev.dsh.mobile.design.QqShape
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha

/**
 * 排队区。
 *
 * 正在跑的时候发的消息会先排在这里，不再像以前那样只显示「已排队 N」——
 * 看不到排的是什么、也改不了顺序，只能整个清空。现在每条都能单独撤下 / 插到队首 /
 * 立刻打断当前这轮发出去。
 */
internal const val QUEUE_EXPANDED_DEFAULT = false

@Composable
internal fun QueueStrip(
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
            val head = if (streaming) stringResource(DesignR.string.chat_queued_head, items.size)
            else stringResource(DesignR.string.chat_pending_head, items.size)
            Text(head, color = p.t2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (expanded) stringResource(DesignR.string.common_collapse) else stringResource(DesignR.string.common_expand),
                color = p.t3,
                fontSize = 11.5.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 6.dp),
            )
            Text(
                stringResource(DesignR.string.chat_clear_action),
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
                            .clip(QqShape.chip())
                            .background(p.bg)
                            .padding(start = 9.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((index + 1).toString(), color = p.t3, fontSize = 11.sp, modifier = Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.text.ifBlank { stringResource(DesignR.string.chat_images_count, item.images.size) },
                                color = p.t1,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index > 0) {
                            Text(
                                stringResource(DesignR.string.chat_jump_queue),
                                color = p.accent,
                                fontSize = 11.5.sp,
                                modifier = Modifier.clickable { onPromote(item.id) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                        Text(
                            stringResource(DesignR.string.chat_send_short),
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
            .height(48.dp)
            .clip(QqShape.field())
            .background(color)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 发送 / 停止共用：命中区 48dp，圆点视觉仍是 34dp，不挤输入区。 */
@Composable
private fun SendStopButton(
    enabled: Boolean,
    background: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(background),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** 输入区右下角的「+」：只开关底部菜单（快捷指令/模板/插件槽/会话底部槽），不管文件/图片/拍照。 */
@Composable
private fun PlusToggleButton(active: Boolean, onClick: () -> Unit) {
    val p = LocalQq.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (active) p.accent.copy(alpha = 0.16f) else p.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = if (active) stringResource(DesignR.string.chat_tools_collapse) else stringResource(DesignR.string.chat_tools_expand),
                tint = if (active) p.accent else p.t2,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * 输入区。文件 / 图片 / 拍照常显在输入行左侧；`+` 只开关下方菜单
 * （快捷指令 / 提示词模板 / 插件 / 会话底部槽），默认收起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Composer(
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
    /** 当前模型是否允许图片输入（M4）；false 时图片按钮置灰并在输入区上方给一行提示。 */
    imageEnabled: Boolean = true,
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
    /**
     * 插件注册在输入框上方的动作按钮（SlotId.ComposerPlus）。
     *
     * 只取前 [COMPOSER_SLOT_CAP] 个、按 pluginId + id 稳定排序：插件装多了不会把输入区顶走，
     * 也不会因为注册顺序变了就换位置。
     */
    composerSlots: List<SlotContribution> = emptyList(),
    /** 已放置到「会话底部」槽的插件组件；点一下沿用 onSlot 回到宿主。 */
    slotPlaced: List<SlotContribution> = emptyList(),
    /** 「会话底部」槽的候选清单，编辑态点「+」时弹出来。 */
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
    onSlot: (SlotContribution) -> Unit = {},
) {
    val p = LocalQq.current
    val focusManager = LocalFocusManager.current
    // 折叠头默认全收起；picker 是「+」打开的候选弹窗。
    var quickOpen by remember { mutableStateOf(false) }
    var tplOpen by remember { mutableStateOf(false) }
    var extraOpen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    // QQ 式 @ 补全：输入里最后一个 @ 之后还没空格，就当正在找人。
    val draft = input.text
    val atQuery = if (draft.contains('@')) draft.substringAfterLast('@') else null
    val mentionMatches = atQuery
        ?.takeIf { tail -> tail.none { it.isWhitespace() } }
        ?.let { q -> mentions.filter { it.contains(q, ignoreCase = true) }.take(8) }
        .orEmpty()
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    Column(Modifier.fillMaxWidth().background(p.card)) {
        ComposerSlotRow(composerSlots, onSlot)
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
                Text(stringResource(DesignR.string.chat_drafts_count, drafts.size), color = p.t3, fontSize = 11.sp)
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
                            ) { Text(stringResource(DesignR.string.chat_image_unit), color = p.t3, fontSize = 12.sp) }
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                // 命中区 32dp（角标视觉 18dp 抠在 62dp 缩略图上，再大就盖住缩略图本身）。
                                .size(32.dp)
                                .clickable { onRemoveDraft(path) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(CircleShape).background(p.scrim),
                                contentAlignment = Alignment.Center,
                            ) { Text("×", color = Color.White, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        // DSH composer：一整块圆角输入区，附件图标在左下、圆形发送键在右下，中间是正文。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(QqShape.sheet())
                .background(p.hover),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp)) {
                if (input.text.isBlank()) {
                    Text(stringResource(DesignR.string.chat_empty_prompt), color = p.t3, fontSize = 15.sp)
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
            if (!imageEnabled) {
                Text(
                    stringResource(DesignR.string.chat_image_needs_model),
                    color = p.t3,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 常显：不受「+」与面板开关影响。
                ToolButton(stringResource(DesignR.string.common_file), Icons.Filled.AttachFile, onPickFile)
                ToolButton(stringResource(DesignR.string.common_image), Icons.Filled.Image, onPickImage, enabled = imageEnabled)
                ToolButton(stringResource(DesignR.string.common_take_photo), Icons.Filled.PhotoCamera, onTakePhoto)
                Spacer(Modifier.weight(1f))
                if (streaming && input.text.isNotBlank()) {
                    // 正在跑的时候有字：右下角仍然是「发送」，按设置里的三档策略处理
                    // （排队 / 插入 / 打断）。想明确指定时用这两个小按钮。
                    ActionChip(stringResource(DesignR.string.chat_mode_queue), p.accent, onQueue)
                    Spacer(Modifier.width(6.dp))
                    ActionChip(stringResource(DesignR.string.chat_mode_insert), p.accent, onInsert)
                    Spacer(Modifier.width(6.dp))
                }
                if (streaming && input.text.isBlank()) {
                    // 没字且有任务在跑：这个圆按钮是「停止」。
                    SendStopButton(enabled = true, background = p.t1, onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(DesignR.string.chat_stop),
                            tint = p.card,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                PlusToggleButton(active = panelOpen) {
                    // 收起面板时顺手清焦点；工具按钮常显，不受焦点影响。
                    if (panelOpen) focusManager.clearFocus()
                    onTogglePanel()
                }
                val canSend = input.text.isNotBlank() || drafts.isNotEmpty()
                SendStopButton(
                    enabled = canSend,
                    background = if (canSend) p.accent else p.line,
                    onClick = onSend,
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(DesignR.string.chat_send),
                        tint = if (canSend) Color.White else p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 底部菜单默认收起，只由输入区右下角的「+」开关（QQ 式）。
        AnimatedVisibility(visible = panelOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .background(p.bg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 三段都折叠成一行：先给目录，展开才出 chip。
                if (quickCommands.isNotEmpty()) {
                    PanelSection(stringResource(DesignR.string.chat_quick_commands), quickOpen, { quickOpen = !quickOpen }) {
                        ChipFlow(quickCommands) { onQuickCommand(it) }
                    }
                }
                if (templates.isNotEmpty()) {
                    PanelSection(stringResource(DesignR.string.chat_prompt_templates), tplOpen, { tplOpen = !tplOpen }) {
                        ChipFlow(templates) { onTemplate(it) }
                    }
                }
                if (extras.isNotEmpty()) {
                    PanelSection(stringResource(DesignR.string.common_plugins), extraOpen, { extraOpen = !extraOpen }) {
                        ChipFlow(extras, onExtra)
                    }
                }

                // 「会话底部」槽：已放置项横排，放不下横向滚；编辑态多一个「+」。
                if (slotPlaced.isNotEmpty() || SlotEditState.enabled) {
                    Spacer(Modifier.height(10.dp))
                    SlotStrip(horizontal = true, modifier = Modifier.fillMaxWidth()) {
                        slotPlaced.forEach { contribution ->
                            SlotEditFrame(
                                onRemove = {
                                    onSlotRemove(
                                        SlotEditItem(
                                            contribution.pluginId,
                                            contribution.id,
                                            contribution.label,
                                            contribution.subtitle,
                                        ),
                                    )
                                },
                            ) {
                                PluginSlotItem(
                                    contribution = contribution,
                                    tokens = rememberHostThemeTokens(),
                                    onAction = { onSlot(contribution) },
                                )
                            }
                        }
                        if (SlotEditState.enabled) {
                            SlotPlusButton(
                                label = stringResource(DesignR.string.common_edit) +
                                    stringResource(SlotEditState.Slot.Composer.labelRes),
                            ) { picker = true }
                        }
                    }
                }

                if (picker) {
                    SlotPickerDialog(
                        title = stringResource(DesignR.string.chat_add_to_bottom),
                        items = slotCandidates,
                        emptyHint = stringResource(DesignR.string.chat_no_slots_add),
                        onPick = { onSlotAdd(it); picker = false },
                        onDismiss = { picker = false },
                    )
                }
            }
        }
    }
}

/**
 * 面板里的一段：一行折叠头（标题 + 箭头），点整行展开/收起。
 * 内容只在展开时挂上，收起的段不占高度。
 */
@Composable
private fun PanelSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = p.t2,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(DesignR.string.common_collapse) else stringResource(DesignR.string.common_expand),
                tint = p.t3,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** 简易自动换行 chip 容器（每行最多 4 个）。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChipFlow(items: List<String>, onClick: (String) -> Unit) {
    val p = LocalQq.current
    // 按可用宽度换行，而不是写死每行 4 个：chip 文字长短差很多，写死会让长的那行
    // 顶出屏幕右边缘（右侧被裁）。FlowRow 按实测宽度排，装不下自动下一行。
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Text(
                item,
                color = p.t1,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(QqShape.chip())
                    .background(p.card)
                    .clickable { onClick(item) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

/** 输入框上方最多挂几个插件按钮：再多就会把输入行挤到键盘后面。 */
internal const val COMPOSER_SLOT_CAP = 4

/**
 * 输入框上方的插件动作行（SlotId.ComposerPlus）。
 *
 * 布局约束是硬要求：单个高度 ≤32dp、宽度 ≤96dp、单行省略、最多 [COMPOSER_SLOT_CAP] 个，
 * 放不下横向滚动 —— 插件的文案再长也不会把原生输入框和发送键挤走。
 * 某一项渲染抛异常时只跳过它自己，并用一条 Toast 告诉用户，不静默。
 */
@Composable
private fun ComposerSlotRow(slots: List<SlotContribution>, onSlot: (SlotContribution) -> Unit) {
    if (slots.isEmpty()) return
    val p = LocalQq.current
    val context = LocalContext.current
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(failed) {
        val msg = failed ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        failed = null
    }
    // 稳定排序 + 上限：插件注册顺序变化不会让按钮跳位。
    val shown = remember(slots) {
        slots.sortedWith(compareBy({ it.pluginId }, { it.id })).take(COMPOSER_SLOT_CAP)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { slot ->
            val ok = runCatching {
                Text(
                    slot.label,
                    color = p.accent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heightIn(max = 32.dp)
                        .widthIn(max = 96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.accent.copy(alpha = 0.12f))
                        .clickable { onSlot(slot) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }.isSuccess
            if (!ok) failed = stringResource(DesignR.string.chat_plugin_slot_failed, slot.label)
        }
        val hidden = slots.size - shown.size
        if (hidden > 0) {
            Text("+" + hidden, color = p.t3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    val p = LocalQq.current
    val tint = if (enabled) p.t2 else p.t3.copy(alpha = 0.45f)
    Row(
        Modifier
            .clip(QqShape.chip())
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(17.dp))
        Text(label, color = tint, fontSize = 12.5.sp)
    }
}
