package com.lmx.xiaoxuanagent.runtime

data class DebugRoutePanel(
    val activeProfileId: String = "",
    val activeTargetPackage: String = "",
    val reason: String = "",
    val policyTag: String = "",
    val modelChoiceProfileId: String = "",
    val selectedProfileId: String = "",
    val fallbackReason: String = "",
    val memoryHints: List<String> = emptyList(),
    val modelRaw: String = "",
)

data class DebugPlanningPanel(
    val activeSkillTitles: List<String> = emptyList(),
    val planType: String = "",
    val currentPlanStage: String = "",
    val currentSubgoalId: String = "",
    val nextObjective: String = "",
    val lastPlannerAction: String = "",
    val lastPlannerRaw: String = "",
    val lastResumeDecision: String = "",
)

data class DebugResultPanel(
    val lastAction: String = "",
    val lastResult: String = "",
    val intentType: String = "",
    val title: String = "",
    val summary: String = "",
    val highlights: List<String> = emptyList(),
    val lastError: String = "",
    val hint: String = "",
)

data class DebugTakeoverPanel(
    val waitingForExternal: Boolean = false,
    val waitingForEvent: String = "",
    val suspendReason: String = "",
    val latestTakeoverType: String = "",
    val latestTakeoverReason: String = "",
    val latestTakeoverResumeHint: String = "",
    val latestTakeoverCorrection: String = "",
)

data class DebugSafetyPanel(
    val awaitingConfirmation: Boolean = false,
    val pendingConfirmationTitle: String = "",
    val pendingConfirmationSummary: String = "",
    val pendingConfirmationActionLabel: String = "",
)

data class DebugStatusPanel(
    val code: String = AgentUiStatus.IDLE,
    val mode: AgentUiMode = AgentUiMode.Idle,
    val category: AgentUiStatusCategory = AgentUiStatusCategory.IDLE,
    val blockedReason: AgentUiBlockedReason = AgentUiBlockedReason.NONE,
    val executionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.NONE,
    val takeoverReason: AgentUiTakeoverReason = AgentUiTakeoverReason.NONE,
    val terminalReason: AgentUiTerminalReason = AgentUiTerminalReason.NONE,
) {
    val isBlockedInput: Boolean
        get() = mode is AgentUiMode.BlockedInput

    val isExecuting: Boolean
        get() = mode is AgentUiMode.Executing

    val isTakeoverLike: Boolean
        get() = mode is AgentUiMode.Takeover

    val isTerminal: Boolean
        get() = mode is AgentUiMode.Terminal

    val isPaused: Boolean
        get() = takeoverReason == AgentUiTakeoverReason.PAUSED

    val isWaitingExternal: Boolean
        get() = takeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL

    val isAwaitingConfirmation: Boolean
        get() = takeoverReason == AgentUiTakeoverReason.AWAITING_CONFIRMATION
}

data class DebugUiState(
    val sessionId: String = "",
    val replayFileName: String = "",
    val entrySource: String = "app",
    val query: String = "",
    val accessibilityConnected: Boolean = false,
    val foregroundPackage: String = "",
    val pageState: AppPageState = AppPageState.Unknown,
    val lastEventType: String = "-",
    val observationSignature: String = "",
    val visibleElementCount: Int = 0,
    val agentRunning: Boolean = false,
    val agentTurn: Int = 0,
    val plannerMode: String = "-",
    val runtimeTransition: String = "",
    val runtimeUpdatedAtLabel: String = "-",
    val screenshotStatus: String = "-",
    val memorySummary: String = "",
    val harnessSummary: String = "",
    val routingSummary: String = "",
    val takeoverSummary: String = "",
    val recentSkillInvocations: List<String> = emptyList(),
    val recentSessionSummaries: List<String> = emptyList(),
    val platformSummaryLines: List<String> = emptyList(),
    val platformApprovalLines: List<String> = emptyList(),
    val capabilityLines: List<String> = emptyList(),
    val remoteBridgeLines: List<String> = emptyList(),
    val remoteTransportLines: List<String> = emptyList(),
    val workerLines: List<String> = emptyList(),
    val proactiveTaskLines: List<String> = emptyList(),
    val externalSignalLines: List<String> = emptyList(),
    val memoryRecallLines: List<String> = emptyList(),
    val traceLines: List<String> = emptyList(),
    val compareLines: List<String> = emptyList(),
    val batchReplayLines: List<String> = emptyList(),
    val stepReplayLines: List<String> = emptyList(),
    val recentBridgeEvents: List<String> = emptyList(),
    val replayVerificationSummary: String = "",
    val recentRuntimeEvents: List<String> = emptyList(),
    val recentRuntimeArtifacts: List<String> = emptyList(),
    val recentLogs: List<String> = emptyList(),
    val route: DebugRoutePanel = DebugRoutePanel(),
    val planning: DebugPlanningPanel = DebugPlanningPanel(),
    val result: DebugResultPanel = DebugResultPanel(),
    val takeover: DebugTakeoverPanel = DebugTakeoverPanel(),
    val safety: DebugSafetyPanel = DebugSafetyPanel(),
    val status: DebugStatusPanel = DebugStatusPanel(),
)
