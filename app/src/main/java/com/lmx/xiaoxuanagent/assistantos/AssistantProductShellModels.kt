package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot

data class AssistantOnboardingStepState(
    val id: String,
    val title: String,
    val summary: String,
    val actionLabel: String,
    val category: String = "core",
    val riskLevel: String = "medium",
    val priority: Int = 0,
    val blocking: Boolean = false,
    val status: String = "pending",
    val requirementMet: Boolean = false,
    val manualState: String = "",
    val note: String = "",
    val updatedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
)

data class AssistantOnboardingState(
    val status: String = "unknown",
    val summary: String = "",
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val steps: List<AssistantOnboardingStepState> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantTipCard(
    val id: String,
    val dedupeKey: String = "",
    val title: String,
    val summary: String,
    val actionLabel: String,
    val recommendedPage: String = "",
    val priority: Int = 0,
    val source: String = "",
    val audience: String = "all",
    val status: String = "active",
    val note: String = "",
    val nextEligibleAtMs: Long = 0L,
    val lastPresentedAtMs: Long = 0L,
    val lastShownAtMs: Long = 0L,
    val shownCount: Int = 0,
    val cooldownSessions: Int = 0,
    val eligibilityReason: String = "",
    val dismissedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

data class AssistantSwarmStrategy(
    val mode: String = "idle",
    val summary: String = "",
    val maxConcurrentWorkers: Int = 3,
    val mailboxBatchSize: Int = 8,
    val mailboxPriorityMode: String = "balanced",
    val fairnessMode: String = "coordinator_fair",
    val leasePolicyMode: String = "runtime_lease",
    val coordinationMode: String = "coordinator_claim_release",
    val leasePressureSummary: String = "",
    val dispatchSummary: String = "",
    val missionSummary: String = "",
    val escalationSummary: String = "",
    val joinSummary: String = "",
    val collaboratorSummary: String = "",
    val dispatchCandidates: List<String> = emptyList(),
    val recommendedWorkerIds: List<String> = emptyList(),
    val focusSessionIds: List<String> = emptyList(),
    val blockedCoordinatorIds: List<String> = emptyList(),
    val laneLines: List<String> = emptyList(),
    val handoffActions: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val pendingPermissionRequests: Int = 0,
    val pendingMailboxMessages: Int = 0,
    val updatedAtMs: Long = 0L,
)

data class AssistantCompanionShellSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val presenceSummary: String = "",
    val voiceSummary: String = "",
    val swarmSummary: String = "",
    val memorySummary: String = "",
    val providerSummary: String = "",
    val nextActionSummary: String = "",
    val laneLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantOperatorShellSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val providerHealthSummary: String = "",
    val providerCoverageSummary: String = "",
    val providerDiagnosticsSummary: String = "",
    val artifactHealthSummary: String = "",
    val replayHealthSummary: String = "",
    val workerHealthSummary: String = "",
    val workerMissionSummary: String = "",
    val workerLeaseSummary: String = "",
    val graphHealthSummary: String = "",
    val swarmPolicySummary: String = "",
    val urgentLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantAgendaShellSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val dueNowCount: Int = 0,
    val upcomingCount: Int = 0,
    val signalCount: Int = 0,
    val focusTitle: String = "",
    val agendaLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantDailyRhythmSnapshot(
    val mode: String = "steady",
    val summary: String = "",
    val nextWindowSummary: String = "",
    val attentionSummary: String = "",
    val signalPressureSummary: String = "",
    val proactivePressureSummary: String = "",
    val followUpSummary: String = "",
    val rhythmLines: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantRoutineShellSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val nextWindowSummary: String = "",
    val focusTheme: String = "",
    val checklistLines: List<String> = emptyList(),
    val followUpLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantRoutinePolicySnapshot(
    val focusTheme: String = "默认主焦点",
    val reviewWindow: String = "09:00-11:30",
    val followUpWindow: String = "14:00-18:00",
    val checklistTemplates: List<String> = listOf("清收今日收件箱", "确认待审批事项", "推进主任务"),
    val preferredSurfaces: List<String> = listOf("notification", "shortcut", "widget"),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

data class AssistantDigestShellSnapshot(
    val mode: String = "quiet",
    val title: String = "",
    val summary: String = "",
    val highlightLines: List<String> = emptyList(),
    val actionLabel: String = "",
    val actionCommand: String = "",
    val updatedAtMs: Long = 0L,
)

data class AssistantDigestPolicySnapshot(
    val enabled: Boolean = true,
    val cadence: String = "adaptive",
    val maxHighlights: Int = 4,
    val deliverySurfaces: List<String> = listOf("notification", "widget"),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

data class AssistantQuietHoursSnapshot(
    val enabled: Boolean = false,
    val startLocalTime: String = "22:00",
    val endLocalTime: String = "08:00",
    val activeNow: Boolean = false,
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

data class AssistantInterruptBudgetSnapshot(
    val mode: String = "open",
    val summary: String = "",
    val totalBudget: Int = 4,
    val consumedBudget: Int = 0,
    val remainingBudget: Int = 4,
    val hardBlock: Boolean = false,
    val cooldownSummary: String = "",
    val allowedSources: List<String> = emptyList(),
    val blockedSources: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantInterruptPolicySnapshot(
    val baseBudget: Int = 4,
    val focusBudget: Int = 2,
    val hardBlockInQuietHours: Boolean = true,
    val preferredSources: List<String> = listOf("notification", "shortcut", "widget"),
    val blockedSources: List<String> = emptyList(),
    val summary: String = "",
    val updatedAtMs: Long = 0L,
)

data class AssistantPersonalFocusSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val focusSessionId: String = "",
    val focusTask: String = "",
    val focusReason: String = "",
    val missionSummary: String = "",
    val coordinationSummary: String = "",
    val userModelSummary: String = "",
    val nextBestActions: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantViewerShellSnapshot(
    val mode: String = "idle",
    val focusSessionId: String = "",
    val focusTask: String = "",
    val detailSummary: String = "",
    val timelineSummary: String = "",
    val graphSummary: String = "",
    val approvalSummary: String = "",
    val resultSummary: String = "",
    val actionLaneSummary: String = "",
    val detailLines: List<String> = emptyList(),
    val timelineLines: List<String> = emptyList(),
    val graphLines: List<String> = emptyList(),
    val approvalLines: List<String> = emptyList(),
    val traceLines: List<String> = emptyList(),
    val actionLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantPermissionProductTabSnapshot(
    val id: String = "",
    val title: String = "",
    val count: Int = 0,
    val summary: String = "",
    val active: Boolean = false,
)

data class AssistantPermissionProductCardSnapshot(
    val cardId: String = "",
    val cardType: String = "rule",
    val behavior: String = "",
    val title: String = "",
    val subtitle: String = "",
    val scope: String = "",
    val sourceTag: String = "",
    val surfaceHint: String = "",
    val explanation: String = "",
    val primaryCommand: String = "",
)

data class AssistantGovernanceShellSnapshot(
    val mode: String = "transparent",
    val summary: String = "",
    val consentSummary: String = "",
    val privacySummary: String = "",
    val historySummary: String = "",
    val approvalSummary: String = "",
    val retentionSummary: String = "",
    val providerPolicySummary: String = "",
    val autonomySummary: String = "",
    val permissionProductSummary: String = "",
    val permissionProductActiveTab: String = "",
    val permissionProductTabs: List<AssistantPermissionProductTabSnapshot> = emptyList(),
    val permissionProductCards: List<AssistantPermissionProductCardSnapshot> = emptyList(),
    val explanationLines: List<String> = emptyList(),
    val controlLines: List<String> = emptyList(),
    val actionLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantAutonomyPlanSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val triggerPolicyMode: String = "balanced",
    val restoreMode: String = "balanced",
    val proactiveMode: String = "balanced",
    val entrySurfaceMode: String = "balanced",
    val eventPrioritySummary: String = "",
    val userModelSummary: String = "",
    val workbenchSummary: String = "",
    val enginePhaseOrder: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class AssistantProductShellSnapshot(
    val onboarding: AssistantOnboardingState = AssistantOnboardingState(),
    val tips: List<AssistantTipCard> = emptyList(),
    val tipLedger: List<AssistantTipCard> = emptyList(),
    val swarmStrategy: AssistantSwarmStrategy = AssistantSwarmStrategy(),
    val companionShell: AssistantCompanionShellSnapshot = AssistantCompanionShellSnapshot(),
    val operatorShell: AssistantOperatorShellSnapshot = AssistantOperatorShellSnapshot(),
    val agendaShell: AssistantAgendaShellSnapshot = AssistantAgendaShellSnapshot(),
    val dailyRhythm: AssistantDailyRhythmSnapshot = AssistantDailyRhythmSnapshot(),
    val routineShell: AssistantRoutineShellSnapshot = AssistantRoutineShellSnapshot(),
    val routinePolicy: AssistantRoutinePolicySnapshot = AssistantRoutinePolicySnapshot(),
    val digestShell: AssistantDigestShellSnapshot = AssistantDigestShellSnapshot(),
    val digestPolicy: AssistantDigestPolicySnapshot = AssistantDigestPolicySnapshot(),
    val quietHours: AssistantQuietHoursSnapshot = AssistantQuietHoursSnapshot(),
    val interruptBudget: AssistantInterruptBudgetSnapshot = AssistantInterruptBudgetSnapshot(),
    val interruptPolicy: AssistantInterruptPolicySnapshot = AssistantInterruptPolicySnapshot(),
    val personalFocus: AssistantPersonalFocusSnapshot = AssistantPersonalFocusSnapshot(),
    val viewerShell: AssistantViewerShellSnapshot = AssistantViewerShellSnapshot(),
    val governanceShell: AssistantGovernanceShellSnapshot = AssistantGovernanceShellSnapshot(),
    val userModel: PersonalAssistantUserModelSnapshot = PersonalAssistantUserModelSnapshot(),
    val memoryInsight: PersonalMemoryInsightSnapshot = PersonalMemoryInsightSnapshot(),
    val voiceInteraction: AssistantVoiceInteractionSnapshot = AssistantVoiceInteractionSnapshot(),
    val autonomyPlan: AssistantAutonomyPlanSnapshot = AssistantAutonomyPlanSnapshot(),
    val diagnostics: AssistantProductDiagnosticsSnapshot = AssistantProductDiagnosticsSnapshot(),
    val analytics: AssistantAnalyticsSummary = AssistantAnalyticsSummary(),
    val lastSyncReason: String = "",
    val updatedAtMs: Long = 0L,
)

internal data class AssistantOnboardingStepCandidate(
    val id: String,
    val title: String,
    val summary: String,
    val actionLabel: String,
    val category: String = "core",
    val riskLevel: String = "medium",
    val priority: Int = 0,
    val blocking: Boolean = false,
    val requirementMet: Boolean,
)

internal data class AssistantTipCandidate(
    val dedupeKey: String,
    val title: String,
    val summary: String,
    val actionLabel: String,
    val recommendedPage: String = "",
    val priority: Int,
    val source: String,
    val audience: String = "all",
    val eligibilityReason: String = "",
    val cooldownSessions: Int = 0,
)
