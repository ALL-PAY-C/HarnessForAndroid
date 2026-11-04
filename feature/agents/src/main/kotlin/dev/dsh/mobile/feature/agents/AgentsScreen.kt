package dev.dsh.mobile.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ProbeFailure
import dev.dsh.mobile.core.data.settings.ModelCatalog
import dev.dsh.mobile.core.data.settings.ModelProvider
import dev.dsh.mobile.core.data.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.contextWindowOf
import dev.dsh.mobile.core.data.settings.reasoningEfforts
import dev.dsh.mobile.core.data.settings.sanitizeDecimalInput
import dev.dsh.mobile.core.data.settings.sanitizeReasoningEffort
import dev.dsh.mobile.core.model.AgentProfile
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.CollapsibleSection
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.RunPill
import dev.dsh.mobile.design.StatusCapsule
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.design.text
import dev.dsh.mobile.design.resolve
import androidx.compose.ui.platform.LocalContext
import dev.dsh.mobile.toolbridge.ChannelStatus

@Composable
fun AgentsScreen(
    viewModel: AgentsViewModel,
    onOpenDrawer: () -> Unit = {},
    onOpenSession: (String) -> Unit,
    agentCardExtras: List<@Composable (AgentProfile) -> Unit> = emptyList(),
    onReconnect: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val p = LocalQq.current
    var editing by remember { mutableStateOf<ModelProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    // 删除模型前先弹确认框（M20）：API Key 会一起删，不可撤销，不能裸点 17dp 图标就删。
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    // 区块折叠状态：只记「展开」的区块 id，未列出的按默认收起。页面重建后保持。
    var openSections by rememberSaveable { mutableStateOf(DEFAULT_OPEN_SECTIONS) }
    val toggleSection: (String) -> Unit = { id ->
        openSections = if (id in openSections) openSections - id else openSections + id
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            toast = it.resolve(ctx)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.probe) {
        state.probe?.let {
            toast = probeText(ctx, it)
            viewModel.dismissProbe()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2_200)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        // 键盘弹起时按 ime 收缩整页（同 ChatScreen.kt:375 的写法），列表尾部不再被键盘盖住。
        Column(Modifier.fillMaxSize().imePadding()) {
            QqScreenTopBar(
                title = "Agent",
                navigationIcon = Icons.Filled.Menu,
                navigationDescription = stringResource(DesignR.string.common_menu),
                onNavigationClick = onOpenDrawer,
                // 运行状态胶囊占右侧操作槽位，位置与迁移前完全一致。
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusCapsule(state.runtime)
                    }
                },
            )

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "runtime") {
                    CollapsibleSection(
                        title = stringResource(DesignR.string.agents_section_runtime),
                        icon = Icons.Filled.Memory,
                        tint = p.accent,
                        subtitle = runtimeLabel(state.runtime),
                        expanded = "runtime" in openSections,
                        onToggle = { toggleSection("runtime") },
                    ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClick = onReconnect)
                                .padding(horizontal = 13.dp, vertical = QqSpace.m()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ava(
                                seed = "runtime",
                                size = 40.dp,
                                emoji = "🐳",
                                online = state.runtime is RuntimeState.Ready,
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "DSH Runtime",
                                    color = p.t1,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when (val r = state.runtime) {
                                        is RuntimeState.Ready -> stringResource(
                                            DesignR.string.agents_runtime_local_ready,
                                            r.detail.ifBlank { stringResource(DesignR.string.agents_runtime_ready_detail) },
                                        )
                                        is RuntimeState.Starting -> stringResource(DesignR.string.agents_runtime_starting, r.detail)
                                        is RuntimeState.Reconnecting -> stringResource(DesignR.string.agents_runtime_reconnecting, r.attempt)
                                        is RuntimeState.Failed -> stringResource(DesignR.string.agents_runtime_failed, r.reason)
                                        RuntimeState.NotConfigured -> stringResource(DesignR.string.agents_runtime_not_started)
                                    },
                                    color = p.t2,
                                    fontSize = qqFontBody(11.5.sp),
                                )
                            }
                            Text(
                                stringResource(if (state.runtime is RuntimeState.Ready) DesignR.string.agents_reconnect else DesignR.string.agents_connect),
                                color = p.accent,
                                fontSize = qqFontBody(13.sp),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    }
                }

                item(key = "models") {
                    CollapsibleSection(
                        title = stringResource(DesignR.string.agents_section_models),
                        subtitle = stringResource(DesignR.string.agents_section_models_sub),
                        icon = Icons.Filled.SmartToy,
                        tint = p.accent2,
                        count = state.models.size,
                        expanded = "models" in openSections,
                        onToggle = { toggleSection("models") },
                    ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.models.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(QqSpace.l())) {
                                Text(
                                    stringResource(DesignR.string.agents_no_models),
                                    color = p.t1,
                                    fontSize = qqFontBody(14.sp),
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(DesignR.string.agents_no_models_hint),
                                    color = p.t3,
                                    fontSize = qqFontBody(11.5.sp),
                                    modifier = Modifier.padding(top = QqSpace.xs()),
                                )
                            }
                        } else {
                            state.models.forEachIndexed { i, m ->
                                ModelRow(
                                    profile = m,
                                    onClick = { editing = m },
                                    onDefault = { viewModel.setDefaultModel(m.id) },
                                    onTest = { viewModel.testModel(m) },
                                    onDelete = { pendingDelete = m },
                                )
                                if (i != state.models.lastIndex) QqCardDivider()
                            }
                        }
                        QqCardDivider()
                        QqRow(
                            icon = Icons.Filled.Add,
                            title = stringResource(DesignR.string.agents_add_model),
                            subtitle = stringResource(DesignR.string.agents_add_model_sub),
                            tint = p.accent,
                            onClick = { adding = true },
                        )
                    }
                    }
                }

                val visibleChannels = state.channels.filter { it.id != "termux" }
                item(key = "tools") {
                    CollapsibleSection(
                        title = stringResource(DesignR.string.agents_section_tools),
                        subtitle = stringResource(DesignR.string.agents_section_tools_sub),
                        icon = Icons.Filled.Build,
                        tint = p.green,
                        count = visibleChannels.count { it.available },
                        expanded = "tools" in openSections,
                        onToggle = { toggleSection("tools") },
                    ) {
                    Column(Modifier.fillMaxWidth()) {
                        visibleChannels.forEachIndexed { i, c ->
                            ChannelRow(
                                status = c,
                                active = c.id == state.activeChannel,
                                onAuthorize = { viewModel.requestShizuku() },
                                onRefresh = { viewModel.refreshChannels() },
                            )
                            if (i != visibleChannels.lastIndex) QqCardDivider()
                        }
                    }
                    }
                }

                if (state.running.isNotEmpty()) {
                    item(key = "running") {
                        CollapsibleSection(
                            title = stringResource(DesignR.string.agents_section_running),
                            subtitle = stringResource(DesignR.string.agents_section_running_sub),
                            icon = Icons.Filled.PlayArrow,
                            tint = p.green,
                            count = state.running.size,
                            expanded = "running" in openSections,
                            onToggle = { toggleSection("running") },
                        ) {
                        Column(Modifier.fillMaxWidth()) {
                            state.running.forEachIndexed { i, card ->
                                AgentRow(card) { onOpenSession(card.session.id) }
                                if (i != state.running.lastIndex) QqCardDivider()
                            }
                        }
                        }
                    }
                }

                if (state.commands.isNotEmpty()) {
                    item(key = "commands") {
                        CollapsibleSection(
                            title = stringResource(DesignR.string.agents_section_commands),
                            icon = Icons.Filled.Code,
                            tint = p.accent2,
                            expanded = "commands" in openSections,
                            onToggle = { toggleSection("commands") },
                        ) {
                            FlowChips(state.commands)
                        }
                    }
                }

                item(key = "all") {
                    CollapsibleSection(
                        title = stringResource(DesignR.string.agents_section_all),
                        subtitle = stringResource(DesignR.string.agents_section_all_sub),
                        icon = Icons.Filled.List,
                        tint = p.accent2,
                        count = state.all.size,
                        expanded = "all" in openSections,
                        onToggle = { toggleSection("all") },
                    ) {
                        if (state.all.isEmpty()) {
                            EmptyState(
                                title = stringResource(DesignR.string.agents_empty_all_title),
                                subtitle = stringResource(DesignR.string.agents_empty_all_hint),
                            )
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                state.all.forEachIndexed { i, s ->
                                    AgentRow(AgentCard(s, DesignR.string.agents_idle)) { onOpenSession(s.id) }
                                    if (i != state.all.lastIndex) QqCardDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        toast?.let { text ->
            Box(Modifier.fillMaxSize().padding(QqSpace.xl()), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .clip(QqShape.field())
                        .background(p.t1.copy(alpha = 0.92f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(text, color = p.card, fontSize = qqFontBody(12.5.sp))
                }
            }
        }
    }

    if (adding || editing != null) {
        ModelDialog(
            existing = editing,
            providers = viewModel.providers(),
            loadEngineModels = viewModel::loadEngineModelIds,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = {
                viewModel.saveModels(it)
                adding = false
                editing = null
            },
        )
    }

    // 删除二次确认：取消不动数据，确认才 onDelete（viewModel.deleteModel）。
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(DesignR.string.agents_delete_model_title)) },
            text = {
                Text(
                    stringResource(
                        DesignR.string.agents_delete_model_body,
                        profile.name.ifBlank { profile.model },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(profile.id)
                    pendingDelete = null
                }) { Text(stringResource(DesignR.string.common_delete), color = p.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(DesignR.string.common_cancel)) }
            },
        )
    }
}

