package dev.dsh.mobile.app.nav

import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.app.BuildConfig
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.data.repo.ChatSearchHit
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.design.AppIconBadge
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotRemoveBadge
import dev.dsh.mobile.design.UiEditSlotPlusRow
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.pluginapi.DrawerEntry
import dev.dsh.mobile.pluginapi.SlotContribution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QQ 手机端形态的侧边栏：头部 + 搜索框 + 「常用 / 最近会话」或「会话 / 聊天记录」两态。
 * 搜索状态在 [DrawerSearchViewModel] 里，本文件只管画。
 */
@Composable
fun AppDrawer(
    runtime: RuntimeState,
    container: AppContainer,
    onPlugins: () -> Unit,
    onOpenSession: (String) -> Unit,
    entries: List<DrawerEntry> = emptyList(),
    onEntry: (DrawerEntry) -> Unit = {},
    // 槽位接线：抽屉里的「已放置项」就是 entries 自己（AppNav 已按 target 过滤过），
    // 所以这里只收候选 + 加删回调；slotPlaced 收下不用，保持和其它界面同一份契约。
    slotPlaced: List<SlotContribution> = emptyList(),
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
) {
    val p = LocalQq.current
    val vm: DrawerSearchViewModel = viewModel(
        factory = viewModelFactory { initializer { DrawerSearchViewModel(container.repo) } },
    )
    val search by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxHeight().width(300.dp).background(p.card)) {
        DrawerHeader(runtime)
        SearchField(query = search.query, onQueryChange = vm::onQueryChange, onClear = vm::clear)
        if (search.query.isBlank()) {
            BrowsePane(
                entries = entries,
                onEntry = onEntry,
                onPlugins = onPlugins,
                recent = recent,
                onOpenSession = onOpenSession,
                slotCandidates = slotCandidates,
                onSlotAdd = onSlotAdd,
                onSlotRemove = onSlotRemove,
                modifier = Modifier.weight(1f),
            )
        } else {
            SearchPane(search = search, onOpenSession = onOpenSession, modifier = Modifier.weight(1f))
        }
        DrawerFooter()
    }
}

@Composable
private fun DrawerHeader(runtime: RuntimeState) {
    val p = LocalQq.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(p.accent2, p.accent)))
            .statusBarsPadding()
            .padding(start = QqSpace.l(), end = QqSpace.l(), top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(56.dp)
            Spacer(Modifier.width(QqSpace.m()))
            Column {
                Text("DSH Mobile", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (runtime) {
                        is RuntimeState.Ready -> stringResource(DesignR.string.nav_drawer_runtime_ready)
                        is RuntimeState.Starting -> stringResource(DesignR.string.nav_drawer_runtime_starting)
                        is RuntimeState.Reconnecting -> stringResource(DesignR.string.nav_drawer_runtime_reconnecting)
                        is RuntimeState.Failed -> stringResource(DesignR.string.nav_drawer_runtime_failed)
                        RuntimeState.NotConfigured -> stringResource(DesignR.string.nav_drawer_runtime_unconfigured)
                    },
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = qqFontBody(12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    val p = LocalQq.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(DesignR.string.nav_search_placeholder),
                color = p.t3,
                fontSize = qqFontBody(13.5.sp),
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = p.t3, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(DesignR.string.nav_search_clear),
                        tint = p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = qqFontBody(14.sp), color = p.t1),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.line,
            focusedContainerColor = p.bg,
            unfocusedContainerColor = p.bg,
            cursorColor = p.accent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = QqSpace.m(), vertical = 10.dp),
    )
}

