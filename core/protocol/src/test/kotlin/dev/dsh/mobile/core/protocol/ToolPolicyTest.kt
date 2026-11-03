package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.1b：授权判定表。判据是「档位 + 复合动作」，改动这里必须同步改 report/规划书的判据表。 */
class ToolPolicyTest {
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun phoneReadActionsAreAllowed() {
        listOf("tree", "readscreen", "currentapp", "apps", "screenshot", "back", "home").forEach { action ->
            val verdict = ToolPolicy.verdict("phone", args("action" to action))
            assertEquals("phone/" + action + " 应直接放行", ToolAuth.Allow, verdict)
        }
    }

    @Test
    fun phoneCompoundActionsAsk() {
        listOf("sequence", "pinch", "drag", "type").forEach { action ->
            val verdict = ToolPolicy.verdict("phone", args("action" to action))
            assertTrue("phone/" + action + " 应逐次确认，实际 " + verdict, verdict is ToolAuth.Ask)
        }
    }

    @Test
    fun singleTapIsDeviceControlNotIrreversible() {
        // 单点按一次可撤销（用户能点回去），不该每点一下弹一次卡，否则 agent 没法用。
        assertEquals(
            ToolSensitivity.DeviceControl,
            ToolPolicy.sensitivity("phone", args("action" to "tap")),
        )
        assertEquals(ToolAuth.Allow, ToolPolicy.verdict("phone", args("action" to "tap")))
    }

    @Test
    fun deleteIsIrreversibleButMoveIsNot() {
        assertTrue(ToolPolicy.verdict("fs_op", args("op" to "delete", "src" to "/sdcard/a")) is ToolAuth.Ask)
        assertEquals(
            ToolAuth.Allow,
            ToolPolicy.verdict("fs_op", args("op" to "move", "src" to "/sdcard/a", "dst" to "/sdcard/b")),
        )
    }

    @Test
    fun readOnlyToolsNeverAsk() {
        listOf("fs_read", "fs_list", "read_output", "channel_list", "bg_status", "phone_tree").forEach { name ->
            assertEquals(name + " 只读，不该确认", ToolAuth.Allow, ToolPolicy.verdict(name, buildJsonObject { }))
        }
    }

    @Test
    fun shellAndSubagentStayIrreversible() {
        assertTrue(ToolPolicy.verdict("shell", args("command" to "ls")) is ToolAuth.Ask)
        assertTrue(ToolPolicy.verdict("subagent", args("task" to "x")) is ToolAuth.Ask)
    }

    @Test
    fun unknownToolIsConservativeNotAllowed() {
        // 不认识的名字不敢声称只读：归 ReversibleWrite，但仍放行（拦不拦不是未知名的责任）。
        assertEquals(
            ToolSensitivity.ReversibleWrite,
            ToolPolicy.sensitivity("brand_new_tool", buildJsonObject { }),
        )
    }
}