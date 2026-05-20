package com.lmx.xiaoxuanagent.runtime

import java.text.SimpleDateFormat
import java.util.Date

internal fun projectDebugRuntimeState(
    state: DebugUiState,
    runtimeState: SessionRuntimeState,
    timeFormatter: SimpleDateFormat,
): DebugUiState {
    val session = runtimeState.session
    val runtimeSemantics = runtimeState.resolveSessionSemantics()
    val projectedSafetyPanel = projectDebugSafetyState(runtimeState.safety)
    val externalWaitState = session.externalWaitState
    val hasActiveRuntimeSession =
        session.sessionId.isNotBlank() ||
            session.task.isNotBlank() ||
            session.turns > 0 ||
            session.running ||
            session.paused
    val runtimeUpdatedAtLabel =
        if (runtimeState.updatedAtMs > 0L) {
            timeFormatter.format(Date(runtimeState.updatedAtMs))
        } else {
            state.runtimeUpdatedAtLabel
        }
    val projectedStatus =
        if (hasActiveRuntimeSession) {
            runtimeSemantics.statusModel.code
        } else {
            AgentUiStatus.deriveProjectedStatus(
                explicitStatus = state.status.code,
                runtimeStatus = session.status,
                sessionRunning = session.running,
                awaitingConfirmation = runtimeState.safety.awaitingConfirmation,
                hasActiveRuntimeSession = hasActiveRuntimeSession,
            )
        }
    val projectedStatusPanel = debugStatusPanelFor(projectedStatus)
    val projectedTakeoverPanel =
        state.takeover.copy(
            waitingForExternal = externalWaitState != null,
            waitingForEvent = externalWaitState?.event.orEmpty(),
            suspendReason = externalWaitState?.reason.orEmpty(),
            latestTakeoverType =
                runtimeState.takeoverSnapshot?.latestTakeoverType ?: state.takeover.latestTakeoverType,
            latestTakeoverReason =
                runtimeState.takeoverSnapshot?.latestTakeoverReason ?: state.takeover.latestTakeoverReason,
            latestTakeoverResumeHint =
                runtimeState.takeoverSnapshot?.latestTakeoverResumeHint ?: state.takeover.latestTakeoverResumeHint,
            latestTakeoverCorrection =
                runtimeState.takeoverSnapshot?.latestTakeoverCorrection ?: state.takeover.latestTakeoverCorrection,
        )
    val projectedRoutePanel =
        runtimeState.routeSnapshot?.let { snapshot ->
            DebugRoutePanel(
                activeProfileId =
                    if (hasActiveRuntimeSession) {
                        session.profileId.ifBlank { snapshot.activeProfileId }
                    } else {
                        snapshot.activeProfileId
                    },
                activeTargetPackage =
                    if (hasActiveRuntimeSession) {
                        session.targetPackageName.ifBlank { snapshot.activeTargetPackage }
                    } else {
                        snapshot.activeTargetPackage
                    },
                reason = snapshot.reason,
                policyTag = snapshot.policyTag,
                modelChoiceProfileId = snapshot.modelChoiceProfileId,
                selectedProfileId = snapshot.selectedProfileId,
                fallbackReason = snapshot.fallbackReason,
                memoryHints = snapshot.memoryHints,
                modelRaw = snapshot.modelRaw,
            )
        } ?: state.route.copy(
            activeProfileId =
                if (hasActiveRuntimeSession) {
                    session.profileId
                } else {
                    state.route.activeProfileId
                },
            activeTargetPackage =
                if (hasActiveRuntimeSession) {
                    session.targetPackageName
                } else {
                    state.route.activeTargetPackage
                },
        )
    val projectedPlanningPanel =
        state.planning.copy(
            activeSkillTitles =
                if (hasActiveRuntimeSession && session.planningSnapshot.activeSkillTitles.isNotEmpty()) {
                    session.planningSnapshot.activeSkillTitles
                } else {
                    state.planning.activeSkillTitles
                },
            planType =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.planType.ifBlank { state.planning.planType }
                } else {
                    state.planning.planType
                },
            currentPlanStage =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.currentPlanStage.ifBlank { state.planning.currentPlanStage }
                } else {
                    state.planning.currentPlanStage
                },
            currentSubgoalId =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.currentSubgoalId.ifBlank { state.planning.currentSubgoalId }
                } else {
                    state.planning.currentSubgoalId
                },
            nextObjective =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.nextObjective.ifBlank { state.planning.nextObjective }
                } else {
                    state.planning.nextObjective
                },
            lastPlannerAction =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.lastPlannerAction.ifBlank { state.planning.lastPlannerAction }
                } else {
                    state.planning.lastPlannerAction
                },
            lastPlannerRaw =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.lastPlannerRaw.ifBlank { state.planning.lastPlannerRaw }
                } else {
                    state.planning.lastPlannerRaw
                },
            lastResumeDecision =
                if (hasActiveRuntimeSession) {
                    session.planningSnapshot.lastResumeDecision
                        .takeIf { it.active }
                        ?.let { snapshot ->
                            buildString {
                                append(snapshot.channel.name.lowercase())
                                append("/")
                                append(snapshot.phase.name.lowercase())
                                append(":")
                                append(snapshot.stage.ifBlank { "-" })
                                append(":")
                                append(snapshot.policy.wireName.ifBlank { "-" })
                                append(":")
                                append(snapshot.actionLabel.ifBlank { "-" })
                                snapshot.recoveryCategory
                                    .takeIf { it.isNotBlank() }
                                    ?.let { append(":").append(it.lowercase()) }
                            }
                        } ?: state.planning.lastResumeDecision
                } else {
                    state.planning.lastResumeDecision
                },
        )
    val projectedResultPanel =
        runtimeState.resultSnapshot?.let { snapshot ->
            DebugResultPanel(
                lastAction = snapshot.lastAction,
                lastResult = snapshot.lastResult,
                intentType = snapshot.intentType,
                title = snapshot.title,
                summary = snapshot.summary,
                highlights = snapshot.highlights,
                lastError = snapshot.lastError,
                hint = snapshot.hint,
            )
        } ?: state.result
    val nextState =
        state.copy(
            sessionId =
                if (hasActiveRuntimeSession) {
                    session.sessionId.ifBlank { state.sessionId }
                } else {
                    state.sessionId
                },
            replayFileName =
                if (hasActiveRuntimeSession) {
                    session.sessionId.takeIf { it.isNotBlank() }?.let(ReplayStore::sessionFileName).orEmpty()
                } else {
                    state.replayFileName
                },
            query =
                if (hasActiveRuntimeSession && session.task.isNotBlank()) {
                    session.task
                } else {
                    state.query
                },
            entrySource =
                if (hasActiveRuntimeSession) {
                    session.entrySource.ifBlank { state.entrySource }
                } else {
                    state.entrySource
                },
            agentRunning = session.running,
            agentTurn =
                if (hasActiveRuntimeSession) {
                    session.turns
                } else {
                    state.agentTurn
                },
            runtimeTransition = runtimeState.lastTransition.ifBlank { state.runtimeTransition },
            runtimeUpdatedAtLabel = runtimeUpdatedAtLabel,
        )
    return nextState
        .withRoutePanel(projectedRoutePanel)
        .withPlanningPanel(projectedPlanningPanel)
        .withResultPanel(projectedResultPanel)
        .withTakeoverPanel(projectedTakeoverPanel)
        .withSafetyPanel(projectedSafetyPanel)
        .withStatusPanel(projectedStatusPanel)
}

