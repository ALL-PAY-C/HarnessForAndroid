package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.protocol.digest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊房间账本（T5.3，规格 docs/design/group-ledger.md）。
 *
 * 为什么要有它：主持人每轮只看 transcript + 成员状态 + 预算，手里没有「谁还欠什么」，
 * 于是同一个待办被反复讨论、被打回的意见下一轮就消失。账本是房间的唯一事实来源：
 * 任务、未解问题、结论都进账本，主持人只读它，成员状态里的待办数也从这里数。
 *
 * 三条硬约束（规格 §2/§6）：
 * 1. 不膨胀：open 任务 20 / 未解问题 10 / 事实 20，超了丢最旧的；单房间总量 50 条封顶。
 * 2. 去重键 = 去空白标题前 60 字，同一件事换个说法也认得出。
 * 3. 只装摘要：标题一律先过 digest(120)，不带成员上下文、不带模型名。
 */

/** 账本条目状态。同一时刻只占一个；Done / Reworked 都不再是 Open。 */
enum class LedgerState { Open, Done, Reworked, Dropped }

/** 账本条目：一行摘要 + 归属，够主持人读、够 UI 显示，别的一概不装。 */
data class LedgerItem(
    /** 任务 id（= AgentTask.id）；问题 / 事实用 q-<digest8> / f-<digest8>。 */
    val id: String,
    /** 进主持人输入的一行，已压到 <= 120 字。 */
    val title: String,
    /** 成员 id，用于 MemberState.pendingTasks；问题 / 事实为 null。 */
    val ownerId: String?,
    val state: LedgerState,
    /** 创建轮次。 */
    val round: Int,
)

/**
 * 给 UI 的只读快照（feature/chat 的「待办 N」胶囊与 LedgerSheet 直接吃它）。
 *
 * 刻意不复用 [LedgerView]：那是主持人 prompt 的输入契约（ModeratorScheduler.kt 冻结），
 * 只有标题字符串、没有负责人与状态色，UI 要按状态画点就得有结构化条目。
 */
data class LedgerSnapshot(
    val open: List<LedgerItem> = emptyList(),
    val questions: List<String> = emptyList(),
    val done: List<LedgerItem> = emptyList(),
    val reworkCount: Int = 0,
) {
    /** 胶囊上的数字：未完成任务 + 未解问题。0 时 UI 不渲染。 */
    val pending: Int get() = open.size + questions.size

    val isEmpty: Boolean get() = open.isEmpty() && questions.isEmpty() && done.isEmpty()
}

/** 账本上限（规格 §2，改这里的数字就是改验收）。 */
internal const val MAX_OPEN_TASKS = 20
internal const val MAX_OPEN_QUESTIONS = 10
internal const val MAX_FACTS = 20
internal const val MAX_DONE_KEPT = 20

/** 去重键长度：去空白后前 60 字。 */
internal const val DEDUPE_CHARS = 60

/** 单条标题上限（规格 §2：已 compress 到 <=120 字）。 */
internal const val LEDGER_TITLE_CHARS = 120

/** 扫自由文本时的单行长度窗口（规格 §4）。 */
internal const val LEDGER_LINE_MIN = 8
internal const val LEDGER_LINE_MAX = 200

/** 每轮最多记几条问题 / 事实（规格 §4：保守，宁可漏）。 */
internal const val QUESTIONS_PER_ROUND = 2
internal const val FACTS_PER_ROUND = 2

/** 摘要一行的硬上限，别把主持人预算吃光。 */
internal const val LEDGER_DIGEST_CHARS = 240

internal val QUESTION_HINTS = listOf("待确认", "需要确认", "待补充", "缺信息")
internal val FACT_HINTS = listOf("结论", "确认", "已修复", "根因")

/** 去重键：去掉所有空白后的前 60 字。 */
internal fun dedupeKey(title: String): String = title.filterNot { it.isWhitespace() }.take(DEDUPE_CHARS)

/** 「（无补充）」这类占位：没有信息量，任何写入点都直接跳过。 */
internal fun isNoAddText(text: String): Boolean {
    val t = text.trim().trim('（', '）', '(', ')', '。', '.', ' ')
    return t.isEmpty() || t == "无补充" || t.equals("no add", ignoreCase = true)
}

/** 任务标题 = prompt 首行（规格 §2）。 */
internal fun titleOfPrompt(prompt: String): String =
    prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** 从一段自由文本里抽问题：行尾问号或含待确认类词，最多 [limit] 条。 */
internal fun questionsOf(text: String, limit: Int = QUESTIONS_PER_ROUND): List<String> =
    scanLedgerLines(text, limit) { line ->
        line.endsWith("？") || line.endsWith("?") || QUESTION_HINTS.any { line.contains(it) }
    }

