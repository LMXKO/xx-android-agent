package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantSessionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotBackedStatusModelsTest {
    @Test
    fun `resume snapshot derives status from snapshot`() {
        val snapshot =
            SessionResumeSnapshot(
                statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.WAITING_EXTERNAL).toSnapshot(),
            )

        assertEquals(AgentUiStatus.WAITING_EXTERNAL, snapshot.status)
        assertEquals(AgentUiTakeoverReason.WAITING_EXTERNAL, snapshot.statusModel.takeoverReason)
    }

    @Test
    fun `assistant session card derives status code from snapshot`() {
        val card =
            AssistantSessionCard(
                sessionId = "session-1",
                statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.COMPLETED).toSnapshot(),
                task = "done",
                summary = "ok",
            )

        assertEquals(AgentUiStatus.COMPLETED, card.statusCode)
        assertEquals(AgentUiTerminalReason.COMPLETED, card.statusModel.terminalReason)
    }

    @Test
    fun `session graph node derives status from snapshot`() {
        val node =
            SessionGraphNode(
                sessionId = "graph-1",
                statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.PAUSED).toSnapshot(),
            )

        assertEquals(AgentUiStatus.PAUSED, node.status)
        assertEquals(AgentUiTakeoverReason.PAUSED, node.statusModel.takeoverReason)
    }
}
