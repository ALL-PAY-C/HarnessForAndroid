package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.mcp.ToolSensitivity
import dev.dsh.mobile.core.platform.device.CapabilityTier
import dev.dsh.mobile.core.platform.device.CapabilityTiers
import dev.dsh.mobile.core.platform.device.DeviceCapability
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

    // ---- T4.5：六类系统能力的三档 ----

    @Test
    fun capabilityDefaultsToAsk() {
        // 没配置过（空串）就是每次询问，绝不静默放行。
        val verdict = ToolPolicy.verdict("phone", args("action" to "contacts"))
        assertTrue("默认档应逐次确认，实际 " + verdict, verdict is ToolAuth.Ask)
        assertEquals(DeviceCapability.Contacts, (verdict as ToolAuth.Ask).capability)
    }

    @Test
    fun capabilityAlwaysTierAllows() {
        val tiers = CapabilityTiers.encode(mapOf(DeviceCapability.Contacts to CapabilityTier.Always))
        assertEquals(
            ToolAuth.Allow,
            ToolPolicy.verdict("phone", args("action" to "contacts"), capabilityTiers = tiers),
        )
    }

    @Test
    fun capabilitySessionTierAllowsOnlyAfterGrant() {
        val tiers = CapabilityTiers.encode(mapOf(DeviceCapability.Location to CapabilityTier.Session))
        val before = ToolPolicy.verdict("phone", args("action" to "location"), capabilityTiers = tiers)
        assertTrue("本会话还没确认过，应问：" + before, before is ToolAuth.Ask)
        val after = ToolPolicy.verdict(
            "phone",
            args("action" to "location"),
            capabilityTiers = tiers,
            sessionGranted = setOf(DeviceCapability.Location),
        )
        assertEquals(ToolAuth.Allow, after)
    }

    @Test
    fun capabilityAskTierStaysAskEvenIfGranted() {
        // Ask 档不看本会话记录：每次都问。
        val tiers = CapabilityTiers.encode(mapOf(DeviceCapability.Sms to CapabilityTier.Ask))
        val verdict = ToolPolicy.verdict(
            "phone",
            args("action" to "sms_send"),
            capabilityTiers = tiers,
            sessionGranted = setOf(DeviceCapability.Sms),
        )
        assertTrue("Ask 档应每次都问，实际 " + verdict, verdict is ToolAuth.Ask)
    }

    @Test
    fun brokenCapabilityTiersFallBackToAsk() {
        // 配置串坏了只该多问一次，不能变成放行。
        val verdict = ToolPolicy.verdict(
            "phone",
            args("action" to "audio_record"),
            capabilityTiers = "{\"microphone\":}",
        )
        assertTrue("坏配置应回落 Ask，实际 " + verdict, verdict is ToolAuth.Ask)
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