package com.lmx.xiaoxuanagent.runtime

import org.json.JSONObject

internal object SessionPlatformSnapshotFacadeSupport {
    fun readCurrentSnapshot(
        eventLimit: Int,
    ): SessionPlatformSnapshot =
        SessionPlatformSnapshotOps.buildSnapshot(
            state = SessionRuntimeStore.read(),
            eventLimit = eventLimit,
        )

    fun readSessionSnapshot(
        sessionId: String,
        eventLimit: Int,
    ): SessionPlatformSnapshot {
        val replaySnapshot = sessionId.takeIf { it.isNotBlank() }?.let(ReplayStore::readSessionSnapshot)
        val resumeSnapshot = SessionResumeStore.readSessionSnapshot(sessionId)
        val currentState =
            sessionId.takeIf { it.isNotBlank() && SessionRuntimeStore.hasSession(it) }
                ?.let(SessionRuntimeStore::readSession)
                ?: SessionRuntimeStore.read()
        val effectiveState =
            if (sessionId.isNotBlank() && currentState.session.sessionId == sessionId) {
                currentState
            } else {
                SessionPlatformSnapshotOps.fallbackRuntimeState(sessionId, replaySnapshot, resumeSnapshot)
            }
        return SessionPlatformSnapshotOps.buildSnapshot(
            state = effectiveState,
            sessionId = sessionId,
            sessionSnapshot = replaySnapshot,
            resumeSnapshot = resumeSnapshot,
            eventLimit = eventLimit,
        )
    }

    fun readCurrentPlatformSummary(): SessionPlatformSummary =
        SessionPlatformSnapshotOps.buildSummary(readCurrentSnapshot(eventLimit = SessionPlatformFacade.DEFAULT_RUNTIME_EVENT_LIMIT))

    fun readSessionHistory(
        limit: Int,
    ): SessionHistorySnapshot = SessionPlatformHistoryOps.readSessionHistory(limit = limit)

    fun searchSessionHistory(
        query: String,
        limit: Int,
    ): SessionHistorySearchResult = SessionPlatformHistoryOps.searchSessionHistory(query = query, limit = limit)

    fun readResumablePlatformSnapshots(
        limit: Int,
        eventLimit: Int,
    ): List<SessionPlatformSnapshot> =
        SessionResumeStore.readResumableSnapshots(limit)
            .map { snapshot ->
                readSessionSnapshot(sessionId = snapshot.sessionId, eventLimit = eventLimit)
            }

    fun exportSessionBundle(
        sessionId: String,
        eventLimit: Int,
    ): JSONObject = SessionPlatformBundleOps.exportSessionBundle(sessionId = sessionId, eventLimit = eventLimit)

    fun exportRemoteViewerBundle(
        sessionId: String,
        eventLimit: Int,
    ): JSONObject = SessionPlatformBundleOps.exportRemoteViewerBundle(sessionId = sessionId, eventLimit = eventLimit)

    fun importSessionBundle(
        bundle: JSONObject,
        bootstrapImportedResume: Boolean,
    ): SessionPlatformSnapshot? =
        SessionPlatformBundleOps.importSessionBundle(
            bundle = bundle,
            bootstrapImportedResume = bootstrapImportedResume,
        )

