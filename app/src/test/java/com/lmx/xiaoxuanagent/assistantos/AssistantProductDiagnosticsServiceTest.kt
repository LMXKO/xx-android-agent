package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.harness.SuperAssistantMaturitySnapshot
import com.lmx.xiaoxuanagent.runtime.AgentUiBlockedReason
import com.lmx.xiaoxuanagent.runtime.AgentUiExecutionPhase
import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusCategory
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.AgentUiTerminalReason
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionPolicy
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionReport
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryQueueTask
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryTaskStatus
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryTaskType
import com.lmx.xiaoxuanagent.runtime.PlatformTraceEntry
import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.runtime.SessionCommandCenterSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionBridgeSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionGraphNode
import com.lmx.xiaoxuanagent.runtime.SessionMemoryMaintenanceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformHealthSummary
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProductDiagnosticsServiceTest {
    @Test
    fun `derive prioritizes stale runtime ahead of other warnings`() {
        val snapshot =
            AssistantProductDiagnosticsService.derive(
                providers =
                    listOf(
                        AssistantSignalProviderState(
                            providerId = "calendar_agenda",
                            source = "calendar",
                            trustLevel = AssistantSignalProviderTrustLevel.SYSTEM,
                            healthScore = 30,
                            failureCount = 4,
                        ),
                    ),
                memoryQueue =
                    listOf(
                        BackgroundMemoryQueueTask(
                            taskId = "mem_1",
                            type = BackgroundMemoryTaskType.SESSION_OUTCOME,
                            status = BackgroundMemoryTaskStatus.FAILED,
                        ),
                    ),
                retentionPreview =
                    ArtifactRetentionReport(
                        policy = ArtifactRetentionPolicy(),
                        deletedArtifacts = 5,
                    ),
                sessionGraph =
                    listOf(
                        SessionGraphNode(
                            sessionId = "s1",
                            pendingApprovalCount = 1,
                            blockedReason = "awaiting_approval",
                        ),
                    ),
                focusPlatformSnapshot =
                    SessionPlatformSnapshot(
                        sessionId = "s1",
                        state = SessionRuntimeState(),
                        bridgeSnapshot =
                            SessionBridgeSnapshot(
                                sessionId = "s1",
                                task = "task",
                                entrySource = "app",
                                targetPackageName = "pkg",
                                profileId = "profile",
                                turn = 1,
                                statusCode = AgentUiStatus.RUNNING,
                                statusCategory = AgentUiStatusCategory.ACTIVE_EXECUTION,
                                statusBlockedReason = AgentUiBlockedReason.NONE,
                                statusExecutionPhase = AgentUiExecutionPhase.RUNNING,
                                statusTakeoverReason = AgentUiTakeoverReason.NONE,
                                statusTerminalReason = AgentUiTerminalReason.NONE,
                                resumable = true,
                                targetAppReturnEligible = true,
                                routeReason = "route",
                                resultSummary = "",
                                errorSummary = "",
                                takeoverSummary = "",
                                pendingSafetyConfirmation = "",
                            ),
                        healthSummary =
                            SessionPlatformHealthSummary(
                                status = "stale_runtime",
                                summary = "runtime stale",
                                staleRuntime = true,
                            ),
                    ),
                traceSnapshot =
                    PlatformTraceStore.buildSnapshot(
                        entries = listOf(PlatformTraceEntry("t1", 1L, "bundle_export", "s1", "exported")),
                        sessionId = "s1",
                        nowMs = 60_000L,
                    ),
                commandCenter =
                    SessionCommandCenterSnapshot(
                        totalCount = 2,
                        runningCount = 1,
                        failedCount = 0,
                        latestSummary = "命令执行中",
                        attentionSummary = "running=1 failed=0",
                    ),
                proactiveTasks =
                    listOf(
                        AssistantProactiveTask(
                            id = "approval_1",
                            type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                            capability = SessionCapabilityKey.APPROVE_SAFETY,
                            title = "等待确认",
                            summary = "确认发送敏感内容",
                            fireAtMs = 1L,
                            createdAtMs = 1L,
                            updatedAtMs = 1L,
                        ),
                    ),
                followUpHealth =
                    AssistantFollowUpHealthSnapshot(
                        totalEnabled = 1,
                        overdueCount = 0,
                        approvalCount = 1,
                        scheduledCount = 1,
                        summary = "enabled=1 overdue=0 deferred=0 approvals=1",
                    ),
                memoryMaintenance =
                    SessionMemoryMaintenanceSnapshot(
                        pendingCount = 1,
                        failedCount = 1,
                        lastStatus = "failed",
                        lastSummary = "notebook update failed",
                    ),
                historySummary = "sessions=3 resumable=1 failed=1",
            )

        assertEquals("stale_runtime", snapshot.status)
        assertTrue(snapshot.summary.contains("活跃 session"))
        assertTrue(snapshot.lines.any { it.contains("stale_runtime=runtime stale") })
    }

    @Test
    fun `derive falls back to healthy when no pressure exists`() {
        val snapshot =
            AssistantProductDiagnosticsService.derive(
                providers = emptyList(),
                memoryQueue = emptyList(),
                retentionPreview = ArtifactRetentionReport(policy = ArtifactRetentionPolicy()),
                sessionGraph = emptyList(),
                focusPlatformSnapshot = null,
                traceSnapshot = PlatformTraceStore.buildSnapshot(entries = emptyList()),
                commandCenter = SessionCommandCenterSnapshot(),
                proactiveTasks = emptyList(),
                followUpHealth = AssistantFollowUpHealthSnapshot(),
                memoryMaintenance = SessionMemoryMaintenanceSnapshot(),
                historySummary = "sessions=0 resumable=0 failed=0",
            )

        assertEquals("healthy", snapshot.status)
        assertTrue(snapshot.summary.contains("健康"))
    }

    @Test
    fun `derive reports super assistant maturity blockers`() {
        val snapshot =
            AssistantProductDiagnosticsService.derive(
                providers = emptyList(),
                memoryQueue = emptyList(),
                retentionPreview = ArtifactRetentionReport(policy = ArtifactRetentionPolicy()),
                sessionGraph = emptyList(),
                focusPlatformSnapshot = null,
                traceSnapshot = PlatformTraceStore.buildSnapshot(entries = emptyList()),
                commandCenter = SessionCommandCenterSnapshot(),
                proactiveTasks = emptyList(),
                followUpHealth = AssistantFollowUpHealthSnapshot(),
                memoryMaintenance = SessionMemoryMaintenanceSnapshot(),
                historySummary = "sessions=0 resumable=0 failed=0",
                superAssistantMaturity =
                    SuperAssistantMaturitySnapshot(
                        status = "red",
                        score = 35,
                        summary = "score=35 status=red domains=3/6 blocked=2",
                        lines = listOf("super_assistant | status=red | score=35"),
                        recommendedCommands = listOf("/permission-product", "/run-regression"),
                    ),
            )

        assertEquals("super_assistant_blocked", snapshot.status)
        assertEquals("red", snapshot.superAssistantStatus)
        assertTrue(snapshot.lines.any { it.contains("super_assistant | status=red") })
        assertTrue(snapshot.lines.any { it == "suggested_action=/permission-product" })
    }

    @Test
    fun `derive prioritizes ordinary app capability preflight before maturity warnings`() {
        val snapshot =
            AssistantProductDiagnosticsService.derive(
                providers = emptyList(),
                memoryQueue = emptyList(),
                retentionPreview = ArtifactRetentionReport(policy = ArtifactRetentionPolicy()),
                sessionGraph = emptyList(),
                focusPlatformSnapshot = null,
                traceSnapshot = PlatformTraceStore.buildSnapshot(entries = emptyList()),
                commandCenter = SessionCommandCenterSnapshot(),
                proactiveTasks = emptyList(),
                followUpHealth = AssistantFollowUpHealthSnapshot(),
                memoryMaintenance = SessionMemoryMaintenanceSnapshot(),
                historySummary = "sessions=0 resumable=0 failed=0",
                appPreflight =
                    AssistantAppCapabilityPreflightSnapshot(
                        status = "red",
                        summary = "普通 App 能力预检 status=red ready=8 degraded=2 blocked=1 unsupported=0",
                        lines = listOf("app_preflight | status=red | ready=8 degraded=2 blocked=1 unsupported=0"),
                        recommendedCommands = listOf("/entry-surfaces", "/operator"),
                    ),
                superAssistantMaturity =
                    SuperAssistantMaturitySnapshot(
                        status = "yellow",
                        score = 72,
                        summary = "score=72 status=yellow domains=4/6",
                        lines = listOf("super_assistant | status=yellow | score=72"),
                    ),
            )

        assertEquals("app_capability_blocked", snapshot.status)
        assertEquals("red", snapshot.appPreflightStatus)
        assertTrue(snapshot.lines.any { it.contains("app_preflight | status=red") })
        assertTrue(snapshot.lines.any { it == "suggested_action=/entry-surfaces" })
    }
}
