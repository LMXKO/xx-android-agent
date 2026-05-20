package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTraceStoreTest {
    @Test
    fun `build snapshot summarizes category coverage and attention`() {
        val now = 1_710_000_000_000L
        val snapshot =
            PlatformTraceStore.buildSnapshot(
                entries =
                    listOf(
                        PlatformTraceEntry(
                            traceId = "t1",
                            timestamp = now - 60_000L,
                            category = "planner_route_failed",
                            sessionId = "session_a",
                            summary = "planner failed due to timeout",
                            metadata = mapOf("status" to "failed"),
                        ),
                        PlatformTraceEntry(
                            traceId = "t2",
                            timestamp = now - 30_000L,
                            category = "remote_bridge",
                            sessionId = "session_a",
                            summary = "remote bridge consumed request",
                        ),
                        PlatformTraceEntry(
                            traceId = "t3",
                            timestamp = now - 15_000L,
                            category = "worker_mailbox",
                            sessionId = "session_b",
                            summary = "mailbox reply chain flushed",
                        ),
                    ),
                nowMs = now,
            )

        assertEquals(3, snapshot.totalCount)
        assertEquals(2, snapshot.sessionCoverageCount)
        assertEquals("planner_route_failed", snapshot.hottestCategory)
        assertEquals(1, snapshot.attentionCount)
        assertTrue(snapshot.categorySummary.contains("categories=3"))
        assertTrue(snapshot.coverageSummary.contains("sessions=2"))
        assertTrue(snapshot.attentionSummary.contains("attention=1"))
        assertTrue(snapshot.recentLines.first().contains("planner_route_failed"))
    }
}
