package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolCatalog
import com.lmx.xiaoxuanagent.agent.AgentToolDescriptor
import com.lmx.xiaoxuanagent.agent.PlannerProviderPolicy
import com.lmx.xiaoxuanagent.assistantos.AssistantDigestPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantInterruptPolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProductShellSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.assistantos.AssistantQuietHoursSnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantRoutinePolicySnapshot
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderState
import com.lmx.xiaoxuanagent.memory.MemoryWorkspaceSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryGovernanceSnapshot
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrant
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyBehavior
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyRule
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyStore
import org.json.JSONObject

object SessionPlatformFacade {
    internal const val DEFAULT_RUNTIME_EVENT_LIMIT = 8
    const val BUNDLE_SCHEMA_VERSION = 15

    fun readCurrentSnapshot(eventLimit: Int = DEFAULT_RUNTIME_EVENT_LIMIT): SessionPlatformSnapshot =
        SessionPlatformSnapshotFacadeSupport.readCurrentSnapshot(eventLimit)

    fun readSessionSnapshot(sessionId: String, eventLimit: Int = DEFAULT_RUNTIME_EVENT_LIMIT): SessionPlatformSnapshot =
        SessionPlatformSnapshotFacadeSupport.readSessionSnapshot(sessionId, eventLimit)

    fun readCurrentPlatformSummary(): SessionPlatformSummary =
        SessionPlatformSnapshotFacadeSupport.readCurrentPlatformSummary()

    fun readSessionHistory(limit: Int = 24): SessionHistorySnapshot =
        SessionPlatformSnapshotFacadeSupport.readSessionHistory(limit)

    fun searchSessionHistory(query: String, limit: Int = 12): SessionHistorySearchResult =
        SessionPlatformSnapshotFacadeSupport.searchSessionHistory(query, limit)

    fun readResumablePlatformSnapshots(limit: Int = 8, eventLimit: Int = DEFAULT_RUNTIME_EVENT_LIMIT): List<SessionPlatformSnapshot> =
        SessionPlatformSnapshotFacadeSupport.readResumablePlatformSnapshots(limit, eventLimit)

    fun exportSessionBundle(sessionId: String, eventLimit: Int = 20): JSONObject =
        SessionPlatformSnapshotFacadeSupport.exportSessionBundle(sessionId, eventLimit)

    fun exportRemoteViewerBundle(sessionId: String, eventLimit: Int = 20): JSONObject =
        SessionPlatformSnapshotFacadeSupport.exportRemoteViewerBundle(sessionId, eventLimit)

    fun importSessionBundle(bundle: JSONObject, bootstrapImportedResume: Boolean = false): SessionPlatformSnapshot? =
        SessionPlatformSnapshotFacadeSupport.importSessionBundle(bundle, bootstrapImportedResume)

    fun runDeterministicReplay(sessionId: String): SessionReplayVerification =
        SessionPlatformSnapshotFacadeSupport.runDeterministicReplay(sessionId)

    fun readPendingApprovalTickets(limit: Int = 8): List<SessionApprovalTicket> =
        SessionPlatformSnapshotFacadeSupport.readPendingApprovalTickets(limit)

    fun searchArtifacts(query: String, sessionId: String = "", limit: Int = 12, types: Set<String> = emptySet()): List<ArtifactStore.ArtifactSearchHit> =
        SessionPlatformSnapshotFacadeSupport.searchArtifacts(query, sessionId, limit, types)

    fun garbageCollectArtifacts(): Int =
        SessionPlatformSnapshotFacadeSupport.garbageCollectArtifacts()

    fun readWorkerMailbox(target: String = "", includeConsumed: Boolean = false, limit: Int = 24): List<SessionWorkerMailboxMessage> =
        SessionPlatformMailboxFacadeSupport.readWorkerMailbox(target, includeConsumed, limit)

    fun readWorkerMailboxSnapshot(
        target: String = "",
        includeConsumed: Boolean = false,
        limit: Int = 24,
        priorityMode: String = "balanced",
    ): SessionWorkerMailboxSnapshot =
        SessionPlatformMailboxFacadeSupport.readWorkerMailboxSnapshot(
            target = target,
            includeConsumed = includeConsumed,
            limit = limit,
            priorityMode = priorityMode,
        )

