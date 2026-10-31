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

/** 本地节选摘要（没有 LLM 摘要时的退路）。 */
private const val LOCAL_PREFIX = "历史摘要（本地节选，仅作历史资料）：\n"

/** LLM 结构化摘要的前缀。 */
private const val SUMMARY_PREFIX = "上下文摘要（早期轮次已压缩，原文不再回放）：\n"

/** 一次压缩的输入：哪些消息该被摘要掉，以及压缩前后的体量。 */
data class CompactionPlan(
    /** 被 LLM 摘要掉的早期消息（原文，按时间顺序）。 */
    val dropped: List<JsonObject>,
    /** 压缩前这段历史的字符数。 */
    val beforeChars: Int,
    /** 尾部原文保留的条数。 */
    val kept: Int,
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

    /**
     * sessionId -> LLM 生成的结构化上下文摘要。
     *
     * 以前超预算只会「丢掉最老消息 + 把剩下每条压成 80 字」——信息直接蒸发，模型忘了自己在干什么。
     * 现在超预算时把被丢弃的轮次交给 LLM 总结成五段（角色/任务/已决定/未完成/关键文件路径），
     * 存这里，每轮作为一条 system 消息插在保留段之前。持久化由调用方（ChatRepository）负责。
     */
    private val summaries = HashMap<String, String>()

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
            summaries.remove(sessionId)
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

        // e2. LLM 结构化摘要（有就带上）：比 80 字单行节选信息密度高得多，
        //     作为独立 system 段紧跟 system prompt，属于「保留段之前」。
        val storedSummary = summaries[sessionId]?.takeIf { it.isNotBlank() }
        val earlyMsgs = ArrayList<JsonObject>()
        if (storedSummary != null) earlyMsgs += message("system", SUMMARY_PREFIX + storedSummary)

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
        var used = sysText.length + earlyMsgs.sumOf { textOf(it).length } +
            digestMsgs.sumOf { textOf(it).length } +
            threadTail.sumOf { textOf(it).length } + pubTail.sumOf { textOf(it).length } +
            userTurn.sumOf { textOf(it).length }
        while (used > budgetChars) {
            when {
                digestMsgs.isNotEmpty() -> used -= textOf(digestMsgs.removeAt(0)).length
                pubTail.isNotEmpty() -> used -= textOf(pubTail.removeAt(0)).length
                threadTail.isNotEmpty() -> used -= textOf(threadTail.removeAt(0)).length
                // LLM 摘要最后才丢：它概括的是全部已丢弃历史，丢了等于把记忆清空。
                earlyMsgs.isNotEmpty() -> used -= textOf(earlyMsgs.removeAt(0)).length
                else -> break
            }
        }

        val out = ArrayList<JsonObject>(2 + earlyMsgs.size + digestMsgs.size + threadTail.size + pubTail.size + userTurn.size)
        out += sysMsg
        out += earlyMsgs
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
        // 有 LLM 摘要就优先用它（它概括的正是被丢掉的早期轮次）；没有才退回 80 字单行节选。
        val stored = synchronized(lock) { summaries[sessionId] }.orEmpty()
        val prefix = if (stored.isNotEmpty()) SUMMARY_PREFIX else LOCAL_PREFIX
        val tail = history.takeLast(keepRecent.coerceAtLeast(0)).toMutableList()
        var tailChars = tail.sumOf { textOf(it).length }
        // 尾部原文自身放不下时，从最旧一条开始让出摘要空间。
        val summaryReserve = minOf(prefix.length + 1, remaining)
        while (tail.isNotEmpty() && tailChars > remaining - summaryReserve) {
            tailChars -= textOf(tail.removeAt(0)).length
        }
        val older = history.dropLast(tail.size)
        val summaryBudget = minOf(DIGEST_BUDGET, remaining - tailChars)
        val summary = when {
            summaryBudget <= prefix.length -> ""
            stored.isNotEmpty() ->
                (prefix + stored).let { if (it.length <= summaryBudget) it else it.take(summaryBudget) }
            older.isEmpty() -> ""
            else -> {
                val lines = older.joinToString("\n") { m ->
                    val role = (m["role"] as? JsonPrimitive)?.content.orEmpty()
                    role + ": " + digest(textOf(m), 80)
                }
                (prefix + lines).take(summaryBudget)
            }
        }
        val out = listOf(sys) + (if (summary.isEmpty()) emptyList() else listOf(message("system", summary))) + tail + userMsg
        return ContextAssembly(out, fixed + tailChars + summary.length, full)
    }

    /** 当前生效的 LLM 摘要（空串表示还没压缩过）。 */
    fun summary(sessionId: String): String = synchronized(lock) { summaries[sessionId].orEmpty() }

    /** 写入/清空 LLM 摘要；持久化由调用方（ChatRepository）负责。 */
    fun setSummary(sessionId: String, text: String) {
        synchronized(lock) {
            if (text.isBlank()) summaries.remove(sessionId) else summaries[sessionId] = text.trim()
        }
    }

    /**
     * 压缩计划：不需要压缩返回 null；需要则给出「哪些早期消息该被 LLM 摘要掉」。
     *
     * 判定口径与 [assembleForSingleChat] 完全一致（full = system + 全部历史 + 本轮用户消息），
     * 所以「计划为 null」就等于「装配路径不会裁剪」，不会出现压了却没生效的空转。
     */
    fun planCompaction(
        sessionId: String,
        budgetChars: Int,
        system: String,
        history: List<JsonObject>,
        userMsg: List<JsonObject>,
        keepRecent: Int = 8,
    ): CompactionPlan? {
        val fixed = system.length + userMsg.sumOf { textOf(it).length }
        val full = fixed + history.sumOf { textOf(it).length }
        if (full <= budgetChars) return null
        // 以前这里还有一句「固定段（system + 本轮提问）自己就超预算 → return null」，
        // 结果是：真实会话的系统提示词带着全套工具说明，动辄几万字符，永远大于旧的 8k/24k 预算，
        // 于是 /compact 一律回「不需要压缩」——真压缩等于没接。压不了固定段，但历史仍然可压，
        // 所以只要还有可丢的更早轮次就给计划；固定段自己超窗口属于另一件事，由装配的 overBudget 直说。
        synchronized(lock) {
            val tail = history.takeLast(keepRecent.coerceAtLeast(0))
            val older = history.dropLast(tail.size)
            if (older.isEmpty()) return null
            return CompactionPlan(older, older.sumOf { textOf(it).length }, tail.size)
        }
    }

    /**
     * 压缩用的 LLM 请求正文：固定五段结构（角色/任务/已决定/未完成/关键文件路径），
     * 与桌面侧 /compact 的摘要形态对齐，两边都能直接读。
     */
    fun compactionPrompt(plan: CompactionPlan): String = buildString {
        append("把下面这段更早的对话压缩成结构化摘要，供后续轮次当作长期记忆使用。\n")
        append("只输出摘要本身，不要寒暄、不要复述原文。严格用这五段，每段 1-3 行：\n")
        append("角色：\n任务：\n已决定：\n未完成：\n关键文件路径：\n")
        append("关键文件路径只列确实出现过且仍然相关的路径；没有就写「无」。\n")
        append("\n--- 待压缩历史 ---\n")
        plan.dropped.forEach { m ->
            val role = (m["role"] as? JsonPrimitive)?.content.orEmpty()
            append(role).append(": ").append(textOf(m)).append('\n')
        }
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
