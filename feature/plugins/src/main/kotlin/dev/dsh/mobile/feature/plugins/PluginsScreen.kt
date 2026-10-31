package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.GroupHeader
import dev.dsh.mobile.design.GroupFrame
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginhost.LoadedPlugin
import dev.dsh.mobile.pluginhost.PluginBoundary
import dev.dsh.mobile.pluginhost.PluginProbe
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel,
    onBack: () -> Unit,
    /** 嵌在插件中心里时，标题栏由外层负责，避免两层「插件」。 */
    embedded: Boolean = false,
    /** 槽位按钮的落地动作由导航层注入；不接就是惰性预览。 */
    onSlotAction: (SlotAction) -> Unit = {},
    /** 最近一次外挂插件加载的逐条结果：装了却看不到时，这里说得出断在哪一步。 */
    probes: List<PluginProbe> = emptyList(),
    /** 重新扫描（含系统里已安装的插件 App）。 */
    onRescan: () -> Unit = {},
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    // 订阅注册表版本号，贡献增删后卡片里的槽位预览会重组。
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val tokens = rememberHostThemeTokens()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 待确认卸载的插件：点删除先弹二次确认，确认后才真正移除。
    var pending by remember { mutableStateOf<LoadedPlugin?>(null) }
    // 顶部小字：说明「已移除」是持久的，重装即恢复。
    val hint: @Composable () -> Unit = {
        Text(
            "被移除过的插件不会自动回来；重装同一个 APK 即恢复。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    val body: @Composable (PaddingValues) -> Unit = { padding ->
        if (plugins.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { hint() }
                item {
                    GroupFrame(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        GroupHeader(label = "已安装插件", count = 0, collapsible = false)
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("没有插件", color = LocalQq.current.t1, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "① 系统里直接装插件 APK，装完回来自动认\n② 「插件市场 → 从文件安装」挑一个 APK",
                                color = LocalQq.current.t3,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalQq.current.line))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onRescan)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "重新扫描已安装插件",
                                color = LocalQq.current.accent,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text("↻ 重扫", color = LocalQq.current.accent, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (probes.isNotEmpty()) {
                    item {
                        GroupFrame(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            GroupHeader(label = "加载诊断", count = probes.size, collapsible = false)
                            Column(Modifier.padding(vertical = 6.dp)) {
                                probes.forEach { probe ->
                                    Text(
                                        (if (probe.ok) "✓ " else "✗ ") + probe.name +
                                            "（" + (if (probe.source == "system") "系统安装" else "App 内导入") + "）" +
                                            (probe.error?.let { "：$it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (probe.ok) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { hint() }
            items(plugins, key = { it.manifest.id }) { plugin ->
                PluginCard(
                    plugin = plugin,
                    slots = remember(revision, plugin.manifest.id) {
                        viewModel.contributionsOf(plugin.manifest.id)
                    },
                    tokens = tokens,
                    onSlotAction = onSlotAction,
                    onToggle = { viewModel.setEnabled(plugin.manifest.id, it) },
                    onUninstall = { pending = plugin },
                )
            }
            item {
                AddRowCard("重新扫描已安装插件", onRescan)
            }
            item {
                Text(
                    "插件在独立 try/catch 中加载与渲染：注册失败只禁用该插件，渲染异常显示降级卡片。HTML/JS 型插件通过“数据/API 适配 + 原生渲染器注册”兼容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (embedded) {
        // 内嵌版没有自己的 Scaffold，Snackbar 用 Box 叠在底部。
        Box(Modifier.fillMaxSize()) {
            body(PaddingValues(0.dp))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    },
                    title = { Text("插件") },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding -> body(padding) }
    }

    // 二次确认：说清「App 内移除」与「系统里那个 App」是两件事，避免用户以为没删掉。
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("移除插件？") },
            text = {
                Text(
                    "「" + target.manifest.name + "」将从 App 中移除，之后不再加载。" +
                        "若插件是以 APK 形式装在系统里，请到系统设置里卸载那个 App。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = target.manifest.name
                        pending = null
                        viewModel.uninstall(target.manifest.id)
                            .onSuccess { scope.launch { snackbar.showSnackbar("已移除 " + name) } }
                            .onFailure { t ->
                                scope.launch {
                                    snackbar.showSnackbar("移除失败：" + (t.message ?: t::class.java.simpleName))
                                }
                            }
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddRowCard(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = p.t1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    plugin: LoadedPlugin,
    slots: List<SlotContribution>,
    tokens: dev.dsh.mobile.pluginapi.HostThemeTokens,
    onSlotAction: (SlotAction) -> Unit,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    val p = LocalQq.current
    var expanded by rememberSaveable(plugin.manifest.id) { mutableStateOf(false) }
    // 运行期回执优先于启用意图：装上了但加载失败，这一行必须是失败态而不是「已启用」。
    val status = pluginRowStatus(plugin.enabled, plugin.loadError ?: plugin.error)
    GroupFrame(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        GroupHeader(
            label = plugin.manifest.name,
            expanded = expanded,
            onClick = { expanded = !expanded },
            trailing = {
                Text(
                    status.text,
                    color = when {
                        status.failed -> MaterialTheme.colorScheme.error
                        plugin.enabled -> p.accent
                        else -> p.t3
                    },
                    style = MaterialTheme.typography.labelMedium,
                    // 失败行点开关没有意义（启用意图已经写在配置里，失败来自加载），别给它假的可操作感。
                    modifier = Modifier
                        .clickable(enabled = !status.failed) { onToggle(!plugin.enabled) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                // 图钉 18dp、命中区 48dp：行内危险操作不能靠手指祈祷。
                Box(
                    Modifier.size(48.dp).clickable(onClick = onUninstall),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "卸载",
                        tint = p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        if (status.failed) {
            // 常显（不用展开）：失败原因就是用户来这一页要看的唯一信息。
            Text(
                "加载失败：" + (status.detail ?: "原因未知"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        if (expanded) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "v" + plugin.manifest.version + " · " + plugin.manifest.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = p.t3,
                )
                if (plugin.manifest.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(plugin.manifest.description, style = MaterialTheme.typography.bodySmall, color = p.t1)
                }
                if (plugin.manifest.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        plugin.manifest.capabilities.forEach { cap ->
                            CapChip(cap.name)
                        }
                    }
                }
                plugin.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("加载错误：" + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (plugin.enabled) {
                    Spacer(Modifier.height(6.dp))
                    PluginBoundary(plugin.manifest.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "槽位已注册：" + slots.size + " 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = p.accent,
                            )
                            // 全部列出：注册了 5 个槽位却只给看 3 个，等于藏了两条。
                            slots.forEach { slot ->
                                // 页面类贡献的 ui 是只读卡片，光点卡片进不去；给一个明确的打开入口。
                                val pageAction = slot.action
                                if (pageAction is SlotAction.Page) {
                                    Column {
                                        PluginSlotItem(contribution = slot, tokens = tokens, onAction = onSlotAction)
                                        TextButton(onClick = { onSlotAction(pageAction) }) { Text("打开页面") }
                                    }
                                } else {
                                    PluginSlotItem(contribution = slot, tokens = tokens, onAction = onSlotAction)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapChip(label: String) {
    val p = LocalQq.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(p.hover)
            .border(1.dp, p.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(label, style = MaterialTheme.typography.labelSmall, color = p.t2) }
}
