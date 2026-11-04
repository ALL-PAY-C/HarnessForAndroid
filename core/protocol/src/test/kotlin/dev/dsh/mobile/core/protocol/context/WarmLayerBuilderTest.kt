package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.model.context.KeyFile
import dev.dsh.mobile.core.model.context.Pitfall
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmNote
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.8-S2 温层生成器单测：fake 模型调用器，不联网、不碰 Room。
 *
 * 覆盖五个场景（对应任务书 4) 的六条）：首次生成 / 增量合并 / 解析失败 / 预算裁剪 / 触发判定。
 */
class WarmLayerBuilderTest {

    private fun builder(
        reply: String,
        prompts: MutableList<String> = ArrayList(),
        clock: () -> Long = { 1_000L },
    ) = WarmLayerBuilder(
        callModel = { prompt ->
            prompts += prompt
            reply
        },
        clock = clock,
    )

    private fun history(vararg rows: Pair<Long, String>) =
        rows.mapIndexed { i, (rowId, text) -> WarmMessage(rowId, if (i % 2 == 0) "user" else "assistant", text) }

    private val nineSections = """
        主要目标：给 HFA 做一个伪无限上下文温层
        硬约束：
        - 不加第三方依赖
        - 不 git commit
        已否决：
        - 用 SharedPrefs 存温层（理由：S1 已经定了 Room 表）
        踩坑：
        - Room 实体没有 rowid（原因：实体主键是 TEXT id；修法：加一条投影查询）
        关键文件：
        - core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/context/WarmLayerBuilder.kt:118 —— 生成入口
        - core/model/src/main/kotlin/dev/dsh/mobile/core/model/context/WarmLayer.kt:79 —— 温层结构
        已决策：
        - 温层刷新按 rowid 水位去重
        待办：
        - 接 prompt 装配（S3）
        当前工作：写 S2 生成器
        下一步：跑四模块测试
    """.trimIndent()

    // ---------------------------------------------------------------- 场景 1：首次生成

