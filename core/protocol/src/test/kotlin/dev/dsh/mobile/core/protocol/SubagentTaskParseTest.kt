package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.protocol.llm.parseSubagentTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.3：子代理结论里的任务清单解析。
 *
 * 三种形状都要认：JSON 数组、`- 任务：标题｜说明` 列表、以及「什么都没有」。
 * 无论哪种，解析都不许抛异常、不许改动结论文本本身（文本由调用方原样带走）。
 */
class SubagentTaskParseTest {

    @Test
    fun parsesJsonArrayOfObjects() {
        val text = """
            结论：缓存层没问题，剩下三件事。
            [
              {"title": "补集成测试", "detail": "给 ExecutionPool 加并发用例"},
              {"title": "清理死代码", "detail": "删掉旧的 taskQueue"}
            ]
        """.trimIndent()

        val tasks = parseSubagentTasks(text)

        assertEquals(2, tasks.size)
        assertEquals("补集成测试", tasks[0].title)
        assertEquals("给 ExecutionPool 加并发用例", tasks[0].detail)
        assertEquals("清理死代码", tasks[1].title)
    }

    @Test
    fun parsesJsonArrayOfStringsAndAliasKeys() {
        val text = """[ "先跑一遍基线", {"task": "再压测", "description": "50 并发"} ]"""

        val tasks = parseSubagentTasks(text)

        assertEquals(2, tasks.size)
        assertEquals("先跑一遍基线", tasks[0].title)
        assertEquals("先跑一遍基线", tasks[0].detail)
        assertEquals("再压测", tasks[1].title)
        assertEquals("50 并发", tasks[1].detail)
    }

    @Test
    fun parsesBulletList() {
        val text = """
            我看了三个模块，建议这么拆：
            - 任务：补协议单测｜覆盖 JSON 与列表两种写法
            * 子任务: 接预算闸门 | 80% 与 100% 两个分支
            - 任务：写报告
        """.trimIndent()

        val tasks = parseSubagentTasks(text)

        assertEquals(3, tasks.size)
        assertEquals("补协议单测", tasks[0].title)
        assertEquals("覆盖 JSON 与列表两种写法", tasks[0].detail)
        assertEquals("接预算闸门", tasks[1].title)
        assertEquals("80% 与 100% 两个分支", tasks[1].detail)
        // 只给了标题：说明回落到标题，不是空串。
        assertEquals("写报告", tasks[2].title)
        assertEquals("写报告", tasks[2].detail)
    }

    @Test
    fun noTasksWhenConclusionHasNoList() {
        assertTrue(parseSubagentTasks("").isEmpty())
        assertTrue(parseSubagentTasks("查完了，没有发现问题。").isEmpty())
        // 半截 JSON 也算「没有任务」，不抛异常。
        assertTrue(parseSubagentTasks("任务：[ {\"title\": ").isEmpty())
    }

    @Test
    fun capsTheNumberOfTasks() {
        val text = (1..20).joinToString("\n") { "- 任务：任务$it" }
        assertEquals(8, parseSubagentTasks(text).size)
    }
}