/** query 为空：常用入口 + 最近会话。 */
@Composable
private fun BrowsePane(
    entries: List<DrawerEntry>,
    onEntry: (DrawerEntry) -> Unit,
    onPlugins: () -> Unit,
    recent: List<Session>,
    onOpenSession: (String) -> Unit,
    slotCandidates: List<SlotEditItem> = emptyList(),
    onSlotAdd: (SlotEditItem) -> Unit = {},
    onSlotRemove: (SlotEditItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val p = LocalQq.current
    var pickingSlot by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
    LazyColumn(Modifier.fillMaxSize().padding(vertical = QqSpace.xs())) {
        item { SectionLabel(stringResource(DesignR.string.nav_drawer_section_common)) }
        // 编辑态：分组标题下方给一个「+」，点开复用本页已有的挑组件弹窗（加到抽屉）。
        if (SlotEditState.plusVisible(SlotEditState.Slot.DrawerGroup)) {
            item {
                UiEditSlotPlusRow(
                    slot = SlotEditState.Slot.DrawerGroup,
                    onClick = { pickingSlot = true },
                )
            }
        }
        items(entries) { entry ->
            // 插件摆进来的入口（id = "slot-<contributionId>"）在编辑态行尾带「−」；
            // 内置入口（pluginId == "app"）不给删。
            val contributionId = entry.id.removePrefix("slot-")
            val removable = entry.pluginId != "app" && entry.id.startsWith("slot-")
            DrawerItem(
                entry.icon ?: Icons.Filled.Extension,
                entry.label,
                onClick = { onEntry(entry) },
                onRemove = if (removable) {
                    {
                        onSlotRemove(
                            SlotEditItem(entry.pluginId, contributionId, entry.label, entry.subtitle),
                        )
                    }
                } else {
                    null
                },
            )
        }
        item {
            DrawerItem(
                Icons.Filled.Extension,
                stringResource(DesignR.string.plugins_page_title),
                onPlugins,
            )
        }
        item {
            Box(
                Modifier.padding(horizontal = 14.dp, vertical = QqSpace.s())
                    .fillMaxWidth().height(1.dp).background(p.line),
            )
        }
        item { SectionLabel(stringResource(DesignR.string.nav_drawer_section_recent)) }
        if (recent.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.ChatBubble, null, tint = p.t3, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(DesignR.string.nav_drawer_no_sessions),
                        color = p.t3,
                        fontSize = qqFontBody(12.5.sp),
                    )
                }
            }
        }
        items(recent) { session ->
            RecentSessionRow(session) { onOpenSession(session.id) }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
        if (pickingSlot) {
            SlotPickerDialog(
                title = stringResource(DesignR.string.nav_drawer_picker_title),
                items = slotCandidates,
                emptyHint = stringResource(DesignR.string.nav_drawer_picker_empty),
                onPick = { pickingSlot = false; onSlotAdd(it) },
                onDismiss = { pickingSlot = false },
            )
        }
    }
}

/** query 非空：会话命中 + 聊天记录命中，含加载中 / 空结果文案。 */
@Composable
private fun SearchPane(
    search: DrawerSearchState,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalQq.current
    when {
        search.searching -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(DesignR.string.nav_search_searching),
                color = p.t3,
                fontSize = qqFontBody(13.sp),
            )
        }

        search.noResult -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                stringResource(DesignR.string.common_no_result),
                stringResource(DesignR.string.nav_search_empty_hint),
            )
        }

        else -> LazyColumn(modifier.fillMaxWidth().padding(vertical = QqSpace.xs())) {
            if (search.sessions.isNotEmpty()) {
                item { SectionLabel(stringResource(DesignR.string.nav_search_section_sessions)) }
                items(search.sessions) { row ->
                    SessionHitRow(row, search.query) { onOpenSession(row.id) }
                }
            }
            if (search.messages.isNotEmpty()) {
                item { SectionLabel(stringResource(DesignR.string.nav_search_section_messages)) }
                items(search.messages) { hit ->
                    MessageHitRow(hit, search.query) { onOpenSession(hit.sessionId) }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t3,
        fontSize = qqFontBody(11.sp),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = QqSpace.xs()),
    )
}

@Composable
private fun RecentSessionRow(session: Session, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = QqSpace.s())
            .clip(QqShape.field())
            .clickable(onClick = onClick)
            .padding(horizontal = QqSpace.m(), vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { stringResource(DesignR.string.common_untitled_session) },
                    color = p.t1,
                    fontSize = qqFontBody(14.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(DesignR.string.common_pinned),
                        color = p.accent,
                        fontSize = qqFontBody(10.sp),
                    )
                }
            }
            Text(
                session.preview.ifBlank { stringResource(DesignR.string.common_no_messages) },
                color = p.t2,
                fontSize = qqFontBody(12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(QqSpace.s()))
        Text(relativeTime(session.updatedAt), color = p.t3, fontSize = qqFontBody(11.sp))
    }
}

