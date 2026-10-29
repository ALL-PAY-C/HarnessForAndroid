package dev.dsh.mobile.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.common.DshResult
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.settings.ModelProfile
import dev.dsh.mobile.core.data.settings.ModelStore
import dev.dsh.mobile.core.data.settings.AppSettings
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话列表里的一层「文件夹」。
 *
 * 分组本身就是会话的 `group` 字符串，这里用 `/` 表示层级（`工作/紧急`），
 * 于是「嵌套分区」不需要新表：父文件夹是子文件夹路径的前缀，祖先按需补出来。
 */
data class FolderRow(
    val path: String,
    val label: String,
    val depth: Int,
    val sessions: List<Session>,
    /** 直接子文件夹个数；0 表示这是叶子。 */
    val children: Int,
)

data class SessionsUiState(
    val groups: List<Pair<String, List<Session>>> = emptyList(),
    val folders: List<FolderRow> = emptyList(),
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val settings: AppSettings = AppSettings.DEFAULT,
    val runtime: RuntimeState = RuntimeState.NotConfigured,
    val busy: Boolean = false,
    val error: String? = null,
    val collapsed: Set<String> = emptySet(),
    val knownGroups: List<String> = emptyList(),
    val archiveCount: Int = 0,
    val sessionCountByFolder: Map<String, Int> = emptyMap(),
    /** 是否已经拿到过第一帧真实数据。false 时不能显示「还没有会话」。 */
    val loaded: Boolean = false,
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()

    /** Grouped sections are collapsible except the single "全部" fallback list. */
    fun isCollapsible(name: String): Boolean = name != ALL_GROUP

    companion object {
        const val ALL_GROUP = "全部"
        const val PINNED_GROUP = "置顶"
        const val ARCHIVE_GROUP = "归档"

        /** 固定货架名，用户分组不得占用（否则列表 key 重复）。 */
        val RESERVED_GROUPS = setOf(ALL_GROUP, PINNED_GROUP, ARCHIVE_GROUP)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val repo: ChatRepository,
    private val settingsStore: SettingsStore,
    private val modelStore: ModelStore,
    private val cwdProvider: () -> String,
) : ViewModel() {

    /** Agent（模型接入）列表，用于新建会话时的选择器。 */
    val models: StateFlow<List<ModelProfile>> = modelStore.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val collapsed = MutableStateFlow<Set<String>>(
        setOf(SessionsUiState.ARCHIVE_GROUP),
    )

    init {
        // 折叠状态写进 DataStore：以前只活在 VM 里，进程被杀/重启后分组又全展开，
        // 用户会以为「点不到收起」。saved 为空时沿用默认（归档默认收起）。
        viewModelScope.launch {
            val saved = runCatching { settingsStore.settings.first().collapsedGroups.toSet() }
                .getOrElse { emptySet() }
            if (saved.isNotEmpty()) collapsed.value = saved
        }
    }

    private data class Status(
        val busy: Boolean,
        val error: String?,
        val runtime: RuntimeState,
        val collapsed: Set<String>,
    )

    private val runtime: StateFlow<RuntimeState> = repo.transport
        .flatMapLatest { t -> t?.state ?: kotlinx.coroutines.flow.flowOf(RuntimeState.NotConfigured) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RuntimeState.NotConfigured)

    val uiState: StateFlow<SessionsUiState> = combine(
        repo.observeSessions(),
        settingsStore.settings,
        query,
        selected,
        combine(busy, error, runtime, collapsed) { b, e, r, c -> Status(b, e, r, c) },
    ) { sessions, settings, q, sel, status ->
        val filtered = if (q.isBlank()) sessions else sessions.filter {
            it.title.contains(q, true) || it.preview.contains(q, true) || it.cwd.contains(q, true)
        }
        val pinned = filtered.filter { it.pinned && !it.archived }
        val archived = filtered.filter { it.archived }
        val rest = filtered.filter { !it.pinned && !it.archived }
        val folders = buildFolders(rest, settings.customFolders)
        val groups = buildList {
            if (pinned.isNotEmpty()) add(SessionsUiState.PINNED_GROUP to pinned)
            if (settings.groupSessions) {
                // 分组即路径：`工作/紧急` 就是「工作」下的「紧急」。列表按路径排序输出，
                // 同级连续、父在子前，UI 只需按 '/' 的个数缩进就能画出文件夹树。
                folders.forEach { f ->
                    val name = folderGroupKey(f.path)
                    add(name to f.sessions)
                }
            } else if (rest.isNotEmpty()) {
                add(SessionsUiState.ALL_GROUP to rest)
            }
            // The archive is a permanent, collapsed-by-default shelf: keeping it
            // in the list even when empty means "where did my archived chats go"
            // never becomes a question, and the shelf stays reachable.
            add(SessionsUiState.ARCHIVE_GROUP to archived)
        }
        val known = (sessions.map { it.group.ifBlank { Session.DEFAULT_GROUP } } + settings.customFolders)
            .filter { it.isNotBlank() }
            .distinct()
        SessionsUiState(
            groups = groups,
            folders = folders,
            query = q,
            selected = sel,
            settings = settings,
            runtime = status.runtime,
            busy = status.busy,
            error = status.error,
            collapsed = status.collapsed,
            knownGroups = known,
            archiveCount = archived.size,
            sessionCountByFolder = sessions.groupingBy { it.group }.eachCount(),
            loaded = true,
        )
        // Eagerly：从聊天页返回时订阅会重来一次，WhileSubscribed 会让首帧迟到，
        // 期间 UI 拿到的是初始空状态 —— 那就是「返回后偶发显示还没有会话」的来源。
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    fun setQuery(q: String) { query.value = q }

    fun toggleSelect(id: String) {
        selected.value = selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { selected.value = emptySet() }

    fun refresh() {
        viewModelScope.launch {
            busy.value = true
            when (val r = repo.refreshSessions(cwdProvider())) {
                is DshResult.Err -> error.value = r.message
                is DshResult.Ok -> error.value = null
            }
            busy.value = false
        }
    }

    fun createSession(cwd: String? = null, onCreated: (String) -> Unit) =
        createChat("", null, emptyList(), cwd = cwd, onCreated = onCreated)

    /**
     * 新建聊天：普通会话绑一个 agent，群聊拉一个或多个（同一 agent 可重复）。
     * [agentId] 只在普通会话里使用。
     */
    fun createChat(
        title: String,
        agentId: String?,
        participants: List<Participant>,
        group: String = "",
        cwd: String? = null,
        onCreated: (String) -> Unit,
    ) {
        val workspace = cwd?.takeIf { it.isNotBlank() } ?: cwdProvider()
        // 没选 agent 就用默认的：否则新会话发消息时没有模型可绑，看起来像「默认 agent 没用」。
        val resolvedAgent = agentId
            ?: models.value.firstOrNull { it.isDefault }?.id
            ?: models.value.firstOrNull()?.id
        viewModelScope.launch {
            busy.value = true
            val result = if (participants.isEmpty()) {
                repo.createSession(workspace, resolvedAgent)
            } else {
                repo.createGroup(workspace, title, participants)
            }
            when (result) {
                is DshResult.Ok -> {
                    if (title.isNotBlank() && participants.isEmpty()) {
                        repo.rename(result.value.id, title.trim())
                    }
                    if (group.isNotBlank()) repo.setGroup(result.value.id, group.trim())
                    onCreated(result.value.id)
                }
                is DshResult.Err -> error.value = result.message
            }
            busy.value = false
        }
    }

    fun archive(id: String, archived: Boolean) = viewModelScope.launch { repo.setArchived(id, archived) }

    fun moveToGroup(id: String, group: String) = viewModelScope.launch {
        repo.setGroup(id, group.trim())
    }

    /** 批量移动：选中的会话一次性挪进某个分组，完成后退出多选。 */
    fun moveSelectedTo(group: String) = viewModelScope.launch {
        val target = group.trim().ifBlank { Session.DEFAULT_GROUP }
        selected.value.forEach { repo.setGroup(it, target) }
        selected.value = emptySet()
    }

    fun toggleGroup(name: String) {
        val next = collapsed.value.let { if (name in it) it - name else it + name }
        collapsed.value = next
        viewModelScope.launch {
            runCatching { settingsStore.setCollapsedGroups(next.toList()) }
        }
    }

    fun archiveSelected() = viewModelScope.launch {
        selected.value.forEach { repo.setArchived(it, true) }
        selected.value = emptySet()
    }

    /** 批量置顶：全部改完再退出多选，避免每行各触发一次重组。 */
    fun pinSelected(pinned: Boolean = true) = viewModelScope.launch {
        selected.value.forEach { repo.setPinned(it, pinned) }
        selected.value = emptySet()
    }

    /** 全选当前可见会话（分组、归档都算）。 */
    fun selectAll(ids: Collection<String>) { selected.value = ids.toSet() }

    fun rename(id: String, title: String) = viewModelScope.launch { repo.rename(id, title) }

    fun delete(id: String) = viewModelScope.launch {
        repo.delete(id)
        selected.value = selected.value - id
    }

    fun deleteSelected() = viewModelScope.launch {
        selected.value.forEach { repo.delete(it) }
        selected.value = emptySet()
    }

    fun dismissError() { error.value = null }

    // ── 文件夹（分组）管理 ───────────────────────────────────────────────
    // 分组不是表，是 Session.group 字符串按 '/' 切出来的路径。所以：
    //  · 新建文件夹 = 在 customFolders 里占位，空文件夹也能存在、也能删；
    //  · 删除文件夹 = 组内会话归档（不是退回「最近」，那样等于变相泄漏）；
    //  · 嵌套 = 路径本身就是层级，父目录由子路径自动补出来。

    /** 新建一个文件夹；[parent] 非空时建在它下面，形成嵌套。 */
    fun createFolder(name: String, parent: String = "") {
        // 名字里带 '/' 就是一次性建多层（work/urgent）。以前把 '/' 换成空格，
        // 结果用户输入 work/urgent 得到的是一个叫「work urgent」的平铺文件夹。
        val leaf = cleanPath(name)
        if (leaf.isBlank()) return
        val path = if (parent.isBlank()) leaf else parent.trimEnd('/') + "/" + leaf
        viewModelScope.launch {
            val current = runCatching { settingsStore.settings.first().customFolders }.getOrElse { emptyList() }
            if (path !in current) runCatching { settingsStore.setCustomFolders(current + path) }
        }
    }

    /** 删除文件夹：组内会话全部归档，子文件夹里的也一起。 */
    fun deleteFolder(path: String, onResult: (Result<Int>) -> Unit = {}) = viewModelScope.launch {
        val result = try {
            val prefix = path.trimEnd('/') + "/"
            val targets = repo.observeSessions().first()
                .filter { it.group == path || it.group.startsWith(prefix) }
                .map { it.id }
            targets.forEach { repo.setArchived(it, true) }
            val current = settingsStore.settings.first().customFolders
            settingsStore.setCustomFolders(current.filterNot { it == path || it.startsWith(prefix) })
            Result.success(targets.size)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        onResult(result)
    }

    fun renameFolder(from: String, to: String) = viewModelScope.launch {
        val leaf = cleanPath(to)
        if (leaf.isBlank() || leaf == from) return@launch
        val parent = from.substringBeforeLast('/', "")
        val target = if (parent.isBlank()) leaf else parent + "/" + leaf
        repo.observeSessions().first()
            .filter { it.group == from || it.group.startsWith(from + "/") }
            .forEach { repo.setGroup(it.id, it.group.replaceFirst(from, target)) }
        runCatching {
            val current = settingsStore.settings.first().customFolders
            settingsStore.setCustomFolders(current.map { if (it == from) target else it.replaceFirst(from + "/", target + "/") })
        }
    }
}

/** 规范文件夹名：按 '/' 分段、丢空段、段内首尾空白剪掉。 */
internal fun folderGroupKey(path: String): String =
    if (path.trimEnd('·') in SessionsUiState.RESERVED_GROUPS) path + "·" else path

internal fun folderPathFromGroup(key: String): String =
    if (key.endsWith('·') && key.trimEnd('·') in SessionsUiState.RESERVED_GROUPS) key.dropLast(1) else key

internal fun folderSessionCount(counts: Map<String, Int>, path: String): Int =
    counts.filterKeys { it == path || it.startsWith(path.trimEnd('/') + "/") }.values.sum()

internal fun cleanPath(raw: String): String =
    raw.split('/').map { it.trim() }.filter { it.isNotBlank() }.joinToString("/")

/**
 * 把「会话 + 显式文件夹」拍平成可渲染的行。
 *
 * 规则：先按 '/' 切路径求所有祖先，父文件夹的 [FolderRow.sessions] 只装直属会话，
 * 子目录单独成行并由 [FolderRow.depth] 缩进 —— 这样折叠父目录时整棵子树一起收。
 */
internal fun buildFolders(sessions: List<Session>, explicit: List<String>): List<FolderRow> {
    val paths = LinkedHashSet<String>()
    sessions.forEach { s ->
        val g = s.group.ifBlank { Session.DEFAULT_GROUP }
        if (g.isBlank()) return@forEach
        val parts = g.trim('/').split('/').filter { it.isNotBlank() }
        var acc = ""
        parts.forEach { seg ->
            acc = if (acc.isEmpty()) seg else acc + "/" + seg
            paths += acc
        }
    }
    explicit.map { it.trim('/') }.filter { it.isNotBlank() }.forEach { p ->
        var acc = ""
        p.split('/').filter { it.isNotBlank() }.forEach { seg ->
            acc = if (acc.isEmpty()) seg else acc + "/" + seg
            paths += acc
        }
    }
    val direct = sessions.groupBy { it.group.ifBlank { Session.DEFAULT_GROUP }.trim('/') }
    val childrenOf = { path: String ->
        paths.count { it.startsWith(path + "/") && !it.removePrefix(path + "/").contains('/') }
    }
    return paths.sorted().map { path ->
        FolderRow(
            path = path,
            label = path.substringAfterLast('/'),
            depth = path.count { it == '/' },
            sessions = direct[path].orEmpty(),
            children = childrenOf(path),
        )
    }
}
