package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkerDispatchPolicyTest {
    @Test
    fun `selectDispatchable prefers starvation recovery owner`() {
        val now = System.currentTimeMillis()
        val graph =
            mapOf(
                "root-a" to SessionGraphNode(sessionId = "root-a", updatedAtMs = now),
                "root-b" to SessionGraphNode(sessionId = "root-b", updatedAtMs = now),
            )
        val workers =
            listOf(
                SessionWorkerRecord(
                    workerId = "active-a",
                    rootSessionId = "root-a",
                    coordinatorSessionId = "root-a",
                    task = "active",
                    entrySource = "test",
                    status = SessionWorkerStatus.RUNNING,
                    createdAtMs = now - 5_000L,
                    updatedAtMs = now - 5_000L,
                ),
                SessionWorkerRecord(
                    workerId = "queued-a",
                    rootSessionId = "root-a",
                    coordinatorSessionId = "root-a",
                    task = "recent",
                    entrySource = "test",
                    status = SessionWorkerStatus.QUEUED,
                    priority = 90,
                    createdAtMs = now - 60_000L,
                    updatedAtMs = now - 60_000L,
                    lastAttemptAtMs = now - 60_000L,
                ),
                SessionWorkerRecord(
                    workerId = "queued-b",
                    rootSessionId = "root-b",
                    coordinatorSessionId = "root-b",
                    task = "stale",
                    entrySource = "test",
                    status = SessionWorkerStatus.QUEUED,
                    priority = 40,
                    createdAtMs = now - 45 * 60_000L,
                    updatedAtMs = now - 45 * 60_000L,
                    lastAttemptAtMs = now - 45 * 60_000L,
                ),
            )

        val selected =
            SessionWorkerDispatchPolicy.selectDispatchable(
                workers = workers,
                limit = 1,
                maxConcurrentWorkers = 3,
                graphReader = graph::get,
            )

        assertEquals(1, selected.size)
        assertEquals("queued-b", selected.first().worker.workerId)
        assertEquals("starvation_recovery", selected.first().lane)
        assertTrue(selected.first().starvationMinutes >= 20)
    }

    @Test
    fun `deriveMailboxPriorityMode reflects starvation lane`() {
        val worker =
            SessionWorkerRecord(
                workerId = "worker-1",
                task = "task",
                entrySource = "test",
                status = SessionWorkerStatus.QUEUED,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            )
        val ready =
            listOf(
                SessionWorkerDispatchEvaluation(
                    worker = worker,
                    ownerKey = "root",
                    score = 100,
                    lane = "starvation_recovery",
                    reasons = listOf("starved"),
                    queueAgeMinutes = 30,
                    starvationMinutes = 30,
                ),
            )

        assertEquals("stale_first", SessionWorkerDispatchPolicy.deriveMailboxPriorityMode(ready))
    }
}
