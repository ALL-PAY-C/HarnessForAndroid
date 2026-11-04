package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.context.KeyFile
import dev.dsh.mobile.core.model.context.Pitfall
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmNote
import dev.dsh.mobile.core.model.context.WarmSource
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 日志守卫：JVM 单测里 android.util.Log 是桩（一调就抛），温层日志绝不能成为失败点。
 * 与 S1 的 WarmLayer 一样，温层只做「有则更好」，任何一环出错都退回旧值。
 */
private fun warmLog(msg: String) {
    runCatching { AppLog.d(msg) }
}

private fun warmWarn(msg: String) {
    runCatching { AppLog.w(msg) }
}

/**
 * 一条温层输入消息：rowid 是对账水位（messages.rowid），role / text 给模型看。
 *
 * 单独建类型而不是复用 ChatMessage：ChatMessage 没有 rowid，而温层的去重完全靠它
 * （S1 的 coveredUntilMessageId）；单测也不用为了造一条消息去拼整个 ChatMessage。
 */
data class WarmMessage(
    val rowId: Long,
    val role: String,
    val text: String,
)

/**
 * 温层刷新回执（T9.8 S2）。
 *
 * [layer] 为 null = 本次没写库：没有新消息、模型输出一个字段都解析不出、或调用失败。
 * 三种情况都不是异常，旧温层原样留着 —— 温层「有则更好」，绝不能挡住一轮对话。
 */
data class WarmRefreshReport(
    val layer: WarmLayer? = null,
    val message: String = "",
) {
    val refreshed: Boolean get() = layer != null
}

/** 装配期触发判定的结论（纯函数，无 IO、不读时钟）。 */
internal enum class WarmDecision {
    /** 什么都不做。 */
    NONE,

    /** 只刷温层，不动摘要（≥40 条新消息、还没到压缩线）。 */
    WARM_ONLY,

    /** 到了压缩线：真压缩按原逻辑走，温层只跟着刷。 */
    COMPACT,
}

internal object WarmTrigger {
    /** 水位之后攒够这么多条新消息才刷温层。/warm 手动命令不看它。 */
    const val TURN_THRESHOLD = 40

    /** 预算占用到这个比例就交给真压缩（与 [dev.dsh.mobile.core.protocol.ContextBudget.COMPACT_RATIO] 同值）。 */
    const val COMPACT_SHARE = 0.8

    /** 温层独立预算占压缩预算的比例。 */
    const val WARM_SHARE = 0.25

    /** 温层预算（字符）：压缩预算的 25%。预算未知（<=0）时返回 0 = 不裁。 */
    fun warmBudgetChars(budgetChars: Int): Int =
        if (budgetChars <= 0) 0 else (budgetChars * WARM_SHARE).toInt().coerceAtLeast(1)

    fun decide(turnsSinceWarm: Int, usedChars: Int, budgetChars: Int, forced: Boolean = false): WarmDecision = when {
        forced -> WarmDecision.WARM_ONLY
        // 预算优先：真压缩永远压在这条分支前面，温层不能抢它的活。
        budgetChars > 0 && usedChars >= (budgetChars * COMPACT_SHARE).toInt() -> WarmDecision.COMPACT
        turnsSinceWarm >= TURN_THRESHOLD -> WarmDecision.WARM_ONLY
        else -> WarmDecision.NONE
    }

    /** 触发判定的可读入口：装配期只关心「要不要只刷温层」。 */
    fun shouldRefreshWarm(turnsSinceWarm: Int, usedChars: Int, budgetChars: Int): Boolean =
        decide(turnsSinceWarm, usedChars, budgetChars) == WarmDecision.WARM_ONLY
}

