package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantAnalyticsSummary
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot

data class SessionPlatformSnapshot(
    val sessionId: String,
    val state: SessionRuntimeState,
    val sessionSnapshot: ReplaySessionSnapshot? = null,
    val resumeSnapshot: SessionResumeSnapshot? = null,
    val bridgeSnapshot: SessionBridgeSnapshot,
    val metricsSnapshot: RuntimeMetricsSnapshot = RuntimeMetricsSnapshot(),
    val healthSummary: SessionPlatformHealthSummary = SessionPlatformHealthSummary(),
    val recentBridgeEvents: List<SessionBridgeProtocolEntry> = emptyList(),
    val recentRuntimeEvents: List<RuntimeEventEntry> = emptyList(),
    val recentArtifacts: List<ReplayArtifactSummary> = emptyList(),
    val pendingSafetySummary: String = "",
)

data class SessionPlatformSummary(
    val sessionId: String,
    val statusCode: String,
    val resumable: Boolean,
    val replayTurnCount: Int,
    val replayEventCount: Int,
    val artifactCount: Int,
    val runtimeEventCount: Int,
    val hasResumeSnapshot: Boolean,
    val pendingSafety: Boolean,
    val lastTransition: String,
    val latestResultSummary: String,
)

data class SessionPlatformHealthSummary(
    val status: String = "idle",
    val summary: String = "",
    val deterministicReplayReady: Boolean = false,
    val resumableSnapshotPresent: Boolean = false,
    val bridgeFeedPresent: Boolean = false,
    val runtimeLedgerPresent: Boolean = false,
    val pendingApprovalCount: Int = 0,
    val remotePendingCount: Int = 0,
    val workerQueueCount: Int = 0,
    val mailboxPendingCount: Int = 0,
    val memoryQueuePendingCount: Int = 0,
    val proactiveTaskCount: Int = 0,
    val staleRuntime: Boolean = false,
)

data class SessionPlatformProductDiagnosticsSnapshot(
    val productShell: AssistantProductShellSnapshot = AssistantProductShellSnapshot(),
    val analytics: AssistantAnalyticsSummary = AssistantAnalyticsSummary(),
)

data class SessionReplayVerification(
    val sessionId: String,
    val replayable: Boolean,
    val matches: Boolean,
    val commandCount: Int,
    val expectedStatus: String,
    val replayedStatus: String,
    val mismatches: List<String> = emptyList(),
)

data class SessionApprovalTicket(
    val sessionId: String,
    val task: String,
    val statusCode: String,
    val approvalSummary: String,
    val entrySource: String,
    val targetPackageName: String,
    val updatedAtMs: Long,
)

data class SessionPlatformDiff(
    val leftSessionId: String,
    val rightSessionId: String,
    val summary: String,
    val lines: List<String>,
)

data class SessionBatchReplayReport(
    val totalSessions: Int,
    val replayableSessions: Int,
    val matchedSessions: Int,
    val mismatchedSessions: Int,
    val lines: List<String>,
)

data class SessionReplayTimelineEntry(
    val commandIndex: Int,
    val timestamp: Long,
    val commandType: String,
    val statusCode: String,
    val transition: String,
    val summary: String,
)

data class SessionStepReplayReport(
    val sessionId: String,
    val uptoCommandCount: Int,
    val totalCommands: Int,
    val replayedStatus: String,
    val expectedStatus: String,
    val mismatches: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

data class SessionReplayInspectReport(
    val sessionId: String,
    val commandIndex: Int,
    val totalCommands: Int,
    val summary: String,
    val lines: List<String> = emptyList(),
)

data class SessionReplayStateFrame(
    val status: String,
    val statusCategory: AgentUiStatusCategory = AgentUiStatusCategory.IDLE,
    val blockedReason: AgentUiBlockedReason = AgentUiBlockedReason.NONE,
    val executionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.NONE,
    val takeoverReason: AgentUiTakeoverReason = AgentUiTakeoverReason.NONE,
    val terminalReason: AgentUiTerminalReason = AgentUiTerminalReason.NONE,
    val turns: Int,
    val running: Boolean,
    val planning: Boolean,
    val paused: Boolean,
    val transition: String,
    val routeReason: String,
    val resultSummary: String,
    val lastResult: String,
    val lastError: String,
    val observationSignature: String,
)

data class SessionReplayStateInspectReport(
    val sessionId: String,
    val commandIndex: Int,
    val totalCommands: Int,
    val commandType: String,
    val beforeState: SessionReplayStateFrame,
    val afterState: SessionReplayStateFrame,
    val summary: String,
    val lines: List<String> = emptyList(),
)

data class SessionReplayArtifactInspectDetail(
    val artifactId: String,
    val type: String,
    val summary: String,
    val path: String,
    val previewLines: List<String> = emptyList(),
)

data class SessionReplayArtifactInspectReport(
    val sessionId: String,
    val commandIndex: Int,
    val totalCommands: Int,
    val turn: Int,
    val summary: String,
    val artifacts: List<SessionReplayArtifactInspectDetail> = emptyList(),
    val lines: List<String> = emptyList(),
)

data class SessionReplayStepCompareReport(
    val sessionId: String,
    val leftCommandIndex: Int,
    val rightCommandIndex: Int,
    val totalCommands: Int,
    val summary: String,
    val lines: List<String> = emptyList(),
)

data class SessionReplayBreakpointInspectReport(
    val sessionId: String,
    val commandIndex: Int,
    val totalCommands: Int,
    val commandType: String = "",
    val statusCode: String = "",
    val turn: Int = 0,
    val swarmMode: String = "",
    val swarmSummary: String = "",
    val graphSummary: String = "",
    val mailboxSummary: String = "",
    val schedulerSummary: String = "",
    val providerSummary: String = "",
    val signalSummary: String = "",
    val approvalSummary: String = "",
    val artifactLifecycleSummary: String = "",
    val traceSummary: String = "",
    val traceCount: Int = 0,
    val artifactCount: Int = 0,
    val summary: String,
    val lines: List<String> = emptyList(),
)
