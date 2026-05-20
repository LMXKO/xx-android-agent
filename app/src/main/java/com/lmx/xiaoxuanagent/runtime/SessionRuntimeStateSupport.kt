package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.taskprofile.TaskRouteResolution

internal object SessionRuntimeStateSupport {
    fun consumeGrantedRiskApproval(approvalKey: String): Boolean =
        SessionRuntimeStore.mutate { current ->
            if (!current.safety.grantedApprovalMatches(approvalKey)) {
                current to false
            } else {
                val next =
                    SessionRuntimeReducer.reduce(
                        current,
                        SessionCommand.ConsumeGrantedRiskApproval(
                            reason = "consume_granted_risk_approval",
                        ),
                    )
                next to true
            }
        }

    fun updateTurnArtifacts(
        sessionId: String,
        turn: Int,
        updater: (TurnArtifactBuffer) -> TurnArtifactBuffer,
    ) = SessionRuntimeArtifactBufferStore.update(sessionId, turn, updater)

    fun consumeTurnArtifactRefs(
        sessionId: String,
        turn: Int,
    ): TurnArtifactRefs =
        SessionRuntimeArtifactBufferStore.consume(sessionId, turn)

    fun clearTurnArtifacts(
        sessionId: String,
        turn: Int,
    ) = SessionRuntimeArtifactBufferStore.clear(sessionId, turn)

    fun turnKey(
        sessionId: String,
        turn: Int,
    ): String = SessionRuntimeArtifactBufferStore.turnKey(sessionId, turn)

    fun publishRuntimeState(
        sessionId: String = SessionRuntimeStore.session().sessionId,
    ) {
        val state =
            sessionId.takeIf { it.isNotBlank() && SessionRuntimeStore.hasSession(it) }
                ?.let(SessionRuntimeStore::readSession)
                ?: SessionRuntimeStore.read()
        SessionResumeStore.persist(state)
        SessionExecutionCoordinatorStore.sync(reason = "runtime_state_published")
        val sessionSnapshot = sessionId.takeIf { it.isNotBlank() }?.let(ReplayStore::readSessionSnapshot)
        val bridgeSnapshot = buildSessionBridgeSnapshot(state, sessionSnapshot)
        val platformSnapshot =
            SessionPlatformFacade.buildSnapshot(
                state = state,
                sessionId = sessionId,
                sessionSnapshot = sessionSnapshot,
                bridgeSnapshot = bridgeSnapshot,
            )
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.RuntimeStateChanged(
                sessionId = sessionId,
                state = state,
                sessionSnapshot = sessionSnapshot,
                bridgeSnapshot = bridgeSnapshot,
                platformSnapshot = platformSnapshot,
            ),
        )
    }

    fun buildRuntimeResultSnapshot(
        lastAction: String,
        finalResult: String,
        taskResult: TaskResultPayload?,
        lastError: String,
        hint: String,
    ): RuntimeResultSnapshot =
        RuntimeResultSnapshot(
            lastAction = lastAction,
            lastResult = finalResult,
            intentType = taskResult?.intentType.orEmpty(),
            title = taskResult?.title.orEmpty(),
            summary = taskResult?.summary.orEmpty(),
            highlights = taskResult?.highlights.orEmpty(),
            lastError = lastError,
            hint = hint,
        )

    fun buildRuntimeRouteSnapshot(
        routeResolution: TaskRouteResolution,
    ): RuntimeRouteSnapshot {
        val route = routeResolution.result
        val profile = route.profile
        return RuntimeRouteSnapshot(
            activeProfileId = profile.id,
            activeTargetPackage = profile.packageName,
            reason = route.reason,
            policyTag = routeResolution.debug.policyTag,
            modelChoiceProfileId = routeResolution.debug.modelChoiceProfileId,
            selectedProfileId = routeResolution.debug.selectedProfileId.ifBlank { profile.id },
            fallbackReason = routeResolution.debug.fallbackReason,
            memoryHints = routeResolution.debug.memoryHintPreview,
            modelRaw = routeResolution.debug.modelRawResponse,
        )
    }

    fun persistMemoryOutcome(
        sessionId: String,
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
    ) {
        if (task.isBlank() || profileId.isBlank()) {
            return
        }
        BackgroundMemoryExtractor.enqueueSessionOutcome(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            status = status,
            finalMessage = finalMessage,
            taskResult = taskResult,
        )
    }
}
