package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

internal object SessionPlatformSnapshotOps {
    fun buildSnapshot(
        state: SessionRuntimeState,
        sessionId: String = state.session.sessionId,
        sessionSnapshot: ReplaySessionSnapshot? = null,
        resumeSnapshot: SessionResumeSnapshot? = null,
        bridgeSnapshot: SessionBridgeSnapshot? = null,
        eventLimit: Int = SessionPlatformFacade.DEFAULT_RUNTIME_EVENT_LIMIT,
    ): SessionPlatformSnapshot {
        val effectiveSessionId = sessionId.ifBlank { state.session.sessionId }
        val effectiveReplay =
            sessionSnapshot ?: effectiveSessionId.takeIf { it.isNotBlank() }?.let(ReplayStore::readSessionSnapshot)
        val effectiveResume =
            resumeSnapshot
                ?: effectiveSessionId.takeIf { it.isNotBlank() }?.let(SessionResumeStore::readSessionSnapshot)
                ?: SessionResumeStore.readLatestSnapshot()?.takeIf { snapshot ->
                    effectiveSessionId.isBlank() || snapshot.sessionId == effectiveSessionId
                }
        val effectiveBridge = bridgeSnapshot ?: buildSessionBridgeSnapshot(state, effectiveReplay)
        val bridgeEvents =
            effectiveSessionId.takeIf { it.isNotBlank() }?.let { SessionBridgeProtocolStore.readRecent(it, eventLimit) }
                ?: SessionBridgeProtocolStore.readRecent(limit = eventLimit)
        val runtimeEvents =
            effectiveSessionId.takeIf { it.isNotBlank() }?.let { RuntimeEventStore.readRecent(it, eventLimit) }
                ?: RuntimeEventStore.readGlobalRecent(eventLimit)
        val remoteSnapshot = RemoteBridgeStore.readSnapshot(requestLimit = eventLimit, eventLimit = eventLimit)
        val workerQueue = SessionWorkerStore.readAll(eventLimit)
        val mailboxQueue = SessionWorkerMailboxStore.readPending(limit = eventLimit * 2)
        val memoryQueue = BackgroundMemoryExtractor.readQueue(includeCompleted = false, limit = eventLimit * 2)
        val proactiveTasks = AssistantProactiveTaskStore.readAll(eventLimit)
        val pendingSafetySummary =
            state.safety.pendingConfirmation?.summary
                ?: effectiveResume?.safety?.pendingConfirmation?.summary
                ?: effectiveReplay?.pendingSafetyConfirmation.orEmpty()
        return SessionPlatformSnapshot(
            sessionId = effectiveSessionId,
            state = state,
            sessionSnapshot = effectiveReplay,
            resumeSnapshot = effectiveResume,
            bridgeSnapshot = effectiveBridge,
            metricsSnapshot = RuntimeMetricsStore.read(),
            healthSummary =
                deriveHealthSummary(
                    state = state,
                    resumeSnapshot = effectiveResume,
                    bridgeEvents = bridgeEvents,
                    runtimeEvents = runtimeEvents,
                    remotePendingCount = remoteSnapshot.pendingRequests.size,
                    workerQueueCount =
                        workerQueue.count { worker ->
                            worker.status == SessionWorkerStatus.QUEUED ||
                                worker.status == SessionWorkerStatus.RUNNING ||
                                worker.status == SessionWorkerStatus.DEFERRED ||
                                worker.status == SessionWorkerStatus.WAITING_CHILDREN ||
                                worker.status == SessionWorkerStatus.WAITING_APPROVAL
                        },
                    mailboxPendingCount = mailboxQueue.size,
                    memoryQueuePendingCount = memoryQueue.count {
                        it.status == BackgroundMemoryTaskStatus.PENDING ||
                            it.status == BackgroundMemoryTaskStatus.DEFERRED ||
                            it.status == BackgroundMemoryTaskStatus.RUNNING
                    },
                    proactiveTaskCount = proactiveTasks.count { it.enabled },
                    capabilityApprovalCount = PlatformCapabilityApprovalStore.readPending(limit = 32).size,
                    pendingSafetySummary = pendingSafetySummary,
                ),
            recentBridgeEvents = bridgeEvents,
            recentRuntimeEvents = runtimeEvents,
            recentArtifacts = effectiveReplay?.recentArtifactGroups?.flatMap { it.artifacts }.orEmpty(),
            pendingSafetySummary = pendingSafetySummary,
        )
    }

