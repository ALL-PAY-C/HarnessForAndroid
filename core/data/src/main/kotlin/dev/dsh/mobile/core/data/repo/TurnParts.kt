package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.ToolCall

/**
 * 流式三条事件各自的归并（T2），从 [ChatRepository.handleEvent] 提出来做纯函数，
 * 好在无 transport / 无 Room 的单测里直接驱动。
 *
 * 数据源严格分开，不许互相兜底：
 * - 正文 → [TurnState.text] / [AuthorPart.text]
 * - 思考（reasoning/thought 正文）→ [TurnState.thought] / [AuthorPart.thought]
 * - 工具调用 → [TurnState.tools] / [AuthorPart.tools]
 *
 * UI 侧「不显示思考正文」是渲染开关（thoughtsExpanded），与本管线无关：思考正文照旧
 * 一路落到 part.thought，展开时才有东西可看，关掉时只是不画出来。
 */
internal fun TurnState.applyText(authorId: String?, authorName: String?, text: String): TurnState =
    if (authorId == null) copy(text = this.text + text)
    else upsertAuthor(authorId, authorName) { it.copy(text = it.text + text) }

/**
 * 思考正文：带 authorId 的直接进该作者的 part；不带 authorId 且本轮只有一个 part 时，
 * 那个 part 就是唯一的发言人，思考归它（否则 thinking 只留在轮级 thought 字段里，
 * part 上的 thought 永远是空的，群聊气泡里也就永远没有思考）。
 * 多个 part 并存时不猜归属，留在轮级 thought。
 * ponytail: 只在「唯一 part」时归并；真要按段对应多作者，得让生产方带上 authorId，
 * 不在数据层靠位置猜。
 */
internal fun TurnState.applyThought(authorId: String?, authorName: String?, text: String): TurnState = when {
    authorId != null -> upsertAuthor(authorId, authorName) { it.copy(thought = it.thought + text) }
    parts.size == 1 -> copy(parts = parts.map { it.copy(thought = it.thought + text) })
    else -> copy(thought = this.thought + text)
}

/** 工具调用：同一 call.id 覆盖式更新（上游会先发 running 再发 completed）。 */
internal fun TurnState.applyToolCall(authorId: String?, authorName: String?, call: ToolCall): TurnState =
    if (authorId == null) {
        copy(tools = tools.filterNot { it.id == call.id } + call)
    } else {
        upsertAuthor(authorId, authorName) { p ->
            p.copy(tools = p.tools.filterNot { it.id == call.id } + call)
        }
    }

/**
 * 往本轮里写某个成员的一段输出。
 *
 * 关键：一个成员在一轮里可能多次开口（自动机的执行者/验收者会反复来），
 * 每次都要有自己的 [AuthorPart.seq]，否则第二轮会撞上第一轮的 part、
 * 落库时又被 (turnId, authorId) 判重拦掉，聊天里就只剩第一轮的气泡。
 */
internal fun TurnState.upsertAuthor(
    id: String,
    name: String?,
    block: (AuthorPart) -> AuthorPart,
): TurnState {
    val idx = parts.indexOfFirst { it.id == id }
    return if (idx >= 0) {
        // 名字可能晚到（ThoughtChunk 不带 authorName，MessageChunk 才带），
        // 已有 part 也要跟着刷新，否则气泡上永远显示 id 前 6 位。
        copy(
            parts = parts.map {
                if (it.id == id) block(it).let { p -> if (name != null && p.name != name) p.copy(name = name) else p } else it
            },
        )
    } else {
        val seq = partSeq[id] ?: 0
        copy(
            parts = parts + block(AuthorPart(id = id, name = name ?: id.take(6), seq = seq)),
            partSeq = partSeq + (id to seq + 1),
        )
    }
}
