package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.text
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.dsh.mobile.design.QqScreenTopBar
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    val context = LocalContext.current
    // 待确认卸载的插件：点删除先弹二次确认，确认后才真正移除。
    var pending by remember { mutableStateOf<LoadedPlugin?>(null) }
    // 插件是外部装进来的：进页面、回到前台、重装 APK 之后都必须重扫一次。
    // 以前这一趟只在「插件设置」页触发，于是重装后第一次进来整行缺失，得绕去设置页才看得见。
    LifecycleResumeEffect(Unit) {
        onRescan()
        onPauseOrDispose { }
    }
    // 顶部小字：说明「已移除」是持久的，重装即恢复。
    val hint: @Composable () -> Unit = {
        Text(
            stringResource(DesignR.string.plugins_removed_persist_hint),
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
                        GroupHeader(label = stringResource(DesignR.string.plugins_group_installed), count = 0, collapsible = false)
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(DesignR.string.plugins_none),
                                color = LocalQq.current.t1,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(DesignR.string.plugins_none_hint),
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
                                stringResource(DesignR.string.plugins_rescan),
                                color = LocalQq.current.accent,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(DesignR.string.plugins_rescan_short),
                                color = LocalQq.current.accent,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (probes.isNotEmpty()) {
                    item {
                        GroupFrame(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            GroupHeader(label = stringResource(DesignR.string.plugins_group_diag), count = probes.size, collapsible = false)
                            Column(Modifier.padding(vertical = 6.dp)) {
                                probes.forEach { probe ->
                                    Text(
                                        (if (probe.ok) "✓ " else "✗ ") + probe.name +
                                            stringResource(
                                                DesignR.string.plugins_diag_source,
                                                stringResource(
                                                    if (probe.source == "system") DesignR.string.plugins_source_system
                                                    else DesignR.string.plugins_source_import,
                                                ),
                                            ) +
                                            (probe.error?.let { stringResource(DesignR.string.plugins_diag_error, it) } ?: ""),
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
                AddRowCard(stringResource(DesignR.string.plugins_rescan), onRescan)
            }
            item {
                Text(
                    stringResource(DesignR.string.plugins_footer),
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
                // 与其它一级页共用同一个顶栏骨架（左返回 / 标题居中 / 右侧槽位留空），
                // 不再用 M3 TopAppBar 自成一套排版。
                QqScreenTopBar(
                    title = stringResource(DesignR.string.plugins_title),
                    isBack = true,
                    navigationDescription = stringResource(DesignR.string.common_back),
                    onNavigationClick = onBack,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding -> body(padding) }
    }

    // 二次确认：说清「App 内移除」与「系统里那个 App」是两件事，避免用户以为没删掉。
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(DesignR.string.plugins_remove_title)) },
            text = {
                Text(
                    stringResource(DesignR.string.plugins_remove_body, target.manifest.name),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = target.manifest.name
                        pending = null
                        viewModel.uninstall(target.manifest.id)
                            .onSuccess {
                                scope.launch {
                                    snackbar.showSnackbar(context.getString(DesignR.string.plugins_removed, name))
                                }
                            }
                            .onFailure { t ->
                                scope.launch {
                                    snackbar.showSnackbar(
                                        context.getString(
                                            DesignR.string.plugins_remove_failed,
                                            t.message ?: t::class.java.simpleName,
                                        ),
                                    )
                                }
                            }
                    },
                ) { Text(stringResource(DesignR.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(DesignR.string.common_cancel)) }
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
                    status.text.text(),
                    color = when {
                        status.failed -> MaterialTheme.colorScheme.error
                        plugin.enabled -> p.accent
                        else -> p.t3
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
                // 开关是唯一的启停入口：它和上面这行文字读同一个 enabled，写同一个 values.<id>.enabled。
                // 失败行不给开关：启用意图已经写进存储，失败来自加载，可点只会给假的可操作感。
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onToggle,
                    enabled = !status.failed,
                )
                // 图钉 18dp、命中区 48dp：行内危险操作不能靠手指祈祷。
                Box(
                    Modifier.size(48.dp).clickable(onClick = onUninstall),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(DesignR.string.plugins_uninstall_desc),
                        tint = p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        if (status.failed) {
            // 常显（不用展开）：失败原因就是用户来这一页要看的唯一信息。
            Text(
                stringResource(
                    DesignR.string.plugins_load_failed,
                    status.detail ?: stringResource(DesignR.string.plugins_reason_unknown),
                ),
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
                    Text(
                        stringResource(DesignR.string.plugins_load_error, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (plugin.enabled) {
                    Spacer(Modifier.height(6.dp))
                    PluginBoundary(plugin.manifest.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(DesignR.string.plugins_slots_registered, slots.size),
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
                                        TextButton(onClick = { onSlotAction(pageAction) }) {
                                            Text(stringResource(DesignR.string.plugins_open_page))
                                        }
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