/** 从一段自由文本里抽结论：行首或冒号后含结论类词，最多 [limit] 条。 */
internal fun factsOf(text: String, limit: Int = FACTS_PER_ROUND): List<String> =
    scanLedgerLines(text, limit) { line ->
        val body = stripListMarker(line)
        FACT_HINTS.any { body.startsWith(it) } ||
            FACT_HINTS.any { body.substringAfter('：', "").contains(it) || body.substringAfter(':', "").contains(it) }
    }

/** 去掉行首的列表符号（- * 1. 、），只看正文。 */
private fun stripListMarker(line: String): String =
    line.trimStart('-', '*', '·', '•', ' ', '\t').trimStart('1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', '、', ')', '）', ' ')

private fun scanLedgerLines(text: String, limit: Int, hit: (String) -> Boolean): List<String> {
    if (limit <= 0 || text.isBlank()) return emptyList()
    val out = ArrayList<String>(limit)
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.length < LEDGER_LINE_MIN || line.length > LEDGER_LINE_MAX) continue
        if (isNoAddText(line)) continue
        if (!hit(line)) continue
        out.add(line)
        if (out.size >= limit) break
    }
    return out
}

/**
 * 一个房间的账本。
 *
 * 线程模型与 GroupRunner 一致：所有写入都在群聊自己的调度协程里，
 * 读取（UI 快照）走 [RoomLedgers] 的 StateFlow，不在锁里做 IO。
 */
internal class RoomLedger(private val sessionId: String) {

    /** 任务：含已完成的（UI 要折叠显示最近几条），key = taskId。 */
    private val tasks = LinkedHashMap<String, LedgerItem>()

    /** 未解问题：key = q-<digest8>。 */
    private val questions = LinkedHashMap<String, LedgerItem>()

    /** 事实 / 结论：key = f-<digest8>，进主持人摘要的事实层。 */
    private val facts = LinkedHashMap<String, LedgerItem>()

    /** 累计完成条数（条目被上限挤掉也照数）。 */
    private var doneTotal = 0

    /** 累计打回次数。 */
    private var reworks = 0

    /** 本轮（这一次用户消息触发的一趟）完成数 / 打回数，散会播报用。 */
    private var turnDone = 0
    private var turnRework = 0

    val turnDoneCount: Int get() = turnDone
    val turnReworkCount: Int get() = turnRework

    /** 新的一轮用户消息开始：清掉本轮计数，累计量与条目都留着（跨轮存活）。 */
    fun beginTurn() {
        turnDone = 0
        turnRework = 0
    }

    /** 派活前入池即记账。已完成的老条目不动，同一任务 id 或同一去重键不重复入账。 */
    fun mergeTasks(tasks: List<AgentTask>, round: Int) {
        if (tasks.isEmpty()) return
        tasks.forEach { task ->
            if (this.tasks.containsKey(task.id)) return@forEach
            val title = digest(titleOfPrompt(task.prompt), LEDGER_TITLE_CHARS)
            if (title.isBlank() || isNoAddText(title)) return@forEach
            val key = dedupeKey(title)
            if (this.tasks.values.any { dedupeKey(it.title) == key }) return@forEach
            this.tasks[task.id] = LedgerItem(task.id, title, task.agentId, LedgerState.Open, round)
        }
        trim()
        publish()
    }

    /** 跑成了：Done。已经从 Done 的不重复计数，Reworked 的就此收敛。 */
    fun markDone(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        var changed = false
        taskIds.forEach { id ->
            val item = tasks[id] ?: return@forEach
            if (item.state == LedgerState.Done) return@forEach
            tasks[id] = item.copy(state = LedgerState.Done)
            doneTotal++
            turnDone++
            changed = true
        }
        if (changed) publish()
    }

    /** 被打回：Reworked（未完成，下一轮主持人看得见）。 */
    fun markRework(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        var changed = false
        taskIds.forEach { id ->
            val item = tasks[id] ?: return@forEach
            if (item.state == LedgerState.Reworked) return@forEach
            tasks[id] = item.copy(state = LedgerState.Reworked)
            reworks++
            turnRework++
            changed = true
        }
        if (changed) publish()
    }

    /** 记一条未解问题：去重 + 上限（超过丢最旧的）。 */
    fun addQuestion(text: String, round: Int) = addNote(questions, text, round, "q-", MAX_OPEN_QUESTIONS)

    /** 记一条事实 / 结论：去重 + 上限。 */
    fun addFact(text: String, round: Int) = addNote(facts, text, round, "f-", MAX_FACTS)