    fun buildSummary(
        snapshot: SessionPlatformSnapshot,
    ): SessionPlatformSummary =
        SessionPlatformSummary(
            sessionId = snapshot.sessionId,
            statusCode = snapshot.bridgeSnapshot.statusCode,
            resumable = snapshot.bridgeSnapshot.resumable,
            replayTurnCount = snapshot.sessionSnapshot?.turnCount ?: 0,
            replayEventCount = snapshot.sessionSnapshot?.recentEvents?.size ?: 0,
            artifactCount = snapshot.recentArtifacts.size,
            runtimeEventCount = snapshot.recentRuntimeEvents.size,
            hasResumeSnapshot = snapshot.resumeSnapshot != null,
            pendingSafety = snapshot.pendingSafetySummary.isNotBlank(),
            lastTransition = snapshot.state.lastTransition,
            latestResultSummary =
                snapshot.bridgeSnapshot.resultSummary.ifBlank {
                    snapshot.bridgeSnapshot.errorSummary.ifBlank {
                        snapshot.sessionSnapshot?.finalMessage.orEmpty()
                    }
                },
        )

    fun deriveHealthSummary(
        state: SessionRuntimeState,
        resumeSnapshot: SessionResumeSnapshot?,
        bridgeEvents: List<SessionBridgeProtocolEntry>,
        runtimeEvents: List<RuntimeEventEntry>,
        remotePendingCount: Int,
        workerQueueCount: Int,
        mailboxPendingCount: Int,
        memoryQueuePendingCount: Int,
        proactiveTaskCount: Int,
        capabilityApprovalCount: Int,
        pendingSafetySummary: String,
    ): SessionPlatformHealthSummary {
        val staleRuntime =
            state.session.sessionId.isNotBlank() &&
                state.updatedAtMs > 0L &&
                System.currentTimeMillis() - state.updatedAtMs >= 20 * 60 * 1000L &&
                state.resolveSessionSemantics().let { semantics ->
                    semantics.statusModel.category == AgentUiStatusCategory.ACTIVE_EXECUTION ||
                        semantics.statusModel.category == AgentUiStatusCategory.TAKEOVER
                }
        val deterministicReplayReady =
            runtimeEvents.any { it.commandPayload.isNotBlank() }
        val status =
            when {
                pendingSafetySummary.isNotBlank() -> "needs_approval"
                capabilityApprovalCount > 0 -> "needs_capability_review"
                staleRuntime -> "stale"
                resumeSnapshot?.resumable == true -> "resumable"
                remotePendingCount > 0 ||
                    workerQueueCount > 0 ||
                    mailboxPendingCount > 0 ||
                    memoryQueuePendingCount > 0 ||
                    proactiveTaskCount > 0 -> "queued"
                state.session.sessionId.isNotBlank() -> "active"
                else -> "idle"
            }
        val summary =
            when {
                pendingSafetySummary.isNotBlank() -> pendingSafetySummary
                capabilityApprovalCount > 0 -> "capability approvals pending=$capabilityApprovalCount"
                staleRuntime -> "runtime 超过 20 分钟未刷新"
                resumeSnapshot?.resumable == true -> "存在可恢复 session"
                remotePendingCount > 0 ||
                    workerQueueCount > 0 ||
                    mailboxPendingCount > 0 ||
                    memoryQueuePendingCount > 0 ||
                    proactiveTaskCount > 0 ->
                    "remote=${remotePendingCount} worker=${workerQueueCount} mailbox=${mailboxPendingCount} memory=${memoryQueuePendingCount} proactive=${proactiveTaskCount}"
                state.session.sessionId.isNotBlank() -> "session platform 运行中"
                else -> "platform idle"
            }
        return SessionPlatformHealthSummary(
            status = status,
            summary = summary,
            deterministicReplayReady = deterministicReplayReady,
            resumableSnapshotPresent = resumeSnapshot != null,
            bridgeFeedPresent = bridgeEvents.isNotEmpty(),
            runtimeLedgerPresent = runtimeEvents.isNotEmpty(),
            pendingApprovalCount = (if (pendingSafetySummary.isNotBlank()) 1 else 0) + capabilityApprovalCount,
            remotePendingCount = remotePendingCount,
            workerQueueCount = workerQueueCount,
            mailboxPendingCount = mailboxPendingCount,
            memoryQueuePendingCount = memoryQueuePendingCount,
            proactiveTaskCount = proactiveTaskCount,
            staleRuntime = staleRuntime,
        )
    }

