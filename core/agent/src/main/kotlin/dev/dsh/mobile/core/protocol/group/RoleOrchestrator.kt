package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.model.GroupLedgerSink
import dev.dsh.mobile.core.model.GroupRole
import dev.dsh.mobile.core.model.LedgerEntry
import dev.dsh.mobile.core.model.LedgerStatus
import dev.dsh.mobile.core.protocol.GroupMember

/** 编排循环里的一个角色席位：谁（群成员）＋ 什么角色。 */
internal data class RoleSeat(val member: GroupMember, val role: GroupRole)

/**
 * 角色编排循环（T9.7b）：把「谁盯目的 / 谁排活 / 谁验货 / 谁干活」变成真的轮转。
 *
 * 一轮的棒次固定：**B 规划 → 逐条（D 执行 → C 检测 → 不过则 D 返工）→ B 收口 → A 监督**。
 * 单条目、一次返工时恰好 7 棒（B D C D C B A）；上限 8 棒，只在多条目计划里才会撞到。
 *
 * 三条硬约束：
 * 1. **没有 B（规划者）就不启用** —— 没人出计划，这个循环就是空转，调用方退回老轮转。
 * 2. **缺角色降级** —— 缺 C 时执行完直接标完成；缺 A 时跳过监督；缺 D 时由 B 自己上手。
 * 3. **没经检测不许说完成** —— 检测者没给「通过」就留在需改，并写明原因。
 *
 * 本类不碰 DataStore、不碰执行池、不碰事件流：一棒怎么说话由 [speak] 注入，
 * 落账走 [GroupLedgerSink]，系统说明走 [notice]。所以它在纯 JVM 单测里能直接跑。
 */
