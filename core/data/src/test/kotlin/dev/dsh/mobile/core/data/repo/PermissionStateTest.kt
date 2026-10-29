package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.PermissionRequest
import org.junit.Assert.*
import org.junit.Test

class PermissionStateTest {
    @Test fun firstMemberFinishingKeepsTurnAliveForSilentMemberPermission() {
        val first = AuthorPart("a", "A", text = "done")
        val afterA = TurnState(running = true, parts = listOf(first)).finishPart(first)
        assertTrue("session TurnEnd has not arrived", afterA.running)
        assertTrue(afterA.parts.isEmpty())
        val afterBTool = afterA.copy(parts = listOf(AuthorPart("b", "B")))
        val request = PermissionRequest("group", "b-permission", "B", emptyList(), "b", "B")
        val waiting = afterBTool.requestPermission(request)
        assertEquals(listOf(request), waiting.permissions)
        assertTrue(waiting.resolvePermission(request.requestId).permissions.isEmpty())
        assertTrue(waiting.running)
        assertFalse(TurnState().running)
    }

    @Test fun answeringOneOwnerKeepsOtherRequestsAndLateEventsStayCleared() {
        val a = PermissionRequest("group", "r1", "A", emptyList(), "a", "A")
        val b = PermissionRequest("group", "r2", "B", emptyList(), "b", "B")
        val pending = TurnState(running = true).requestPermission(a).requestPermission(b).requestPermission(a)
        assertEquals(setOf("r1", "r2"), pending.permissions.map { it.requestId }.toSet())
        assertEquals(pending, pending.resolvePermission("r1", "b"))
        val answered = pending.resolvePermission("r1", "a")
        assertEquals(listOf(b), answered.permissions)
        assertEquals(answered, answered.resolvePermission("stale"))
        assertTrue(TurnState().requestPermission(a).resolvePermission("r1").permissions.isEmpty())
    }
}
