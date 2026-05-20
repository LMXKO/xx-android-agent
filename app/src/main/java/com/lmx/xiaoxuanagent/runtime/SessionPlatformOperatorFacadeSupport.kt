package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicy
import com.lmx.xiaoxuanagent.assistantos.AssistantAnalyticsStore
import com.lmx.xiaoxuanagent.assistantos.AssistantDigestPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantInterruptPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantQuietHoursSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantRoutinePolicySnapshot
import com.lmx.xiaoxuanagent.memory.MemoryWorkspaceGovernance
import com.lmx.xiaoxuanagent.memory.MemoryWorkspaceSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryGovernanceSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrant
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore

internal object SessionPlatformOperatorFacadeSupport {
    fun readBackgroundMemoryQueue(
        includeCompleted: Boolean,
        limit: Int,
    ): List<BackgroundMemoryQueueTask> =
        SessionPlatformOperatorOps.readBackgroundMemoryQueue(
            includeCompleted = includeCompleted,
            limit = limit,
        )

    fun retryBackgroundMemoryTask(
        taskId: String,
    ): BackgroundMemoryQueueTask? =
        SessionPlatformOperatorOps.retryBackgroundMemoryTask(taskId)

    fun readProductShellSnapshot(): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.readProductShellSnapshot()

    fun readProductDiagnosticsSnapshot(): SessionPlatformProductDiagnosticsSnapshot =
        SessionPlatformProductDiagnosticsSnapshot(
            productShell = readProductShellSnapshot(),
            analytics = AssistantAnalyticsStore.read().toSummary(),
        )

