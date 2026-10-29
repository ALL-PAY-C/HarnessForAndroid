package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.TaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core/protocol 的最小自检：纯 Kotlin，不依赖测试框架也能在 IDE 里直接跑
 * ProtocolSelfCheck.selfCheck()，返回空 List 即全过。
 *
 * 覆盖：reasoningOf 的五种字段名、正文里的 think 标签提取（含跨 chunk 与未闭合）、
 * buildUserContent 的图片路径行。
 */
object ProtocolSelfCheck {

    private val CLOSE = "<\uFF5Cend\u2581of\u2581thinking\uFF5C>"

    fun selfCheck(): List<String> {
        val bad = ArrayList<String>()

        fun expect(name: String, actual: Any?, want: Any?) {
            if (actual != want) bad += name + ": 期望 <" + want + "> 实际 <" + actual + ">"
        }

        // ---- reasoningOf：各家用不同字段名
        expect("reasoning_content", reasoningOf(obj("reasoning_content" to JsonPrimitive("思考A"))), "思考A")
        expect("reasoning", reasoningOf(obj("reasoning" to JsonPrimitive("思考B"))), "思考B")
        expect("thinking", reasoningOf(obj("thinking" to JsonPrimitive("思考C"))), "思考C")
        expect("analysis", reasoningOf(obj("analysis" to JsonPrimitive("思考D"))), "思考D")
        val details = JsonArray(
            listOf(
                JsonPrimitive("d1"),
                buildJsonObject { put("summary", "S") },
                buildJsonObject { put("text", "T") },
            ),
        )
        expect("reasoning_details", reasoningOf(obj("reasoning_details" to details)), "d1ST")
        expect("没有 reasoning 字段", reasoningOf(obj("content" to JsonPrimitive("正文"))), null)
        expect("null 对象", reasoningOf(null), null)

        // ---- 正文里的 think 标签
        val a = splitThinking("思考过程1" + CLOSE + "正文")
        expect("end 标记 正文", a.first, "正文")
        expect("end 标记 推理", a.second, "思考过程1")

        val b = splitThinking("<thinking>思路</thinking>答案")
        expect("thinking 正文", b.first, "答案")
        expect("thinking 推理", b.second, "思路")

        // ---- 跨 chunk：开始标签与结束标签都被切开
        val sp = ThinkSplitter()
        val visible = sp.feed("开头<thi") + sp.feed("nking>隐") + sp.feed("藏") +
            sp.feed(CLOSE + "可见") + sp.finish()
        expect("跨 chunk 正文", visible, "开头可见")
        expect("跨 chunk 推理", sp.thoughtText, "隐藏")

        // ---- 未闭合：正文不吐，整段算推理
        val sp2 = ThinkSplitter()
        val visible2 = sp2.feed("A<thinking>没闭合") + sp2.finish()
        expect("未闭合 正文", visible2, "A")
        expect("未闭合 推理", sp2.thoughtText, "没闭合")

        // ---- buildUserContent：带图片时正文末尾要有本地路径
        val content = buildUserContent("看这张图", listOf("/nonexistent/a.png", "/nonexistent/b.jpg"))
        val text = ((content as? JsonArray)?.firstOrNull()?.jsonObject?.get("text") as? JsonPrimitive)
            ?.content.orEmpty()
        if (!text.startsWith("看这张图")) bad += "buildUserContent: 原文没保留 <" + text + ">"
        if (!text.contains("附件（本地绝对路径")) bad += "buildUserContent: 缺附件行 <" + text + ">"
        if (!text.contains("/nonexistent/a.png") || !text.contains("/nonexistent/b.jpg")) {
            bad += "buildUserContent: 缺图片路径 <" + text + ">"
        }

        // ---- 纯文本保持原来的字符串形态（不发 parts 数组）
        expect("纯文本 content", buildUserContent("你好", emptyList()), JsonPrimitive("你好"))


        // ================= 群聊后端：调度器 / 上下文预算 / 唤醒 / 摘要 =================

        fun task(sid: String, agent: String, at: Long, dep: List<String> = emptyList()) = AgentTask(
            id = java.util.UUID.randomUUID().toString(),
            sessionId = sid,
            agentId = agent,
            agentName = agent,
            prompt = "任务" + agent,
            dependsOn = dep,
            createdAt = at,
        )

        // ---- 1) 调度器：有界并行 / 依赖门控 / 失败隔离 / 取消
        var peakParallel = 0
        try {
            runBlocking {
                // 1a) maxParallel=2 + 3 个独立任务 → 峰值并发必须正好 2（不是 3，也不是 1）
                var concurrent = 0
                var peak = 0
                val p1 = ExecutionPool(this, maxParallelSetting = 2) { t ->
                    concurrent++
                    if (concurrent > peak) peak = concurrent
                    delay(20)
                    concurrent--
                    "done-" + t.agentId
                }
                p1.submit("s1", listOf(task("s1", "a", 1), task("s1", "b", 2), task("s1", "c", 3)))
                p1.await("s1")
                peakParallel = peak
                expect("调度器: 峰值并发", peak, 2)
                expect("调度器: 全部 Done", p1.tasks.value.count { it.state == TaskState.Done }, 3)
                expect("调度器: stats.done", p1.stats.value.done, 3)
                expect("调度器: stats.running 归零", p1.stats.value.running, 0)

                // 1b) 依赖门控：乱序提交，b 依赖 a，执行顺序必须是 a → b
                val order = ArrayList<String>()
                val p2 = ExecutionPool(this, maxParallelSetting = 3) { t ->
                    order += t.agentId
                    "ok"
                }
                val a = task("s2", "a", 1)
                val b = task("s2", "b", 2, dep = listOf(a.id))
                p2.submit("s2", listOf(b, a))
                p2.await("s2")
                expect("依赖: 执行顺序", order, listOf("a", "b"))

                // 1c) 失败隔离：失败任务下游 Skipped，旁支照常 Done
                val p3 = ExecutionPool(this, maxParallelSetting = 2) { t ->
                    if (t.agentId == "boom") throw IllegalStateException("炸了") else "ok"
                }
                val f1 = task("s3", "boom", 1)
                val f2 = task("s3", "down", 2, dep = listOf(f1.id))
                val f3 = task("s3", "other", 3)
                p3.submit("s3", listOf(f1, f2, f3))
                p3.await("s3")
                val by3 = p3.tasks.value.associateBy { it.id }
                expect("失败: 自身 Failed", by3[f1.id]?.state, TaskState.Failed)
                if (by3[f1.id]?.error.isNullOrBlank()) bad += "失败: error 文本为空"
                expect("失败: 下游 Skipped", by3[f2.id]?.state, TaskState.Skipped)
                expect("失败: 旁支 Done", by3[f3.id]?.state, TaskState.Done)
                expect("失败: stats.failed", p3.stats.value.failed, 1)

                // 1d) cancel：Running/Queued 全收，不留悬挂
                val p4 = ExecutionPool(this, maxParallelSetting = 2) { delay(10_000); "late" }
                p4.submit("s4", listOf(task("s4", "a", 1), task("s4", "b", 2)))
                delay(30)
                p4.cancel("s4")
                val alive = p4.tasks.value.count {
                    it.sessionId == "s4" && (it.state == TaskState.Running || it.state == TaskState.Queued)
                }
                expect("取消: 无 Running/Queued", alive, 0)
            }
        } catch (e: Throwable) {
            bad += "调度器自检异常: " + e
        }

        // ---- 2) digest：压平换行 / 去 markdown / 截断到 limit+1
        expect("digest 截断长度", digest("啊".repeat(200), 80).length, 81)
        if (!digest("啊".repeat(200), 80).endsWith("…")) bad += "digest: 截断没加 …"
        expect("digest 压平换行", digest("第一行\n第二行\r\n第三行", 80), "第一行 第二行 第三行")
        expect("digest 去 markdown", digest("**加粗** 和 \u0060代码\u0060", 80), "加粗 和 代码")
        expect("digest 短文本原样", digest("短", 80), "短")

        // ---- 3) 上下文预算：40 条公共历史 → 装配后 ≤ budgetChars，本轮用户消息一定在，最早历史变摘要
        val th = AgentThreads()
        // 每条正文末尾挂「尾巴N」：只有原文才有，被 digest 掉就消失 —— 用来区分「原文」与「摘要」。
        for (i in 1..40) {
            th.appendPublic("s", "assistant", "第" + i + "条：" + "内容".repeat(200) + "【尾巴" + i + "】", "成员" + (i % 3))
        }
        for (i in 1..3) th.appendThread("s", "a", "assistant", "自己的第" + i + "条")
        val turn = listOf(buildJsonObject { put("role", "user"); put("content", "最后一次提问") })
        val roomy = th.buildContextReport("s", "a", "系统提示", "在场：A(你)、B(空闲)", turn, budgetChars = 8000)
        val tight = th.buildContextReport("s", "a", "系统提示", "在场：A(你)、B(空闲)", turn, budgetChars = 1200)
        val roomyText = roomy.messages.joinToString("\n") { it.toString() }
        val tightText = tight.messages.joinToString("\n") { it.toString() }
        if (roomy.usedChars > 8000) bad += "预算: 8000 档超预算 <" + roomy.usedChars + ">"
        if (tight.usedChars > 1200) bad += "预算: 1200 档超预算 <" + tight.usedChars + ">"
        if (roomy.usedChars >= roomy.fullChars) {
            bad += "预算: 没省下字符 <" + roomy.usedChars + " vs " + roomy.fullChars + ">"
        }
        if (!roomyText.contains("最后一次提问")) bad += "预算: 本轮用户消息被裁掉了"
        if (!tightText.contains("最后一次提问")) bad += "预算: 紧档里本轮用户消息被裁掉了"
        if (!roomyText.contains("…")) bad += "预算: 早期历史没压成摘要"
        // 最近 12 条保留原文（尾巴在），更早的被 digest 成 80 字（尾巴没了）
        if (roomyText.contains("【尾巴1】")) bad += "预算: 最早历史仍以全文出现"
        if (roomyText.contains("【尾巴28】")) bad += "预算: 更早历史没压成摘要"
        if (!roomyText.contains("【尾巴40】")) bad += "预算: 最近公共历史没保留原文"
        if (roomy.messages.last()?.get("role")?.toString()?.contains("user") != true) {
            bad += "预算: 最后一条不是本轮用户消息"
        }
        if (tight.messages.size >= roomy.messages.size) bad += "预算: 紧档没有比宽档更短"
        // token 账：报给执行池的两个数字
        val ledger = ExecutionPool(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()), maxParallelSetting = 1) { "" }
        ledger.reportContext(roomy.usedChars, roomy.fullChars)
        expect("账本: 实发字符", ledger.stats.value.promptCharsTotal, roomy.usedChars.toLong())
        expect(
            "账本: 省下字符",
            ledger.stats.value.digestCharsTotal,
            (roomy.fullChars - roomy.usedChars).toLong(),
        )