@Composable
private fun SessionHitRow(row: SessionEntity, query: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = QqSpace.s())
            .clip(QqShape.field())
            .clickable(onClick = onClick)
            .padding(horizontal = QqSpace.m(), vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                highlight(
                    row.title.ifBlank { stringResource(DesignR.string.common_untitled_session) },
                    query,
                    p.accent,
                ),
                color = p.t1,
                fontSize = qqFontBody(14.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.preview.isNotBlank()) {
                Text(
                    row.preview,
                    color = p.t2,
                    fontSize = qqFontBody(12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(QqSpace.s()))
        Text(relativeTime(row.updatedAt), color = p.t3, fontSize = qqFontBody(11.sp))
    }
}

@Composable
private fun MessageHitRow(hit: ChatSearchHit, query: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = QqSpace.s())
            .clip(QqShape.field())
            .clickable(onClick = onClick)
            .padding(horizontal = QqSpace.m(), vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.sessionTitle.ifBlank { stringResource(DesignR.string.common_untitled_session) },
                    color = p.t1,
                    fontSize = qqFontBody(13.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    roleLabel(hit.role),
                    color = p.accent,
                    fontSize = qqFontBody(10.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(p.accent.copy(alpha = 0.12f))
                        .padding(horizontal = QqSpace.xs(), vertical = 1.dp),
                )
            }
            Text(
                highlight(hit.snippet, query, p.accent),
                color = p.t2,
                fontSize = qqFontBody(12.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(QqSpace.s()))
        Text(relativeTime(hit.at), color = p.t3, fontSize = qqFontBody(11.sp))
    }
}

@Composable
private fun DrawerFooter() {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Memory, null, tint = p.t3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(QqSpace.s()))
            Text(
                stringResource(DesignR.string.nav_drawer_runtime_footer),
                color = p.t3,
                fontSize = qqFontBody(11.sp),
            )
        }
        Spacer(Modifier.height(QqSpace.xs()))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, null, tint = p.t3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(QqSpace.s()))
            Text("v" + BuildConfig.VERSION_NAME.removePrefix("v"), color = p.t3, fontSize = qqFontBody(11.sp))
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** 非空 = 这一项是可摆放的插件入口；编辑态在行尾叠「−」。 */
    onRemove: (() -> Unit)? = null,
) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = QqSpace.s())
            .clip(QqShape.field())
            .clickable(onClick = onClick)
            .padding(horizontal = QqSpace.m(), vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = p.accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(QqSpace.m()))
        Text(
            label,
            color = p.t1,
            fontSize = qqFontBody(14.5.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null && SlotEditState.enabled) {
            Spacer(Modifier.width(QqSpace.s()))
            SlotRemoveBadge(onClick = onRemove)
        }
    }
}

/** 命中词用 accent 加粗；大小写不敏感，同一片段里多次命中都标。 */
private fun highlight(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var i = 0
    while (i < text.length) {
        val hit = text.indexOf(q, i, ignoreCase = true)
        if (hit < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, hit))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(text.substring(hit, hit + q.length))
        }
        i = hit + q.length
    }
}

// 改成 @Composable：角色名与相对时间都要走资源，而这两处都没有 Context 参数。
@Composable
private fun roleLabel(role: String): String = when (role.uppercase(Locale.ROOT)) {
    "USER" -> stringResource(DesignR.string.nav_role_user)
    "ASSISTANT" -> stringResource(DesignR.string.nav_role_assistant)
    "SYSTEM" -> stringResource(DesignR.string.nav_role_system)
    else -> role.ifBlank { stringResource(DesignR.string.nav_role_message) }
}

/** 刚刚 / N 分钟前 / N 小时前 / 昨天 / MM-dd。 */
@Composable
private fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return ""
    val diff = now - at
    return when {
        diff < 60_000L -> stringResource(DesignR.string.nav_time_just_now)
        diff < 3_600_000L -> stringResource(DesignR.string.nav_time_minutes, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(DesignR.string.nav_time_hours, (diff / 3_600_000L).toInt())
        diff < 172_800_000L -> stringResource(DesignR.string.nav_time_yesterday)
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(at))
    }
}

/**
 * 整页搜索：把抽屉里那套搜索框 + 结果列表原样搬出来复用，状态仍在 [DrawerSearchViewModel]，
 * 所以在抽屉里搜过的词切到整页还在，反过来也一样。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val p = LocalQq.current
    val vm: DrawerSearchViewModel = viewModel(
        factory = viewModelFactory { initializer { DrawerSearchViewModel(container.repo) } },
    )
    val search by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(p.bg)) {
        QqTopBar(
            leading = {
                IconBtn(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(DesignR.string.common_back),
                    onBack,
                )
            },
        ) {
            Text(
                stringResource(DesignR.string.nav_search_title),
                color = p.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SearchField(query = search.query, onQueryChange = vm::onQueryChange, onClear = vm::clear)
        if (search.query.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    stringResource(DesignR.string.nav_search_page_empty_title),
                    stringResource(DesignR.string.nav_search_page_empty_hint),
                )
            }
        } else {
            SearchPane(search = search, onOpenSession = onOpenSession, modifier = Modifier.weight(1f))
        }
    }
}
