package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.runtime.SessionWorkerEscalationPolicy
import com.lmx.xiaoxuanagent.runtime.SessionWorkerJoinExpectation
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMissionType
import com.lmx.xiaoxuanagent.runtime.SessionWorkerRecord
import com.lmx.xiaoxuanagent.runtime.SessionWorkerStatus
import com.lmx.xiaoxuanagent.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPersonalShellPolicyTest {
    @Test
    fun `derive personal shells prioritizes approvals and due work`() {
        val now = System.currentTimeMillis()
        val assistantSnapshot =
            AssistantOsSnapshot(
                activeSession =
                    AssistantActiveSession(
                        sessionId = "session-active",
                        statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.RUNNING).toSnapshot(),
                        task = "处理当前会话",
                        summary = "running",
                    ),
                approvalSessions =
                    listOf(
                        AssistantSessionCard(
                            sessionId = "session-approval",
                            task = "确认是否继续发送",
                            summary = "需要确认",
                        ),
                    ),
            )
        val proactiveTasks =
            listOf(
                AssistantProactiveTask(
                    id = "task-1",
                    type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                    capability = SessionCapabilityKey.READ_PRODUCT_SHELL,
                    title = "跟进审批回执",
                    summary = "已经到点",
                    fireAtMs = now - 1_000L,
                    createdAtMs = now - 10_000L,
                    updatedAtMs = now - 1_000L,
                ),
            )
        val externalSignals =
            listOf(
                AssistantExternalSignal(
                    id = "signal-1",
                    type = AssistantExternalSignalType.CALENDAR,
                    capability = SessionCapabilityKey.START_SESSION,
                    title = "会议提醒",
                    summary = "马上开始",
                    fireAtMs = now - 1_000L,
                    createdAtMs = now - 10_000L,
                    updatedAtMs = now - 1_000L,
                ),
            )
        val workers =
            listOf(
                SessionWorkerRecord(
                    workerId = "worker-1",
                    sessionId = "session-approval",
                    missionType = SessionWorkerMissionType.APPROVAL,
                    escalationPolicy = SessionWorkerEscalationPolicy.MANUAL_APPROVAL,
                    joinExpectation = SessionWorkerJoinExpectation.PARENT_RESUME,
                    missionLabel = "审批协作",
                    missionSummary = "审批协作 | parent_resume | 需要人工审批",
                    task = "确认是否继续发送",
                    entrySource = "approval_queue",
                    status = SessionWorkerStatus.WAITING_APPROVAL,
                    priority = 90,
                    createdAtMs = now - 10_000L,
                    updatedAtMs = now - 5_000L,
                ),
            )
        val proactiveQueue =
            AssistantProactiveTaskDispatchPolicy.buildSnapshot(
                tasks = proactiveTasks,
                activeSessionId = assistantSnapshot.activeSession.sessionId,
                dispatchableWorkerIds = emptySet(),
                nowMs = now,
                limit = 8,
            )

        val agenda =
            AssistantPersonalShellPolicy.deriveAgendaShell(
                now = now,
                assistantSnapshot = assistantSnapshot,
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
            )
        val rhythm =
            AssistantPersonalShellPolicy.deriveDailyRhythm(
                now = now,
                assistantSnapshot = assistantSnapshot,
                providers = emptyList(),
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
            )
        val focus =
            AssistantPersonalShellPolicy.derivePersonalFocus(
                now = now,
                assistantSnapshot = assistantSnapshot,
                proactiveTasks = proactiveTasks,
                proactiveQueue = proactiveQueue,
                externalSignals = externalSignals,
                workers = workers,
                userModel =
                    PersonalAssistantUserModelSnapshot(
                        identitySummary = "wechat_assistant",
                        preferenceSummary = "structured=2",
                    ),
                autonomyPlan =
                    AssistantAutonomyPlanSnapshot(
                        mode = "approval_guard",
                        summary = "先处理审批再推进自动动作",
                        recommendedCommands = listOf("/approval-center"),
                    ),
            )

        assertEquals("approval_queue", agenda.mode)
        assertTrue(agenda.recommendedCommands.contains("/approve"))
        assertEquals("interrupt_heavy", rhythm.mode)
        assertEquals("approval_focus", focus.mode)
        assertEquals("approval_session", focus.focusReason)
        assertTrue(focus.nextBestActions.contains("/approve"))
        assertTrue(focus.userModelSummary.contains("wechat_assistant"))
    }
}