        // ---- 4) 唤醒：@ 多人 → 各一个任务、prompt 不复制别人的整段；无 @ → 只 1 个任务
        val cfg = LlmConfig("http://127.0.0.1:1", "k", "m")
        val m1 = GroupMember("a1", "阿尔法", "", cfg)
        val m2 = GroupMember("a2", "贝塔", "", cfg)
        val plan = GroupPlan(listOf(m1, m2))
        val segA = "甲".repeat(400)
        val segB = "乙".repeat(400)
        val two = planGroupTasks("s", plan, "@阿尔法 " + segA + " @贝塔 " + segB, emptyList())
        expect("提及: 任务数", two.size, 2)
        val pA = two.firstOrNull { it.agentId == "a1" }?.prompt.orEmpty()
        val pB = two.firstOrNull { it.agentId == "a2" }?.prompt.orEmpty()
        if (pA.contains("乙")) bad += "提及: 阿尔法的 prompt 里塞了贝塔的整段"
        if (pB.contains("甲".repeat(201))) bad += "提及: 贝塔的 prompt 里塞了阿尔法的整段"
        if (pA.length > 700 || pB.length > 700) bad += "提及: prompt 没有收敛 <" + pA.length + "/" + pB.length + ">"

        // 串行链：第二个任务必须依赖第一个（否则两个人并行各答一遍 = 各干各的）
        expect("提及: 首任务无依赖", two.first().dependsOn.size, 0)
        expect("提及: 次任务依赖首任务", two.getOrNull(1)?.dependsOn, listOf(two.first().id))