    fun refreshProductShellSnapshot(
        reason: String,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.refreshProductShellSnapshot(reason = reason)

    fun acknowledgeProductShellTip(
        tipId: String,
        action: String,
        note: String,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.acknowledgeProductShellTip(
            tipId = tipId,
            action = action,
            note = note,
        )

    fun updateProductOnboardingStep(
        stepId: String,
        action: String,
        note: String,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.updateProductOnboardingStep(
            stepId = stepId,
            action = action,
            note = note,
        )

    fun readMemoryWorkspaceSnapshot(): MemoryWorkspaceSnapshot =
        MemoryWorkspaceGovernance.scanWorkspace()

    fun readMemoryGovernanceSnapshot(
        limit: Int,
        auditLimit: Int,
    ): PersonalMemoryGovernanceSnapshot =
        PersonalMemoryStore.readGovernanceSnapshot(limit = limit, auditLimit = auditLimit)

    fun upsertMemoryGovernanceEntry(
        typeWire: String,
        primary: String,
        secondary: String,
        note: String,
        profileId: String,
    ): PersonalMemoryGovernanceSnapshot =
        PersonalMemoryStore.upsertGovernedEntry(
            typeWire = typeWire,
            primary = primary,
            secondary = secondary,
            note = note,
            profileId = profileId,
        )

    fun deleteMemoryGovernanceEntry(
        typeWire: String,
        entryRef: String,
    ): PersonalMemoryGovernanceSnapshot =
        PersonalMemoryStore.deleteGovernedEntry(
            typeWire = typeWire,
            entryRef = entryRef,
        )

    fun readArtifactRetentionPolicy(): ArtifactRetentionPolicy =
        SessionPlatformOperatorOps.readArtifactRetentionPolicy()

    fun updateArtifactRetentionPolicy(
        policy: ArtifactRetentionPolicy,
    ): ArtifactRetentionPolicy =
        SessionPlatformOperatorOps.updateArtifactRetentionPolicy(policy)

    fun readPlannerProviderPolicy(): PlannerProviderPolicy =
        SessionPlatformOperatorOps.readPlannerProviderPolicy()

    fun updatePlannerProviderPolicy(
        policy: PlannerProviderPolicy,
    ): PlannerProviderPolicy =
        SessionPlatformOperatorOps.updatePlannerProviderPolicy(policy)

    fun updateRoutinePolicy(
        reducer: (AssistantRoutinePolicySnapshot) -> AssistantRoutinePolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.updateRoutinePolicy(reducer)

    fun updateDigestPolicy(
        reducer: (AssistantDigestPolicySnapshot) -> AssistantDigestPolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.updateDigestPolicy(reducer)

    fun updateQuietHours(
        reducer: (AssistantQuietHoursSnapshot) -> AssistantQuietHoursSnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.updateQuietHours(reducer)

    fun updateInterruptPolicy(
        reducer: (AssistantInterruptPolicySnapshot) -> AssistantInterruptPolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorOps.updateInterruptPolicy(reducer)

    fun previewArtifactRetention(
        policy: ArtifactRetentionPolicy,
    ): ArtifactRetentionReport =
        SessionPlatformOperatorOps.previewArtifactRetention(policy = policy)

    fun readArtifactLifecycleSnapshot(
        sessionId: String,
        beforeTurnInclusive: Int,
    ): ArtifactLifecycleSnapshot =
        ArtifactStore.readLifecycleSnapshot(
            sessionId = sessionId,
            beforeTurnInclusive = beforeTurnInclusive,
        )

    fun runArtifactRetentionSweep(
        policy: ArtifactRetentionPolicy,
    ): ArtifactRetentionReport =
        SessionPlatformOperatorOps.runArtifactRetentionSweep(policy = policy)

    fun approvePendingSafetyForSession(
        sessionId: String,
        userCorrection: String,
    ): Boolean {
        if (!SessionRuntime.bootstrapFromResumeSnapshot(sessionId)) return false
        return SessionRuntime.Lifecycle.approvePendingSafetyConfirmation(userCorrection) != null
    }

    fun rejectPendingSafetyForSession(
        sessionId: String,
    ): Boolean {
        if (!SessionRuntime.bootstrapFromResumeSnapshot(sessionId)) return false
        SessionRuntime.rejectPendingSafetyConfirmation()
        return true
    }

    fun readRuntimeSafetyGrants(
        limit: Int,
        includeInactive: Boolean,
        sessionId: String,
        targetPackageName: String,
        actionFamily: String,
    ): List<RuntimeSafetyGrant> =
        RuntimeSafetyGrantStore.readGrants(
            limit = limit,
            includeInactive = includeInactive,
            sessionId = sessionId,
            targetPackageName = targetPackageName,
            actionFamily = actionFamily,
        )

    fun revokeRuntimeSafetyGrant(
        grantId: String,
        note: String,
    ): RuntimeSafetyGrant? =
        RuntimeSafetyGrantStore.revokeGrant(
            grantId = grantId,
            note = note,
        )?.also { grant ->
            SessionSafetyDecisionStore.record(
                sessionId = grant.sessionId,
                turn = 0,
                actionLabel = grant.actionFamily.ifBlank { grant.grantId },
                actionFamily = grant.actionFamily,
                targetPackageName = grant.targetPackageName,
                outcome = "grant_revoked",
                summary = "运行时安全授权已撤销 ${grant.scope}",
                detailLines = listOfNotNull(note.takeIf { it.isNotBlank() }),
            )
        }

    fun listCapabilities(): List<SessionCapabilityDescriptor> =
        SessionCapabilityBus.listCapabilities()

    fun readRemoteBridgeSnapshot(
        requestLimit: Int,
        eventLimit: Int,
    ): RemoteBridgeSnapshot =
        RemoteBridgeStore.readSnapshot(requestLimit = requestLimit, eventLimit = eventLimit)

    fun readRemoteTransportSnapshot(
        inboundLimit: Int,
        outboundLimit: Int,
    ): RemoteTransportSnapshot =
        RemoteTransportStore.readSnapshot(inboundLimit = inboundLimit, outboundLimit = outboundLimit)

    fun enqueueRemoteCapabilityRequest(
        capability: SessionCapabilityKey,
        sessionId: String,
        task: String,
        query: String,
        userCorrection: String,
        payload: Map<String, String>,
        summary: String,
    ): RemoteBridgeRequest =
        RemoteBridgeStore.enqueueRequest(
            capability = capability,
            sessionId = sessionId,
            task = task,
            query = query,
            userCorrection = userCorrection,
            payload = payload,
            summary = summary,
        )
}