    @Test
    fun firstBuildParsesNineSectionsAndAdvancesWatermark() = runBlocking {
        val prompts = ArrayList<String>()
        val report = builder(nineSections, prompts).build(
            sessionId = "s1",
            history = history(11L to "开始重构温层", 12L to "先做数据层"),
            previous = null,
            warmBudgetChars = 0,
        )

        val layer = report.layer!!
        assertEquals("给 HFA 做一个伪无限上下文温层", layer.primaryGoal)
        assertEquals(listOf("不加第三方依赖", "不 git commit"), layer.hardConstraints)
        assertEquals(1, layer.rejectedOptions.size)
        assertEquals("用 SharedPrefs 存温层", layer.rejectedOptions[0].text)
        assertEquals("S1 已经定了 Room 表", layer.rejectedOptions[0].reason)
        assertEquals(1, layer.pitfalls.size)
        assertEquals("Room 实体没有 rowid", layer.pitfalls[0].symptom)
        assertEquals("实体主键是 TEXT id", layer.pitfalls[0].cause)
        assertEquals("加一条投影查询", layer.pitfalls[0].fix)
        assertEquals(2, layer.keyFiles.size)
        assertEquals(
            "core/protocol/src/main/kotlin/dev/dsh/mobile/core/protocol/context/WarmLayerBuilder.kt",
            layer.keyFiles[0].path,
        )
        assertEquals(118, layer.keyFiles[0].line)
        assertEquals(listOf(WarmNote("温层刷新按 rowid 水位去重")), layer.decisions)
        assertEquals(listOf("接 prompt 装配（S3）"), layer.openTodos)
        assertEquals("写 S2 生成器", layer.currentWork)
        assertEquals("跑四模块测试", layer.nextStep)
        // 水位推进到本次最后一条 rowid，版本从 1 起，时间戳由注入的时钟盖章。
        assertEquals(12L, layer.coveredUntilMessageId)
        assertEquals(1, layer.warmVersion)
        assertEquals(1_000L, layer.updatedAt)
        assertTrue(report.refreshed)
        // 提示词里带了新历史与九段格式（模型侧唯一输入）。
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].contains("user#11"))
        assertTrue(prompts[0].contains("下一步"))
    }

    // ---------------------------------------------------------------- 场景 2：增量合并

    @Test
    fun incrementalMergeKeepsLockedDedupesPinnedAndOverwritesSoft() = runBlocking {
        val previous = WarmLayer(
            primaryGoal = "旧目标",
            hardConstraints = listOf("不联网", "不加依赖"),
            rejectedOptions = listOf(WarmNote("用 SharedPrefs 存温层", "旧理由")),
            pitfalls = listOf(Pitfall(symptom = "Room 实体没有 rowid", cause = "", fix = "")),
            keyFiles = listOf(KeyFile("WarmLayer.kt", 79, "温层结构")),
            decisions = listOf(WarmNote("按 rowid 去重")),
            openTodos = listOf("旧待办"),
            currentWork = "旧工作",
            nextStep = "旧下一步",
            coveredUntilMessageId = 10L,
            warmVersion = 1,
        )
        // 模型这次只提到少量段落：LOCKED 空段必须回退旧值，PINNED 同 key 只补细节，SOFT 直接覆盖。
        val reply = """
            硬约束：
            - 不加依赖
            已决策：
            - 按 rowid 去重（理由：水位要抗压缩）
            待办：
            - 新待办
            当前工作：新工作
            下一步：新下一步
        """.trimIndent()

        val report = builder(reply).build("s1", history(21L to "又干了一轮"), previous, 0)
        val layer = report.layer!!

        assertEquals("旧目标", layer.primaryGoal)
        assertEquals(listOf("不联网", "不加依赖"), layer.hardConstraints)
        assertEquals(1, layer.rejectedOptions.size)
        assertEquals("用 SharedPrefs 存温层", layer.rejectedOptions[0].text)
        assertEquals(1, layer.pitfalls.size)
        assertEquals(1, layer.keyFiles.size)
        assertEquals(1, layer.decisions.size)
        assertEquals("按 rowid 去重", layer.decisions[0].text)
        assertEquals("水位要抗压缩", layer.decisions[0].reason)
        assertEquals(listOf("新待办"), layer.openTodos)
        assertEquals("新工作", layer.currentWork)
        assertEquals("新下一步", layer.nextStep)
        assertEquals(21L, layer.coveredUntilMessageId)
        assertEquals(2, layer.warmVersion)
    }

    // ---------------------------------------------------------------- 场景 3：解析失败

    @Test
    fun garbageOutputKeepsOldLayerAndDoesNotThrow() = runBlocking {
        val previous = WarmLayer(primaryGoal = "旧目标", hardConstraints = listOf("不联网"))
        val report = builder("抱歉，我不知道你在说什么。这一段完全没有标题。").build(
            "s1",
            history(31L to "随便聊聊"),
            previous,
            0,
        )

        assertNull(report.layer)
        assertFalse(report.refreshed)
        assertTrue(report.message.contains("解析"))
        assertEquals("旧目标", previous.primaryGoal)
        assertEquals(listOf("不联网"), previous.hardConstraints)
    }

    // ---------------------------------------------------------------- 场景 4：预算裁剪

    @Test
    fun oversizedLayerTrimsSoftFirstAndKeepsEveryLockedItem() = runBlocking {
        val todos = (1..10).joinToString("\n") { "- 待办第 " + it + " 条要把三段结构都写清楚并且跑完四模块测试" }
        val reply = """
            主要目标：把温层压进预算里
            硬约束：
            - 约束一
            - 约束二
            - 约束三
            - 约束四
            - 约束五
            待办：
            $todos
            当前工作：写裁剪
            下一步：验证裁剪
        """.trimIndent()

        // 预算故意卡在「LOCKED + 一两条待办」的位置：只有裁 SOFT 才能压进去。
        val budget = 200
        val report = builder(reply).build("s1", history(41L to "又攒了新消息"), null, budget)
        val layer = report.layer!!

        // LOCKED 一条不丢。
        assertEquals(5, layer.hardConstraints.size)
        assertEquals("把温层压进预算里", layer.primaryGoal)
        // SOFT 被裁：待办少于 10 条，必要时当前工作 / 下一步让位。
        assertTrue("待办应被裁剪，实际 " + layer.openTodos.size, layer.openTodos.size < 10)
        assertTrue(layer.render().length <= budget || layer.openTodos.isEmpty())
        // LOCKED 文本逐条还在渲染里（不是只留在字段里）。
        listOf("约束一", "约束二", "约束三", "约束四", "约束五").forEach {
            assertTrue(it + " 丢了", layer.render().contains(it))
        }
    }

    // ---------------------------------------------------------------- 场景 5：触发判定

    @Test
    fun triggerKeepsWarmAndCompactionOnSeparateThresholds() {
        // 39 条不刷、40 条刷。
        assertEquals(WarmDecision.NONE, WarmTrigger.decide(39, 100, 4_000))
        assertEquals(WarmDecision.WARM_ONLY, WarmTrigger.decide(40, 100, 4_000))
        assertFalse(WarmTrigger.shouldRefreshWarm(39, 100, 4_000))
        assertTrue(WarmTrigger.shouldRefreshWarm(40, 100, 4_000))
        // 0.80 预算走真压缩，哪怕轮次早就够了（压缩永远优先）。
        assertEquals(WarmDecision.COMPACT, WarmTrigger.decide(0, 3_200, 4_000))
        assertEquals(WarmDecision.COMPACT, WarmTrigger.decide(99, 3_200, 4_000))
        // /warm 手动命令不看轮次与预算。
        assertEquals(WarmDecision.WARM_ONLY, WarmTrigger.decide(0, 3_900, 4_000, forced = true))
        // 温层独立预算 = 压缩预算的 25%。
        assertEquals(1_000, WarmTrigger.warmBudgetChars(4_000))
        assertEquals(0, WarmTrigger.warmBudgetChars(0))
    }
}
