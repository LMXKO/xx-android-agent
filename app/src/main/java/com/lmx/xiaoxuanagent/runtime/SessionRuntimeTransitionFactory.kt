package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord

internal object SessionRuntimeTransitionFactory {
    fun blockedSession(
        sessionId: String,
        task: String,
        entrySource: String,
        statusModel: AgentUiStatusModel,
    ): RuntimeSession =
        RuntimeSession(
            running = false,
            planning = false,
            paused = false,
            statusSnapshot = statusModel.toSnapshot(),
            sessionId = sessionId,
            entrySource = entrySource,
            task = task,
            turns = 0,
            lastObservationSignature = "",
            history = emptyList(),
            recentFingerprints = emptyList(),
        )

    fun routingSession(
        sessionId: String,
        task: String,
        entrySource: String,
    ): RuntimeSession =
        blockedSession(
            sessionId = sessionId,
            task = task,
            entrySource = entrySource,
            statusModel = AgentUiStatus.modelForBlockedReason(AgentUiBlockedReason.ROUTING),
        )

    fun executionSession(
        sessionId: String,
        task: String,
        entrySource: String,
        profileId: String,
        targetPackageName: String,
        executionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.STARTING,
        turns: Int = 0,
        history: List<AgentTurnRecord> = emptyList(),
        recentFingerprints: List<String> = emptyList(),
    ): RuntimeSession =
        RuntimeSession(
            running = true,
            planning = executionPhase == AgentUiExecutionPhase.PLANNING,
            paused = false,
            statusSnapshot = AgentUiStatus.modelForExecutionPhase(executionPhase).toSnapshot(),
            sessionId = sessionId,
            entrySource = entrySource,
            profileId = profileId,
            targetPackageName = targetPackageName,
            task = task,
            turns = turns,
            lastObservationSignature = "",
            history = history,
            recentFingerprints = recentFingerprints,
        )

    fun restoreSession(
        snapshot: SessionResumeSnapshot,
    ): RuntimeSession =
        RuntimeSession(
            running = snapshot.running,
            planning = snapshot.planning,
            paused = snapshot.paused,
            statusSnapshot = snapshot.statusSnapshot,
            sessionId = snapshot.sessionId,
            entrySource = snapshot.entrySource,
            profileId = snapshot.profileId,
            targetPackageName = snapshot.targetPackageName,
            task = snapshot.task,
            turns = snapshot.turns,
            lastObservationSignature = snapshot.lastObservationSignature,
            nextPlanEligibleAtMs = snapshot.nextPlanEligibleAtMs,
            history = snapshot.history,
            recentFingerprints = snapshot.recentFingerprints,
            externalWaitState = snapshot.externalWaitState,
            resumeContext = snapshot.resumeContext,
            planningSnapshot = snapshot.planningSnapshot,
        )
}