internal class RoleOrchestrator(
    private val seats: List<RoleSeat>,
    private val ledger: GroupLedgerSink,
    /** 一棒发言：给角色与 prompt，拿回这个角色的输出。 */
    private val speak: suspend (RoleSeat, String) -> String,
    /** 系统说明（不调模型、不进成员上下文）。 */
    private val notice: suspend (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val supervisor = seats.firstOrNull { it.role == GroupRole.Supervisor }
    private val planner = seats.firstOrNull { it.role == GroupRole.Planner }
    private val inspector = seats.firstOrNull { it.role == GroupRole.Inspector }

    /** 执行者：没有 Worker 席位时由规划者自己上手（缺角色降级）。 */
    private val worker = seats.firstOrNull { it.role == GroupRole.Worker } ?: planner

    /** 已经用掉的棒数。上限是硬截断，不是「尽量」。 */
    private var sticks = 0

    /** 撞到棒数上限后置位：剩下的收口动作全部不再叫模型。 */
    private var exhausted = false

    /** 没有规划者就什么都不做（调用方本来就该先判，这里再兜一次）。 */
    suspend fun run(sessionId: String, userText: String) {
        val b = planner ?: return
        val d = worker ?: return
        tell(null, "本轮按角色轮转：" + seats.joinToString("、") { it.member.name + "（" + it.role.label + "）" })

        val titles = parsePlan(say(b, planPrompt(userText)))
        val stamp = clock()
        // 条目 id 带时间戳：跨轮不覆盖上一轮的计划书，「这条什么时候加的」也看得出来。
        val entries = titles.mapIndexed { i, title ->
            LedgerEntry(
                id = "t" + stamp + "-" + (i + 1),
                title = title,
                owner = d.member.name,
            )
        }
        for (e in entries) ledger.upsert(sessionId, e)
        tell(GroupRole.Planner, "本轮 " + entries.size + " 条：" + entries.joinToString("；") { it.title })

        for (entry in entries) {
            if (exhausted) break
            ledger.updateStatus(sessionId, entry.id, LedgerStatus.Doing)
            var out = say(d, workPrompt(userText, entry.title))
            ledger.updateStatus(sessionId, entry.id, LedgerStatus.Review)
            var check = inspect(sessionId, entry, out)
            var rework = 0
            while (!check.passed && !exhausted && rework < MAX_REWORK) {
                rework++
                ledger.appendNote(sessionId, entry.id, "第 " + rework + " 次返工：" + check.reason)
                tell(GroupRole.Inspector, "打回「" + entry.title + "」：" + check.reason)
                out = say(d, reworkPrompt(entry.title, check.reason, out))
                check = inspect(sessionId, entry, out)
            }
            if (check.passed) {
                ledger.updateStatus(sessionId, entry.id, LedgerStatus.Done)
            } else if (!exhausted) {
                // 返工上限用完：留在「需改」，原因进备注，不假装完成。
                ledger.appendNote(
                    sessionId,
                    entry.id,
                    "返工 " + rework + " 次仍未通过：" + check.reason.ifBlank { "检测未给出依据" },
                )
            }
        }

        // 棒数用尽：剩下的收口全跳过（say 自己会拒），只在群里说清为什么停。
        if (exhausted) {
            tell(null, "用满 " + MAX_STICKS + " 棒上限，本轮先收在这里；剩余条目留在规划书里等下一轮。")
            return
        }
        val summary = summaryOf(sessionId, entries)
        say(b, closePrompt(summary))
        supervisor?.let { a -> say(a, supervisePrompt(summary)) }
        if (exhausted) {
            tell(null, "用满 " + MAX_STICKS + " 棒上限，本轮先收在这里；剩余条目留在规划书里等下一轮。")
        }
    }

    /**
     * 检测一棒：缺 C 直接算过（缺角色降级）。
     * 撞上限时算「没过，但不再叫模型」—— 没验过的条目绝不能标完成。
     */
    private suspend fun inspect(sessionId: String, entry: LedgerEntry, out: String): Check {
        val c = inspector ?: return Check(true, "")
        if (exhausted) return Check(false, "棒数用尽，未完成检测")
        return parseCheck(say(c, inspectPrompt(entry.title, out)))
    }

    /** 系统说明统一带角色前缀：[监督者] / [规划者] / [检测者] / [执行者]；没有具体角色时用「角色编排」。 */
    private suspend fun tell(role: GroupRole?, text: String) {
        notice("[" + (role?.label ?: "角色编排") + "] " + text)
    }

    /** 一棒：先扣预算再叫模型；预算用尽就置位 [exhausted] 并返回空串。 */
    private suspend fun say(seat: RoleSeat, prompt: String): String {
        if (exhausted) return ""
        if (sticks >= MAX_STICKS) {
            exhausted = true
            return ""
        }
        sticks++
        return speak(seat, prompt)
    }

    private suspend fun summaryOf(sessionId: String, entries: List<LedgerEntry>): String {
        val ids = entries.map { it.id }.toSet()
        val rows = ledger.list(sessionId).filter { it.id in ids }
        if (rows.isEmpty()) return entries.joinToString("\n") { "- " + it.title }
        return rows.joinToString("\n") { row ->
            "- [" + row.status.label + "] " + row.title +
                if (row.note.isBlank()) "" else "（" + row.note.lineSequence().last().trim() + "）"
        }
    }

    /** 计划解析：只认「- 」/「* 」开头的行；一行都没有就退化成一件事，别让整轮空转。 */
    private fun parsePlan(text: String): List<String> {
        val items = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).trim() }
            .filter { it.length >= 2 }
            .take(MAX_ITEMS)
            .toList()
        if (items.isNotEmpty()) return items
        val head = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)
        return listOf(head.ifBlank { "回应用户这一轮的需求" })
    }

    private fun planPrompt(userText: String): String = buildString {
        append("你是本群的项目规划者（B）。用户这一轮的需求：\n")
        append(userText.trim())
        append("\n\n把它拆成本轮要交付的条目，每条都以「- 」开头，一句话写清做什么与怎么验收。")
        append("最多 ").append(MAX_ITEMS).append(" 条；不要编号、不要多级分点、不要解释。")
        append("如果这件事一条就能做完，就只写一条。")
    }

    private fun workPrompt(userText: String, title: String): String = buildString {
        append("本轮用户需求：\n").append(userText.trim())
        append("\n\n规划书条目（你负责这一条）：").append(title)
        append("\n\n动手做完它，然后交付：说清改了哪里、跑了什么、结果是什么。")
        append("只做这一条，不要替检测者下结论，不要改别的条目。")
    }

    private fun inspectPrompt(title: String, out: String): String = buildString {
        append("规划书条目：").append(title)
        append("\n\n执行者交付：\n").append(out.trim().take(1600))
        append("\n\n按条目的验收标准逐条核对：证据够不够、有没有跳过、有没有说成完成但没做。")
        append("第一行只写「通过」或「不通过：<一句原因>」，后面最多三行依据。")
    }

    private fun reworkPrompt(title: String, reason: String, previous: String): String = buildString {
        append("规划书条目：").append(title)
        append("\n\n检测者打回：").append(reason)
        append("\n\n你上一版交付：\n").append(previous.trim().take(1200))
        append("\n\n按打回原因重做这一条：只修被指出的问题，别扩大范围，改完再说清改了什么。")
    }

    private fun closePrompt(summary: String): String = buildString {
        append("本轮规划书状态：\n").append(summary)
        append("\n\n三行以内收口：哪些完成了、哪些还欠着、下一轮建议先做什么。")
    }

    private fun supervisePrompt(summary: String): String = buildString {
        append("本轮规划书状态：\n").append(summary)
        append("\n\n按监督者职责检查：谁忘了本轮目的、谁越了权、哪条没经检测就被标完成。")
        append("只指出有证据的问题，指名到人；没有就只回一句「无异常」。")
    }

    private data class Check(val passed: Boolean, val reason: String)

    /**
     * 检测结论解析：先看「不通过」（它包含「通过」，顺序不能反），再看「通过」。
     * 什么都没说的按不通过处理 —— 宁可多返工一次，也不凭空把条目标成完成。
     */
    private fun parseCheck(text: String): Check {
        val t = text.trim()
        if (t.isEmpty()) return Check(false, "检测没有回报")
        val notPass = t.lineSequence().firstOrNull { it.contains("不通过") }
        if (notPass != null) return Check(false, notPass.trim().take(160))
        if (t.contains("通过")) return Check(true, "")
        val head = t.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return Check(false, head.take(160))
    }

    companion object {
        /** 一轮最多叫几次模型：B 计划 1 + 单条目 D/C + 返工 2 + B 收口 + A 监督恰好用不满。 */
        const val MAX_STICKS: Int = 8

        /** 单条目单轮最多返工次数。 */
        const val MAX_REWORK: Int = 2

        /** 一轮最多几条计划，超出的留到下一轮（计划书本身也有 50 条上限）。 */
        const val MAX_ITEMS: Int = 4
    }
}