        // 无 @：全员按链轮流（群聊里每个人都该说话），后一个依赖前一个。
        val none = planGroupTasks("s", plan, "帮我看看这个", emptyList())
        expect("无提及: 全员发言", none.size, 2)
        expect("无提及: 首任务叫第一个", none.firstOrNull()?.agentId, "a1")
        expect("无提及: 次任务依赖首任务", none.getOrNull(1)?.dependsOn, listOf(none.first().id))

        // 点名（不带 @）：只有被点到的成员开口，别再被第一个人抢答。
        val byName = planGroupTasks("s", plan, "贝塔你怎么不回", emptyList(), lastSpeakerId = "a1")
        expect("点名: 只叫被点名的人", byName.map { it.agentId }, listOf("a2"))

        val followUp = planGroupTasks("s", plan, "继续", emptyList(), lastSpeakerId = "a2")
        expect("追问: 上一个发言人排第一", followUp.firstOrNull()?.agentId, "a2")
        expect("追问: 追问也全员在链上", followUp.size, 2)
        val fresh = planGroupTasks("s", plan, "帮我实现一个新的下载器", emptyList(), lastSpeakerId = "a2")
        expect("新任务: 不追着上一个", fresh.firstOrNull()?.agentId, "a1")

        // 昵称独占段：Alpha-DS4.1f-GJ 的「GJ」只属于它，共有段「Alpha」不叫醒所有人。
        val g1 = GroupMember("g1", "Alpha-DS4.1f-CN", "", cfg)
        val g2 = GroupMember("g2", "Alpha-DS4.1f-GJ", "", cfg)
        expect("昵称: 只命中 GJ", namedMentions("GJ怎么不回", listOf(g1, g2)).map { it.id }, listOf("g2"))
        expect("昵称: 大小写不敏感", namedMentions("gj怎么不回", listOf(g1, g2)).map { it.id }, listOf("g2"))
        expect("昵称: 短段 GJ 命中", namedMentions("请看 GJ", listOf(g1, g2)).map { it.id }, listOf("g2"))
        expect("昵称: 共有段不算", namedMentions("Alpha 你好", listOf(g1, g2)).size, 0)
        // ASCII 词边界：Demo2 不算点名 Demo，否则一句话把两个人都叫起来。
        val d1 = GroupMember("d1", "Demo", "", cfg)
        val d2 = GroupMember("d2", "Demo2", "", cfg)
        expect("昵称: Demo2 只命中自己", namedMentions("Demo2 你好", listOf(d1, d2)).map { it.id }, listOf("d2"))
        expect("昵称: Demo 只命中自己", namedMentions("Demo 你好", listOf(d1, d2)).map { it.id }, listOf("d1"))
        // @ 昵称段：用户打 @GJ / @Alpha 也要点到人，且同名多实例轮流认领。
        expect("@昵称段: @GJ 命中", parseMentions("@GJ 干活", listOf(g1, g2)).map { it.first.id }, listOf("g2"))
        expect("@昵称段: @Alpha 最长优先不打散", parseMentions("@Alpha-DS4.1f-GJ 干活", listOf(g1, g2)).map { it.first.id }, listOf("g2"))
        val same1 = GroupMember("s1", "同名", "", cfg)
        val same2 = GroupMember("s2", "同名", "", cfg)
        expect("@同名多实例: 按顺序认领", parseMentions("@同名 一 @同名 二", listOf(same1, same2)).map { it.first.id }, listOf("s1", "s2"))
        expect("@同名多实例: #2 显式指定", parseMentions("@同名#2 干活", listOf(same1, same2)).map { it.first.id }, listOf("s2"))
        expect("@中文名: 正常命中", parseMentions("@阿尔法 你好", listOf(m1, m2)).map { it.first.id }, listOf("a1"))
        expect("追问判定: 短句", isFollowUp("继续"), true)
        expect("追问判定: 带动词", isFollowUp("再加一个按钮"), false)

