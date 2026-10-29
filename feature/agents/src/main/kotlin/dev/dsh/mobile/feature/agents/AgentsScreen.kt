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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelCatalog
import dev.dsh.mobile.core.data.settings.ModelProvider
import dev.dsh.mobile.core.data.settings.ModelProviders
import dev.dsh.mobile.core.data.settings.contextWindowOf
import dev.dsh.mobile.core.data.settings.reasoningEfforts
import dev.dsh.mobile.core.data.settings.reasoningLabelOf
import dev.dsh.mobile.core.data.settings.sanitizeDecimalInput
import dev.dsh.mobile.core.data.settings.sanitizeReasoningEffort
import dev.dsh.mobile.core.model.AgentProfile
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.design.Ava
import dev.dsh.mobile.design.CollapsibleSection
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.RunPill
import dev.dsh.mobile.design.StatusCapsule
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
    // 删除模型前先弹确认框（M20）：API Key 会一起删，不可撤销，不能裸点 17dp 图标就删。
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    // 区块折叠状态：只记「展开」的区块 id，未列出的按默认收起。页面重建后保持。
    var openSections by rememberSaveable { mutableStateOf(DEFAULT_OPEN_SECTIONS) }
    val toggleSection: (String) -> Unit = { id ->
        openSections = if (id in openSections) openSections - id else openSections + id
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            toast = it
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2_200)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqTopBar(
                leading = { IconBtn(Icons.Filled.Menu, "菜单", onOpenDrawer) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusCapsule(state.runtime)
                    }
                },
            ) {
                Text("Agent", color = p.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "runtime") {
                    CollapsibleSection(
                        title = "运行环境",
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
                                .padding(horizontal = 13.dp, vertical = 12.dp),
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
                                        is RuntimeState.Ready -> "本地运行中 · ${r.detail.ifBlank { "就绪" }}"
                                        is RuntimeState.Starting -> "启动中 · ${r.detail}"
                                        is RuntimeState.Reconnecting -> "重连中 · 第 ${r.attempt} 次"
                                        is RuntimeState.Failed -> "异常 · ${r.reason}"
                                        RuntimeState.NotConfigured -> "未启动"
                                    },
                                    color = p.t2,
                                    fontSize = 11.5.sp,
                                )
                            }
                            Text(
                                if (state.runtime is RuntimeState.Ready) "重连" else "连接",
                                color = p.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    }
                }

                item(key = "models") {
                    CollapsibleSection(
                        title = "模型接入",
                        subtitle = "API Key 与默认模型",
                        icon = Icons.Filled.SmartToy,
                        tint = p.accent2,
                        count = state.models.size,
                        expanded = "models" in openSections,
                        onToggle = { toggleSection("models") },
                    ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.models.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(
                                    "还没有接入模型",
                                    color = p.t1,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "填一个 API Key 就能让 Agent 干活（DeepSeek / OpenAI 兼容 / 第三方中转都可以）",
                                    color = p.t3,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(top = 4.dp),
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
                            title = "添加模型",
                            subtitle = "API Key / Base URL / 模型名",
                            tint = p.accent,
                            onClick = { adding = true },
                        )
                    }
                    }
                }

                val visibleChannels = state.channels.filter { it.id != "termux" }
                item(key = "tools") {
                    CollapsibleSection(
                        title = "手机工具",
                        subtitle = "通道与授权状态",
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
                            title = "运行中",
                            subtitle = "正在执行的 Agent",
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
                            title = "可用指令",
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
                        title = "全部会话",
                        subtitle = "全部历史会话",
                        icon = Icons.Filled.List,
                        tint = p.accent2,
                        count = state.all.size,
                        expanded = "all" in openSections,
                        onToggle = { toggleSection("all") },
                    ) {
                        if (state.all.isEmpty()) {
                            EmptyState(title = "还没有 Agent 会话", subtitle = "回到会话页点右上角 + 新建一个")
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                state.all.forEachIndexed { i, s ->
                                    AgentRow(AgentCard(s, "空闲")) { onOpenSession(s.id) }
                                    if (i != state.all.lastIndex) QqCardDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        toast?.let { text ->
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.t1.copy(alpha = 0.92f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(text, color = p.card, fontSize = 12.5.sp)
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
                viewModel.saveModel(it)
                adding = false
                editing = null
            },
        )
    }

    // 删除二次确认：取消不动数据，确认才 onDelete（viewModel.deleteModel）。
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型") },
            text = {
                Text("删除「${profile.name.ifBlank { profile.model }}」？API Key 会一起删除，无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(profile.id)
                    pendingDelete = null
                }) { Text("删除", color = p.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 默认展开的区块 id：主列表展开，状态/次要区块收起。 */
private val DEFAULT_OPEN_SECTIONS = emptyList<String>()

/** engine:// 端点拉到空表时的提示：协议没错，是插件没上报，给下一步动作而不是报错。 */
private const val ENGINE_NO_MODELS =
    "引擎插件没有上报模型：确认插件已安装并在插件页启动过，或直接手填模型名"

/** 折叠标题右侧的运行时状态摘要，与卡片内文案同源。 */
private fun runtimeLabel(r: RuntimeState): String = when (r) {
    is RuntimeState.Ready -> "运行中 · ${r.detail.ifBlank { "就绪" }}"
    is RuntimeState.Starting -> "启动中 · ${r.detail}"
    is RuntimeState.Reconnecting -> "重连中 · 第 ${r.attempt} 次"
    is RuntimeState.Failed -> "异常 · ${r.reason}"
    RuntimeState.NotConfigured -> "未启动"
}

/** provider 名 + 模型名 + 上下文窗口，一行塞不下就省略中段。 */
private fun providerLabel(id: String): String =
    ModelProviders.all().firstOrNull { it.id == id }?.label ?: id

private fun tokensLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M 上下文"
    tokens >= 1_000 -> "${tokens / 1_000}K 上下文"
    else -> "$tokens 上下文"
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
        Text(text, color = p.t2, fontSize = 10.sp, maxLines = 1)
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
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile.isDefault) {
                    Spacer(Modifier.width(6.dp))
                    RunPill("默认")
                }
                Spacer(Modifier.width(6.dp))
                // 思考强度徽标：关 / 低 / 中 / 高
                MetaPill("思考${profile.reasoningLabel}")
            }
            Text(
                "${providerLabel(profile.providerId)} · ${profile.model} · " +
                    tokensLabel(contextWindowOf(profile.model, profile.contextWindow)),
                color = p.t3,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "测试",
            color = p.accent,
            fontSize = 11.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTest)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        if (!profile.isDefault) {
            Text(
                "设为默认",
                color = p.accent,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDefault)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        // 48dp 命中区：删除不可撤销（连 API Key 一起删），不能让 17dp 小图标裸奔（M20）。
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Delete,
                "删除",
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
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Terminal,
                null,
                tint = if (status.available) p.green else p.t3,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.label, color = p.t1, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    RunPill("当前")
                }
            }
            if (status.detail.isNotBlank()) Text(status.detail, color = p.t3, fontSize = 11.5.sp)
        }
        if (!status.available && status.id == "shizuku") {
            Text(
                "授权",
                color = p.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAuthorize)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        } else {
            Text(
                if (status.available) "可用" else "重新探测",
                color = if (status.available) p.green else p.t3,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
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
                card.session.title.ifBlank { "未命名会话" },
                color = p.t1,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                card.session.preview.ifBlank { "无输出" },
                color = p.t3,
                fontSize = 11.5.sp,
                maxLines = 1,
            )
        }
        RunPill(card.label)
    }
}