    fun fallbackRuntimeState(
        sessionId: String,
        replaySnapshot: ReplaySessionSnapshot?,
        resumeSnapshot: SessionResumeSnapshot?,
    ): SessionRuntimeState {
        if (resumeSnapshot != null) {
            return SessionRuntimeState(
                session =
                    SessionRuntimeTransitionFactory.restoreSession(resumeSnapshot),
                safety = resumeSnapshot.safety,
                routeSnapshot = resumeSnapshot.routeSnapshot,
                resultSnapshot = resumeSnapshot.resultSnapshot,
                takeoverSnapshot = resumeSnapshot.takeoverSnapshot,
                lastTransition = resumeSnapshot.lastTransition,
                updatedAtMs = resumeSnapshot.updatedAtMs,
            )
        }
        val profileId = replaySnapshot?.profileId ?: TaskRegistry.defaultProfile.id
        val targetPackageName = replaySnapshot?.targetPackageName ?: TaskRegistry.defaultProfile.packageName
        return SessionRuntimeState(
            session =
                RuntimeSession(
                    statusSnapshot = replaySnapshot?.statusSnapshot ?: AgentUiStatus.resolve(replaySnapshot?.status ?: AgentUiStatus.IDLE).toSnapshot(),
                    sessionId = sessionId,
                    entrySource = replaySnapshot?.entrySource ?: "app",
                    profileId = profileId,
                    targetPackageName = targetPackageName,
                    task = replaySnapshot?.task.orEmpty(),
                    turns = replaySnapshot?.turnCount ?: 0,
                ),
            routeSnapshot =
                replaySnapshot?.let { snapshot ->
                    RuntimeRouteSnapshot(
                        activeProfileId = snapshot.profileId,
                        activeTargetPackage = snapshot.targetPackageName,
                        reason = snapshot.routeReason,
                        policyTag = snapshot.routePolicyTag,
                        selectedProfileId = snapshot.routeSelectedProfileId,
                        fallbackReason = snapshot.routeFallbackReason,
                        memoryHints = snapshot.routeMemoryHints,
                    )
                },
            resultSnapshot =
                replaySnapshot?.let { snapshot ->
                    RuntimeResultSnapshot(
                        lastResult = snapshot.latestTurn?.result.orEmpty(),
                        title = snapshot.finalTaskResult?.title.orEmpty(),
                        summary =
                            snapshot.finalTaskResult?.summary.orEmpty().ifBlank {
                                snapshot.finalMessage
                            },
                        hint = snapshot.finalMessage,
                    )
                },
            lastTransition = "session_platform_replay_projection",
            updatedAtMs = replaySnapshot?.updatedAt ?: 0L,
        )
    }
}
