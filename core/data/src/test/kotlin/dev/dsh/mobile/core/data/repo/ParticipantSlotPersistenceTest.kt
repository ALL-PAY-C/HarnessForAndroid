package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T8：席位号必须原样穿过持久化与远端合并。
 * 只要某处按列表下标重新编号，两个成员就会落到同一个 slot —— 派活时就成了「一个 Agent 干两人的活」，
 * 或者反过来一个人占两个席位。这里锁住三件事：往返不丢、空洞保留、合并不重排。
 */
class ParticipantSlotPersistenceTest {

    private fun session(vararg slots: Int) = Session(
        id = "s1",
        title = "群",
        cwd = "/tmp",
        kind = SessionKind.Group,
        participants = slots.map { Participant(id = "p$it", modelId = "m$it", name = "成员$it", slot = it) },
    )

    @Test
    fun slotSurvivesEntityRoundTrip() {
        val back = session(0, 1, 2).toEntity().toModel()
        assertEquals(listOf(0, 1, 2), back.participants.map { it.slot })
        assertEquals(listOf("p0", "p1", "p2"), back.participants.map { it.id })
    }

    @Test
    fun holesInSeatsAreNotRenumbered() {
        // 删过人留下的空洞（0,2,5）不能被压成 0,1,2：下游任务按 slot 认人。
        val back = session(0, 2, 5).toEntity().toModel()
        assertEquals(listOf(0, 2, 5), back.participants.map { it.slot })
    }

    @Test
    fun remoteMergeKeepsLocalSeatNumbers() {
        val local = session(0, 3).toEntity()
        val remote = session(0, 1, 2).copy(title = "远端改名")
        val merged = mergeRemoteSession(local, remote).toModel()
        // H1：本地 title/participants 是权威，远端只覆盖 status 与 updatedAt。
        assertEquals("群", merged.title)
        assertEquals("本地席位不能被远端列表下标覆盖", listOf(0, 3), merged.participants.map { it.slot })
    }

    @Test
    fun twoMembersNeverShareOneSeat() {
        val back = session(0, 0).toEntity().toModel()
        // 数据层不负责去重（允许重复 modelId 做并行），但必须如实保留，绝不擅自合并。
        assertEquals(2, back.participants.size)
        assertNotEquals("两个成员变成一条即数据丢失", 1, back.participants.size)
    }
}