/** 温层生成的系统提示词：九段固定标题，别让模型把历史复述一遍。 */
internal const val WARM_SYSTEM_PROMPT =
    "你是上下文温层生成器。把给你的历史压成九段结构化温层，只留后续继续干活必需的信息，" +
        "不要复述原文，不要解释，不要寒暄。严格用这九段标题输出，没有内容的段留空标题：" +
        "主要目标 / 硬约束 / 已否决 / 踩坑 / 关键文件 / 已决策 / 待办 / 当前工作 / 下一步。" +
        "硬约束、已否决、踩坑、关键文件、已决策是高价值段，宁可多留一条也不要漏。"

/**
 * 温层生成器（T9.8 S2）：历史 + 上一次温层 → 新温层。
 *
 * 一次模型调用、九段解析、三级合并、预算裁剪，全部在这里；不碰 Room、不碰装配顺序，
 * 模型调用经 [callModel] 注入，单测传 fake 就不联网。
 *
 * 合并语义（方案文档 T98-context.md §S2）：
 * - LOCKED（primaryGoal / hardConstraints / rejectedOptions / pitfalls）：只能「追加或改写为更具体」，
 *   新解析结果为空就保留旧值，永不清空。
 * - PINNED（keyFiles / decisions）：按 key 去重（路径:行号 / 结论文本），同 key 合并成更具体的一条。
 * - SOFT（openTodos / currentWork / nextStep）：直接用新值覆盖。
 */