    private fun addNote(into: LinkedHashMap<String, LedgerItem>, text: String, round: Int, prefix: String, cap: Int) {
        if (isNoAddText(text)) return
        val title = digest(text.trim(), LEDGER_TITLE_CHARS)
        if (title.isBlank()) return
        val key = prefix + keyHash(title)
        if (into.containsKey(key)) return
        into[key] = LedgerItem(key, title, null, LedgerState.Open, round)
        while (into.size > cap) {
            val oldest = into.keys.firstOrNull() ?: break
            into.remove(oldest)
        }
        publish()
    }

    /** 给主持人 prompt 用的视图（LedgerView 字段冻结，不要加字段）。 */
    fun view(): LedgerView = LedgerView(
        openTasks = openItems().map { it.title },
        openQuestions = questions.values.map { it.title },
        doneCount = doneTotal,
        reworkCount = reworks,
    )

    /** 给 UI 用的只读快照。 */
    fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        open = openItems(),
        questions = questions.values.map { it.title },
        done = tasks.values.filter { it.state == LedgerState.Done },
        reworkCount = reworks,
    )

    /**
     * 开会 / 散会播报的一行（规格 §5）。空账本返回空串 —— 调用方据此不播报，
     * 新房间零视觉变化。
     */
    fun digest(): String {
        val open = openItems()
        if (open.isEmpty() && questions.isEmpty() && facts.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Unfinished ").append(open.size).append(" items")
        if (open.isNotEmpty()) sb.append(": ").append(open.take(3).joinToString("; ") { it.title })
        sb.append("; open questions ").append(questions.size).append(" items")
        if (questions.isNotEmpty()) {
            sb.append(": ").append(questions.values.take(2).joinToString("; ") { it.title })
        }
        sb.append("; recorded facts ").append(facts.size).append(" items")
        sb.append("; done ").append(doneTotal).append(" items; sent back ").append(reworks).append(" times")
        return if (sb.length <= LEDGER_DIGEST_CHARS) sb.toString()
        else sb.substring(0, LEDGER_DIGEST_CHARS - 1) + "…"
    }

    /** 某人手上还有几件没做完（MemberState.pendingTasks 的唯一来源）。 */
    fun openTasksOf(ownerId: String): Int = openItems().count { it.ownerId == ownerId }

    /** 未完成条目按负责人计数（散会播报的「成员分布」）。 */
    fun ownerCounts(): Map<String, Int> = openItems()
        .mapNotNull { it.ownerId }
        .groupingBy { it }
        .eachCount()

    /** 未完成 = Open + Reworked（打回没做完的也算欠着）。 */
    private fun openItems(): List<LedgerItem> =
        tasks.values.filter { it.state == LedgerState.Open || it.state == LedgerState.Reworked }

    private fun trim() {
        while (openItems().size > MAX_OPEN_TASKS) {
            val victim = openItems().firstOrNull() ?: return
            tasks.remove(victim.id)
        }
        val done = tasks.values.filter { it.state == LedgerState.Done }
        if (done.size > MAX_DONE_KEPT) {
            done.take(done.size - MAX_DONE_KEPT).forEach { tasks.remove(it.id) }
        }
    }

    private fun publish() {
        RoomLedgers.publish(sessionId, snapshot())
    }

    private fun keyHash(title: String): String {
        val h = title.hashCode()
        val hex = Integer.toHexString(h)
        return hex.padStart(8, '0').takeLast(8)
    }
}

/**
 * 账本快照出口：GroupRunner 写，feature/chat 读。
 *
 * 为什么用一个对象而不是给 DshTransport 加方法：账本是运行期结构（规格 §9 不落盘），
 * 也刻意不新开全局「业务状态」——它只镜像 GroupRunner 里那份 mutableMapOf 的当前值，
 * forget(sessionId) 时清空，会话删除即释放。
 */
object RoomLedgers {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<LedgerSnapshot>>()

    /** 本会话的账本快照流；没有账本时发空快照（UI 据此不渲染，零视觉变化）。 */
    fun flow(sessionId: String): StateFlow<LedgerSnapshot> = of(sessionId).asStateFlow()

    fun snapshot(sessionId: String): LedgerSnapshot = of(sessionId).value

    internal fun publish(sessionId: String, snapshot: LedgerSnapshot) {
        of(sessionId).value = snapshot
    }

    /** 会话取消 / 关闭：和 GroupRunner.forget 同一时刻清掉。 */
    fun clear(sessionId: String) {
        flows.remove(sessionId)?.value = LedgerSnapshot()
    }

    private fun of(sessionId: String): MutableStateFlow<LedgerSnapshot> =
        flows.getOrPut(sessionId) { MutableStateFlow(LedgerSnapshot()) }
}
