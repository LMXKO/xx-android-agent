package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation

data class SessionBridgeSnapshot(
    val sessionId: String,
    val task: String,
    val entrySource: String,
    val targetPackageName: String,
    val profileId: String,
    val turn: Int,
    val statusCode: String,
    val statusCategory: AgentUiStatusCategory,
    val statusBlockedReason: AgentUiBlockedReason = AgentUiBlockedReason.NONE,
    val statusExecutionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.NONE,
    val statusTakeoverReason: AgentUiTakeoverReason = AgentUiTakeoverReason.NONE,
    val statusTerminalReason: AgentUiTerminalReason = AgentUiTerminalReason.NONE,
    val resumable: Boolean,
    val targetAppReturnEligible: Boolean,
    val routeReason: String,
    val resultSummary: String,
    val errorSummary: String,
    val takeoverSummary: String,
    val pendingSafetyConfirmation: String,
    val recentArtifacts: List<ReplayArtifactSummary> = emptyList(),
    val recentEvents: List<ReplayEventSummary> = emptyList(),
)

fun SessionBridgeSnapshot.toStatusSnapshot(): AgentUiStatusSnapshot =
    AgentUiStatusSnapshot(
        code = statusCode.ifBlank { AgentUiStatus.IDLE },
        category = statusCategory,
        blockedReason = statusBlockedReason,
        executionPhase = statusExecutionPhase,
        takeoverReason = statusTakeoverReason,
        terminalReason = statusTerminalReason,
    )

fun buildSessionBridgeSnapshot(
    state: SessionRuntimeState,
    sessionSnapshot: ReplaySessionSnapshot?,
): SessionBridgeSnapshot {
    val session = state.session
    val semantics = state.resolveSessionSemantics()
    return SessionBridgeSnapshot(
        sessionId = session.sessionId,
        task = session.task,
        entrySource = session.entrySource,
        targetPackageName = session.targetPackageName,
        profileId = session.profileId,
        turn = session.turns,
        statusCode = semantics.statusModel.code,
        statusCategory = semantics.statusModel.category,
        statusBlockedReason = semantics.statusModel.blockedReason,
        statusExecutionPhase = semantics.statusModel.executionPhase,
        statusTakeoverReason = semantics.statusModel.takeoverReason,
        statusTerminalReason = semantics.statusModel.terminalReason,
        resumable = semantics.resumable,
        targetAppReturnEligible = semantics.targetAppReturnEligible,
        routeReason = state.routeSnapshot?.reason.orEmpty(),
        resultSummary = state.resultSnapshot?.summary ?: state.resultSnapshot?.lastResult.orEmpty(),
        errorSummary = state.resultSnapshot?.lastError.orEmpty(),
        takeoverSummary = state.takeoverSnapshot?.latestTakeoverReason.orEmpty(),
        pendingSafetyConfirmation = state.safety.pendingConfirmation?.summary.orEmpty(),
        recentArtifacts = sessionSnapshot?.recentArtifactGroups?.flatMap { it.artifacts }.orEmpty(),
        recentEvents = sessionSnapshot?.recentEvents.orEmpty(),
    )
}

sealed interface SessionBridgeEvent {
    data class RoutingStarted(
        val sessionId: String,
        val task: String,
        val entrySource: String,
    ) : SessionBridgeEvent

    data class RoutingDiagnostics(
        val sessionId: String,
        val messages: List<String>,
    ) : SessionBridgeEvent

    data class RoutingFailed(
        val sessionId: String,
        val task: String,
        val entrySource: String,
        val routeReason: String,
    ) : SessionBridgeEvent

    data class AppUnavailable(
        val sessionId: String,
        val task: String,
        val entrySource: String,
        val profileDisplayName: String,
        val targetPackageName: String,
        val routeReason: String,
    ) : SessionBridgeEvent

    data class RouteResolved(
        val sessionId: String,
        val task: String,
        val entrySource: String,
        val profileDisplayName: String,
        val targetPackageName: String,
        val routeReason: String,
    ) : SessionBridgeEvent

    data class RuntimeStateChanged(
        val sessionId: String,
        val state: SessionRuntimeState,
        val sessionSnapshot: ReplaySessionSnapshot? = null,
        val bridgeSnapshot: SessionBridgeSnapshot? = null,
        val platformSnapshot: SessionPlatformSnapshot? = null,
    ) : SessionBridgeEvent

    data class ArtifactRecorded(
        val sessionId: String,
        val turn: Int,
        val artifactId: String,
        val type: String,
        val summary: String,
    ) : SessionBridgeEvent

    data class TurnCompleted(
        val sessionId: String,
        val turn: Int,
        val actionLabel: String,
        val finalStatus: String,
        val finalResult: String,
        val taskResult: TaskResultPayload?,
        val keepRunning: Boolean,
        val loopDetected: Boolean,
    ) : SessionBridgeEvent

    data class ErrorRecorded(
        val sessionId: String,
        val error: String,
        val keepRunning: Boolean,
    ) : SessionBridgeEvent

    data class ExternalWaitEntered(
        val sessionId: String,
        val waitingEvent: String,
        val suspendReason: String,
        val observationSignature: String,
    ) : SessionBridgeEvent

    data class ExternalWaitResolved(
        val sessionId: String,
        val previousEvent: String,
        val resumeHint: String,
        val userCorrection: String,
        val observationSignature: String,
    ) : SessionBridgeEvent

    data class AgentPaused(
        val sessionId: String,
    ) : SessionBridgeEvent

    data class AgentResumed(
        val sessionId: String,
        val resumeHint: String,
        val resumeSource: String,
        val userCorrection: String,
    ) : SessionBridgeEvent

    data class SafetyConfirmationRequested(
        val sessionId: String,
        val confirmation: PendingSafetyConfirmation,
    ) : SessionBridgeEvent

    data class SafetyConfirmationApproved(
        val sessionId: String,
        val confirmation: PendingSafetyConfirmation,
        val resumeHint: String,
        val userCorrection: String,
    ) : SessionBridgeEvent

    data class AgentStopped(
        val sessionId: String,
        val reason: String,
        val status: String,
    ) : SessionBridgeEvent

    data class PlanningContextAcquired(
        val sessionId: String,
        val turn: Int,
        val observationSignature: String,
        val pageState: String,
        val topTextsPreview: String,
    ) : SessionBridgeEvent

    data class PlannerDecisionRecorded(
        val sessionId: String,
        val actionLabel: String,
        val reason: String,
    ) : SessionBridgeEvent

    data class LogMessage(
        val message: String,
    ) : SessionBridgeEvent

    data class AgentStarted(
        val sessionId: String,
        val task: String,
        val profileDisplayName: String,
        val targetPackageName: String,
        val routeReason: String,
        val entrySource: String,
    ) : SessionBridgeEvent
}

fun interface SessionBridge {
    fun publish(event: SessionBridgeEvent)
}

object NullSessionBridge : SessionBridge {
    override fun publish(event: SessionBridgeEvent) = Unit
}