/** 默认展开的区块 id：主列表展开，状态/次要区块收起。 */
private val DEFAULT_OPEN_SECTIONS = emptyList<String>()

/** 折叠标题右侧的运行时状态摘要，与卡片内文案同源。 */
@Composable
private fun runtimeLabel(r: RuntimeState): String = when (r) {
    is RuntimeState.Ready -> stringResource(
        DesignR.string.agents_runtime_running,
        r.detail.ifBlank { stringResource(DesignR.string.agents_runtime_ready_detail) },
    )
    is RuntimeState.Starting -> stringResource(DesignR.string.agents_runtime_starting, r.detail)
    is RuntimeState.Reconnecting -> stringResource(DesignR.string.agents_runtime_reconnecting, r.attempt)
    is RuntimeState.Failed -> stringResource(DesignR.string.agents_runtime_failed, r.reason)
    RuntimeState.NotConfigured -> stringResource(DesignR.string.agents_runtime_not_started)
}

/** 思考强度的显示名：关 / 低 / 中 / 高（文案在 design 资源里，core/data 只存 off/low/medium/high）。 */
@Composable
private fun reasoningLabel(effort: String): String = stringResource(
    when (effort) {
        "off" -> DesignR.string.agents_reasoning_off
        "low" -> DesignR.string.agents_reasoning_low
        "medium" -> DesignR.string.agents_reasoning_medium
        else -> DesignR.string.agents_reasoning_high
    },
)