    fun runDeterministicReplay(
        sessionId: String,
    ): SessionReplayVerification {
        val commands = RuntimeEventStore.readSessionCommandLedger(sessionId)
        if (commands.isEmpty()) {
            return SessionReplayVerification(
                sessionId = sessionId,
                replayable = false,
                matches = false,
                commandCount = 0,
                expectedStatus = readSessionSnapshot(sessionId, SessionPlatformFacade.DEFAULT_RUNTIME_EVENT_LIMIT).bridgeSnapshot.statusCode,
                replayedStatus = AgentUiStatus.IDLE,
                mismatches = listOf("runtime ledger does not contain command payloads for deterministic replay"),
            )
        }
        var replayed = SessionRuntimeState()
        commands.forEachIndexed { index, command ->
            replayed = SessionRuntimeReducer.reduce(replayed, command, nowMs = index.toLong() + 1L)
        }
        val live = readSessionSnapshot(sessionId, SessionPlatformFacade.DEFAULT_RUNTIME_EVENT_LIMIT)
        val mismatches = mutableListOf<String>()
        if (replayed.session.status != live.state.session.status) mismatches += "status ${replayed.session.status} != ${live.state.session.status}"
        if (replayed.session.turns != live.state.session.turns) mismatches += "turns ${replayed.session.turns} != ${live.state.session.turns}"
        if (replayed.session.task != live.state.session.task) mismatches += "task mismatch"
        if (replayed.routeSnapshot?.reason.orEmpty() != live.state.routeSnapshot?.reason.orEmpty()) mismatches += "route_reason mismatch"
        if (replayed.resultSnapshot?.summary.orEmpty() != live.state.resultSnapshot?.summary.orEmpty()) mismatches += "result_summary mismatch"
        RuntimeMetricsStore.recordPlatformEvent("deterministic_replay")
        return SessionReplayVerification(
            sessionId = sessionId,
            replayable = true,
            matches = mismatches.isEmpty(),
            commandCount = commands.size,
            expectedStatus = live.state.session.status,
            replayedStatus = replayed.session.status,
            mismatches = mismatches,
        )
    }

    fun readPendingApprovalTickets(
        limit: Int,
    ): List<SessionApprovalTicket> =
        readResumablePlatformSnapshots(limit = limit, eventLimit = SessionPlatformFacade.DEFAULT_RUNTIME_EVENT_LIMIT)
            .mapNotNull { snapshot ->
                snapshot.takeIf { it.pendingSafetySummary.isNotBlank() || it.bridgeSnapshot.pendingSafetyConfirmation.isNotBlank() }
                    ?.let {
                        SessionApprovalTicket(
                            sessionId = it.sessionId,
                            task = it.bridgeSnapshot.task,
                            statusCode = it.bridgeSnapshot.statusCode,
                            approvalSummary = it.pendingSafetySummary.ifBlank { it.bridgeSnapshot.pendingSafetyConfirmation },
                            entrySource = it.bridgeSnapshot.entrySource,
                            targetPackageName = it.bridgeSnapshot.targetPackageName,
                            updatedAtMs = it.state.updatedAtMs,
                        )
                    }
            }

    fun searchArtifacts(
        query: String,
        sessionId: String,
        limit: Int,
        types: Set<String>,
    ): List<ArtifactStore.ArtifactSearchHit> =
        ArtifactStore.searchArtifacts(
            query = query,
            sessionId = sessionId,
            limit = limit,
            types = types,
        )

    fun garbageCollectArtifacts(): Int {
        val keepSessionIds =
            (ReplayStore.listSessionIds(limit = 200) + SessionResumeStore.readResumableSnapshots(200).map { it.sessionId })
                .filter { it.isNotBlank() }
                .toSet()
        return ArtifactStore.garbageCollect(keepSessionIds).also {
            if (it > 0) {
                RuntimeMetricsStore.recordPlatformEvent("artifact_gc")
            }
        }
    }

    fun buildSnapshot(
        state: SessionRuntimeState,
        sessionId: String,
        sessionSnapshot: ReplaySessionSnapshot?,
        resumeSnapshot: SessionResumeSnapshot?,
        bridgeSnapshot: SessionBridgeSnapshot?,
        eventLimit: Int,
    ): SessionPlatformSnapshot =
        SessionPlatformSnapshotOps.buildSnapshot(
            state = state,
            sessionId = sessionId,
            sessionSnapshot = sessionSnapshot,
            resumeSnapshot = resumeSnapshot,
            bridgeSnapshot = bridgeSnapshot,
            eventLimit = eventLimit,
        )
}