@Composable
private fun FlowChips(items: List<String>) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.hover)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(item, color = p.t2, fontSize = 12.sp, maxLines = 1)
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
    onSave: (ModelProfile) -> Unit,
    /** engine:// 端点的模型列表来源；默认空表，纯 HTTP 端点用不到。 */
    loadEngineModels: suspend () -> List<String> = { emptyList() },
) {
    val p = LocalQq.current
    var provider by remember { mutableStateOf(providers.first()) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: providers.first().defaultBaseUrl) }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: providers.first().defaultModel) }
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
        title = { Text(if (existing == null) "添加模型" else "编辑模型", color = p.t1) },
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
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (prov.id == provider.id) p.accent.copy(alpha = 0.14f) else p.hover)
                                .clickable {
                                    provider = prov
                                    baseUrl = prov.defaultBaseUrl
                                    model = prov.defaultModel
                                    if (name.isBlank()) name = prov.label
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                prov.label,
                                color = if (prov.id == provider.id) p.accent else p.t2,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                DialogField("名称", name) { name = it }
                DialogField("Base URL", baseUrl) { baseUrl = it }
                DialogField("API Key", apiKey, secret = true) { apiKey = it }
                DialogField("模型名", model) { model = it }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    engineNote = if (ids.isEmpty()) ENGINE_NO_MODELS else null
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
                        Text(if (fetching) "拉取中…" else "拉取模型列表", color = p.accent, fontSize = 12.sp)
                    }
                    if (available.isNotEmpty()) {
                        Text("共 ${available.size} 个", color = p.t3, fontSize = 11.sp)
                    }
                }
                fetchError?.let { Text("拉取失败：$it", color = p.red, fontSize = 11.sp) }
                engineNote?.let { Text(it, color = p.t3, fontSize = 11.sp) }
                DialogField("上下文窗口 tokens（留空自动判断）", contextWindow) { input ->
                    contextWindow = input.filter { ch -> ch.isDigit() }
                }
                Text("思考强度", color = p.t3, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    reasoningEfforts.forEach { value ->
                        val on = value == effort
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (on) p.accent.copy(alpha = 0.14f) else p.hover)
                                .clickable { effort = value }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                reasoningLabelOf(value),
                                color = if (on) p.accent else p.t2,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                DialogField("最大输出 tokens（留空 = 不发送）", maxOutput) { input ->
                    maxOutput = input.filter { ch -> ch.isDigit() }
                }
                DialogField("温度（0-2，可小数，留空 = 不发送）", temperature) { input ->
                    temperature = sanitizeDecimalInput(input)
                }
                DialogField("Top P（0-1，可小数，留空 = 不发送）", topP) { input ->
                    topP = sanitizeDecimalInput(input)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("支持工具调用", color = p.t2, fontSize = 12.5.sp)
                        Text("关闭后请求里不带 tools", color = p.t3, fontSize = 10.5.sp)
                    }
                    Switch(checked = supportsTools, onCheckedChange = { supportsTools = it })
                }
                if (available.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        available.forEach { item ->
                            val selected = item == model
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) p.accent.copy(alpha = 0.14f) else p.hover)
                                    .clickable { model = item }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    item,
                                    color = if (selected) p.accent else p.t2,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Text(provider.hint, color = p.t3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // engine:// 走本地引擎，密钥由插件自己管；空 API Key 也算填好，否则点了没反应。
                val keyless = baseUrl.trim().startsWith("engine://")
                if ((keyless || apiKey.isNotBlank()) && model.isNotBlank()) {
                    onSave(
                        ModelProfile(
                            id = existing?.id ?: "m-${System.currentTimeMillis()}",
                            name = name.ifBlank { provider.label },
                            providerId = provider.id,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            isDefault = existing?.isDefault ?: false,
                            contextWindow = contextWindow.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                            reasoningEffort = sanitizeReasoningEffort(effort),
                            maxOutputTokens = maxOutput.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                            temperature = temperature.trim().toFloatOrNull()?.takeIf { it >= 0f } ?: -1f,
                            topP = topP.trim().toFloatOrNull()?.takeIf { it >= 0f } ?: -1f,
                            supportsTools = supportsTools,
                        ),
                    )
                }
            }) { Text("保存", color = p.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.t2) } },
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, color = p.t3, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = p.t1, fontSize = 13.5.sp),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.hover)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}