    fun postWorkerMailboxMessage(
        type: SessionWorkerMailboxMessageType,
        senderSessionId: String = "",
        senderWorkerId: String = "",
        senderAgentId: String = "",
        recipientSessionId: String = "",
        recipientWorkerId: String = "",
        recipientAgentId: String = "",
        rootSessionId: String = "",
        coordinatorSessionId: String = "",
        replyToMessageId: String = "",
        title: String = "",
        summary: String = "",
        payload: Map<String, String> = emptyMap(),
        dedupeKey: String = "",
    ): SessionWorkerMailboxMessage =
        SessionPlatformMailboxFacadeSupport.postWorkerMailboxMessage(
            type = type,
            senderSessionId = senderSessionId,
            senderWorkerId = senderWorkerId,
            senderAgentId = senderAgentId,
            recipientSessionId = recipientSessionId,
            recipientWorkerId = recipientWorkerId,
            recipientAgentId = recipientAgentId,
            rootSessionId = rootSessionId,
            coordinatorSessionId = coordinatorSessionId,
            replyToMessageId = replyToMessageId,
            title = title,
            summary = summary,
            payload = payload,
            dedupeKey = dedupeKey,
        )

    fun postWorkerPermissionResponse(
        requestMessageId: String,
        decision: String,
        note: String,
        responderSessionId: String = "",
        responderWorkerId: String = "",
        responderAgentId: String = "",
    ): SessionWorkerMailboxMessage? =
        SessionPlatformMailboxFacadeSupport.postWorkerPermissionResponse(
            requestMessageId = requestMessageId,
            decision = decision,
            note = note,
            responderSessionId = responderSessionId,
            responderWorkerId = responderWorkerId,
            responderAgentId = responderAgentId,
        )

    fun acknowledgeWorkerMailboxMessage(messageId: String, note: String = ""): SessionWorkerMailboxMessage? =
        SessionPlatformMailboxFacadeSupport.acknowledgeWorkerMailboxMessage(messageId, note)

    fun processWorkerMailbox(limit: Int = 8, priorityMode: String = "balanced"): List<SessionWorkerMailboxMessage> =
        SessionPlatformMailboxFacadeSupport.processWorkerMailbox(limit, priorityMode)

    fun renewWorkerLease(workerId: String, owner: String, ttlMs: Long = 90_000L, leaseToken: String = ""): SessionWorkerRecord? =
        SessionPlatformMailboxFacadeSupport.renewWorkerLease(workerId, owner, ttlMs, leaseToken)

    fun releaseWorkerLease(
        workerId: String,
        owner: String = "",
        leaseToken: String = "",
        reason: String = "platform_release_worker_lease",
    ): SessionWorkerRecord? =
        SessionPlatformMailboxFacadeSupport.releaseWorkerLease(workerId, owner, leaseToken, reason)

    fun revokeWorkerLease(workerId: String, owner: String = "", reason: String = "platform_revoke_worker_lease"): SessionWorkerRecord? =
        SessionPlatformMailboxFacadeSupport.revokeWorkerLease(workerId, owner, reason)

    fun handoffWorkerLease(workerId: String, fromOwner: String, toOwner: String, ttlMs: Long = 90_000L): SessionWorkerRecord? =
        SessionPlatformMailboxFacadeSupport.handoffWorkerLease(workerId, fromOwner, toOwner, ttlMs)

    fun readBackgroundMemoryQueue(includeCompleted: Boolean = false, limit: Int = 24): List<BackgroundMemoryQueueTask> =
        SessionPlatformOperatorFacadeSupport.readBackgroundMemoryQueue(includeCompleted, limit)

    fun retryBackgroundMemoryTask(taskId: String): BackgroundMemoryQueueTask? =
        SessionPlatformOperatorFacadeSupport.retryBackgroundMemoryTask(taskId)

    fun readProductShellSnapshot(): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.readProductShellSnapshot()

    fun readProductDiagnosticsSnapshot(): SessionPlatformProductDiagnosticsSnapshot =
        SessionPlatformOperatorFacadeSupport.readProductDiagnosticsSnapshot()

    fun refreshProductShellSnapshot(reason: String = "platform_refresh_product_shell"): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.refreshProductShellSnapshot(reason)

    fun acknowledgeProductShellTip(tipId: String, action: String, note: String = ""): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.acknowledgeProductShellTip(tipId, action, note)

    fun updateProductOnboardingStep(stepId: String, action: String, note: String = ""): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.updateProductOnboardingStep(stepId, action, note)

    fun readMemoryWorkspaceSnapshot(): MemoryWorkspaceSnapshot =
        SessionPlatformOperatorFacadeSupport.readMemoryWorkspaceSnapshot()

