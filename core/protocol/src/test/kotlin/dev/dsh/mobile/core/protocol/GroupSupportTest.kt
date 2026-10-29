package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 判别测试：`automatonVerdict` 只对最后一行里的明确结论词投票，
 * 空输出 / 普通总结 / 无法识别一律停（Done）并留可诊断日志，不再默认「继续」空转。
 */
class GroupSupportTest {

    @Test
    fun `empty review output stops instead of looping`() {
        assertEquals(Verdict.Done, automatonVerdict(""))
    }

    @Test
    fun `whitespace only review output stops`() {
        assertEquals(Verdict.Done, automatonVerdict("   \n\n  "))
    }

    @Test
    fun `normal summary without verdict stops`() {
        assertEquals(Verdict.Done, automatonVerdict("x 现在是 10，没有要补充的"))
    }

    @Test
    fun `explicit continue keeps looping`() {
        assertEquals(Verdict.More, automatonVerdict("回写 x=2\n【继续】"))
    }

    @Test
    fun `explicit done stops`() {
        assertEquals(Verdict.Done, automatonVerdict("x 已经是 10\n【完成】"))
    }

    @Test
    fun `explicit rework`() {
        assertEquals(Verdict.Rework, automatonVerdict("x 算错了\n【打回】进位错"))
    }

    @Test
    fun `verdict keyword in earlier line is ignored`() {
        assertEquals(Verdict.More, automatonVerdict("如果不对就打回重做。\n【继续】"))
    }

    @Test
    fun `rework wins over done in same last line`() {
        assertEquals(Verdict.Rework, automatonVerdict("【打回】但也【完成】了"))
    }

    @Test
    fun `done wins over continue in same last line`() {
        assertEquals(Verdict.Done, automatonVerdict("【完成】但【继续】检查"))
    }

    @Test
    fun `unrecognized output logs a diagnosable reason`() {
        automatonVerdict("随便写一句没有结论的话")
        assertTrue(AppLog.dump().contains("自动机：验收结论未识别"))
    }

    // ---- 群组并行分工：默认并行，无 @ 按句子切给各成员

    @Test
    fun `two members no mention two sentences split in parallel`() {
        val cfg = LlmConfig("http://127.0.0.1:1", "k", "m")
        val plan = GroupPlan(
            listOf(
                GroupMember("a1", "阿尔法", "", cfg),
                GroupMember("a2", "贝塔", "", cfg),
            ),
        )
        val tasks = planGroupTasks(
            "s",
            plan,
            "写一个登录页。再写一个注册页。",
            emptyList(),
            mode = GroupMode.Parallel,
        )
        assertEquals(2, tasks.size)
        assertTrue("并行任务不得有依赖", tasks.all { it.dependsOn.isEmpty() })
        val p1 = tasks.firstOrNull { it.agentId == "a1" }?.prompt.orEmpty()
        val p2 = tasks.firstOrNull { it.agentId == "a2" }?.prompt.orEmpty()
        assertTrue("a1 应含自己的切片", p1.contains("登录页"))
        assertFalse("a1 不应含对方的切片", p1.contains("注册页"))
        assertTrue("a2 应含自己的切片", p2.contains("注册页"))
        assertFalse("a2 不应含对方的切片", p2.contains("登录页"))
    }

    // ---- T8：策略=parallel 且席位>=2 → 每个 Participant.slot 一条独立任务；只有显式单包 / chain 才串行

    private fun cfg() = LlmConfig("http://127.0.0.1:1", "k", "m")

    private fun planOf(vararg names: String, routing: GroupRouting = GroupRouting.All): GroupPlan {
        val c = cfg()
        return GroupPlan(names.map { GroupMember("id-" + it, it, "", c) }, routing = routing)
    }

    @Test
    fun `parallel gives every slot its own task with its own slice`() {
        val plan = planOf("阿尔法", "贝塔", "伽马")
        val tasks = planGroupTasks(
            "s",
            plan,
            "写登录页。写注册页。写找回密码页。",
            emptyList(),
            mode = GroupMode.Parallel,
        )
        assertEquals("三个席位三条任务", 3, tasks.size)
        assertEquals("一人一条", listOf("id-阿尔法", "id-贝塔", "id-伽马"), tasks.map { it.agentId })
        assertTrue("并行任务不得有依赖", tasks.all { it.dependsOn.isEmpty() })
        assertTrue("每人只拿自己那段", tasks.all { it.prompt.contains("只负责这部分") })
        assertFalse("首人 prompt 不复制别人的切片", tasks[0].prompt.contains("注册页"))
        assertFalse("次人 prompt 不复制别人的切片", tasks[1].prompt.contains("登录页"))
    }