        // ---- 4b) 编排分诊：并行 / 接力 / 自动机
        expect("分诊: 同时干 → 并行", detectGroupMode("@阿尔法 @贝塔 你俩同时干", 2), GroupMode.Parallel)
        expect(
            "分诊: 监督到 x=10 → 自动机",
            detectGroupMode("CN 算 x+1 直到 x=10，GJ 监督回写", 2),
            GroupMode.Automaton,
        )
        expect("分诊: 普通一句 → 并行", detectGroupMode("帮我看看这个", 2), GroupMode.Parallel)
        expect("分诊: 单人 → 接力", detectGroupMode("你俩互相监督", 1), GroupMode.Chain)

        // 并行任务必须真的没有依赖，否则执行池照样排队跑
        val par = planGroupTasks("s", plan, "@阿尔法 @贝塔 你俩同时算", emptyList(), mode = GroupMode.Parallel)
        expect("并行: 全员", par.size, 2)
        expect("并行: 无依赖", par.count { it.dependsOn.isEmpty() }, 2)
        expect("并行: 都带分工提示", par.count { it.hint.isNotBlank() }, 2)

        // 自动机：轮次预算 + 验收结论解析
        expect("自动机: 直到 → 长循环", automatonRounds("CN 算 x+1 直到 x=10"), MAX_AUTOMATON_ROUNDS)
        expect("自动机: 只说验收 → 一轮", automatonRounds("帮我验收一下这个页面"), 1)
        expect("自动机: 【完成】", automatonVerdict("x 已经是 10\n【完成】"), Verdict.Done)
        expect("自动机: 【继续】", automatonVerdict("回写 x=2\n【继续】"), Verdict.More)
        expect("自动机: 【打回】", automatonVerdict("x 算错了\n【打回】进位错"), Verdict.Rework)
        // 正文里提到「打回」不能当成结论：只看最后一行
        expect(
            "自动机: 正文提及打回不算结论",
            automatonVerdict("如果不对就打回重做。\n【继续】"),
            Verdict.More,
        )