/** provider 名 + 模型名 + 上下文窗口，一行塞不下就省略中段。 */
private fun providerLabel(id: String): String =
    ModelProviders.all().firstOrNull { it.id == id }?.label ?: id

@Composable
private fun tokensLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> stringResource(DesignR.string.agents_context_m, tokens / 1_000_000)
    tokens >= 1_000 -> stringResource(DesignR.string.agents_context_k, tokens / 1_000)
    else -> stringResource(DesignR.string.agents_context, tokens)
}

@Composable
private fun MetaPill(text: String) {
    val p = LocalQq.current
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(p.hover)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = p.t2, fontSize = qqFontBody(10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ModelRow(
    profile: ModelProfile,
    onClick: () -> Unit,
    onDefault: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ava(seed = profile.id, size = 34.dp, label = profile.name)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    color = p.t1,
                    fontSize = qqFontBody(14.5.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile.isDefault) {
                    Spacer(Modifier.width(6.dp))
                    RunPill(stringResource(DesignR.string.agents_badge_default))
                }
                Spacer(Modifier.width(6.dp))
                // 思考强度徽标：关 / 低 / 中 / 高
                MetaPill(stringResource(DesignR.string.agents_reasoning_badge, reasoningLabel(profile.reasoningEffort)))
            }
            Text(
                "${providerLabel(profile.providerId)} · ${profile.model} · " +
                    tokensLabel(contextWindowOf(profile.model, profile.contextWindow)),
                color = p.t3,
                fontSize = qqFontBody(11.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(DesignR.string.agents_test),
            color = p.accent,
            fontSize = qqFontBody(11.5.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTest)
                .padding(horizontal = QqSpace.s(), vertical = 5.dp),
        )
        if (!profile.isDefault) {
            Text(
                stringResource(DesignR.string.agents_set_default),
                color = p.accent,
                fontSize = qqFontBody(11.5.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDefault)
                    .padding(horizontal = QqSpace.s(), vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(QqSpace.xs()))
        // 48dp 命中区：删除不可撤销（连 API Key 一起删），不能让 17dp 小图标裸奔（M20）。
        Box(
            Modifier
                .size(48.dp)
                .clip(QqShape.field())
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Delete,
                stringResource(DesignR.string.common_delete),
                tint = p.t3,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    status: ChannelStatus,
    active: Boolean,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(QqShape.chip()).background(p.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Terminal,
                null,
                tint = if (status.available) p.green else p.t3,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(QqSpace.m()))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.label, color = p.t1, fontSize = qqFontBody(14.5.sp), fontWeight = FontWeight.Medium)
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    RunPill(stringResource(DesignR.string.agents_badge_current))
                }
            }
            if (status.detail.isNotBlank()) Text(status.detail, color = p.t3, fontSize = qqFontBody(11.5.sp))
        }
        if (!status.available && status.id == "shizuku") {
            Text(
                stringResource(DesignR.string.agents_authorize),
                color = p.accent,
                fontSize = qqFontBody(12.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAuthorize)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        } else {
            Text(
                stringResource(if (status.available) DesignR.string.agents_available else DesignR.string.agents_reprobe),
                color = if (status.available) p.green else p.t3,
                fontSize = qqFontBody(11.5.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = QqSpace.s(), vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun AgentRow(card: AgentCard, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ava(
            seed = card.session.id,
            size = 38.dp,
            emoji = "🤖",
            online = card.session.status == SessionStatus.Running,
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.session.title.ifBlank { stringResource(DesignR.string.common_untitled_session) },
                color = p.t1,
                fontSize = qqFontBody(14.5.sp),
                fontWeight = FontWeight.Medium,
            )
            Text(
                card.session.preview.ifBlank { stringResource(DesignR.string.agents_no_output) },
                color = p.t3,
                fontSize = qqFontBody(11.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RunPill(stringResource(card.labelRes))
    }
}

@Composable
private fun FlowChips(items: List<String>) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(QqSpace.m()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(QqShape.chip())
                            .background(p.hover)
                            .padding(horizontal = 10.dp, vertical = QqSpace.s()),
                    ) {
                        Text(item, color = p.t2, fontSize = qqFontBody(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelDialog(
    existing: ModelProfile?,
    providers: List<ModelProvider>,
    onDismiss: () -> Unit,
    onSave: (List<ModelProfile>) -> Unit,
    /** engine:// 端点的模型列表来源；默认空表，纯 HTTP 端点用不到。 */
    loadEngineModels: suspend () -> List<String> = { emptyList() },
) {
    val p = LocalQq.current
    // 非 Compose 的协程体（scope.launch / onClick）里取不到 stringResource，先在这里取值。
    val engineNoModels = stringResource(DesignR.string.agents_engine_no_models)
    val firstTemplate = templateOf(providers.first())
    var provider by remember { mutableStateOf(providers.first()) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: firstTemplate.baseUrl) }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: firstTemplate.apiKey) }
    var model by remember { mutableStateOf(existing?.model ?: firstTemplate.model) }
    // 勾多个模型 = 一次保存多条 profile；空表时退回上面那个单条输入框。
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var contextWindow by remember {
        mutableStateOf(existing?.contextWindow?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var fetchError by remember { mutableStateOf<String?>(null) }
    // 引擎通道拿不到模型不是「失败」（协议没错、端点没错），只是插件没上报，单独提示。
    var engineNote by remember { mutableStateOf<String?>(null) }
    // 新增的高级参数：全部「留空/关闭 = 不发送」，旧行为不受影响
    var effort by remember { mutableStateOf(sanitizeReasoningEffort(existing?.reasoningEffort ?: "high")) }
    var maxOutput by remember {
        mutableStateOf(existing?.maxOutputTokens?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var temperature by remember {
        mutableStateOf(existing?.temperature?.takeIf { it >= 0f }?.toString().orEmpty())
    }
    var topP by remember {
        mutableStateOf(existing?.topP?.takeIf { it >= 0f }?.toString().orEmpty())
    }
    var supportsTools by remember { mutableStateOf(existing?.supportsTools ?: true) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (existing == null) DesignR.string.agents_add_model else DesignR.string.agents_dialog_edit),
                color = p.t1,
            )
        },
        text = {
            // 字段变多了：内容区自己滚动，保存/取消按钮由 AlertDialog 固定在底部
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // 预设改成一行横滑：竖滑容器里 LazyRow 的横滑手势不会被外层抢走。
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(providers, key = { it.id }) { prov ->
                        Box(
                            Modifier
                                .clip(QqShape.chip())
                                .background(if (prov.id == provider.id) p.accent.copy(alpha = 0.14f) else p.hover)
                                .clickable {
                                    provider = prov
                                    val template = templateOf(prov)
                                    baseUrl = template.baseUrl
                                    model = template.model
                                    // 模板自带 Key（本地网关）就填上；没有的话保留用户已经输入的。
                                    if (template.apiKey.isNotBlank()) apiKey = template.apiKey
                                    picked = emptyList()
                                    if (name.isBlank()) name = template.name
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                prov.label,
                                color = if (prov.id == provider.id) p.accent else p.t2,
                                fontSize = qqFontBody(12.sp),
                            )
                        }
                    }
                }
                DialogField(stringResource(DesignR.string.agents_field_name), name) { name = it }
                DialogField("Base URL", baseUrl) { baseUrl = it }
                DialogField("API Key", apiKey, secret = true) { apiKey = it }
                DialogField(stringResource(DesignR.string.agents_field_model_name), model) { model = it }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QqSpace.s()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            fetching = true
                            fetchError = null
                            engineNote = null
                            scope.launch {
                                val raw = baseUrl.trim()
                                if (ModelCatalog.isEngineEndpoint(raw)) {
                                    // engine:// 不打 HTTP：模型列表由引擎插件经宿主通道上报。
                                    val ids = loadEngineModels()
                                    available = ids
                                    engineNote = if (ids.isEmpty()) engineNoModels else null
                                } else {
                                    val result = ModelCatalog.fetch(raw, apiKey.trim())
                                    available = result.getOrDefault(emptyList())
                                    fetchError = result.exceptionOrNull()?.message
                                }
                                fetching = false
                            }
                        },
                        enabled = !fetching && baseUrl.isNotBlank(),
                    ) {
                        Text(
                            stringResource(
                                if (fetching) DesignR.string.agents_fetching else DesignR.string.agents_fetch_models,
                            ),
                            color = p.accent,
                            fontSize = qqFontBody(12.sp),
                        )
                    }
                    if (available.isNotEmpty()) {
                        Text(
                            if (picked.isEmpty()) {
                                stringResource(DesignR.string.agents_available_count, available.size)
                            } else {
                                stringResource(DesignR.string.agents_picked_count, picked.size)
                            },
                            color = p.t3,
                            fontSize = qqFontBody(11.sp),
                        )
                    }
                }
                fetchError?.let {
                    Text(
                        stringResource(DesignR.string.agents_fetch_failed, it),
                        color = p.red,
                        fontSize = qqFontBody(11.sp),
                    )
                }
                engineNote?.let { Text(it, color = p.t3, fontSize = qqFontBody(11.sp)) }
                if (available.isNotEmpty()) {
                    // 列表紧跟「拉取模型列表」，勾选就在眼前。
                    Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        available.forEach { item ->
                            val selected = item == model || item in picked
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) p.accent.copy(alpha = 0.14f) else p.hover)
                                    // 点一下 = 勾选/取消；多勾几个保存时一次落成多条 profile。
                                    .clickable {
                                        picked = if (item in picked) picked - item else picked + item
                                        model = item
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item,
                                        color = if (selected) p.accent else p.t2,
                                        fontSize = qqFontBody(12.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) Text("✓", color = p.accent, fontSize = qqFontBody(12.sp))
                                }
                            }
                        }
                    }
                }
                DialogField(stringResource(DesignR.string.agents_field_context_window), contextWindow) { input ->
                    contextWindow = input.filter { ch -> ch.isDigit() }
                }
                Text(
                    stringResource(DesignR.string.agents_field_reasoning),
                    color = p.t3,
                    fontSize = qqFontBody(11.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    reasoningEfforts.forEach { value ->
                        val on = value == effort
                        Box(
                            Modifier
                                .clip(QqShape.chip())
                                .background(if (on) p.accent.copy(alpha = 0.14f) else p.hover)
                                .clickable { effort = value }
                                .padding(horizontal = QqSpace.m(), vertical = 6.dp),
                        ) {
                            Text(
                                reasoningLabel(value),
                                color = if (on) p.accent else p.t2,
                                fontSize = qqFontBody(12.sp),
                            )
                        }
                    }
                }
                DialogField(stringResource(DesignR.string.agents_field_max_output), maxOutput) { input ->
                    maxOutput = input.filter { ch -> ch.isDigit() }
                }
                DialogField(stringResource(DesignR.string.agents_field_temperature), temperature) { input ->
                    temperature = sanitizeDecimalInput(input)
                }
                DialogField(stringResource(DesignR.string.agents_field_top_p), topP) { input ->
                    topP = sanitizeDecimalInput(input)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(DesignR.string.agents_supports_tools),
                            color = p.t2,
                            fontSize = qqFontBody(12.5.sp),
                        )
                        Text(
                            stringResource(DesignR.string.agents_supports_tools_hint),
                            color = p.t3,
                            fontSize = qqFontBody(10.5.sp),
                        )
                    }
                    Switch(checked = supportsTools, onCheckedChange = { supportsTools = it })
                }
                Text(provider.hint, color = p.t3, fontSize = qqFontBody(11.sp), modifier = Modifier.padding(top = QqSpace.xs()))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // engine:// 走本地引擎，密钥由插件自己管；空 API Key 也算填好，否则点了没反应。
                val keyless = baseUrl.trim().startsWith("engine://")
                // 勾了多条就一次存多条；没勾就存上面那个模型名输入框里的单条。
                val chosen = picked.ifEmpty { listOf(model.trim()) }.filter { it.isNotBlank() }
                if ((keyless || apiKey.isNotBlank()) && chosen.isNotEmpty()) {
                    onSave(
                        buildProfiles(
                            draft = ModelDraft(
                                name = name,
                                providerId = provider.id,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                contextWindow = contextWindow.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                                reasoningEffort = effort,
                                maxOutputTokens = maxOutput.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                                temperature = temperature.trim().toFloatOrNull()?.takeIf { it >= 0f } ?: -1f,
                                topP = topP.trim().toFloatOrNull()?.takeIf { it >= 0f } ?: -1f,
                                supportsTools = supportsTools,
                                isDefault = existing?.isDefault ?: false,
                            ),
                            models = chosen,
                            existingId = existing?.id,
                            nowMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }) { Text(stringResource(DesignR.string.common_save), color = p.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.common_cancel), color = p.t2) }
        },
        containerColor = p.card,
    )
}

