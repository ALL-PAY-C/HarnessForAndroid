package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/** H1 纯合并函数行为测试：本地为基底，远端只覆盖权威 status/时间。 */
class SessionMergeTest {

    private fun localEntity(
        updatedAt: Long = 100L,
        title: String = "本地重命名",
    ) = Session(
        id = "s1",
        title = title,
        cwd = "/local/cwd",
        updatedAt = updatedAt,
        createdAt = 50L,
        status = SessionStatus.Idle,
        preview = "本地预览",
        messageCount = 7,
        usage = TokenUsage(10, 20, 0),
        pinned = true,
        archived = true,
        group = "我的分组",
        kind = dev.dsh.mobile.core.model.SessionKind.Single,
        agentId = "agent-local",
        persona = "本地人设",
        participants = listOf(Participant("p1", "m1", "成员A", "成员人设", 0)),
    ).toEntity()

    private fun remote(
        title: String = "远端自动标题",
        updatedAt: Long = 200L,
        status: SessionStatus = SessionStatus.Running,
    ) = Session(
        id = "s1",
        title = title,
        cwd = "/remote/cwd",
        updatedAt = updatedAt,
        createdAt = 60L,
        status = status,
        preview = "远端预览",
        messageCount = 3,
        usage = TokenUsage(1, 2, 0),
        pinned = false,
        archived = false,
        group = "最近",
        kind = dev.dsh.mobile.core.model.SessionKind.Single,
        agentId = "agent-remote",
        persona = "远端人设",
        participants = emptyList(),
    )

    @Test
    fun `keeps local rename and fields, overrides only status and updatedAt`() {
        val merged = mergeRemoteSession(localEntity(), remote()).toModel()

        // 本地字段全部保留
        assertEquals("本地重命名", merged.title)
        assertEquals("/local/cwd", merged.cwd)
        assertEquals("agent-local", merged.agentId)
        assertEquals("本地人设", merged.persona)
        assertEquals(1, merged.participants.size)
        assertEquals("成员A", merged.participants[0].name)
        assertEquals(true, merged.pinned)
        assertEquals(true, merged.archived)
        assertEquals("我的分组", merged.group)
        assertEquals(7, merged.messageCount)
        assertEquals(TokenUsage(10, 20, 0), merged.usage)
        assertEquals(50L, merged.createdAt)
        // 远端只覆盖权威字段
        assertEquals(SessionStatus.Running, merged.status)
        assertEquals(200L, merged.updatedAt)
    }

    @Test
    fun `updatedAt does not regress when local is newer`() {
        val merged = mergeRemoteSession(localEntity(updatedAt = 500L), remote(updatedAt = 200L)).toModel()
        assertEquals(500L, merged.updatedAt)
    }

    @Test
    fun `new remote session is imported whole`() {
        val r = Session(
            id = "new-remote",
            title = "远端新会话",
            cwd = "/remote",
            updatedAt = 300L,
            createdAt = 300L,
            status = SessionStatus.Running,
            agentId = "agent-r",
        )
        val merged = mergeRemoteSession(null, r).toModel()
        assertEquals("new-remote", merged.id)
        assertEquals("远端新会话", merged.title)
        assertEquals(SessionStatus.Running, merged.status)
        assertEquals(300L, merged.updatedAt)
        assertEquals("agent-r", merged.agentId)
    }
}