    @Test
    fun `parallel with mentions fans out one task per slot and drops chain wording`() {
        val plan = planOf("阿尔法", "贝塔")
        val tasks = planGroupTasks(
            "s",
            plan,
            "@阿尔法 写登录页 @贝塔 写注册页",
            emptyList(),
            mode = GroupMode.Parallel,
        )
        assertEquals("点名并行也是每个席位一条", 2, tasks.size)
        assertEquals(listOf("id-阿尔法", "id-贝塔"), tasks.map { it.agentId })
        assertTrue("并行任务不得有依赖", tasks.all { it.dependsOn.isEmpty() })
        assertTrue("并行档不得留接力话术", tasks.none { it.prompt.contains("只补充他们没说到") })
        assertTrue("分工提示是并行版", tasks.all { it.hint.contains("并行") })
        assertTrue("各拿各的点名段", tasks[0].prompt.contains("登录页"))
        assertFalse("不复制别人的点名段", tasks[0].prompt.contains("注册页"))
        assertTrue("各拿各的点名段", tasks[1].prompt.contains("注册页"))
        assertFalse("不复制别人的点名段", tasks[1].prompt.contains("登录页"))
    }

    @Test
    fun `parallel five slots keeps every slot instead of truncating at speaker cap`() {
        val plan = planOf("甲一", "乙二", "丙三", "丁四", "戊五")
        val tasks = planGroupTasks(
            "s",
            plan,
            "一件事。两件事。三件事。四件事。五件事。",
            emptyList(),
            mode = GroupMode.Parallel,
        )
        assertEquals("席位不能被发言上限砍掉", 5, tasks.size)
        assertEquals(plan.members.map { it.id }, tasks.map { it.agentId })
        assertTrue("并行任务不得有依赖", tasks.all { it.dependsOn.isEmpty() })
    }

    @Test
    fun `parallel keeps duplicate seats of the same agent apart`() {
        val c = cfg()
        val plan = GroupPlan(
            listOf(
                GroupMember("seat-0", "同名", "", c),
                GroupMember("seat-1", "同名", "", c),
            ),
        )
        val tasks = planGroupTasks("s", plan, "算甲。算乙。", emptyList(), mode = GroupMode.Parallel)
        assertEquals("同名两席位各一条任务", 2, tasks.size)
        assertEquals(listOf("seat-0", "seat-1"), tasks.map { it.agentId })
        assertTrue("并行任务不得有依赖", tasks.all { it.dependsOn.isEmpty() })
    }

    @Test
    fun `chain strategy stays serial with dependencies`() {
        val plan = planOf("阿尔法", "贝塔")
        val tasks = planGroupTasks("s", plan, "看看这个报错", emptyList(), mode = GroupMode.Chain)
        assertEquals(2, tasks.size)
        assertTrue("首任务无依赖", tasks[0].dependsOn.isEmpty())
        assertEquals("次任务依赖首任务", listOf(tasks[0].id), tasks[1].dependsOn)
    }

    @Test
    fun `explicit single agent round robin stays one task`() {
        val plan = planOf("阿尔法", "贝塔", "伽马", routing = GroupRouting.RoundRobin)
        val tasks = planGroupTasks("s", plan, "你俩同时干", emptyList(), mode = GroupMode.Parallel)
        assertEquals("显式单包只有一个任务", 1, tasks.size)
        assertTrue(tasks.single().dependsOn.isEmpty())
    }

    @Test
    fun `single participant parallel degrades to one serial task`() {
        val plan = planOf("阿尔法")
        val tasks = planGroupTasks("s", plan, "写个登录页", emptyList(), mode = GroupMode.Parallel)
        assertEquals(1, tasks.size)
        assertTrue(tasks.single().dependsOn.isEmpty())
    }

    @Test
    fun `detectGroupMode defaults to parallel for two members`() {
        assertEquals(GroupMode.Parallel, detectGroupMode("帮我看看这个报错", 2))
    }

    @Test
    fun `detectGroupMode sequential wording stays chain`() {
        assertEquals(GroupMode.Chain, detectGroupMode("先看看这个报错再修", 2))
    }

    @Test
    fun `detectGroupMode single member stays chain`() {
        assertEquals(GroupMode.Chain, detectGroupMode("你俩同时干", 1))
    }
}
