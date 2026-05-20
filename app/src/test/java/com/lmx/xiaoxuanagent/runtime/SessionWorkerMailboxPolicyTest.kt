package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkerMailboxPolicyTest {
    @Test
    fun `build snapshot summarizes pending attention and reply chain`() {
        val now = 1_710_000_000_000L
        val snapshot =
            SessionWorkerMailboxPolicy.buildSnapshot(
                messages =
                    listOf(
                        message(
                            id = "m1",
                            type = SessionWorkerMailboxMessageType.PERMISSION_REQUEST,
                            priority = 100,
                            summary = "需要审批",
                            createdAtMs = now - 5 * 60_000L,
                            recipientSessionId = "session_parent",
                        ),
                        message(
                            id = "m2",
                            type = SessionWorkerMailboxMessageType.PERMISSION_RESPONSE,
                            priority = 90,
                            summary = "已批准",
                            replyTo = "m1",
                            createdAtMs = now - 2 * 60_000L,
                            recipientSessionId = "session_child",
                        ),
                        message(
                            id = "m3",
                            type = SessionWorkerMailboxMessageType.CONTROL,
                            priority = 80,
                            summary = "切换 worker owner",
                            createdAtMs = now - 20 * 60_000L,
                            recipientSessionId = "session_parent",
                        ),
                    ),
                priorityMode = "reply_chain_first",
                target = "session_parent",
                nowMs = now,
            )

        assertEquals(3, snapshot.totalCount)
        assertEquals(3, snapshot.pendingCount)
        assertEquals(3, snapshot.attentionCount)
        assertEquals(1, snapshot.replyChainCount)
        assertEquals(1, snapshot.permissionRequestCount)
        assertEquals(1, snapshot.permissionResponseCount)
        assertEquals(2, snapshot.permissionPendingCount)
        assertEquals(1, snapshot.controlCount)
        assertTrue(snapshot.categorySummary.contains("permission=2"))
        assertTrue(snapshot.coordinationSummary.contains("mode=reply_chain_first"))
        assertTrue(snapshot.recentLines.first().contains("permission_request"))
    }

    private fun message(
        id: String,
        type: SessionWorkerMailboxMessageType,
        priority: Int,
        summary: String,
        createdAtMs: Long,
        recipientSessionId: String,
        replyTo: String = "",
    ): SessionWorkerMailboxMessage =
        SessionWorkerMailboxMessage(
            messageId = id,
            type = type,
            priority = priority,
            summary = summary,
            recipientSessionId = recipientSessionId,
            replyToMessageId = replyTo,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
        )
}
