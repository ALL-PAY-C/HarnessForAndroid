package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.GroupChild
import dev.dsh.mobile.design.GroupFrame
import dev.dsh.mobile.design.GroupHeader
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import dev.dsh.mobile.pluginhost.LocalPluginHost
import dev.dsh.mobile.pluginhost.PluginHostHolder

/** Installed contributions are candidates; only explicit placements occupy interface slots. */
@Composable
fun SlotManagerScreen(onBack: () -> Unit, showTopBar: Boolean = true) {
    val context = LocalContext.current
    val host = LocalPluginHost.current ?: PluginHostHolder.instance
    val hostRevision by (host?.revision?.collectAsStateWithLifecycle(0) ?: remember { mutableIntStateOf(0) })
    val store = host?.placements(context)
    val placementRevision = store?.revision ?: 0
    val candidates = remember(host, hostRevision) { host?.slots?.bridgedCandidates().orEmpty() }
    val available = remember(candidates, placementRevision) { store?.available(candidates).orEmpty() }
    var adding by remember { mutableStateOf(false) }
    var selectedPlugin by remember { mutableStateOf<String?>(null) }
    var selectedComponent by remember { mutableStateOf<SlotContribution?>(null) }
    val destinations = SlotId.entries.filter { it != SlotId.PluginPage }

    Column(Modifier.fillMaxSize()) {
        if (showTopBar) QqTopBar(
            leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) },
            content = { Text("插件槽位", style = MaterialTheme.typography.titleMedium) },
        )
        if (host == null || store == null) {
            EmptyState("插件宿主未就绪", "宿主初始化后可添加组件")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(destinations.size) { index ->
                val target = destinations[index]
                val placed = store.visible(target, candidates)
                GroupFrame {
                    GroupHeader(
                        label = slotTitle(target),
                        count = placed.size,
                        collapsible = false,
                        trailing = {
                            Text(
                                "添加",
                                color = LocalQq.current.accent,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clickable { selectedPlugin = null; selectedComponent = null; adding = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        },
                    )
                    Column(Modifier.padding(vertical = 6.dp)) {
                        if (placed.isEmpty()) {
                            Text(
                                "空 · 点右上「添加」选择插件组件",
                                color = LocalQq.current.t3,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                        placed.forEachIndexed { position, component ->
                            GroupChild(
                                onClick = { selectedPlugin = null; selectedComponent = null; adding = true },
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(component.label.ifBlank { component.id }, color = LocalQq.current.t1)
                                    Text(
                                        component.pluginId,
                                        color = LocalQq.current.t3,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    "↑",
                                    color = if (position > 0) LocalQq.current.t2 else LocalQq.current.line,
                                    modifier = Modifier
                                        .clickable(enabled = position > 0) {
                                            store.move(component.pluginId, component.id, target, -1)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                                Text(
                                    "↓",
                                    color = if (position < placed.lastIndex) LocalQq.current.t2 else LocalQq.current.line,
                                    modifier = Modifier
                                        .clickable(enabled = position < placed.lastIndex) {
                                            store.move(component.pluginId, component.id, target, 1)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                                Text(
                                    "移除",
                                    color = LocalQq.current.t3,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .clickable { store.remove(component.pluginId, component.id, target) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding && store != null) {
        val pluginNames = host?.plugins?.value.orEmpty().associate { it.manifest.id to it.manifest.name }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(when { selectedComponent != null -> "选择放置位置"; selectedPlugin != null -> "选择组件"; else -> "选择插件" }) },
            text = {
                LazyColumn {
                    when {
                        selectedComponent != null -> {
                            val sc = selectedComponent
                            // 原生 DrawerEntry 桥接项只能摆进抽屉，避免落入 Settings 等不可见槽位。
                            val targetable = if (sc != null && host?.slots?.drawerEntryResolver()?.isBridged(sc) == true)
                                destinations.filter { it == SlotId.Drawer } else destinations
                            items(targetable.size) { index ->
                                val target = targetable[index]
                                PickerRow(slotTitle(target), "已放置 ${store.visible(target, candidates).size} 项") {
                                    sc?.let { store.add(it.pluginId, it.id, it.slot, target) }
                                    adding = false
                                }
                            }
                        }
                        selectedPlugin != null -> {
                            val components = available.filter { it.pluginId == selectedPlugin }
                            items(components.size) { index ->
                                val component = components[index]
                                PickerRow(component.label.ifBlank { component.id }, component.subtitle) { selectedComponent = component }
                            }
                        }
                        else -> {
                            val plugins = available.map { it.pluginId }.distinct()
                            if (plugins.isEmpty()) item { Text("暂无可添加组件，请先安装并启用提供组件的插件。") }
                            items(plugins.size) { index ->
                                val id = plugins[index]
                                PickerRow(pluginNames[id] ?: id, id) { selectedPlugin = id }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                when { selectedComponent != null -> selectedComponent = null; selectedPlugin != null -> selectedPlugin = null; else -> adding = false }
            }) { Text(if (selectedPlugin == null) "关闭" else "上一步") } },
        )
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

private fun slotTitle(slot: SlotId): String = when (slot) {
    SlotId.ComposerPlus -> "输入框「+」面板"
    SlotId.ChatOverflow -> "聊天页「⋯」菜单"
    SlotId.SessionRow -> "会话行「⋯」菜单"
    SlotId.Drawer -> "左侧抽屉"
    SlotId.Settings -> "设置页"
    SlotId.PluginPage -> "插件中心页面"
    SlotId.AgentPreset -> "智能体预设"
}
