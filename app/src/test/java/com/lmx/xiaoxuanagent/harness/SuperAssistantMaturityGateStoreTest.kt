package com.lmx.xiaoxuanagent.harness

import com.lmx.xiaoxuanagent.runtime.AgentUiBlockedReason
import com.lmx.xiaoxuanagent.runtime.AgentUiExecutionPhase
import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusCategory
import com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason
import com.lmx.xiaoxuanagent.runtime.AgentUiTerminalReason
import com.lmx.xiaoxuanagent.runtime.PlatformTraceEntry
import com.lmx.xiaoxuanagent.runtime.PlatformTraceStore
import com.lmx.xiaoxuanagent.runtime.ReplayArtifactSummary
import com.lmx.xiaoxuanagent.runtime.RuntimeSession
import com.lmx.xiaoxuanagent.runtime.SessionBridgeSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionGroundingHealthSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPermissionProductSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformHealthSummary
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionResumeSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperAssistantMaturityGateStoreTest {
    @Test
    fun `evaluate covers all super assistant maturity domains`() {
        val snapshot =
            SuperAssistantMaturityGateStore.evaluate(
                profileId = "wechat_assistant",
                packageName = "com.tencent.mm",
                focusSessionId = "s1",
                aggregate =
                    HarnessStore.AggregateSnapshot(
                        runCount = 8,
                        successRate = 0.82,
                        resumeContinuationAttemptCount = 2,
                        resumeContinuationSuccessCount = 1,
                    ),
                screenAutomation =
                    ScreenAutomationCapabilitySnapshot(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        runCount = 6,
                        automationSuccessRate = 0.88,
                        recentSuccessRate = 0.9,
                        handoffReadyRate = 0.7,
                        confidenceBand = "strong",
                    ),
                releaseGate =
                    ScreenAutomationReleaseGateSnapshot(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        gateStatus = "green",
                        focusApp = true,
                        summary = "focus=wechat gate=green",
                    ),
                focusPlatformSnapshot = platformSnapshot(),
                traceSnapshot =
                    PlatformTraceStore.buildSnapshot(
                        entries =
                            listOf(
                                PlatformTraceEntry(
                                    traceId = "t1",
                                    timestamp = 1L,
                                    category = "planning_screenshot",
                                    sessionId = "s1",
                                    summary = "visual grounding captured",
                                ),
                            ),
                        sessionId = "s1",
                        nowMs = 60_000L,
                    ),
                groundingHealth =
                    SessionGroundingHealthSnapshot(
                        sessionId = "s1",
                        totalCount = 4,
                        failureCount = 0,
                        bucketSummary = listOf("visual_text:3/ok=3", "detector_visual:1/ok=1"),
                        summary = "grounding=4 fail=0",
                    ),
                permissionProduct =
                    SessionPermissionProductSnapshot(
                        sessionId = "s1",
                        pendingCount = 1,
                        recentCount = 2,
                        askCount = 4,
                        denyCount = 1,
                        sourceCount = 2,
                        tabCount = 4,
                        cardCount = 5,
                        summary = "ask=4 deny=1",
                    ),
                entrySurfaceSignal =
                    SuperAssistantEntrySurfaceSignal(
                        enabledSurfaceCount = 6,
                        readySurfaceCount = 5,
                        blockedSurfaceCount = 1,
                        voiceReady = true,
                        recentEntryCount = 2,
                        pendingActionCount = 1,
                    ),
            )

        assertEquals("green", snapshot.status)
        assertTrue(snapshot.score >= 80)
        assertEquals(
            setOf("visual", "connector_first", "resume", "permission", "entry", "regression"),
            snapshot.signals.map { it.domain }.toSet(),
        )
        assertTrue(snapshot.lines.any { it.startsWith("super_assistant | status=green") })
    }

    @Test
    fun `urgency maps blocked maturity domains to matching scenarios`() {
        val snapshot =
            SuperAssistantMaturitySnapshot(
                status = "red",
                signals =
                    listOf(
                        SuperAssistantMaturitySignal(domain = "permission", status = "blocked", score = 20),
                        SuperAssistantMaturitySignal(domain = "regression", status = "attention", score = 50),
                    ),
            )
        val scenario = RegressionRunner.scenarioCatalog().first { it.intentType == "safety_governance" }
        val urgency = SuperAssistantMaturityGateStore.urgencyForScenario(scenario, snapshot)

        assertTrue(urgency.penalty >= 9)
        assertTrue(urgency.reason.contains("permission:blocked"))
    }

    private fun platformSnapshot(): SessionPlatformSnapshot =
        SessionPlatformSnapshot(
            sessionId = "s1",
            state =
                SessionRuntimeState(
                    session =
                        RuntimeSession(
                            running = true,
                            sessionId = "s1",
                            profileId = "wechat_assistant",
                            targetPackageName = "com.tencent.mm",
                            task = "去微信发消息",
                            turns = 3,
                        ),
                ),
            resumeSnapshot =
                SessionResumeSnapshot(
                    resumable = true,
                    sessionId = "s1",
                    profileId = "wechat_assistant",
                    targetPackageName = "com.tencent.mm",
                    task = "去微信发消息",
                    turns = 3,
                    lastTransition = "resumed",
                ),
            bridgeSnapshot =
                SessionBridgeSnapshot(
                    sessionId = "s1",
                    task = "去微信发消息",
                    entrySource = "app",
                    targetPackageName = "com.tencent.mm",
                    profileId = "wechat_assistant",
                    turn = 3,
                    statusCode = AgentUiStatus.RUNNING,
                    statusCategory = AgentUiStatusCategory.ACTIVE_EXECUTION,
                    statusBlockedReason = AgentUiBlockedReason.NONE,
                    statusExecutionPhase = AgentUiExecutionPhase.RUNNING,
                    statusTakeoverReason = AgentUiTakeoverReason.NONE,
                    statusTerminalReason = AgentUiTerminalReason.NONE,
                    resumable = true,
                    targetAppReturnEligible = true,
                    routeReason = "profile",
                    resultSummary = "",
                    errorSummary = "",
                    takeoverSummary = "",
                    pendingSafetyConfirmation = "",
                    recentArtifacts =
                        listOf(
                            ReplayArtifactSummary(
                                turn = 2,
                                artifactId = "a1",
                                type = "planning_screenshot",
                                summary = "visual evidence",
                            ),
                        ),
                ),
            healthSummary =
                SessionPlatformHealthSummary(
                    status = "ready",
                    summary = "runtime ready",
                    deterministicReplayReady = true,
                    resumableSnapshotPresent = true,
                    bridgeFeedPresent = true,
                    runtimeLedgerPresent = true,
                ),
        )
}