        // ---- 5) 群状态行：≤200 字符，能看出谁在忙
        val stateAgents = mapOf(
            "a1" to AgentRuntimeState(
                agentId = "a1",
                agentName = "阿尔法",
                state = AgentRunState.Thinking,
                taskId = "t1",
                note = "任务".repeat(100),
            ),
        )
        val line = groupStateLine(stateAgents, plan.members, "a2")
        if (line.length > 200) bad += "群状态: 超 200 字符 <" + line.length + ">"
        if (!line.contains("阿尔法(执行中")) bad += "群状态: 没标出谁在忙 <" + line + ">"
        if (!line.contains("贝塔(你)")) bad += "群状态: 没标出自己 <" + line + ">"

        println(
            "[selfCheck] 并发峰值=" + peakParallel +
                " 上下文: 全量=" + roomy.fullChars + "字 → 裁剪后=" + roomy.usedChars +
                "字（省 " + (roomy.fullChars - roomy.usedChars) + "）紧档=" + tight.usedChars + "字" +
                " 提及任务=" + two.size + " 无@任务=" + none.size,
        )
        return bad
    }

    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(kv.toMap())
}

class ProtocolSelfCheckTest {
    @Test
    fun selfCheckPasses() {
        val bad = ProtocolSelfCheck.selfCheck()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
