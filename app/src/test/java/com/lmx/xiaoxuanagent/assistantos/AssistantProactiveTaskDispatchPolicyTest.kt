package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProactiveTaskDispatchPolicyTest {
    @Test
    fun `build snapshot prioritizes approval and runnable worker tasks`() {
        val now = 1_710_000_000_000L
        val snapshot =
            AssistantProactiveTaskDispatchPolicy.buildSnapshot(
                tasks =
                    listOf(
                        task(
                            id = "scheduled",
                            type = AssistantProactiveTaskType.SCHEDULED_TASK,
                            capability = SessionCapabilityKey.START_SESSION,
                            title = "普通计划任务",
                            fireAtMs = now - 5_000L,
                        ),
                        task(
                            id = "approval",
                            type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                            capability = SessionCapabilityKey.ACK_WORKER_MESSAGE,
                            title = "审批回执",
                            fireAtMs = now - 30_000L,
                        ),
                        task(
                            id = "worker",
                            type = AssistantProactiveTaskType.WORKER_TASK,
                            capability = SessionCapabilityKey.START_SESSION,
                            title = "派发子 worker",
                            fireAtMs = now - 15_000L,
                            metadata = mapOf("worker_id" to "worker_1"),
                        ),
                    ),
                activeSessionId = "",
                dispatchableWorkerIds = setOf("worker_1"),
                nowMs = now,
                limit = 8,
            )

        assertEquals("approval_first", snapshot.priorityMode)
        assertEquals(listOf("approval", "worker", "scheduled"), snapshot.dispatchCandidates.map { it.task.id })
    }

    @Test
    fun `build snapshot counts blocked tasks when active session or worker gate exists`() {
        val now = 1_710_000_000_000L
        val snapshot =
            AssistantProactiveTaskDispatchPolicy.buildSnapshot(
                tasks =
                    listOf(
                        task(
                            id = "blocked_start",
                            type = AssistantProactiveTaskType.SCHEDULED_TASK,
                            capability = SessionCapabilityKey.START_SESSION,
                            title = "启动新任务",
                            fireAtMs = now - 5_000L,
                        ),
                        task(
                            id = "blocked_worker",
                            type = AssistantProactiveTaskType.WORKER_TASK,
                            capability = SessionCapabilityKey.START_SESSION,
                            title = "不可运行 worker",
                            fireAtMs = now - 5_000L,
                            metadata = mapOf("worker_id" to "worker_x"),
                        ),
                        task(
                            id = "memory",
                            type = AssistantProactiveTaskType.MEMORY_NUDGE,
                            capability = SessionCapabilityKey.UPSERT_MEMORY_ENTRY,
                            title = "整理记忆",
                            fireAtMs = now - 5_000L,
                        ),
                    ),
                activeSessionId = "session_active",
                dispatchableWorkerIds = emptySet(),
                nowMs = now,
                limit = 8,
            )

        assertEquals(1, snapshot.blockedByActiveSessionCount)
        assertEquals(1, snapshot.blockedByWorkerCount)
        assertEquals(listOf("memory"), snapshot.dispatchCandidates.map { it.task.id })
        assertTrue(snapshot.summary.contains("blocked_active=1"))
    }

    private fun task(
        id: String,
        type: AssistantProactiveTaskType,
        capability: SessionCapabilityKey,
        title: String,
        fireAtMs: Long,
        metadata: Map<String, String> = emptyMap(),
    ): AssistantProactiveTask =
        AssistantProactiveTask(
            id = id,
            type = type,
            capability = capability,
            title = title,
            summary = title,
            fireAtMs = fireAtMs,
            createdAtMs = fireAtMs - 1_000L,
            updatedAtMs = fireAtMs - 500L,
            metadata = metadata,
        )
}
