package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.GroupHeader
import dev.dsh.mobile.design.GroupFrame
import dev.dsh.mobile.design.GroupChild
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SectionChevron
import dev.dsh.mobile.design.SlotEditFrame
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotPlusButton
import dev.dsh.mobile.pluginapi.PluginConfigPage
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens

/**
 * The "插件" tab: one list, one row per [PluginConfigPage] followed by one row
 * per slot contribution that landed in [SlotId.PluginPage] (and friends).
 *
 * Each plugin contributes its own configuration screen through
 * [dev.dsh.mobile.pluginapi.PluginRegistry.configPage]; the hub owns the
 * navigation (list ⇄ detail) so plugins stay pure content and a new plugin
 * needs no changes here.
 *
 * [slotContributions] 是「声明式」那条路的入口：插件只声明 slot = PluginPage
 * 时没有 Compose 代码可画，宿主在这里给它一行、点开整屏用 [PluginSlotItem]
 * 渲染（ui 为空时它自己用 label 降级成一行）。
 */
@Composable
fun PluginHubScreen(
    pages: List<PluginConfigPage>,
    title: String = "插件",
    slotContributions: List<SlotContribution> = emptyList(),
    onAction: (SlotAction) -> Unit = {},
    drawerResolver: DrawerEntryResolver? = null,
    /** 每个已装插件一行设置入口，由 [pluginSettingsEntries] 组装。 */
    pluginSettings: List<PluginSettingsEntry> = emptyList(),
    /** 写回某个插件的一项配置：pluginId, key, value。 */
    onConfigSave: (pluginId: String, key: String, value: String) -> Unit = { _, _, _ -> },
    /** 从设置详情返回列表时通知宿主重读插件（配置可能改了插件行为）。 */
    onSettingsClosed: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    // 槽位接线（target = SlotId.PluginPage）：候选 + 加删回调由 AppNav 绑定。
    slotPlaced: List<SlotContribution> = emptyList(),
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
) {
    var pickingSlot by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<PluginConfigPage?>(null) }
    var openSlot by remember { mutableStateOf<SlotContribution?>(null) }
    var openSettings by remember { mutableStateOf<PluginSettingsEntry?>(null) }
    // 提到详情页早退分支之前：BackHandler 必须每帧都挂在组合里，
    // 否则详情页打开时它根本不存在，系统返回就落到 NavHost 上。
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // 页内二级导航先吃返回键：详情页 → 槽位详情 → 插件设置文件夹。
    // 漏掉这一层，NavHost 会 pop 掉整个 plugins/hub 目的地，用户直接回会话主页。
    val backStep = hubBackStep(
        pageOpen = open != null,
        slotOpen = openSlot != null,
        folderOpen = settingsOpen,
        settingsOpen = openSettings != null,
    )
    BackHandler(enabled = backStep != HubBackStep.NotMine) {
        when (backStep) {
            HubBackStep.ClosePage -> open = null
            HubBackStep.CloseSlot -> openSlot = null
            HubBackStep.CloseSettings -> {
                openSettings = null
                onSettingsClosed()
            }
            HubBackStep.CloseFolder -> settingsOpen = false
            HubBackStep.NotMine -> Unit
        }
    }

    val currentSettings = openSettings
    if (currentSettings != null) {
        Column(Modifier.fillMaxSize().background(LocalQq.current.bg)) {
            QqTopBar(
                leading = {
                    BackChip {
                        openSettings = null
                        onSettingsClosed()
                    }
                },
                content = {
                    Text(
                        currentSettings.name,
                        color = LocalQq.current.t1,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
            PluginSettingsDetail(
                entry = currentSettings,
                onSave = { key, value -> onConfigSave(currentSettings.pluginId, key, value) },
                onAction = onAction,
                onOpenUrl = onOpenUrl,
            )
        }
        return
    }

    val current = open
    if (current != null) {
        Column(Modifier.fillMaxSize().background(LocalQq.current.bg)) {
            QqTopBar(
                leading = { BackChip { open = null } },
                content = {
                    Text(
                        current.label,
                        color = LocalQq.current.t1,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
            Box(Modifier.fillMaxSize()) { current.content() }
        }
        return
    }

    val currentSlot = openSlot
    if (currentSlot != null) {
        Column(Modifier.fillMaxSize().background(LocalQq.current.bg)) {
            QqTopBar(
                leading = { BackChip { openSlot = null } },
                content = {
                    Text(
                        currentSlot.label,
                        color = LocalQq.current.t1,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
            // 整屏交给渲染器：有声明式 ui 走翻译，没有就用 content / label 兜底。
            val native = drawerResolver?.resolve(currentSlot)
            if (native != null) {
                // 原生 DrawerEntry：全屏直画 content，不经 PluginSlotItem/HostContainer。
                Box(Modifier.fillMaxSize()) { native.content() }
            } else {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    PluginSlotItem(currentSlot, rememberHostThemeTokens(), onAction)
                }
            }
        }
        return
    }

    // 内置三类页面固定排在前面，插件自己提供的配置页收进「插件设置」文件夹。
    val builtinOrder = listOf("app.plugins.manage", "app.plugins.slots", "app.plugins.market")
    val builtin = builtinOrder.mapNotNull { id -> pages.firstOrNull { it.id == id } }
    val extras = pages.filterNot { page -> builtinOrder.any { it == page.id } }
    // 同一个插件在三处都可能出现（配置页 / PluginPage 槽位 / manifest 设置），
    // 只要有可操作的 manifest 设置行就只留那一行，避免文件夹里同名列好几条。
    val covered = pluginSettings.filter { it.hasSettings }.map { it.pluginId }.toSet()
    val extraRows = extras.filter { it.pluginId.isBlank() || it.pluginId !in covered }
    val slotRows = slotContributions.filter { it.pluginId !in covered }
    // AppNav 也会把 PluginPage 的已放置项按 slotPlaced 传进来；已经在 slotRows 里的不重复画。
    val placedExtra = slotPlaced.filter { c ->
        slotContributions.none { it.pluginId == c.pluginId && it.id == c.id }
    }
    val settingsCount = extraRows.size + slotRows.size + pluginSettings.size

    Column(Modifier.fillMaxSize().background(LocalQq.current.bg)) {
        QqTopBar(content = {
            Text(title, color = LocalQq.current.t1, fontWeight = FontWeight.SemiBold)
        })
        if (builtin.isEmpty() && settingsCount == 0) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有插件提供页面", color = LocalQq.current.t3)
            }
        } else LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(builtin, key = { it.id }) { page ->
                PrimaryCardRow(page.label, page.subtitle, page.icon) { open = page }
            }
            // 已放置的 PluginPage 组件：AppNav 只按 slotPlaced 传进来的那些（editor 态带「−」）。
            if (placedExtra.isNotEmpty()) {
                item(key = "slot-placed") {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "插件组件",
                            color = LocalQq.current.t3,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        )
                        placedExtra.forEach { c ->
                            SlotEditFrame(
                                onRemove = { onSlotRemove(SlotEditItem(c.pluginId, c.id, c.label, c.subtitle)) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            ) {
                                PluginSlotItem(c, rememberHostThemeTokens(), onAction)
                            }
                        }
                    }
                }
            }
            if (settingsCount > 0) {
                item(key = "plugin-settings") {
                    GroupFrame {
                        GroupHeader(
                            label = "插件设置",
                            count = settingsCount,
                            expanded = settingsOpen,
                            onClick = { settingsOpen = !settingsOpen },
                        )
                        if (settingsOpen) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                extraRows.forEach { page ->
                                    GroupChild(onClick = { open = page }) {
                                        PluginChildText(page.label, page.subtitle)
                                        SectionChevron(expanded = false)
                                    }
                                }
                                slotRows.forEach { slot ->
                                    // 编辑态右上角叠「−」：把这个插件组件从插件页拿掉。
                                    SlotEditFrame(
                                        onRemove = {
                                            onSlotRemove(
                                                SlotEditItem(slot.pluginId, slot.id, slot.label, slot.subtitle),
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        GroupChild(onClick = { openSlot = slot }) {
                                            PluginChildText(slot.label, slot.subtitle.ifBlank { slot.pluginId })
                                            SectionChevron(expanded = false)
                                        }
                                    }
                                }
                                pluginSettings.forEach { entry ->
                                    GroupChild(onClick = { openSettings = entry }) {
                                        PluginChildText(entry.name, entry.rowSubtitle)
                                        SectionChevron(expanded = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 列表区域下方：编辑态多一条「+」，挑插件组件摆进插件页。
        if (SlotEditState.enabled) {
            SlotPlusButton(
                label = "添加插件组件",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) { pickingSlot = true }
        }
        if (pickingSlot) {
            SlotPickerDialog(
                title = "添加到插件页",
                items = slotCandidates,
                emptyHint = "没有可添加的插件组件：先到插件页装一个带 PluginPage 槽位的插件",
                onPick = { pickingSlot = false; onSlotAdd(it) },
                onDismiss = { pickingSlot = false },
            )
        }
    }
}

@Composable
private fun RowScope.PluginChildText(label: String, subtitle: String) {
    val p = LocalQq.current
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = p.t1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (subtitle.isNotBlank()) {
            Text(
                "—" + subtitle,
                color = p.t3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun PrimaryCardRow(
    label: String,
    subtitle: String,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon ?: Icons.Filled.Extension,
            contentDescription = null,
            tint = p.accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = p.t1, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = p.t3)
            }
        }
        SectionChevron(expanded = false)
    }
}


@Composable
private fun BackChip(onBack: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = LocalQq.current.t1,
        )
    }
}
