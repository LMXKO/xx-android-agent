package com.lmx.xiaoxuanagent.runtime

internal object SessionRuntimeBootstrapSupport {
    fun resolveTargetAppReturnDirective(
        trigger: TargetAppReturnTrigger,
    ): TargetAppReturnDirective? {
        val state = SessionRuntimeStore.read()
        val session = state.session
        val semantics = state.resolveSessionSemantics()
        val targetPackageName = session.targetPackageName.ifBlank { return null }
        return when (trigger) {
            TargetAppReturnTrigger.USER_ACTION -> {
                val reason =
                    when {
                        state.safety.awaitingConfirmation -> "awaiting_confirmation"
                        session.running || semantics.statusModel.category == AgentUiStatusCategory.ACTIVE_EXECUTION -> "active_execution"
                        semantics.statusModel.takeoverReason == AgentUiTakeoverReason.PAUSED -> "paused_takeover"
                        semantics.statusModel.takeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL -> "waiting_external"
                        else -> ""
                    }
                if (reason.isBlank()) null else TargetAppReturnDirective(targetPackageName, reason, moveAssistantToBack = true)
            }

            TargetAppReturnTrigger.BOOTSTRAP_AUTO -> {
                if (state.lastTransition != "bootstrap_from_resume_snapshot" || state.safety.awaitingConfirmation) {
                    return null
                }
                val reason =
                    when {
                        semantics.statusModel.takeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL -> "bootstrap_waiting_external"
                        semantics.statusModel.takeoverReason == AgentUiTakeoverReason.PAUSED -> "bootstrap_paused_takeover"
                        else -> ""
                    }
                if (reason.isBlank()) {
                    null
                } else {
                    TargetAppReturnDirective(
                        targetPackageName = targetPackageName,
                        reason = reason,
                        moveAssistantToBack = true,
                        continueBeforeLaunch =
                            semantics.statusModel.isTakeoverReason(AgentUiTakeoverReason.PAUSED) &&
                                !state.safety.awaitingConfirmation &&
                                state.takeoverSnapshot?.latestTakeoverType == "cold_start_resume",
                    )
                }
            }
        }
    }

    fun resolveTargetAppLaunchDirective(): TargetAppLaunchDirective? {
        val directive = resolveTargetAppReturnDirective(TargetAppReturnTrigger.USER_ACTION) ?: return null
        return TargetAppLaunchDirective(
            targetPackageName = directive.targetPackageName,
            reason = directive.reason,
            moveAssistantToBack = directive.moveAssistantToBack,
        )
    }

    fun resolveBootstrapAutoLaunchDirective(): TargetAppLaunchDirective? {
        val directive = resolveTargetAppReturnDirective(TargetAppReturnTrigger.BOOTSTRAP_AUTO) ?: return null
        return TargetAppLaunchDirective(
            targetPackageName = directive.targetPackageName,
            reason = directive.reason,
            moveAssistantToBack = directive.moveAssistantToBack,
        )
    }

    fun shouldAutoContinueBootstrappedSession(): Boolean {
        return resolveTargetAppReturnDirective(TargetAppReturnTrigger.BOOTSTRAP_AUTO)?.continueBeforeLaunch == true
    }

    fun bootstrapFromLatestResumeSnapshot(): Boolean {
        val snapshot = SessionResumeStore.readLatestSnapshot() ?: return false
        return bootstrapFromResumeSnapshot(snapshot)
    }

    fun bootstrapFromResumeSnapshot(
        sessionId: String,
    ): Boolean {
        val snapshot = SessionResumeStore.readSessionSnapshot(sessionId) ?: return false
        return bootstrapFromResumeSnapshot(snapshot)
    }

    fun bootstrapFromResumeSnapshot(
        snapshot: SessionResumeSnapshot,
    ): Boolean {
        if (!snapshot.resumable || snapshot.sessionId.isBlank()) {
            if (SessionResumeStore.readLatestSnapshot()?.sessionId == snapshot.sessionId) {
                SessionResumeStore.clear()
            } else {
                SessionResumeStore.clearSession(snapshot.sessionId)
            }
            return false
        }
        val bootstrapDecision = SessionRuntimePlanningSupport.resolveResumeBootstrapDecision(snapshot)
        val loadedState = SessionRuntimeStore.readSession(snapshot.sessionId)
        if (!SessionRuntimePlanningSupport.hasRestoredOrActiveRuntimeState(loadedState)) {
            SessionRuntimeStore.importSessionState(
                sessionId = snapshot.sessionId,
                state = bootstrapDecision.restoredState,
                makeActive = SessionRuntimeStore.currentContextSessionId().isBlank(),
            )
        } else if (SessionRuntimeStore.currentContextSessionId().isBlank()) {
            SessionRuntimeStore.activateSession(snapshot.sessionId)
        }
        ReplayStore.appendEvent(
            sessionId = snapshot.sessionId,
            type = "resume_snapshot_restored",
            message = bootstrapDecision.replayMessage,
            attributes = bootstrapDecision.replayAttributes,
        )
        SessionRuntime.publishRuntimeState(snapshot.sessionId)
        SessionRuntimeBridgeSupport.publish(SessionBridgeEvent.LogMessage(bootstrapDecision.logMessage))
        return true
    }
}
