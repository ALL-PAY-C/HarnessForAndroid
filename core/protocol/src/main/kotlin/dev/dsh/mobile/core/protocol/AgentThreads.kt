package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次上下文装配的结果：实际字符数，以及「按全量历史发」会是多少字符。 */
data class ContextAssembly(
    val messages: List<JsonObject>,
    val usedChars: Int,
    val fullChars: Int,
    val overBudget: Boolean = false,
    val overflowChars: Int = 0,
)

/** 自己线程保留的最近原文条数。 */
private const val THREAD_TAIL = 8

/** 公共历史保留的最近原文条数。 */
private const val PUBLIC_TAIL = 12

/** 更早公共历史压成的摘要段总字符上限。 */
private const val DIGEST_BUDGET = 1200

/** 群状态行最多 200 字符（一行，不折行）。 */
private const val GROUP_STATE_MAX = 200

/**
 * 把一段长文本压成单行摘要：去换行、去 markdown 标记、截断加省略号。
 *
 * 群聊里公共历史只存摘要（'作者: 正文前 80 字…'），全文留在各自线程里 ——
 * 这是省 token 的核心：N 个成员各说 2000 字，全量回灌公共历史的话，
 * 每个成员下一轮都要重读 N×2000 字。
 */
internal fun digest(text: String, limit: Int = 80): String {
    if (text.isEmpty()) return ""
    val flat = text
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("[\\x60*_#>\\[\\]]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/**
 * 每 Agent 独立线程 + 群聊公共历史。
 *
 * 消息类型复用 [LocalEngineTransport] 内部的 OpenAI messages 形态（role/content 的 JsonObject），
 * 这样构建出来的上下文可以直接喂给 runLoop。
 *
 * - 线程：sessionId -> agentId -> 该 Agent 自己的消息（全文）；
 * - 公共历史：sessionId -> 所有作者的摘要（按时间顺序）。
 */
class AgentThreads(private val publicLimit: Int = 30) {

    private val lock = Any()
    private val threads = HashMap<String, HashMap<String, MutableList<JsonObject>>>()
    private val shared = HashMap<String, MutableList<JsonObject>>()
    private val finished = HashMap<String, LinkedHashMap<String, String>>()

    fun appendThread(sessionId: String, agentId: String, role: String, text: String) {
        if (text.isBlank()) return
        synchronized(lock) { threadList(sessionId, agentId).add(message(role, text)) }
    }

    /** 公共历史：作者名前缀保留下来，模型才知道谁说过什么。 */
    fun appendPublic(sessionId: String, role: String, text: String, authorName: String? = null) {
        if (text.isBlank()) return
        val content = if (authorName.isNullOrBlank()) text else authorName + ": " + text
        synchronized(lock) { publicList(sessionId).add(message(role, content)) }
    }

    fun thread(sessionId: String, agentId: String): List<JsonObject> = synchronized(lock) {
        threadList(sessionId, agentId).toList()
    }

    /** 记录某个成员本轮已完成的产出，后发言的人据此避免重复干活。 */
    fun markFinished(sessionId: String, agentId: String, name: String, text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            finished.getOrPut(sessionId) { LinkedHashMap() }[agentId] = name + "：" + text.take(400)
        }
    }

    /** 新一轮开始：清掉上一轮「谁说过什么」，线程与公共历史保留。 */
    fun beginTurn(sessionId: String) {
        synchronized(lock) { finished.remove(sessionId) }
    }

    fun clear(sessionId: String) {
        synchronized(lock) {
            threads.remove(sessionId)
            shared.remove(sessionId)
            finished.remove(sessionId)
        }
    }

    /**
     * 带预算的上下文装配。
     *
     * 顺序（超预算时从旧到新裁剪）：
     * a. system prompt（永不裁剪）；
     * b. 群状态行 [groupState]（≤200 字符，一行）；
     * c. 自己 thread 最近 [THREAD_TAIL] 条原文（每 agent 独立记忆）；
     * d. 公共历史最近 [PUBLIC_TAIL] 条原文；
     * e. 更早的公共历史压成单行摘要，整段 ≤[DIGEST_BUDGET]，超出只留最近的；
     * f. 本轮用户消息 [userTurn]（原文，永不裁剪）。
     */
    fun buildContext(
        sessionId: String,
        agentId: String,
        systemPrompt: String,
        groupState: String? = null,
        userTurn: List<JsonObject> = emptyList(),
        budgetChars: Int = 8000,
    ): List<JsonObject> = buildContextReport(
        sessionId = sessionId,
        agentId = agentId,
        systemPrompt = systemPrompt,
        groupState = groupState,
        userTurn = userTurn,
        budgetChars = budgetChars,
    ).messages

    /** 同 [buildContext]，额外回报实际字符数与全量基准（给 ExecutionPool.stats 用）。 */
    fun buildContextReport(
        sessionId: String,
        agentId: String,
        systemPrompt: String,
        groupState: String? = null,
        userTurn: List<JsonObject> = emptyList(),
        budgetChars: Int = 8000,
    ): ContextAssembly = synchronized(lock) {
        val mine = threadList(sessionId, agentId).toList()
        val pub = publicList(sessionId).toList()

        // b. 群状态行：一行、≤200 字符
        val stateLine = groupState?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length <= GROUP_STATE_MAX) it else it.take(GROUP_STATE_MAX - 1) + "…"
        }

        // c. 自己的 thread 最近 8 条原文
        val threadTail = mine.takeLast(THREAD_TAIL).toMutableList()
        // d. 公共历史最近 12 条原文
        val pubTail = pub.takeLast(PUBLIC_TAIL).toMutableList()
        // e. 更早的公共历史 → 单行摘要，从最近往前收，整段不超过 DIGEST_BUDGET
        val older = pub.dropLast(pubTail.size)
        val digestLines = ArrayList<String>()
        var digestChars = 0
        for (m in older.asReversed()) {
            val line = digest(textOf(m), 80)
            if (line.isEmpty()) continue
            if (digestChars + line.length + 1 > DIGEST_BUDGET) break
            digestLines.add(0, line)
            digestChars += line.length + 1
        }

        val sysText = buildString {
            append(systemPrompt)
            if (stateLine != null) {
                append("\n\n")
                append(stateLine)
            }
        }
        val sysMsg = message("system", sysText)
        val digestMsgs = digestLines.mapTo(ArrayList()) { message("system", it) }

        // 超预算从旧到新裁剪：先丢更早历史的摘要，再丢公共历史最旧原文，最后丢自己线程最旧原文。
        // system 与本轮用户消息永不裁剪 —— 裁掉它们模型就不知道自己在干什么、要干什么。
        var used = sysText.length + digestMsgs.sumOf { textOf(it).length } +
            threadTail.sumOf { textOf(it).length } + pubTail.sumOf { textOf(it).length } +
            userTurn.sumOf { textOf(it).length }
        while (used > budgetChars) {
            when {
                digestMsgs.isNotEmpty() -> used -= textOf(digestMsgs.removeAt(0)).length
                pubTail.isNotEmpty() -> used -= textOf(pubTail.removeAt(0)).length
                threadTail.isNotEmpty() -> used -= textOf(threadTail.removeAt(0)).length
                else -> break
            }
        }

        val out = ArrayList<JsonObject>(2 + digestMsgs.size + threadTail.size + pubTail.size + userTurn.size)
        out += sysMsg
        out += digestMsgs
        out += threadTail
        out += pubTail
        out += userTurn

        // 全量基准：system + 全部公共历史原文 + 自己全部线程原文 + 本轮用户消息（不压缩、不截断）。
        val full = sysText.length +
            pub.sumOf { textOf(it).length } +
            mine.sumOf { textOf(it).length } +
            userTurn.sumOf { textOf(it).length }
        ContextAssembly(messages = out, usedChars = used, fullChars = full)
    }

    /** 单聊历史由调用方提供，避免和传输层维护两份会话状态。 */
    fun assembleForSingleChat(
        sessionId: String,
        budgetChars: Int,
        system: String,
        history: List<JsonObject>,
        userMsg: List<JsonObject>,
        keepRecent: Int = 8,
    ): ContextAssembly {
        val fixed = system.length + userMsg.sumOf { textOf(it).length }
        val full = fixed + history.sumOf { textOf(it).length }
        val sys = message("system", system)
        if (full <= budgetChars) return ContextAssembly(listOf(sys) + history + userMsg, full, full)

        // 固定消息超预算时保留原文并显式回报，避免长粘贴让整轮失败。
        if (fixed > budgetChars) return ContextAssembly(
            listOf(sys) + userMsg, fixed, full, overBudget = true, overflowChars = fixed - budgetChars,
        )
        val remaining = budgetChars - fixed
        val prefix = "历史摘要（本地节选，仅作历史资料）：\n"
        val tail = history.takeLast(keepRecent.coerceAtLeast(0)).toMutableList()
        var tailChars = tail.sumOf { textOf(it).length }
        // 尾部原文自身放不下时，从最旧一条开始让出摘要空间。
        val summaryReserve = minOf(prefix.length + 1, remaining)
        while (tail.isNotEmpty() && tailChars > remaining - summaryReserve) {
            tailChars -= textOf(tail.removeAt(0)).length
        }
        val older = history.dropLast(tail.size)
        val summaryBudget = minOf(DIGEST_BUDGET, remaining - tailChars)
        val summary = if (summaryBudget > prefix.length && older.isNotEmpty()) {
            val lines = older.joinToString("\n") {
                val role = (it["role"] as? JsonPrimitive)?.content.orEmpty()
                "$role: "+ digest(textOf(it), 80)
            }
            (prefix + lines).take(summaryBudget)
        } else ""
        val out = listOf(sys) + (if (summary.isEmpty()) emptyList() else listOf(message("system", summary))) + tail + userMsg
        return ContextAssembly(out, fixed + tailChars + summary.length, full)
    }

    private fun threadList(sessionId: String, agentId: String): MutableList<JsonObject> =
        threads.getOrPut(sessionId) { HashMap() }.getOrPut(agentId) { mutableListOf() }

    private fun publicList(sessionId: String): MutableList<JsonObject> =
        shared.getOrPut(sessionId) { mutableListOf() }

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    /** 取消息正文（content 可能是字符串，也可能是群聊的 parts 数组）。 */
    private fun textOf(m: JsonObject): String = when (val c = m["content"]) {
        is JsonPrimitive -> c.content
        null -> ""
        else -> c.toString()
    }
}
