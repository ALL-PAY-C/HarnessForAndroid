package dev.dsh.mobile.core.platform.device

import org.junit.Assert.assertEquals
import org.junit.Test

/** T4.5：三档配置的解析/回落。配置坏了只该多问一次，不能变成放行。 */
class CapabilityTiersTest {

    @Test
    fun blankMeansAllAsk() {
        assertEquals(CapabilityTiers.DEFAULT, CapabilityTiers.parse(""))
        assertEquals(CapabilityTiers.DEFAULT, CapabilityTiers.parse(null))
        DeviceCapability.entries.forEach { cap ->
            assertEquals(CapabilityTier.Ask, CapabilityTiers.tierOf("", cap))
        }
    }

    @Test
    fun encodeParseRoundTrip() {
        val map = mapOf(
            DeviceCapability.Camera to CapabilityTier.Always,
            DeviceCapability.Sms to CapabilityTier.Session,
            DeviceCapability.Location to CapabilityTier.Ask,
        )
        val raw = CapabilityTiers.encode(map)
        assertEquals(CapabilityTier.Always, CapabilityTiers.tierOf(raw, DeviceCapability.Camera))
        assertEquals(CapabilityTier.Session, CapabilityTiers.tierOf(raw, DeviceCapability.Sms))
        assertEquals(CapabilityTier.Ask, CapabilityTiers.tierOf(raw, DeviceCapability.Location))
        // 没写进 map 的能力补 Ask，不是补 Always。
        assertEquals(CapabilityTier.Ask, CapabilityTiers.tierOf(raw, DeviceCapability.Microphone))
    }

    @Test
    fun unknownKeysAndTiersAreIgnored() {
        val raw = "{\"nfc\":\"always\",\"camera\":\"sometimes\",\"contacts\":\"session\"}"
        assertEquals(CapabilityTier.Ask, CapabilityTiers.tierOf(raw, DeviceCapability.Camera))
        assertEquals(CapabilityTier.Session, CapabilityTiers.tierOf(raw, DeviceCapability.Contacts))
    }

    @Test
    fun malformedInputFallsBackToAsk() {
        // 逐对解析能救的残缺串（缺右括号、个别对没写完）仍按能认出的那几项生效；
        // 真正救不回来（一对都认不出）才整体回落 Ask。
        listOf("{\"camera\":}", "not json at all", "{}{}", "").forEach { raw ->
            assertEquals("坏配置 " + raw + " 应回落 Ask", CapabilityTier.Ask, CapabilityTiers.tierOf(raw, DeviceCapability.Camera))
        }
    }

    @Test
    fun withReplacesOneEntryOnly() {
        val base = CapabilityTiers.parse(CapabilityTiers.encode(mapOf(DeviceCapability.Dial to CapabilityTier.Always)))
        val next = CapabilityTiers.with(base, DeviceCapability.Camera, CapabilityTier.Session)
        assertEquals(CapabilityTier.Session, next[DeviceCapability.Camera])
        assertEquals(CapabilityTier.Always, next[DeviceCapability.Dial])
        assertEquals(CapabilityTier.Ask, next[DeviceCapability.Sms])
    }

    @Test
    fun capabilityActionTableIsConsistent() {
        // 六个动作词都认得，且都能映射到能力项；词表只有一份。
        assertEquals(6, CapabilityActions.PHONE_ACTIONS.size)
        CapabilityActions.PHONE_ACTIONS.forEach { action ->
            assertEquals(true, CapabilityActions.isCapabilityAction(action))
            assertEquals(true, CapabilityActions.capabilityOf(action) != null)
        }
        assertEquals(DeviceCapability.Sms, CapabilityActions.capabilityOf(CapabilityActions.SMS_SEND))
        assertEquals(null, CapabilityActions.capabilityOf("tap"))
    }
}
