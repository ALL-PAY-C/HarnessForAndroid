package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-6：todo / goal / 用量 / 压缩统计的只读投影。 */
class SessionProjectionTest {
    @Test
    fun writeTodosGivesUiReadableSnapshot() {
        val projections = SessionProjections()
        projections.writeTodos(
            "s1",
            listOf(
                ProjectedTodo("读上下文", ProjectedTodo.STATUS_DONE),
                ProjectedTodo("改装配", ProjectedTodo.STATUS_DOING),
                ProjectedTodo("跑测试"),
            ),
        )
        val snap = projections.observe("s1").value
        assertEquals(3, snap.todos.size)
        assertTrue(snap.hasTodos)
        assertEquals(1, snap.doneCount)
        assertEquals("改装配", snap.runningTodo?.content)
        // 会话隔离：别的会话看不到这份待办。
        assertFalse(projections.snapshot("s2").hasTodos)
    }

    @Test
    fun parseTodosRejectsBadShapes() {
        val ok = parseTodos(buildJsonObject {
            putJsonArray("todos") {
                addJsonObject { put("content", "a"); put("status", "completed") }
                addJsonObject { put("content", "b") }
            }
        })!!
        assertEquals(2, ok.size)
        assertEquals(ProjectedTodo.STATUS_PENDING, ok[1].status)

        assertNull("缺 todos", parseTodos(buildJsonObject { put("x", "y") }))
        assertNull("越界 status", parseTodos(buildJsonObject {
            putJsonArray("todos") { addJsonObject { put("content", "a"); put("status", "doing") } }
        }))
        assertNull("空 content", parseTodos(buildJsonObject {
            putJsonArray("todos") { addJsonObject { put("content", "  "); put("status", "pending") } }
        }))
    }

    @Test
    fun goalRoundTripsAndUsageAndCompactionAddUp() {
        val projections = SessionProjections()
        assertNull(projections.snapshot("s").goal)
        projections.writeGoal("s", ProjectedGoal("发版", phase = ProjectedGoal.PHASE_BLOCKED, blockedReason = "等构建"))
        val goal = projections.snapshot("s").goal!!
        assertEquals("发版", goal.objective)
        assertEquals(ProjectedGoal.PHASE_BLOCKED, goal.phase)
        assertEquals("等构建", goal.blockedReason)

        projections.noteUsage("s", usedChars = 300, fullChars = 900, budgetChars = 200)
        val usage = projections.snapshot("s").usage
        assertTrue(usage.overBudget)
        assertEquals(1.5f, usage.ratio, 0.001f)

        projections.noteCompaction("s", beforeTokens = 1000, afterTokens = 200, summaryChars = 800)
        projections.noteCompaction("s", beforeTokens = 500, afterTokens = 100, summaryChars = 400)
        val snap = projections.snapshot("s")
        assertEquals(2, snap.compaction.count)
        assertEquals(500, snap.compaction.beforeTokens)
        assertEquals(400, snap.compaction.savedTokens)
        assertEquals(400, snap.summaryChars)

        // clear 之后重新开一条干净投影：只保留 sessionId，别的状态清空。
        projections.clear("s")
        assertEquals("s", projections.snapshot("s").sessionId)
        assertNull(projections.snapshot("s").goal)
    }
}
