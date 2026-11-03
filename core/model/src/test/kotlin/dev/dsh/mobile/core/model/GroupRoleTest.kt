package dev.dsh.mobile.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9.7a 角色卡：五张默认卡都能渲染出内容，且禁区段必须存在（禁区是硬约束，缺了卡就没意义）。 */
class GroupRoleTest {

    @Test
    fun everyRoleRendersANonEmptyCardWithBoundarySection() {
        GroupRole.entries.forEach { role ->
            val card = role.defaultCard()
            assertTrue(role.id + " 默认卡不能为空", card.hasContent())
            val text = card.render()
            assertTrue(role.id + " 渲染结果不能为空", text.isNotBlank())
            assertTrue(role.id + " 必须带身份卡标题", text.startsWith("## 身份卡："))
            assertTrue(role.id + " 缺禁区段", text.contains("禁区："))
            assertTrue(role.id + " 禁区不能是空列表", card.boundaries.isNotEmpty())
            assertTrue(role.id + " 职责不能为空", card.duties.isNotEmpty())
        }
    }

    @Test
    fun fourFixedRolesCarryTheirOwnDutiesAndBoundaries() {
        assertEquals(listOf("supervisor", "planner", "inspector", "worker", "custom"), GroupRole.entries.map { it.id })
        // 四条共同禁区：越权执行 / 跳过检测 / 自行改规划书结论。
        listOf(GroupRole.Supervisor, GroupRole.Planner, GroupRole.Inspector, GroupRole.Worker).forEach { role ->
            val boundaries = role.defaultCard().boundaries.joinToString("\n")
            assertTrue(role.id + " 缺越权禁区", boundaries.contains("不得越权执行"))
            assertTrue(role.id + " 缺跳过检测禁区", boundaries.contains("不得跳过检测"))
            assertTrue(role.id + " 缺规划书结论禁区", boundaries.contains("不得自行改规划书结论"))
        }
        // 角色之间不能是同一张卡（否则「可配身份」等于没配）。
        assertNotEquals(GroupRole.Supervisor.defaultCard(), GroupRole.Planner.defaultCard())
        assertNotEquals(GroupRole.Inspector.defaultCard(), GroupRole.Worker.defaultCard())
    }

    @Test
    fun unknownOrNullIdFallsBackToCustom() {
        assertEquals(GroupRole.Custom, GroupRole.of(null))
        assertEquals(GroupRole.Custom, GroupRole.of(""))
        assertEquals(GroupRole.Custom, GroupRole.of("nope"))
        assertEquals(GroupRole.Worker, GroupRole.of("worker"))
    }
}