@Composable
private fun DialogField(
    label: String,
    value: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(top = QqSpace.s())) {
        Text(label, color = p.t3, fontSize = qqFontBody(11.sp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = p.t1, fontSize = qqFontBody(13.5.sp)),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .clip(QqShape.chip())
                .background(p.hover)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

/** 连接测试结论 → 文案。数据层只给 ProbeResult，中文都在这里取资源。 */
private fun probeText(context: android.content.Context, notice: ProbeNotice): String {
    val result = notice.result
    val head = if (result.ok) {
        val detail = if (result.engineOffline) {
            context.getString(DesignR.string.agents_probe_engine_offline)
        } else {
            context.getString(DesignR.string.agents_probe_ms, result.elapsedMs)
        }
        context.getString(DesignR.string.agents_probe_ok, detail)
    } else {
        val why = when {
            result.httpCode > 0 -> "HTTP " + result.httpCode
            result.failure == ProbeFailure.EmptyBaseUrl -> context.getString(DesignR.string.agents_probe_empty_base_url)
            result.failure == ProbeFailure.Timeout -> context.getString(DesignR.string.agents_probe_timeout)
            result.error.isNotBlank() -> result.error
            else -> context.getString(DesignR.string.agents_probe_unknown)
        }
        context.getString(DesignR.string.agents_probe_fail, why)
    }
    val withModels = if (result.ok && result.models.isNotEmpty()) {
        head + " · " + context.getString(DesignR.string.agents_probe_models, result.models.joinToString(","))
    } else head
    return if (notice.window > 0) {
        withModels + " · " + context.getString(DesignR.string.agents_probe_context, notice.window)
    } else withModels
}