    fun readMemoryGovernanceSnapshot(limit: Int = 24, auditLimit: Int = 12): PersonalMemoryGovernanceSnapshot =
        SessionPlatformOperatorFacadeSupport.readMemoryGovernanceSnapshot(limit, auditLimit)

    fun upsertMemoryGovernanceEntry(
        typeWire: String,
        primary: String,
        secondary: String = "",
        note: String = "",
        profileId: String = "",
    ): PersonalMemoryGovernanceSnapshot =
        SessionPlatformOperatorFacadeSupport.upsertMemoryGovernanceEntry(typeWire, primary, secondary, note, profileId)

    fun deleteMemoryGovernanceEntry(typeWire: String, entryRef: String): PersonalMemoryGovernanceSnapshot =
        SessionPlatformOperatorFacadeSupport.deleteMemoryGovernanceEntry(typeWire, entryRef)

    fun readArtifactRetentionPolicy(): ArtifactRetentionPolicy =
        SessionPlatformOperatorFacadeSupport.readArtifactRetentionPolicy()

    fun updateArtifactRetentionPolicy(policy: ArtifactRetentionPolicy): ArtifactRetentionPolicy =
        SessionPlatformOperatorFacadeSupport.updateArtifactRetentionPolicy(policy)

    fun readPlannerProviderPolicy(): PlannerProviderPolicy =
        SessionPlatformOperatorFacadeSupport.readPlannerProviderPolicy()

    fun updatePlannerProviderPolicy(policy: PlannerProviderPolicy): PlannerProviderPolicy =
        SessionPlatformOperatorFacadeSupport.updatePlannerProviderPolicy(policy)