internal fun projectDebugSafetyState(
    safetyState: RuntimeSafetyState,
): DebugSafetyPanel {
    val pendingConfirmation = safetyState.pendingConfirmation.takeIf { safetyState.awaitingConfirmation }
    return DebugSafetyPanel(
        awaitingConfirmation = pendingConfirmation != null,
        pendingConfirmationTitle = pendingConfirmation?.title.orEmpty(),
        pendingConfirmationSummary = pendingConfirmation?.summary.orEmpty(),
        pendingConfirmationActionLabel = pendingConfirmation?.actionLabel.orEmpty(),
    )
}

internal fun DebugUiState.withRoutePanel(
    panel: DebugRoutePanel,
): DebugUiState =
    copy(
        route = panel,
    )

internal fun DebugUiState.withPlanningPanel(
    panel: DebugPlanningPanel,
): DebugUiState =
    copy(
        planning = panel,
    )

internal fun DebugUiState.withResultPanel(
    panel: DebugResultPanel,
): DebugUiState =
    copy(
        result = panel,
    )

internal fun DebugUiState.withTakeoverPanel(
    panel: DebugTakeoverPanel,
): DebugUiState =
    copy(
        takeover = panel,
    )

internal fun DebugUiState.withSafetyPanel(
    panel: DebugSafetyPanel,
): DebugUiState =
    copy(
        safety = panel,
    )

internal fun DebugUiState.withStatusPanel(
    panel: DebugStatusPanel,
): DebugUiState =
    copy(
        status = panel,
    )

internal fun debugStatusPanelFor(
    code: String,
): DebugStatusPanel {
    val statusModel = AgentUiStatus.resolve(code)
    return DebugStatusPanel(
        code = statusModel.code,
        mode = statusModel.toMode(),
        category = statusModel.category,
        blockedReason = statusModel.blockedReason,
        executionPhase = statusModel.executionPhase,
        takeoverReason = statusModel.takeoverReason,
        terminalReason = statusModel.terminalReason,
    )
}
