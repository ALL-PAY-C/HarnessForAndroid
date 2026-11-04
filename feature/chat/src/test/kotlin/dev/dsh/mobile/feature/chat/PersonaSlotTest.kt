package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginapi.SlotContribution
import dev.dsh.mobile.pluginapi.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话设置里「破甲注入」开关的挑选规则：只认 kind = setpersona，且只取一条。 */
class PersonaSlotTest {

    private fun slot(id: String, label: String, action: SlotAction?) = SlotContribution(
        slot = SlotId.ChatSettings,
        id = id,
        label = label,
        pluginId = "dsh.sauce",
        action = action,
    )

    @Test
    fun picksOnlySetPersona() {
        val list = listOf(
            slot("chat.settings.rewriter", "改写器（本会话）", null),
            slot("chat.settings.persona", "破甲注入（本会话）", SlotAction.SetPersona("词", toggle = true)),
            slot("chat.settings.matrix", "改写器：按矩阵自动换模型", null),
        )
        assertEquals("chat.settings.persona", personaSlotOf(list)?.id)
    }

    @Test
    fun duplicateContributionsRenderOnce() {
        val list = List(2) { i ->
            slot("p$i", "破甲注入（本会话）", SlotAction.SetPersona("词", toggle = true))
        }
        // 插件重复注册两条也只画一个开关：取第一条。
        assertEquals("p0", personaSlotOf(list)?.id)
    }

    @Test
    fun labelDropsEmbeddedState() {
        // 插件 label 里自带「：开（本会话）」，状态交给 Switch 表达，标题只留名字。
        val fallback = "破甲注入（本会话）"
        assertEquals("破甲注入", personaLabelOf("破甲注入：开（本会话）", fallback))
        assertEquals("破甲注入（本会话）", personaLabelOf("破甲注入（本会话）", fallback))
        assertEquals("破甲注入（本会话）", personaLabelOf("", fallback))
    }

    @Test
    fun noContributionMeansNoSwitch() {
        assertNull(personaSlotOf(emptyList()))
        assertNull(personaSlotOf(listOf(slot("x", "改写器", null))))
    }
}
