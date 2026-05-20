package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskDispatchPolicy
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderState
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderStore

internal object SessionPlatformInspectorFacadeSupport {
    fun readWorkerQueue(
        limit: Int,
    ): List<SessionWorkerRecord> =
        SessionWorkerStore.readAll(limit)

    fun readWorkerTree(
        limit: Int,
    ): List<SessionWorkerTreeNode> =
        SessionWorkerStore.readTree(limit)

    fun readWorkerGraphSnapshot(
        limit: Int,
        maxConcurrentWorkers: Int,
    ): SessionWorkerGraphSnapshot =
        SessionWorkerStore.readGraphSnapshot(
            limit = limit,
            maxConcurrentWorkers = maxConcurrentWorkers,
        )

    fun readSessionGraphNodes(
        rootSessionId: String,
        limit: Int,
    ): List<SessionGraphNode> =
        SessionSessionGraphStore.readAll(limit = limit, rootSessionId = rootSessionId)

    fun readExecutionSchedulerSnapshot(): SessionExecutionSchedulerSnapshot =
        SessionExecutionCoordinatorStore.readSnapshot()

    fun readPendingCapabilityApprovals(
        limit: Int,
    ): List<PlatformCapabilityApprovalRequest> =
        PlatformCapabilityApprovalStore.readPending(limit)

    fun readProactiveTasks(
        limit: Int,
    ): List<AssistantProactiveTask> =
        AssistantProactiveTaskStore.readAll(limit)

    fun readExternalSignals(
        limit: Int,
    ): List<AssistantExternalSignal> =
        AssistantExternalSignalStore.readAll(limit)

    fun readSignalProviders(
        limit: Int,
    ): List<AssistantSignalProviderState> =
        AssistantSignalProviderStore.readAll(limit)

    fun readAgentSidechainSnapshots(
        rootSessionId: String,
        limit: Int,
    ): List<SessionAgentSidechainSnapshot> =
        SessionAgentSidechainStore.readSnapshots(limit = limit, rootSessionId = rootSessionId)

    fun readAgentSidechainEvents(
        agentId: String,
        workerId: String,
        rootSessionId: String,
        limit: Int,
    ): List<SessionAgentSidechainEvent> =
        SessionAgentSidechainStore.readRecent(
            limit = limit,
            agentId = agentId,
            workerId = workerId,
            rootSessionId = rootSessionId,
        )

    fun readRecentTraces(
        sessionId: String,
        limit: Int,
    ): List<PlatformTraceEntry> =
        PlatformTraceStore.readRecent(sessionId = sessionId, limit = limit)

    fun readTraceSnapshot(
        sessionId: String,
        limit: Int,
    ): PlatformTraceSnapshot =
        PlatformTraceStore.readSnapshot(sessionId = sessionId, limit = limit)

    fun readSwarmCoordinationSnapshot(
        activeSessionId: String,
        mailboxTarget: String,
        traceSessionId: String,
        workerGraphLimit: Int,
        maxConcurrentWorkers: Int,
        mailboxLimit: Int,
        traceLimit: Int,
    ): SessionSwarmCoordinationSnapshot {
        val now = System.currentTimeMillis()
        val workerGraph = readWorkerGraphSnapshot(limit = workerGraphLimit, maxConcurrentWorkers = maxConcurrentWorkers)
        val mailboxSnapshot =
            SessionWorkerMailboxStore.readSnapshot(
                target = mailboxTarget,
                includeConsumed = false,
                limit = mailboxLimit,
                priorityMode = workerGraph.scheduler.mailboxPriorityMode,
            )
        val proactiveQueue =
            AssistantProactiveTaskDispatchPolicy.buildSnapshot(
                tasks = AssistantProactiveTaskStore.readAll(limit = 24),
                activeSessionId = activeSessionId,
                dispatchableWorkerIds = workerGraph.scheduler.readyWorkerIds.toSet(),
                nowMs = now,
                limit = 8,
            )
        val traceSnapshot = readTraceSnapshot(sessionId = traceSessionId, limit = traceLimit)
        return SessionSwarmCoordinationPolicy.buildSnapshot(
            schedulerSnapshot = readExecutionSchedulerSnapshot(),
            workerGraph = workerGraph,
            mailboxSnapshot = mailboxSnapshot,
            proactiveQueue = proactiveQueue,
            traceSnapshot = traceSnapshot,
            activeSessionId = activeSessionId,
        )
    }

    fun compareSessions(
        leftSessionId: String,
        rightSessionId: String,
    ): SessionPlatformDiff =
        SessionPlatformInspectorOps.compareSessions(
            leftSessionId = leftSessionId,
            rightSessionId = rightSessionId,
        )

    fun runBatchDeterministicReplay(
        sessionIds: List<String>,
    ): SessionBatchReplayReport =
        SessionPlatformInspectorOps.runBatchDeterministicReplay(sessionIds)

    fun readReplayTimeline(
        sessionId: String,
        limit: Int,
    ): List<SessionReplayTimelineEntry> =
        SessionPlatformInspectorOps.readReplayTimeline(
            sessionId = sessionId,
            limit = limit,
        )

    fun runStepReplay(
        sessionId: String,
        uptoCommandCount: Int,
    ): SessionStepReplayReport =
        SessionPlatformInspectorOps.runStepReplay(
            sessionId = sessionId,
            uptoCommandCount = uptoCommandCount,
        )

    fun inspectReplayCommand(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayInspectReport =
        SessionReplayDebugger.inspectReplayCommand(sessionId = sessionId, commandIndex = commandIndex)

    fun readReplayInspectState(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayStateInspectReport =
        SessionReplayDebugger.readReplayInspectState(sessionId = sessionId, commandIndex = commandIndex)

    fun readReplayArtifactsForCommand(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayArtifactInspectReport =
        SessionReplayDebugger.readReplayArtifactsForCommand(sessionId = sessionId, commandIndex = commandIndex)

    fun compareReplaySteps(
        sessionId: String,
        leftCommandIndex: Int,
        rightCommandIndex: Int,
    ): SessionReplayStepCompareReport =
        SessionReplayDebugger.compareReplaySteps(
            sessionId = sessionId,
            leftCommandIndex = leftCommandIndex,
            rightCommandIndex = rightCommandIndex,
        )

    fun inspectReplayBreakpoint(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayBreakpointInspectReport =
        SessionReplayDebugger.inspectReplayBreakpoint(sessionId = sessionId, commandIndex = commandIndex)
}
