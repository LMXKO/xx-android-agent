package com.lmx.xiaoxuanagent.runtime

object DebugSessionBridge : SessionBridge {
    override fun publish(event: SessionBridgeEvent) {
        when (event) {
            is SessionBridgeEvent.RoutingStarted ->
                DebugAgentStore.onRuntimeRoutingStarted(
                    sessionId = event.sessionId,
                    task = event.task,
                )

            is SessionBridgeEvent.RoutingDiagnostics ->
                DebugAgentStore.onRuntimeRoutingDiagnostics(
                    sessionId = event.sessionId,
                    messages = event.messages,
                )

            is SessionBridgeEvent.RoutingFailed ->
                DebugAgentStore.onRuntimeRoutingFailed(
                    sessionId = event.sessionId,
                    routeReason = event.routeReason,
                )

            is SessionBridgeEvent.AppUnavailable ->
                DebugAgentStore.onRuntimeAppUnavailable(
                    sessionId = event.sessionId,
                    profileDisplayName = event.profileDisplayName,
                    targetPackageName = event.targetPackageName,
                    routeReason = event.routeReason,
                )

            is SessionBridgeEvent.RouteResolved ->
                DebugAgentStore.onRuntimeRouteResolved(
                    sessionId = event.sessionId,
                    profileDisplayName = event.profileDisplayName,
                    targetPackageName = event.targetPackageName,
                    routeReason = event.routeReason,
                )

            is SessionBridgeEvent.RuntimeStateChanged ->
                DebugAgentStore.onRuntimeStatePublished(
                    sessionId = event.sessionId,
                    state = event.state,
                    sessionSnapshot = event.sessionSnapshot,
                    bridgeSnapshot = event.bridgeSnapshot,
                    platformSnapshot = event.platformSnapshot,
                )

            is SessionBridgeEvent.ArtifactRecorded ->
                DebugAgentStore.onRuntimeArtifactRecorded(
                    sessionId = event.sessionId,
                    turn = event.turn,
                    artifactId = event.artifactId,
                    type = event.type,
                    summary = event.summary,
                )

            is SessionBridgeEvent.TurnCompleted ->
                DebugAgentStore.onRuntimeTurnCompleted(
                    sessionId = event.sessionId,
                    turn = event.turn,
                    actionLabel = event.actionLabel,
                    finalStatus = event.finalStatus,
                    finalResult = event.finalResult,
                    taskResult = event.taskResult,
                    keepRunning = event.keepRunning,
                    loopDetected = event.loopDetected,
                )

            is SessionBridgeEvent.ErrorRecorded ->
                DebugAgentStore.onRuntimeErrorRecorded(
                    sessionId = event.sessionId,
                    error = event.error,
                    keepRunning = event.keepRunning,
                )

            is SessionBridgeEvent.ExternalWaitEntered ->
                DebugAgentStore.onRuntimeExternalWaitEntered(
                    sessionId = event.sessionId,
                    waitingEvent = event.waitingEvent,
                    suspendReason = event.suspendReason,
                    observationSignature = event.observationSignature,
                )

            is SessionBridgeEvent.ExternalWaitResolved ->
                DebugAgentStore.onRuntimeExternalWaitResolved(
                    sessionId = event.sessionId,
                    previousEvent = event.previousEvent,
                    observationSignature = event.observationSignature,
                )

            is SessionBridgeEvent.AgentPaused ->
                DebugAgentStore.onRuntimeAgentPaused(sessionId = event.sessionId)

            is SessionBridgeEvent.AgentResumed ->
                DebugAgentStore.onRuntimeAgentResumed(
                    sessionId = event.sessionId,
                    resumeSource = event.resumeSource,
                    userCorrection = event.userCorrection,
                )

            is SessionBridgeEvent.SafetyConfirmationRequested ->
                DebugAgentStore.onRuntimeSafetyConfirmationRequested(
                    sessionId = event.sessionId,
                    confirmation = event.confirmation,
                )

            is SessionBridgeEvent.SafetyConfirmationApproved ->
                DebugAgentStore.onRuntimeSafetyConfirmationApproved(
                    sessionId = event.sessionId,
                    confirmation = event.confirmation,
                    userCorrection = event.userCorrection,
                )

            is SessionBridgeEvent.AgentStopped ->
                DebugAgentStore.onRuntimeAgentStopped(
                    sessionId = event.sessionId,
                    reason = event.reason,
                    status = event.status,
                )

            is SessionBridgeEvent.PlanningContextAcquired ->
                DebugAgentStore.onRuntimePlanningContextAcquired(
                    sessionId = event.sessionId,
                    turn = event.turn,
                    observationSignature = event.observationSignature,
                    pageState = event.pageState,
                    topTextsPreview = event.topTextsPreview,
                )

            is SessionBridgeEvent.PlannerDecisionRecorded ->
                DebugAgentStore.onRuntimePlannerDecisionRecorded(
                    sessionId = event.sessionId,
                    actionLabel = event.actionLabel,
                    reason = event.reason,
                )

            is SessionBridgeEvent.LogMessage ->
                DebugAgentStore.appendLog(event.message)

            is SessionBridgeEvent.AgentStarted ->
                DebugAgentStore.onRuntimeAgentStarted(
                    sessionId = event.sessionId,
                    task = event.task,
                    profileDisplayName = event.profileDisplayName,
                    targetPackageName = event.targetPackageName,
                    routeReason = event.routeReason,
                    entrySource = event.entrySource,
                )
        }
    }
}
