package dev.dsh.mobile.feature.chat

import dev.dsh.mobile.core.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8：席位号必须唯一（删除后再加人不能与留下的空洞撞号）。 */
class ParticipantSlotTest {

    private fun p(id: String, slot: Int) = Participant(id = id, modelId = "m", name = id, slot = slot)

    @Test
    fun firstSeatIsZero() {
        assertEquals(0, emptyList<Participant>().nextFreeSlot())
    }

    @Test
    fun nextSeatIsMaxPlusOne() {
        assertEquals(2, listOf(p("a", 0), p("b", 1)).nextFreeSlot())
    }

    @Test
    fun removingMiddleSeatDoesNotCauseCollision() {
        val original = listOf(p("a", 0), p("b", 1), p("c", 2))
        // 删掉中间那个（slot=1），再加一个：size 会给出 2，与现存 slot=2 撞号。
        val afterRemove = original.filterNot { it.id == "b" }
        assertEquals("旧实现 participants.size 会撞号", afterRemove.size, 2)
        val slot = afterRemove.nextFreeSlot()
        assertEquals(3, slot)
        assertTrue("新席位不能与现存任何席位重复", afterRemove.none { it.slot == slot })
    }

    @Test
    fun everySeatStaysUniqueAcrossAddRemoveAdd() {
        var list = listOf(p("a", 0), p("b", 1))
        list = list + p("c", list.nextFreeSlot())
        list = list.filterNot { it.id == "b" }
        list = list + p("d", list.nextFreeSlot())
        assertEquals("席位号不能重复", list.size, list.map { it.slot }.distinct().size)
    }
}
