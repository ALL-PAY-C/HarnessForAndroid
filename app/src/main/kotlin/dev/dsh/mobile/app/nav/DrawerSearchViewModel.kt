package dev.dsh.mobile.app.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.repo.ChatSearchHit
import dev.dsh.mobile.core.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 抽屉搜索的一次查询结果：会话分区最多 5 条、聊天记录分区最多 30 条。 */
data class DrawerSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val sessions: List<SessionEntity> = emptyList(),
    val messages: List<ChatSearchHit> = emptyList(),
) {
    /** 有输入、不在查、两个分区都空：此时才显示「没有找到相关内容」。 */
    val noResult: Boolean
        get() = query.isNotBlank() && !searching && sessions.isEmpty() && messages.isEmpty()
}

/**
 * 抽屉的搜索状态机。query 与结果分两个字段存：
 * 输入框的 value 始终来自 [DrawerSearchState.query]，结果回填只写 sessions/messages，
 * 所以查询慢回来也不会把用户正在敲的字顶掉（输入不被结果刷新打断）。
 */
class DrawerSearchViewModel(private val repo: ChatRepository) : ViewModel() {

    private val _state = MutableStateFlow(DrawerSearchState())
    val state: StateFlow<DrawerSearchState> = _state.asStateFlow()

    /** 最近会话：归档的不进抽屉（抽屉是「随手打开」的入口，归档只从会话页找回）。 */
    val recent: StateFlow<List<Session>> = repo.observeSessions()
        .map { list -> list.filterNot { it.archived }.take(RECENT_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        // 防抖用「取消上一个 job + delay」，不引 FlowPreview 的 debounce。
        searchJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(searching = false, sessions = emptyList(), messages = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            val sessions = withContext(Dispatchers.IO) { repo.searchSessions(q, SESSION_LIMIT) }
            val messages = withContext(Dispatchers.IO) { repo.searchMessages(q, MESSAGE_LIMIT) }
            // 只有 query 仍是这一轮的字才回填：晚到的旧结果不许覆盖新一轮的输入。
            _state.update {
                if (it.query.trim() == q) {
                    it.copy(searching = false, sessions = sessions, messages = messages)
                } else {
                    it
                }
            }
        }
    }

    fun clear() = onQueryChange("")

    companion object {
        const val RECENT_LIMIT = 8
        const val SESSION_LIMIT = 5
        const val MESSAGE_LIMIT = 30
        const val DEBOUNCE_MS = 250L
    }
}