internal class WarmLayerBuilder(
    /** 模型调用：入参是整段提示词，返回模型原文。失败抛异常即可，本类会吞掉并返回 null 层。 */
    private val callModel: suspend (String) -> String,
    /** 时钟：updatedAt 盖章（单测固定值即可断言）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * 生成新温层。[history] 只该给水位之后的新消息（调用方按 coveredUntilMessageId 过滤）。
     *
     * 返回的层 **未落盘**：写库由调用方（Compaction.refreshWarm）负责，这里保持纯函数好测。
     */
    suspend fun build(
        sessionId: String,
        history: List<WarmMessage>,
        previous: WarmLayer?,
        warmBudgetChars: Int,
    ): WarmRefreshReport {
        val fresh = history.filter { it.text.isNotBlank() }
        if (fresh.isEmpty()) return WarmRefreshReport(null, "没有新消息，温层跳过。")
        val covered = fresh.maxOf { it.rowId }
        val raw = try {
            callModel(prompt(previous, fresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            warmWarn("温层生成失败，保留旧温层：" + (e.message ?: e.javaClass.simpleName))
            return WarmRefreshReport(null, "温层生成失败，保留旧温层。")
        } catch (e: Throwable) {
            warmWarn("温层生成异常，保留旧温层：" + (e.message ?: e.javaClass.simpleName))
            return WarmRefreshReport(null, "温层生成失败，保留旧温层。")
        }
        val parsed = WarmParser.parse(raw)
        if (parsed.isEmpty()) {
            warmWarn("温层输出解析不出任何字段，跳过写入（" + raw.length + " 字符）")
            return WarmRefreshReport(null, "温层输出解析不出字段，保留旧温层。")
        }
        val merged = merge(previous, parsed).copy(
            coveredUntilMessageId = covered,
            warmVersion = (previous?.warmVersion ?: 0) + 1,
            source = WarmSource.LOCAL,
            updatedAt = clock(),
        )
        val fitted = fit(merged, warmBudgetChars)
        val note = if (fitted.render().length > warmBudgetChars && warmBudgetChars > 0) {
            "（LOCKED 已超温层预算，未裁剪）"
        } else {
            ""
        }
        warmLog("温层 " + sessionId + " v" + fitted.warmVersion + " 覆盖到 rowid " + covered + note)
        return WarmRefreshReport(fitted, "温层 v" + fitted.warmVersion + "，覆盖到 rowid " + covered + note)
    }

    /** 给模型的提示词：旧温层作对照 + 九段要求 + 带 rowid 的新历史。 */
    private fun prompt(previous: WarmLayer?, history: List<WarmMessage>): String = buildString {
        appendLine("把下面这段对话历史压成温层。九段标题与格式：")
        appendLine(FORMAT_SPEC)
        if (previous != null && !previous.isEmpty()) {
            appendLine()
            appendLine("【已有温层】新内容更具体就改写，没提到就保持原样，不要凭空删掉已有的硬约束/否决/踩坑：")
            appendLine(previous.render())
        }
        appendLine()
        appendLine("【新历史】")
        history.takeLast(MAX_MESSAGES).forEach { m ->
            append(m.role).append("#").append(m.rowId).append(": ")
                .append(m.text.replace('\n', ' ').take(MAX_CHARS_PER_MESSAGE))
                .appendLine()
        }
    }

    // ------------------------------------------------------------------ 合并（三级保护）

    private fun merge(old: WarmLayer?, fresh: WarmLayer): WarmLayer {
        val base = old ?: WarmLayer.EMPTY
        return base.copy(
            // LOCKED：只允许追加 / 改写为更具体，空值一律回退旧值。
            primaryGoal = refine(base.primaryGoal, fresh.primaryGoal),
            hardConstraints = unionItems(base.hardConstraints, fresh.hardConstraints),
            rejectedOptions = unionNotes(base.rejectedOptions, fresh.rejectedOptions),
            pitfalls = unionPitfalls(base.pitfalls, fresh.pitfalls),
            // PINNED：按 key 去重（KeyFile.key() / 结论文本），同 key 取更具体的一条。
            keyFiles = unionFiles(base.keyFiles, fresh.keyFiles),
            decisions = unionNotes(base.decisions, fresh.decisions),
            // SOFT：直接覆盖。
            openTodos = fresh.openTodos,
            currentWork = fresh.currentWork,
            nextStep = fresh.nextStep,
        )
    }

    /** 「改写为更具体」：新的为空保留旧的；新的包含旧的取新的；新的更泛就不降级。 */
    private fun refine(old: String, fresh: String): String {
        val o = old.trim()
        val n = fresh.trim()
        return when {
            n.isEmpty() -> o
            o.isEmpty() -> n
            n.contains(o) -> n
            o.contains(n) -> o
            else -> n
        }
    }

    private fun unionItems(old: List<String>, fresh: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        (old + fresh).forEach { raw ->
            val t = raw.trim()
            if (t.isNotEmpty()) out.putIfAbsent(t.lowercase(), t)
        }
        return out.values.toList()
    }

    private fun unionNotes(old: List<WarmNote>, fresh: List<WarmNote>): List<WarmNote> {
        val out = LinkedHashMap<String, WarmNote>()
        old.filter { it.text.isNotBlank() }.forEach { out[it.key()] = it }
        fresh.filter { it.text.isNotBlank() }.forEach { n ->
            val prev = out[n.key()]
            out[n.key()] = if (prev == null) {
                n
            } else {
                prev.copy(text = refine(prev.text, n.text), reason = refine(prev.reason, n.reason))
            }
        }
        return out.values.toList()
    }

    private fun unionPitfalls(old: List<Pitfall>, fresh: List<Pitfall>): List<Pitfall> {
        val out = LinkedHashMap<String, Pitfall>()
        old.filter { it.symptom.isNotBlank() || it.fix.isNotBlank() }.forEach { out[it.key()] = it }
        fresh.filter { it.symptom.isNotBlank() || it.fix.isNotBlank() }.forEach { p ->
            val prev = out[p.key()]
            out[p.key()] = if (prev == null) {
                p
            } else {
                prev.copy(
                    symptom = refine(prev.symptom, p.symptom),
                    cause = refine(prev.cause, p.cause),
                    fix = refine(prev.fix, p.fix),
                )
            }
        }
        return out.values.toList()
    }

    private fun unionFiles(old: List<KeyFile>, fresh: List<KeyFile>): List<KeyFile> {
        val out = LinkedHashMap<String, KeyFile>()
        old.filter { it.path.isNotBlank() }.forEach { out[it.key()] = it }
        fresh.filter { it.path.isNotBlank() }.forEach { f ->
            val prev = out[f.key()]
            out[f.key()] = if (prev == null) {
                f
            } else {
                prev.copy(
                    path = prev.path.ifBlank { f.path },
                    line = if (prev.line > 0) prev.line else f.line,
                    note = refine(prev.note, f.note),
                )
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ 预算（只裁 SOFT）

    /**
     * 温层预算裁剪：超 [budget] 先裁 SOFT，LOCKED 一条不丢。
     *
     * SOFT 的裁剪次序 = 待办（列表头部先删；新条目追加在尾部，所以头部就是更旧的）→ 当前工作 → 下一步。
     * 温层只有一个 updatedAt（整层盖章），条目级时间戳不存在，所以「updatedAt 旧者先裁」落到列表序上。
     * LOCKED 即使自己就超预算也不裁 —— 宁可温层超一点，也不能丢掉硬约束。
     */
    private fun fit(layer: WarmLayer, budget: Int): WarmLayer {
        if (budget <= 0 || layer.render().length <= budget) return layer
        var cur = layer
        val todos = cur.openTodos.filter { it.isNotBlank() }
        for (i in 1..todos.size) {
            if (cur.render().length <= budget) return cur
            cur = cur.copy(openTodos = todos.drop(i))
        }
        if (cur.render().length > budget) cur = cur.copy(currentWork = "")
        if (cur.render().length > budget) cur = cur.copy(nextStep = "")
        return cur
    }

    private companion object {
        const val MAX_MESSAGES = 80
        const val MAX_CHARS_PER_MESSAGE = 400

        /** 写给模型看的九段格式：与 [WarmParser] 的标题表一一对应。 */
        val FORMAT_SPEC = listOf(
            "主要目标：一行",
            "硬约束：",
            "- 一条一句",
            "已否决：",
            "- 结论（理由：为什么否决）",
            "踩坑：",
            "- 症状（原因：根因；修法：怎么绕过）",
            "关键文件：",
            "- 路径:行号 —— 为什么看它",
            "已决策：",
            "- 结论（理由：依据）",
            "待办：",
            "- 一件一句",
            "当前工作：一行",
            "下一步：一行",
        ).joinToString("\n")
    }
}

/**
 * 九段解析：按标题切段，逐条转成字段。
 *
 * 解析是「尽力而为」：认不出的标题行、列表外的散句直接丢并记一条日志，
 * 一段解析失败不影响其它段（空段在合并期自动回退旧值）。
 */
internal object WarmParser {
    private enum class Section { GOAL, CONSTRAINTS, REJECTED, PITFALLS, FILES, DECISIONS, TODOS, WORK, NEXT }

    private val HEADINGS: Map<String, Section> = mapOf(
        "主要目标" to Section.GOAL,
        "主线目标" to Section.GOAL,
        "目标" to Section.GOAL,
        "goal" to Section.GOAL,
        "硬约束" to Section.CONSTRAINTS,
        "硬性约束" to Section.CONSTRAINTS,
        "约束" to Section.CONSTRAINTS,
        "constraints" to Section.CONSTRAINTS,
        "已否决" to Section.REJECTED,
        "已否决方案" to Section.REJECTED,
        "否决方案" to Section.REJECTED,
        "已排除" to Section.REJECTED,
        "rejected" to Section.REJECTED,
        "踩坑" to Section.PITFALLS,
        "踩过的坑" to Section.PITFALLS,
        "坑" to Section.PITFALLS,
        "pitfalls" to Section.PITFALLS,
        "关键文件" to Section.FILES,
        "关键文件路径" to Section.FILES,
        "关键路径" to Section.FILES,
        "文件" to Section.FILES,
        "files" to Section.FILES,
        "已决策" to Section.DECISIONS,
        "决策" to Section.DECISIONS,
        "decisions" to Section.DECISIONS,
        "待办" to Section.TODOS,
        "未完成" to Section.TODOS,
        "未完成事项" to Section.TODOS,
        "todo" to Section.TODOS,
        "todos" to Section.TODOS,
        "当前工作" to Section.WORK,
        "现在在做" to Section.WORK,
        "当前" to Section.WORK,
        "current work" to Section.WORK,
        "下一步" to Section.NEXT,
        "接下来" to Section.NEXT,
        "next step" to Section.NEXT,
    )

    /** 关键文件段的标题后缀（「关键文件:行号」不算内容）。 */
    private val FILE_SUFFIX = setOf("行号", "文件", "路径")

    private val ORDER_PREFIX = Regex("^(\\d{1,2}|[一二三四五六七八九十]{1,3})[.、)）]\\s*")

    fun parse(raw: String): WarmLayer {
        var section: Section? = null
        val goal = StringBuilder()
        val work = StringBuilder()
        val next = StringBuilder()
        val constraints = LinkedHashSet<String>()
        val rejected = ArrayList<WarmNote>()
        val pitfalls = ArrayList<Pitfall>()
        val files = ArrayList<KeyFile>()
        val decisions = ArrayList<WarmNote>()
        val todos = LinkedHashSet<String>()
        var junk = 0

        fun add(sec: Section, text: String) {
            when (sec) {
                Section.GOAL -> goal.append(if (goal.isEmpty()) text else " " + text)
                Section.WORK -> work.append(if (work.isEmpty()) text else " " + text)
                Section.NEXT -> next.append(if (next.isEmpty()) text else " " + text)
                Section.CONSTRAINTS -> constraints += text
                Section.TODOS -> todos += text
                Section.REJECTED -> rejected += splitReason(text)
                Section.DECISIONS -> decisions += splitReason(text)
                Section.PITFALLS -> pitfalls += splitPitfall(text)
                Section.FILES -> parseKeyFile(text)?.let { files += it }
            }
        }

        for (line in raw.lines()) {
            val head = headingOf(line)
            if (head != null) {
                section = head.first
                if (head.second.isNotBlank()) add(head.first, head.second)
                continue
            }
            val text = clean(line)
            if (text.isEmpty()) continue
            val sec = section
            if (sec == null) {
                junk++
                continue
            }
            add(sec, text)
        }
        if (junk > 0) warmWarn("温层输出有 " + junk + " 行不属于任何段落，已丢弃")
        return WarmLayer(
            primaryGoal = goal.toString().trim(),
            hardConstraints = constraints.toList(),
            rejectedOptions = distinctNotes(rejected),
            pitfalls = pitfalls.distinctBy { it.key() },
            keyFiles = files.distinctBy { it.key() },
            decisions = distinctNotes(decisions),
            openTodos = todos.toList(),
            currentWork = work.toString().trim(),
            nextStep = next.toString().trim(),
        )
    }

    /**
     * 插件 checkpoint → 温层（S3 共存）：插件已经压过历史时不再花一次模型调用，
     * 直接把插件给的内容按九段解析，来源标 [WarmSource.PLUGIN]。
     *
     * 解析不出任何段（[WarmLayer.isEmpty]）返回 null = 没有可用的温层内容，别写空层。
     */
    fun fromCheckpoint(raw: String, coveredUntil: Long, now: Long): WarmLayer? {
        val body = raw.trim()
            .removePrefix(WarmAssembly.WARM_OPEN)
            .removeSuffix(WarmAssembly.WARM_CLOSE)
            .trim()
        val layer = parse(body)
        if (layer.isEmpty()) return null
        return layer.copy(
            coveredUntilMessageId = coveredUntil,
            warmVersion = 1,
            source = WarmSource.PLUGIN,
            updatedAt = now,
        )
    }

    /** 标题行 → (段, 行内内容)；不是标题给 null。 */
    private fun headingOf(line: String): Pair<Section, String>? {
        var s = clean(line)
        if (s.isEmpty()) return null
        s = ORDER_PREFIX.replace(s, "").trim()
        val idx = s.indexOfFirst { it == ':' || it == '：' }
        if (idx <= 0) return null
        val name = s.substring(0, idx).trim().trim('*', '-', ' ', '#')
        val section = HEADINGS[name] ?: HEADINGS[name.lowercase()] ?: return null
        var rest = s.substring(idx + 1).trim()
        if (section == Section.FILES && rest in FILE_SUFFIX) rest = ""
        return section to rest
    }

    /** 去掉列表符号 / 强调符号。 */
    private fun clean(line: String): String {
        var s = line.trim()
        while (s.startsWith("#")) s = s.removePrefix("#").trim()
        s = s.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
        return s.trim('*').trim()
    }

    private fun distinctNotes(items: List<WarmNote>): List<WarmNote> {
        val out = LinkedHashMap<String, WarmNote>()
        items.filter { it.text.isNotBlank() }.forEach { n ->
            val prev = out[n.key()]
            out[n.key()] = if (prev == null || n.reason.length > prev.reason.length) n else prev
        }
        return out.values.toList()
    }

    /** 「结论（理由：...）」→ WarmNote；没写理由就整条当结论。 */
    private fun splitReason(raw: String): WarmNote {
        val s = raw.trim()
        val idx = s.indexOf("理由")
        if (idx <= 0) return WarmNote(s)
        val head = s.substring(0, idx).trim().trimEnd('（', '(', '；', ';', ',', '，', ' ')
        var tail = s.substring(idx + 2).trim()
        if (tail.startsWith("：") || tail.startsWith(":")) tail = tail.substring(1).trim()
        tail = tail.trimEnd('）', ')').trim()
        return WarmNote(head.ifBlank { s }, tail)
    }

    /** 「症状（原因：...；修法：...）」→ Pitfall。 */
    private fun splitPitfall(raw: String): Pitfall {
        val s = raw.trim()
        val cause = field(s, "原因", listOf("修法"))
        val fix = field(s, "修法", emptyList())
        val cut = listOf("原因", "修法").map { s.indexOf(it) }.filter { it > 0 }.minOrNull() ?: s.length
        val symptom = s.substring(0, cut).trim().trimEnd('（', '(', '；', ';', ',', '，', ' ')
        return Pitfall(
            symptom = symptom.ifBlank { s },
            cause = cause,
            fix = fix,
        )
    }

    private fun field(s: String, tag: String, stops: List<String>): String {
        val start = s.indexOf(tag)
        if (start < 0) return ""
        var begin = start + tag.length
        if (begin < s.length && (s[begin] == '：' || s[begin] == ':')) begin++
        var end = s.length
        stops.forEach { stop ->
            val i = s.indexOf(stop, begin)
            if (i in begin until end) end = i
        }
        return s.substring(begin, end).trim().trim('（', '(', '）', ')', '；', ';', ',', '，', ' ')
    }

    /** 「路径:行号 —— 备注」→ KeyFile（行号 0 = 整文件，备注可省）。 */
    private fun parseKeyFile(raw: String): KeyFile? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val dash = s.indexOf("——")
        val head = (if (dash >= 0) s.substring(0, dash) else s).trim()
        val note = if (dash >= 0) s.substring(dash + 2).trim() else ""
        if (head.isEmpty()) return null
        val m = FILE_LINE.find(head)
        return if (m != null) {
            KeyFile(path = m.groupValues[1].trim(), line = m.groupValues[2].toIntOrNull() ?: 0, note = note)
        } else {
            KeyFile(path = head, line = 0, note = note)
        }
    }

    private val FILE_LINE = Regex("^(.*?):(\\d{1,6})$")
}
