package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.PermissionRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import dev.dsh.mobile.core.model.TaskState
import org.junit.Assert.*
import org.junit.Test

class PermissionLifecycleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unansweredPermissionTimesOutAndClearsOwner() = runTest {
        val events = mutableListOf<DshEvent>()
        var returned = false
        lateinit var pool: ExecutionPool
        pool = ExecutionPool(backgroundScope, permissionEvent = { events += it }) { task ->
            assertNull(pool.requestPermission(task, PermissionRequest("group", "timeout", "confirm", emptyList())))
            returned = true
            delay(1)
            "done"
        }
        pool.submitOne("group", AgentTask(id = "task", sessionId = "group", agentId = "a", agentName = "A", prompt = "x"))
        runCurrent()
        advanceTimeBy(179_999)
        runCurrent()
        assertFalse(returned)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("permission must return after 180 seconds", returned)
        assertEquals(TaskState.Running, pool.tasks.value.single().state)
        assertEquals(listOf("timeout"), events.filterIsInstance<DshEvent.PermissionResolved>().map { it.requestId })
        assertEquals(ExecutionPool.PermissionOutcome.Unknown, pool.resolvePermission("timeout", "allow", "a"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TaskState.Done, pool.tasks.value.single().state)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sameGroupRequestsKeepTheirOwnersAndIndependentAnswers() = runTest {
        val events = mutableListOf<DshEvent>()
        val choices = mutableMapOf<String, String?>()
        lateinit var pool: ExecutionPool
        pool = ExecutionPool(backgroundScope, permissionEvent = { events += it }) { task ->
            choices[task.id] = pool.requestPermission(task, PermissionRequest(task.sessionId, task.id, "confirm", emptyList()))
            "done"
        }
        pool.submit("group", listOf(
            AgentTask(id = "r1", sessionId = "group", agentId = "a", agentName = "A", prompt = "x"),
            AgentTask(id = "r2", sessionId = "group", agentId = "b", agentName = "B", prompt = "x"),
        ))
        runCurrent()
        assertEquals(setOf("a", "b"), events.filterIsInstance<DshEvent.Permission>().map { it.request.ownerAgentId }.toSet())
        assertEquals(ExecutionPool.PermissionOutcome.Unknown, pool.resolvePermission("r1", "allow", "a", "other"))
        assertEquals(ExecutionPool.PermissionOutcome.Accepted, pool.resolvePermission("r1", "allow", "a"))
        runCurrent()
        assertEquals(mapOf("r1" to "allow"), choices)
        assertEquals(listOf("r1"), events.filterIsInstance<DshEvent.PermissionResolved>().map { it.requestId })
        assertTrue(pool.resolvePermission("r2", "deny", "a") is ExecutionPool.PermissionOutcome.Rejected)
        assertEquals(ExecutionPool.PermissionOutcome.Accepted, pool.resolvePermission("r2", "deny", "b"))
        runCurrent()
        assertEquals(mapOf("r1" to "allow", "r2" to "deny"), choices)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun requestsReachTransportAndCancelOnlyTheirSession() = runTest {
        val events = mutableListOf<DshEvent>()
        val continued = mutableListOf<String>()
        lateinit var pool: ExecutionPool
        pool = ExecutionPool(backgroundScope, permissionEvent = { events += it }) { task ->
            pool.requestPermission(task, PermissionRequest(task.sessionId, task.id, "confirm", emptyList()))
            continued += task.id
            "done"
        }
        pool.submitOne("one", AgentTask(id = "r1", sessionId = "one", agentId = "a", agentName = "A", prompt = "x"))
        pool.submitOne("two", AgentTask(id = "r2", sessionId = "two", agentId = "a", agentName = "A", prompt = "x"))
        runCurrent()
        assertEquals(setOf("r1", "r2"), events.filterIsInstance<DshEvent.Permission>().map { it.request.requestId }.toSet())
        assertEquals(ExecutionPool.PermissionOutcome.Rejected("不是你的提问，忽略"), pool.resolvePermission("r2", "allow", "wrong"))
        pool.cancel("one")
        runCurrent()
        assertEquals(ExecutionPool.PermissionOutcome.Accepted, pool.resolvePermission("r2", "allow", "a"))
        runCurrent()
        assertEquals(setOf("r1", "r2"), events.filterIsInstance<DshEvent.PermissionResolved>().map { it.requestId }.toSet())
        assertEquals(listOf("r2"), continued)
        assertEquals(ExecutionPool.PermissionOutcome.Unknown, pool.resolvePermission("r2", "allow", "a"))
    }
}
