package dev.dsh.mobile.toolbridge.phone

import dev.dsh.mobile.core.common.device.CapabilityActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 权限引导与动作状态的纯逻辑：真实权限状态 → 引导步骤/状态文案，
 * 动作目录与 PhoneBridge 的 action 词表一一对应，动作结果只有「成功/失败」两态。
 */
class PhonePermissionTest {

    @Test
    fun controlRequiresEnabledAndBound() {
        assertFalse(PhonePermissionState(serviceEnabled = true, serviceBound = false).canControl)
        assertFalse(PhonePermissionState(serviceEnabled = false, serviceBound = true).canControl)
        assertTrue(PhonePermissionState(serviceEnabled = true, serviceBound = true).canControl)
    }

    @Test
    fun accessibilityStepIsPendingWhileNotBound() {
        val s = PhonePermissionState(serviceEnabled = true, serviceBound = false)
        assertEquals(PhoneStepStatus.Pending, s.status(PhonePermissionStep.Accessibility))
        assertEquals(PhoneStepStatus.Granted, PhonePermissionState(true, true).status(PhonePermissionStep.Accessibility))
        assertEquals(PhoneStepStatus.Off, PhonePermissionState(false, false).status(PhonePermissionStep.Accessibility))
    }

    @Test
    fun overlayStepFollowsCanDrawOverlays() {
        assertEquals(PhoneStepStatus.Off, PhonePermissionState(overlayGranted = false).status(PhonePermissionStep.Overlay))
        assertEquals(PhoneStepStatus.Granted, PhonePermissionState(overlayGranted = true).status(PhonePermissionStep.Overlay))
    }

    @Test
    fun headlineCountsRealGaps() {
        // 两步都缺时说「两步」；只剩无障碍没连上时说「一步」；能操控就是可用（缺悬浮窗不影响操控）。
        assertEquals("还差 2 步才能操控", PhonePermissionState().headline())
        assertEquals("还差一步才能操控", PhonePermissionState(serviceEnabled = true, serviceBound = false, overlayGranted = true).headline())
        assertEquals("手机控制可用", PhonePermissionState(serviceEnabled = true, serviceBound = true).headline())
    }

    @Test
    fun missingHintNamesTheNextAction() {
        assertEquals(
            "先去 设置 → 无障碍 开启「DSH Mobile 手机控制」。再给悬浮窗授权（只影响实时状态浮层，不影响操控）。",
            PhonePermissionState().missingHint(),
        )
        assertEquals(
            "系统已勾选，服务还没连上：回到设置确认服务开关是打开的。",
            PhonePermissionState(serviceEnabled = true, serviceBound = false, overlayGranted = true).missingHint(),
        )
        assertEquals("", PhonePermissionState(true, true, true).missingHint())
    }

    @Test
    fun missingStepsListAccessibilityFirst() {
        val missing = PhonePermissionState().missing
        assertEquals(listOf(PhonePermissionStep.Accessibility, PhonePermissionStep.Overlay), missing)
        assertTrue(PhonePermissionState(serviceEnabled = true, serviceBound = true, overlayGranted = true).missing.isEmpty())
    }

    @Test
    fun actionCatalogMatchesBridgeVerbs() {
        // 与 core:protocol ToolCatalog 里 phone 的 action 枚举保持同一组词（那里是模型看的声明，这里是页面看的目录）。
        assertEquals(
            setOf(
                "tap", "swipe", "type", "key", "back", "home", "recents",
                "scroll", "pinch", "drag", "readscreen", "tree", "wait_for",
                "currentapp", "apps", "sequence", "openapp", "screenshot",
                // T4.5 六类系统能力动作
                "contacts", "sms_list", "sms_send", "dial", "location", "audio_record",
            ),
            PhoneAction.entries.map { it.id }.toSet(),
        )
        assertEquals(PhoneAction.entries.size, PhoneAction.entries.map { it.id }.toSet().size)
        // 六个能力动作的词表来自 CapabilityActions，这里再核一次，防止三处分叉。
        assertEquals(CapabilityActions.PHONE_ACTIONS.toSet(), PhoneAction.entries.map { it.id }.filter { CapabilityActions.isCapabilityAction(it) }.toSet())
        assertEquals(PhoneAction.Tap, PhoneAction.of("tap"))
        assertEquals(null, PhoneAction.of("nope"))
    }

    @Test
    fun everyActionHasLabelAndHint() {
        PhoneAction.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.hint.isNotBlank())
        }
    }

    @Test
    fun recordStatusIsBinary() {
        assertEquals(PhoneActionStatus.Ok, PhoneActionRecord("tap", "点击", true, "ok", 1L).status())
        assertEquals(PhoneActionStatus.Failed, PhoneActionRecord("tap", "点击", false, "boom", 1L).status())
    }

    @Test
    fun actionLogKeepsNewestFirstAndCapsLength() {
        PhoneActionLog.clear()
        repeat(PhoneActionLog.LIMIT + 5) { i -> PhoneActionLog.record("tap", i % 2 == 0, "m$i", at = i.toLong()) }
        val records = PhoneActionLog.records.value
        assertEquals(PhoneActionLog.LIMIT, records.size)
        assertEquals((PhoneActionLog.LIMIT + 4).toLong(), records.first().at)
        assertEquals("点击", records.first().label)
        PhoneActionLog.clear()
        assertTrue(PhoneActionLog.records.value.isEmpty())
    }
}
