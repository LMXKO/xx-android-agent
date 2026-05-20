package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkerMissionSupportTest {
    @Test
    fun `derive mission profile infers approval recovery semantics`() {
        val profile =
            deriveSessionWorkerMissionProfile(
                task = "确认是否继续执行高风险发送",
                entrySource = "safety_gate",
                joinPolicy = SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN,
                metadata = emptyMap(),
                hasParent = true,
            )

        assertEquals(SessionWorkerMissionType.APPROVAL, profile.missionType)
        assertEquals(SessionWorkerEscalationPolicy.SAFETY_CONFIRMATION, profile.escalationPolicy)
        assertEquals(SessionWorkerJoinExpectation.PARENT_RESUME, profile.joinExpectation)
        assertTrue(profile.missionSummary.contains("需要安全确认"))
    }

    @Test
    fun `worker summaries reflect mission and escalation pressure`() {
        val workers =
            listOf(
                SessionWorkerRecord(
                    workerId = "w-1",
                    missionType = SessionWorkerMissionType.APPROVAL,
                    escalationPolicy = SessionWorkerEscalationPolicy.MANUAL_APPROVAL,
                    joinExpectation = SessionWorkerJoinExpectation.PARENT_RESUME,
                    task = "审批确认",
                    entrySource = "approval_queue",
                    status = SessionWorkerStatus.WAITING_APPROVAL,
                    createdAtMs = 1L,
                    updatedAtMs = 1L,
                ),
                SessionWorkerRecord(
                    workerId = "w-2",
                    missionType = SessionWorkerMissionType.RECOVERY,
                    escalationPolicy = SessionWorkerEscalationPolicy.PARENT_ACK,
                    joinExpectation = SessionWorkerJoinExpectation.ALL_CHILDREN,
                    task = "恢复子任务",
                    entrySource = "runtime_recover",
                    status = SessionWorkerStatus.WAITING_CHILDREN,
                    createdAtMs = 2L,
                    updatedAtMs = 2L,
                ),
            )

        assertTrue(workers.first().requiresEscalationAttention())
        assertTrue(workers.missionMixSummary().contains("approval:1"))
        assertTrue(workers.missionMixSummary().contains("recovery:1"))
        assertTrue(workers.escalationPressureSummary().contains("awaiting=2"))
        assertTrue(workers.joinPressureSummary().contains("parent_resume=1"))
    }
}
