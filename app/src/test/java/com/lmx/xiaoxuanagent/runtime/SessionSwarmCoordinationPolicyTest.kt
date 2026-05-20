package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskDispatchCandidate
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskQueueSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSwarmCoordinationPolicyTest {
    @Test
    fun `build snapshot enters approval first when mailbox has pending permission request`() {
        val snapshot =
            SessionSwarmCoordinationPolicy.buildSnapshot(
                schedulerSnapshot =
                    SessionExecutionSchedulerSnapshot(
                        parallelismBudget = 2,
                        activeSessionId = "session_parent",
                    ),
                workerGraph =
                    SessionWorkerGraphSnapshot(
                        scheduler =
                            SessionWorkerSchedulerSnapshot(
                                maxConcurrentWorkers = 3,
                                queuedCount = 1,
                                activeCount = 0,
                                blockedCount = 0,
                                deferredCount = 0,
                                waitingChildrenCount = 0,
                                readyWorkerIds = listOf("worker_a"),
                            ),
                    ),
                mailboxSnapshot =
                    SessionWorkerMailboxSnapshot(
                        pendingCount = 2,
                        attentionCount = 2,
                        permissionRequestCount = 1,
                        permissionPendingCount = 1,
                        categorySummary = "pending=2 permission=1",
                        coordinationSummary = "mode=balanced attention=2",
                        recentLines = listOf("permission_request | 等待批准"),
                    ),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(summary = "due=0"),
                traceSnapshot = PlatformTraceSnapshot(),
                activeSessionId = "session_parent",
            )

        assertEquals("approval_first", snapshot.mode)
        assertEquals("approval_first", snapshot.mailboxPriorityMode)
        assertEquals(1, snapshot.pendingPermissionRequests)
        assertTrue(snapshot.summary.contains("权限请求"))
        assertTrue(snapshot.recommendedActions.contains("/mailbox"))
        assertTrue(snapshot.parallelismPressureSummary.contains("budget=2"))
    }

    @Test
    fun `build snapshot merges fairness proactive and trace attention`() {
        val snapshot =
            SessionSwarmCoordinationPolicy.buildSnapshot(
                schedulerSnapshot =
                    SessionExecutionSchedulerSnapshot(
                        parallelismBudget = 3,
                        fairnessSummary = "owners=3 skew=2",
                        ownerQueueSummary = listOf("owner_a=3", "owner_b=1"),
                        dispatchPlan =
                            listOf(
                                SessionExecutionDispatchPlanItem(
                                    sessionId = "session_focus",
                                    lane = "starvation_recovery",
                                    score = 88,
                                ),
                            ),
                    ),
                workerGraph =
                    SessionWorkerGraphSnapshot(
                        scheduler =
                            SessionWorkerSchedulerSnapshot(
                                maxConcurrentWorkers = 3,
                                queuedCount = 2,
                                activeCount = 1,
                                blockedCount = 1,
                                deferredCount = 0,
                                waitingChildrenCount = 0,
                                readyWorkerIds = listOf("worker_focus", "worker_other"),
                                fairnessMode = "coordinator_fair",
                                missionSummary = "follow_up:2 | execution:1",
                                escalationSummary = "escalations=1 awaiting=1",
                                joinSummary = "joins=1 wait_all=1",
                                blockedCoordinatorIds = listOf("coordinator_a"),
                                dispatchCandidates =
                                    listOf(
                                        SessionWorkerDispatchCandidate(
                                            workerId = "worker_focus",
                                            sessionId = "session_focus",
                                            missionType = "follow_up",
                                            missionLabel = "跟进回链",
                                            lane = "starvation_recovery",
                                            score = 96,
                                        ),
                                    ),
                            ),
                    ),
                mailboxSnapshot =
                    SessionWorkerMailboxSnapshot(
                        pendingCount = 3,
                        attentionCount = 2,
                        replyChainCount = 1,
                        permissionResponseCount = 1,
                        controlCount = 1,
                        categorySummary = "pending=3 permission=1 reply=1 control=1",
                        coordinationSummary = "mode=reply_chain_first attention=2 hottest_recipient=session_focus",
                        recentLines = listOf("permission_response | 已批准"),
                    ),
                proactiveQueue =
                    AssistantProactiveTaskQueueSnapshot(
                        priorityMode = "worker_first",
                        dueCount = 2,
                        dispatchCandidates =
                            listOf(
                                AssistantProactiveTaskDispatchCandidate(
                                    task =
                                        AssistantProactiveTask(
                                            id = "task_1",
                                            type = AssistantProactiveTaskType.WORKER_TASK,
                                            capability = SessionCapabilityKey.POST_WORKER_MESSAGE,
                                            title = "推进子 worker",
                                            summary = "继续派发",
                                            fireAtMs = 1L,
                                            createdAtMs = 1L,
                                            updatedAtMs = 1L,
                                        ),
                                    lane = "worker_dispatch",
                                    score = 140,
                                ),
                            ),
                        summary = "mode=worker_first due=2 ready=1",
                    ),
                traceSnapshot =
                    PlatformTraceSnapshot(
                        totalCount = 2,
                        attentionCount = 1,
                        categorySummary = "categories=2 hottest=planner_route_failed",
                        coverageSummary = "sessions=1 hottest=session_focus",
                        attentionSummary = "attention=1 failed=1",
                        recentLines = listOf("planner_route_failed | timeout"),
                    ),
                activeSessionId = "session_focus",
            )

        assertEquals("reply_chain_flush", snapshot.mode)
        assertTrue(snapshot.workerDispatchSummary.contains("starvation_recovery"))
        assertTrue(snapshot.proactiveDispatchSummary.contains("worker_dispatch"))
        assertTrue(snapshot.ownerFairnessSummary.contains("owners=3"))
        assertTrue(snapshot.traceAttentionSummary.contains("attention=1"))
        assertTrue(snapshot.recommendedActions.contains("/worker-reply"))
        assertTrue(snapshot.recommendedActions.contains("/worker-dispatch"))
        assertTrue(snapshot.focusSessionIds.contains("session_focus"))
        assertTrue(snapshot.recentLines.any { it.contains("trace |") })
    }

    @Test
    fun `build snapshot enters fair recovery when owner starvation is detected`() {
        val snapshot =
            SessionSwarmCoordinationPolicy.buildSnapshot(
                schedulerSnapshot =
                    SessionExecutionSchedulerSnapshot(
                        parallelismBudget = 2,
                        fairnessSummary = "owners=2 skew=3",
                        ownerQueueSummary = listOf("owner_hot=4", "owner_cold=0"),
                    ),
                workerGraph =
                    SessionWorkerGraphSnapshot(
                        scheduler =
                            SessionWorkerSchedulerSnapshot(
                                maxConcurrentWorkers = 2,
                                queuedCount = 2,
                                activeCount = 0,
                                blockedCount = 1,
                                deferredCount = 0,
                                waitingChildrenCount = 0,
                                readyWorkerIds = listOf("worker_cold"),
                                dispatchCandidates =
                                    listOf(
                                        SessionWorkerDispatchCandidate(
                                            workerId = "worker_cold",
                                            sessionId = "session_cold",
                                            missionType = "execution",
                                            lane = "starvation_recovery",
                                            score = 99,
                                        ),
                                    ),
                            ),
                    ),
                mailboxSnapshot =
                    SessionWorkerMailboxSnapshot(
                        pendingCount = 0,
                        categorySummary = "pending=0",
                        coordinationSummary = "mode=balanced attention=0",
                    ),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(summary = "due=0"),
                traceSnapshot = PlatformTraceSnapshot(),
            )

        assertEquals("fair_recovery", snapshot.mode)
        assertEquals(2, snapshot.maxConcurrentWorkers)
        assertEquals("stale_first", snapshot.mailboxPriorityMode)
        assertTrue(snapshot.summary.contains("公平恢复"))
        assertTrue(snapshot.recommendedActions.contains("/worker-dispatch"))
    }
}