    fun updateRoutinePolicy(
        reducer: (AssistantRoutinePolicySnapshot) -> AssistantRoutinePolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.updateRoutinePolicy(reducer)

    fun updateDigestPolicy(
        reducer: (AssistantDigestPolicySnapshot) -> AssistantDigestPolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.updateDigestPolicy(reducer)

    fun updateQuietHours(
        reducer: (AssistantQuietHoursSnapshot) -> AssistantQuietHoursSnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.updateQuietHours(reducer)

    fun updateInterruptPolicy(
        reducer: (AssistantInterruptPolicySnapshot) -> AssistantInterruptPolicySnapshot,
    ): AssistantProductShellSnapshot =
        SessionPlatformOperatorFacadeSupport.updateInterruptPolicy(reducer)

    fun previewArtifactRetention(policy: ArtifactRetentionPolicy = ArtifactRetentionPolicyStore.read()): ArtifactRetentionReport =
        SessionPlatformOperatorFacadeSupport.previewArtifactRetention(policy)

    fun readArtifactLifecycleSnapshot(sessionId: String, beforeTurnInclusive: Int = Int.MAX_VALUE): ArtifactLifecycleSnapshot =
        SessionPlatformOperatorFacadeSupport.readArtifactLifecycleSnapshot(sessionId, beforeTurnInclusive)

    fun runArtifactRetentionSweep(policy: ArtifactRetentionPolicy = ArtifactRetentionPolicyStore.read()): ArtifactRetentionReport =
        SessionPlatformOperatorFacadeSupport.runArtifactRetentionSweep(policy)

    fun approvePendingSafetyForSession(sessionId: String, userCorrection: String = ""): Boolean =
        SessionPlatformOperatorFacadeSupport.approvePendingSafetyForSession(sessionId, userCorrection)

    fun rejectPendingSafetyForSession(sessionId: String): Boolean =
        SessionPlatformOperatorFacadeSupport.rejectPendingSafetyForSession(sessionId)

    fun readRuntimeSafetyGrants(
        limit: Int = 12,
        includeInactive: Boolean = false,
        sessionId: String = "",
        targetPackageName: String = "",
        actionFamily: String = "",
    ): List<RuntimeSafetyGrant> =
        SessionPlatformOperatorFacadeSupport.readRuntimeSafetyGrants(
            limit = limit,
            includeInactive = includeInactive,
            sessionId = sessionId,
            targetPackageName = targetPackageName,
            actionFamily = actionFamily,
        )

    fun revokeRuntimeSafetyGrant(
        grantId: String,
        note: String = "",
    ): RuntimeSafetyGrant? =
        SessionPlatformOperatorFacadeSupport.revokeRuntimeSafetyGrant(grantId, note)

    fun readRuntimeSafetyPolicies(
        limit: Int = 16,
        actionFamily: String = "",
        targetPackageName: String = "",
        toolName: String = "",
        pageState: String = "",
        behavior: RuntimeSafetyPolicyBehavior? = null,
    ): List<RuntimeSafetyPolicyRule> =
        RuntimeSafetyPolicyStore.readRules(
            limit = limit,
            actionFamily = actionFamily,
            targetPackageName = targetPackageName,
            toolName = toolName,
            pageState = pageState,
            behavior = behavior,
        )

    fun upsertRuntimeSafetyPolicy(
        behavior: RuntimeSafetyPolicyBehavior,
        actionFamily: String,
        targetPackageName: String = "",
        toolName: String = "",
        pageState: String = "",
        targetTextContains: String = "",
        note: String = "",
        sourceTag: String = "",
        surfaceHint: String = "",
        explanation: String = "",
    ): RuntimeSafetyPolicyRule? =
        RuntimeSafetyPolicyStore.upsertRule(
            behavior = behavior,
            actionFamily = actionFamily,
            targetPackageName = targetPackageName,
            toolName = toolName,
            pageState = pageState,
            targetTextContains = targetTextContains,
            note = note,
            sourceTag = sourceTag,
            surfaceHint = surfaceHint,
            explanation = explanation,
        )

    fun deleteRuntimeSafetyPolicy(
        ruleId: String,
    ): RuntimeSafetyPolicyRule? =
        RuntimeSafetyPolicyStore.deleteRule(ruleId)

    fun readSessionExplanationLog(
        sessionId: String,
        limit: Int = 8,
    ): List<SessionExplanationEntry> =
        SessionExplanationStore.readRecent(sessionId = sessionId, limit = limit)

    fun readSessionMemoryNotebook(
        sessionId: String,
    ): SessionMemoryNotebookSnapshot? =
        SessionMemoryNotebookStore.readSnapshot(sessionId)

    fun readSessionActionLifecycle(
        sessionId: String,
        limit: Int = 8,
    ): SessionActionLifecycleSnapshot =
        SessionActionLifecycleStore.readSnapshot(sessionId = sessionId, limit = limit)

    fun readSessionMainLoop(
        sessionId: String,
    ): SessionMainLoopSnapshot? =
        SessionMainLoopStore.readSnapshot(sessionId)

    fun readSessionLoopRuntime(
        sessionId: String,
    ): SessionLoopRuntimeSnapshot? =
        SessionLoopRuntimeStore.readSnapshot(sessionId)

    fun readSessionLoopInbox(
        sessionId: String,
    ): SessionLoopInboxSnapshot? =
        SessionLoopInboxStore.readSnapshot(sessionId)

    fun readSessionMemoryPolicy(
        sessionId: String,
    ): SessionMemoryPolicySnapshot? =
        SessionMemoryPolicyStore.readSnapshot(sessionId)

    fun readSessionMemoryMaintenance(): SessionMemoryMaintenanceSnapshot =
        SessionMemoryMaintenanceStore.read()

    fun readSessionTurnLoop(
        sessionId: String,
    ): SessionTurnLoopSnapshot? =
        SessionTurnLoopStore.readSnapshot(sessionId)

    fun readSessionToolLedger(
        sessionId: String,
        limit: Int = 8,
    ): List<SessionToolUseEntry> =
        SessionToolUseLedgerStore.readRecent(sessionId = sessionId, limit = limit)

    fun readSessionToolRuntime(
        sessionId: String,
        limit: Int = 12,
    ): SessionToolRuntimeSnapshot =
        SessionToolRuntimeStore.readSnapshot(sessionId = sessionId, limit = limit)

    fun readSessionSafetyDecisions(
        sessionId: String = "",
        limit: Int = 12,
    ): List<SessionSafetyDecisionEntry> =
        SessionSafetyDecisionStore.readRecent(sessionId = sessionId, limit = limit)

    fun readToolCatalog(): List<AgentToolDescriptor> = AgentToolCatalog.descriptors()

    fun readFollowUpHealth(limit: Int = 24): com.lmx.xiaoxuanagent.assistantos.AssistantFollowUpHealthSnapshot =
        com.lmx.xiaoxuanagent.assistantos.AssistantFollowUpHealthStore.derive(
            tasks = readProactiveTasks(limit = limit),
        )

    fun readTaskOs(limit: Int = 24): com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsSnapshot =
        com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsStore.derive(
            tasks = readProactiveTasks(limit = limit),
            activeSessionId = SessionRuntime.State.runtimeState().session.sessionId,
        )

    fun readSessionMemoryCurator(
        sessionId: String,
    ): SessionMemoryCuratorSnapshot? = SessionMemoryCuratorStore.readSnapshot(sessionId)

    fun readSessionMemoryFork(
        sessionId: String,
    ): SessionMemoryForkRuntimeSnapshot? = SessionMemoryForkRuntimeStore.readSnapshot(sessionId)

    fun readPermissionCenter(sessionId: String = "", limit: Int = 12): SessionPermissionCenterSnapshot =
        SessionPermissionCenterStore.readSnapshot(sessionId = sessionId, limit = limit)

    fun readPermissionProduct(
        sessionId: String = "",
        limit: Int = 12,
        behavior: String = "",
        query: String = "",
    ): SessionPermissionProductSnapshot =
        SessionPermissionProductStore.refresh(
            sessionId = sessionId,
            limit = limit,
            behavior = behavior,
            query = query,
        )

    fun readGroundingHealth(sessionId: String, limit: Int = 12): SessionGroundingHealthSnapshot =
        SessionGroundingHealthStore.readSnapshot(sessionId = sessionId, limit = limit)

    fun readToolContracts(limit: Int = 12): SessionToolContractSnapshot =
        SessionToolContractStore.readSnapshot(limit = limit)

    fun listCapabilities(): List<SessionCapabilityDescriptor> =
        SessionPlatformOperatorFacadeSupport.listCapabilities()

    fun readRemoteBridgeSnapshot(requestLimit: Int = 8, eventLimit: Int = 8): RemoteBridgeSnapshot =
        SessionPlatformOperatorFacadeSupport.readRemoteBridgeSnapshot(requestLimit, eventLimit)

    fun readRemoteTransportSnapshot(inboundLimit: Int = 8, outboundLimit: Int = 8): RemoteTransportSnapshot =
        SessionPlatformOperatorFacadeSupport.readRemoteTransportSnapshot(inboundLimit, outboundLimit)

    fun enqueueRemoteCapabilityRequest(
        capability: SessionCapabilityKey,
        sessionId: String = "",
        task: String = "",
        query: String = "",
        userCorrection: String = "",
        payload: Map<String, String> = emptyMap(),
        summary: String = "",
    ): RemoteBridgeRequest =
        SessionPlatformOperatorFacadeSupport.enqueueRemoteCapabilityRequest(
            capability = capability,
            sessionId = sessionId,
            task = task,
            query = query,
            userCorrection = userCorrection,
            payload = payload,
            summary = summary,
        )

    fun readWorkerQueue(limit: Int = 12): List<SessionWorkerRecord> =
        SessionPlatformInspectorFacadeSupport.readWorkerQueue(limit)

    fun readWorkerTree(limit: Int = 12): List<SessionWorkerTreeNode> =
        SessionPlatformInspectorFacadeSupport.readWorkerTree(limit)

    fun readWorkerGraphSnapshot(limit: Int = 12, maxConcurrentWorkers: Int = 3): SessionWorkerGraphSnapshot =
        SessionPlatformInspectorFacadeSupport.readWorkerGraphSnapshot(limit, maxConcurrentWorkers)

    fun readSessionGraphNodes(rootSessionId: String = "", limit: Int = 16): List<SessionGraphNode> =
        SessionPlatformInspectorFacadeSupport.readSessionGraphNodes(rootSessionId, limit)

    fun readExecutionSchedulerSnapshot(): SessionExecutionSchedulerSnapshot =
        SessionPlatformInspectorFacadeSupport.readExecutionSchedulerSnapshot()

    fun readPendingCapabilityApprovals(limit: Int = 16): List<PlatformCapabilityApprovalRequest> =
        SessionPlatformInspectorFacadeSupport.readPendingCapabilityApprovals(limit)

    fun readProactiveTasks(limit: Int = 12): List<AssistantProactiveTask> =
        SessionPlatformInspectorFacadeSupport.readProactiveTasks(limit)

    fun readExternalSignals(limit: Int = 12): List<AssistantExternalSignal> =
        SessionPlatformInspectorFacadeSupport.readExternalSignals(limit)

    fun readSignalProviders(limit: Int = 12): List<AssistantSignalProviderState> =
        SessionPlatformInspectorFacadeSupport.readSignalProviders(limit)

    fun readAgentSidechainSnapshots(rootSessionId: String = "", limit: Int = 12): List<SessionAgentSidechainSnapshot> =
        SessionPlatformInspectorFacadeSupport.readAgentSidechainSnapshots(rootSessionId, limit)

    fun readAgentSidechainEvents(
        agentId: String = "",
        workerId: String = "",
        rootSessionId: String = "",
        limit: Int = 24,
    ): List<SessionAgentSidechainEvent> =
        SessionPlatformInspectorFacadeSupport.readAgentSidechainEvents(agentId, workerId, rootSessionId, limit)

    fun readRecentTraces(sessionId: String = "", limit: Int = 16): List<PlatformTraceEntry> =
        SessionPlatformInspectorFacadeSupport.readRecentTraces(sessionId, limit)

    fun readTraceSnapshot(sessionId: String = "", limit: Int = 16): PlatformTraceSnapshot =
        SessionPlatformInspectorFacadeSupport.readTraceSnapshot(sessionId, limit)

    fun readSwarmCoordinationSnapshot(
        activeSessionId: String = "",
        mailboxTarget: String = "",
        traceSessionId: String = "",
        workerGraphLimit: Int = 12,
        maxConcurrentWorkers: Int = 3,
        mailboxLimit: Int = 24,
        traceLimit: Int = 16,
    ): SessionSwarmCoordinationSnapshot =
        SessionPlatformInspectorFacadeSupport.readSwarmCoordinationSnapshot(
            activeSessionId = activeSessionId,
            mailboxTarget = mailboxTarget,
            traceSessionId = traceSessionId,
            workerGraphLimit = workerGraphLimit,
            maxConcurrentWorkers = maxConcurrentWorkers,
            mailboxLimit = mailboxLimit,
            traceLimit = traceLimit,
        )

    fun compareSessions(leftSessionId: String, rightSessionId: String): SessionPlatformDiff =
        SessionPlatformInspectorFacadeSupport.compareSessions(leftSessionId, rightSessionId)

    fun runBatchDeterministicReplay(sessionIds: List<String>): SessionBatchReplayReport =
        SessionPlatformInspectorFacadeSupport.runBatchDeterministicReplay(sessionIds)

    fun readReplayTimeline(sessionId: String, limit: Int = 24): List<SessionReplayTimelineEntry> =
        SessionPlatformInspectorFacadeSupport.readReplayTimeline(sessionId, limit)

    fun runStepReplay(sessionId: String, uptoCommandCount: Int): SessionStepReplayReport =
        SessionPlatformInspectorFacadeSupport.runStepReplay(sessionId, uptoCommandCount)

    fun inspectReplayCommand(sessionId: String, commandIndex: Int): SessionReplayInspectReport =
        SessionPlatformInspectorFacadeSupport.inspectReplayCommand(sessionId, commandIndex)

    fun readReplayInspectState(sessionId: String, commandIndex: Int): SessionReplayStateInspectReport =
        SessionPlatformInspectorFacadeSupport.readReplayInspectState(sessionId, commandIndex)

    fun readReplayArtifactsForCommand(sessionId: String, commandIndex: Int): SessionReplayArtifactInspectReport =
        SessionPlatformInspectorFacadeSupport.readReplayArtifactsForCommand(sessionId, commandIndex)

    fun compareReplaySteps(sessionId: String, leftCommandIndex: Int, rightCommandIndex: Int): SessionReplayStepCompareReport =
        SessionPlatformInspectorFacadeSupport.compareReplaySteps(sessionId, leftCommandIndex, rightCommandIndex)

    fun inspectReplayBreakpoint(sessionId: String, commandIndex: Int): SessionReplayBreakpointInspectReport =
        SessionPlatformInspectorFacadeSupport.inspectReplayBreakpoint(sessionId, commandIndex)

    fun buildSnapshot(
        state: SessionRuntimeState,
        sessionId: String = state.session.sessionId,
        sessionSnapshot: ReplaySessionSnapshot? = null,
        resumeSnapshot: SessionResumeSnapshot? = null,
        bridgeSnapshot: SessionBridgeSnapshot? = null,
        eventLimit: Int = DEFAULT_RUNTIME_EVENT_LIMIT,
    ): SessionPlatformSnapshot =
        SessionPlatformSnapshotFacadeSupport.buildSnapshot(
            state = state,
            sessionId = sessionId,
            sessionSnapshot = sessionSnapshot,
            resumeSnapshot = resumeSnapshot,
            bridgeSnapshot = bridgeSnapshot,
            eventLimit = eventLimit,
        )
}
