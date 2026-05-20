package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ResumeContext

object SessionRuntimeReducer {
    fun reduce(
        current: SessionRuntimeState,
        command: SessionCommand,
        nowMs: Long = System.currentTimeMillis(),
    ): SessionRuntimeState =
        when (command) {
            is SessionCommand.BlockPreflight ->
                current.copy(
                    session = command.session,
                    safety = RuntimeSafetyState(),
                    routeSnapshot = RuntimeRouteSnapshot(),
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.EnterRouting ->
                current.copy(
                    session = command.session,
                    safety = RuntimeSafetyState(),
                    routeSnapshot = RuntimeRouteSnapshot(),
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RoutingFailed ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForBlockedReason(AgentUiBlockedReason.ROUTING_FAILED))
                            .copy(
                            running = false,
                            planning = false,
                            paused = false,
                        ),
                    routeSnapshot = command.routeSnapshot,
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.MarkAppUnavailable ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForBlockedReason(AgentUiBlockedReason.APP_UNAVAILABLE))
                            .copy(
                            running = false,
                            planning = false,
                            paused = false,
                        ),
                    routeSnapshot = command.routeSnapshot,
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RouteResolved ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForBlockedReason(AgentUiBlockedReason.ROUTING))
                            .copy(
                            running = false,
                            planning = false,
                            paused = false,
                        ),
                    routeSnapshot = command.routeSnapshot,
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.StartExecution ->
                current.copy(
                    session = command.session,
                    safety = RuntimeSafetyState(),
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RecordPlannerDecisionState ->
                current.copy(
                    session =
                        current.session.copy(
                            planningSnapshot =
                                current.session.planningSnapshot.copy(
                                    lastPlannerAction = command.actionLabel,
                                    lastPlannerRaw = command.rawResponse.take(600),
                                ),
                        ),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RecordResumeDecisionState ->
                current.copy(
                    session =
                        current.session.copy(
                            planningSnapshot =
                                current.session.planningSnapshot.copy(
                                    lastResumeDecision = command.resumeDecision,
                                ),
                        ),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.ResetSafety ->
                current.copy(
                    safety = RuntimeSafetyState(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RejectSafetyAction ->
                current.copy(
                    safety = RuntimeSafetyState(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.ConsumeGrantedRiskApproval ->
                current.copy(
                    safety = RuntimeSafetyState(),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.FinishTurn -> {
                val session = current.session
                val nextStatusModel = command.disposition.toStatusModel()
                val nextFingerprints =
                    (session.recentFingerprints + "${command.turnRecord.observationSignature}|${command.turnRecord.action}")
                        .takeLast(4)
                current.copy(
                    session =
                        session
                            .withStatusModel(nextStatusModel)
                            .copy(
                            running = command.keepRunning,
                            planning = false,
                            turns = session.turns + 1,
                            lastObservationSignature =
                                if (command.clearLastObservationSignature) {
                                    ""
                                } else {
                                    session.lastObservationSignature
                                },
                            nextPlanEligibleAtMs = command.nextPlanEligibleAtMs,
                            history = session.history + command.turnRecord,
                            recentFingerprints = nextFingerprints,
                            externalWaitState = null,
                            resumeContext =
                                if (command.clearResumeContext) {
                                    ResumeContext()
                                } else {
                                    session.resumeContext
                                },
                        ),
                    safety =
                        if (command.clearSafety) {
                            RuntimeSafetyState()
                        } else {
                            current.safety
                        },
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = command.takeoverSnapshot,
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )
            }

            is SessionCommand.AcquirePlanning ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForExecutionPhase(AgentUiExecutionPhase.PLANNING))
                            .copy(
                            planning = true,
                            lastObservationSignature = command.observationSignature,
                        ),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.AdoptResumeContext ->
                current.copy(
                    session = current.session.copy(resumeContext = command.resumeContext),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.PlanningAcquired ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForExecutionPhase(AgentUiExecutionPhase.PLANNING))
                            .copy(
                            sessionId = command.sessionId,
                            profileId = command.profileId,
                            targetPackageName = command.targetPackageName,
                            task = command.task,
                            planning = true,
                            lastObservationSignature = command.observationSignature,
                            planningSnapshot = command.planningSnapshot,
                        ),
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.RecordRecoverableError ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForExecutionPhase(AgentUiExecutionPhase.WAITING))
                            .copy(
                            running = true,
                            planning = false,
                            nextPlanEligibleAtMs = 0L,
                            lastObservationSignature = "",
                            externalWaitState = null,
                        ),
                    safety =
                        if (command.clearSafety) {
                            RuntimeSafetyState()
                        } else {
                            current.safety
                        },
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = command.takeoverSnapshot,
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.TerminateSession ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForTerminalReason(command.terminalReason))
                            .copy(
                            running = false,
                            planning = false,
                            paused = false,
                            nextPlanEligibleAtMs = 0L,
                            externalWaitState = null,
                            resumeContext = ResumeContext(),
                        ),
                    safety =
                        if (command.clearSafety) {
                            RuntimeSafetyState()
                        } else {
                            current.safety
                        },
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = command.takeoverSnapshot,
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.EnterTakeover ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForTakeoverReason(command.takeoverReason))
                            .copy(
                            paused = true,
                            planning = false,
                            lastObservationSignature = "",
                            nextPlanEligibleAtMs = 0L,
                            externalWaitState = command.externalWaitState,
                            planningSnapshot = command.planningSnapshot ?: current.session.planningSnapshot,
                        ),
                    safety = command.safetyState,
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = command.takeoverSnapshot,
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )

            is SessionCommand.ResumeExecution ->
                current.copy(
                    session =
                        current.session
                            .withStatusModel(AgentUiStatus.modelForExecutionPhase(AgentUiExecutionPhase.RUNNING))
                            .copy(
                            running = true,
                            paused = false,
                            planning = false,
                            lastObservationSignature = "",
                            nextPlanEligibleAtMs = command.nextPlanEligibleAtMs,
                            externalWaitState = null,
                            resumeContext = command.resumeContext,
                            planningSnapshot = command.planningSnapshot ?: current.session.planningSnapshot,
                        ),
                    safety = command.safetyState,
                    resultSnapshot = command.resultSnapshot,
                    takeoverSnapshot = command.takeoverSnapshot,
                    lastTransition = command.reason,
                    updatedAtMs = nowMs,
                )
        }
}
