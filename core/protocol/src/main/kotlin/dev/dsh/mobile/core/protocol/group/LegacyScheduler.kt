package dev.dsh.mobile.core.protocol.group

import dev.dsh.mobile.core.protocol.GroupMember
import dev.dsh.mobile.core.protocol.GroupPlan
import dev.dsh.mobile.core.protocol.MAX_GROUP_SPEAKERS
import dev.dsh.mobile.core.protocol.groupRoundNotice
import dev.dsh.mobile.core.protocol.orderForTurn
import dev.dsh.mobile.core.protocol.withPriority

/**
 * 旧调度：把现有「追问时上一个发言人先上 → 上一轮被挤下的人优先 → 最多 4 位、其余顺延」
 * 原样包成 [GroupScheduler]，用于默认开关下**零行为变化**。
 *
 * 等价性不是靠抄一遍顺序规则：这里直接调 [orderForTurn] / [withPriority] 本身，
 * 顺序与 [dev.dsh.mobile.core.protocol.planGroupRound] 共用同一份实现，所以不会漂。
 * 点名 / RoundRobin / Parallel 档的分支由传输层在调调度器之前就分掉（旧代码同样如此），
 * 本类只负责 planGroupRound 最后一个分支（接力/自动机默认档）的候选顺序。
 */
class LegacyScheduler(
    private val plan: GroupPlan,
    private val userText: String,
    private val priorityIds: List<String> = plan.pendingSpeakerIds,
) : GroupScheduler {

    /** 本轮的候选顺序（含被顺延的人），与 planGroupRound 完全同一份算法。 */
    fun speakerOrder(room: RoomView): List<GroupMember> =
        withPriority(orderForTurn(plan.members, userText, room.lastSpeakerId), priorityIds)

    /** 本轮没轮到、要排到下一轮优先的人。 */
    fun deferred(room: RoomView): List<String> =
        speakerOrder(room).drop(MAX_GROUP_SPEAKERS).map { it.id }

    override suspend fun next(room: RoomView): SchedulerDecision {
        val ordered = speakerOrder(room)
        if (ordered.isEmpty()) {
            return SchedulerDecision.Done("Legacy scheduler: no member in the room can speak")
        }
        val speakers = ordered.take(MAX_GROUP_SPEAKERS)
        val deferred = ordered.drop(speakers.size)
        // 文案与 planGroupRound 的 notice 逐字一致：同一个现象不能有两种说法。
        val notice = groupRoundNotice(speakers.size, ordered.size, deferred.map { it.name })
        return if (speakers.size == 1) {
            SchedulerDecision.NextSpeaker(speakers.first().id, notice ?: "Legacy scheduler: only one candidate this round")
        } else {
            SchedulerDecision.Roundtable(notice ?: "Legacy scheduler: everyone speaks in the existing rotation order")
        }
    }
}
