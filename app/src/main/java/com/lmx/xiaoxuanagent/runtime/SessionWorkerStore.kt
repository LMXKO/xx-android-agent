package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class SessionWorkerStatus {
    QUEUED,
    RUNNING,
    WAITING_EXTERNAL,
    WAITING_APPROVAL,
    WAITING_CHILDREN,
    PAUSED,
    DEFERRED,
    COMPLETED,
    FAILED,
    STOPPED,
}

enum class SessionWorkerJoinPolicy {
    DETACHED,
    WAIT_ALL_CHILDREN,
    WAIT_ANY_CHILD,
}

data class SessionWorkerTreeNode(
    val record: SessionWorkerRecord,
    val children: List<SessionWorkerTreeNode> = emptyList(),
)

data class SessionWorkerDispatchCandidate(
    val workerId: String,
    val sessionId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val missionType: String = "",
    val missionLabel: String = "",
    val escalationPolicy: String = "",
    val ownerKey: String = "",
    val leaseOwner: String = "",
    val leaseExpiresAtMs: Long = 0L,
    val lane: String = "balanced",
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
)

data class SessionWorkerSchedulerSnapshot(
    val maxConcurrentWorkers: Int,
    val queuedCount: Int,
    val activeCount: Int,
    val blockedCount: Int,
    val deferredCount: Int,
    val waitingChildrenCount: Int,
    val readyWorkerIds: List<String> = emptyList(),
    val fairnessMode: String = "coordinator_fair",
    val mailboxPriorityMode: String = "balanced",
    val missionSummary: String = "",
    val escalationSummary: String = "",
    val joinSummary: String = "",
    val blockedCoordinatorIds: List<String> = emptyList(),
    val coordinatorLoadSummary: List<String> = emptyList(),
    val dispatchCandidates: List<SessionWorkerDispatchCandidate> = emptyList(),
)

data class SessionWorkerGraphSnapshot(
    val roots: List<SessionWorkerTreeNode> = emptyList(),
    val scheduler: SessionWorkerSchedulerSnapshot,
    val activeSessionIds: List<String> = emptyList(),
)

data class SessionWorkerRecord(
    val workerId: String,
    val agentId: String = "",
    val parentSessionId: String = "",
    val parentWorkerId: String = "",
    val parentAgentId: String = "",
    val rootSessionId: String = "",
    val coordinatorSessionId: String = "",
    val sessionId: String = "",
    val depth: Int = 0,
    val childWorkerIds: List<String> = emptyList(),
    val childSessionIds: List<String> = emptyList(),
    val missionType: SessionWorkerMissionType = SessionWorkerMissionType.EXECUTION,
    val escalationPolicy: SessionWorkerEscalationPolicy = SessionWorkerEscalationPolicy.NONE,
    val joinExpectation: SessionWorkerJoinExpectation = SessionWorkerJoinExpectation.ALL_CHILDREN,
    val missionLabel: String = "",
    val missionSummary: String = "",
    val task: String,
    val entrySource: String,
    val status: SessionWorkerStatus,
    val priority: Int = 0,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val nextEligibleAtMs: Long = 0L,
    val blockedReason: String = "",
    val joinPolicy: SessionWorkerJoinPolicy = SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN,
    val summary: String = "",
    val completionStatus: String = "",
    val resultSummary: String = "",
    val source: String = "",
    val leaseOwner: String = "",
    val leaseToken: String = "",
    val leaseAcquiredAtMs: Long = 0L,
    val leaseExpiresAtMs: Long = 0L,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastAttemptAtMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
)

object SessionWorkerStore {
    private const val WORKER_DIR = "runtime"
    private const val WORKER_FILE = "session_workers.json"
    private const val MAX_WORKERS = 80
    private const val DEFAULT_MAX_CONCURRENT_WORKERS = 3
    private const val DEFAULT_LEASE_TTL_MS = 90_000L
    private val lock = Any()
    private var hydrated = false
    private val workers = ArrayDeque<SessionWorkerRecord>()

    fun enqueueFork(
        parentSessionId: String,
        task: String,
        entrySource: String,
        source: String,
        summary: String,
        metadata: Map<String, String> = emptyMap(),
    ): SessionWorkerRecord {
        val now = System.currentTimeMillis()
        val record =
            synchronized(lock) {
                hydrateIfNeededUnlocked()
                val parentWorker =
                    workers.firstOrNull { worker ->
                        worker.sessionId.isNotBlank() && worker.sessionId == parentSessionId
                    }
                val parentWorkerId = parentWorker?.workerId.orEmpty()
                val depth = (parentWorker?.depth ?: -1) + 1
                val joinPolicy = metadata["join_policy"].toJoinPolicy()
                val missionProfile =
                    deriveSessionWorkerMissionProfile(
                        task = task.trim(),
                        entrySource = entrySource,
                        joinPolicy = joinPolicy,
                        metadata = metadata,
                        hasParent = parentWorkerId.isNotBlank(),
                    )
                val next =
                    SessionWorkerRecord(
                        workerId = "worker_${now}_${task.hashCode().toUInt().toString(16)}",
                        agentId = createAgentIdRef(task).raw,
                        parentSessionId = parentSessionId,
                        parentWorkerId = parentWorkerId,
                        parentAgentId = parentWorker?.agentId.orEmpty(),
                        rootSessionId = parentWorker?.rootSessionId.orEmpty().ifBlank { parentSessionId },
                        coordinatorSessionId = parentWorker?.coordinatorSessionId.orEmpty().ifBlank { parentSessionId },
                        missionType = missionProfile.missionType,
                        escalationPolicy = missionProfile.escalationPolicy,
                        joinExpectation = missionProfile.joinExpectation,
                        missionLabel = missionProfile.missionLabel,
                        missionSummary = missionProfile.missionSummary,
                        task = task.trim(),
                        entrySource = entrySource,
                        depth = depth.coerceAtLeast(0),
                        status = SessionWorkerStatus.QUEUED,
                        priority = metadata["priority"]?.toIntOrNull() ?: 0,
                        maxRetries = metadata["max_retries"]?.toIntOrNull()?.coerceAtLeast(0) ?: 2,
                        nextEligibleAtMs = now,
                        blockedReason = "",
                        joinPolicy = joinPolicy,
                        summary = summary,
                        source = source,
                        createdAtMs = now,
                        updatedAtMs = now,
                        metadata = metadata,
                    )
                workers.addFirst(next)
                if (parentWorkerId.isNotBlank()) {
                    updateUnlocked(parentWorkerId) { current ->
                        current.copy(
                            childWorkerIds = (current.childWorkerIds + next.workerId).distinct(),
                            updatedAtMs = now,
                        )
                    }
                    reconcileAncestorsUnlocked(parentWorkerId, now)
                }
                trimUnlocked()
                persistUnlocked()
                next
            }
        SessionAgentSidechainStore.recordWorkerEnqueued(record)
        return record
    }

    fun readAll(
        limit: Int = 24,
    ): List<SessionWorkerRecord> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            workers.take(limit).toList()
        }

    fun importRecords(
        importedRecords: List<SessionWorkerRecord>,
    ) {
        if (importedRecords.isEmpty()) return
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val merged =
                (workers + importedRecords)
                    .distinctBy { it.workerId }
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_WORKERS)
            workers.clear()
            merged.forEach(workers::addLast)
            persistUnlocked()
        }
    }

    fun readQueued(
        limit: Int = 8,
    ): List<SessionWorkerRecord> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            workers
                .filter { it.status == SessionWorkerStatus.QUEUED || it.status == SessionWorkerStatus.DEFERRED }
                .take(limit)
        }

    fun readChildren(
        parentSessionId: String,
        limit: Int = 12,
    ): List<SessionWorkerRecord> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            workers.filter { it.parentSessionId == parentSessionId }.take(limit)
        }

    fun readBySessionId(
        sessionId: String,
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            workers.firstOrNull { it.sessionId == sessionId }
        }

    fun readByWorkerId(
        workerId: String,
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            workers.firstOrNull { it.workerId == workerId }
        }

    fun readTree(
        limit: Int = 12,
    ): List<SessionWorkerTreeNode> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            val indexed = workers.associateBy { it.workerId }
            val roots =
                workers
                    .filter { it.parentWorkerId.isBlank() || indexed[it.parentWorkerId] == null }
                    .sortedByDescending { it.updatedAtMs }
                    .take(limit)
            roots.map { it.toTreeNode(indexed) }
        }

    fun readGraphSnapshot(
        limit: Int = 12,
        maxConcurrentWorkers: Int = DEFAULT_MAX_CONCURRENT_WORKERS,
    ): SessionWorkerGraphSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            val indexed = workers.associateBy { it.workerId }
            val roots =
                workers
                    .filter { it.parentWorkerId.isBlank() || indexed[it.parentWorkerId] == null }
                    .sortedByDescending { it.updatedAtMs }
                    .take(limit)
                    .map { it.toTreeNode(indexed) }
            val activeWorkers = workers.filter { it.status.isActive() }
            val readyWorkers =
                SessionWorkerDispatchPolicy.selectDispatchable(
                    workers = workers.toList(),
                    limit = limit,
                    maxConcurrentWorkers = maxConcurrentWorkers,
                    graphReader = SessionSessionGraphStore::read,
                )
            val queuedCount = workers.count { it.status == SessionWorkerStatus.QUEUED }
            val deferredCount = workers.count { it.status == SessionWorkerStatus.DEFERRED }
            val waitingChildrenCount = workers.count { it.status == SessionWorkerStatus.WAITING_CHILDREN }
            SessionWorkerGraphSnapshot(
                roots = roots,
                scheduler =
                    SessionWorkerSchedulerSnapshot(
                        maxConcurrentWorkers = maxConcurrentWorkers,
                        queuedCount = queuedCount,
                        activeCount = activeWorkers.size,
                        blockedCount = (queuedCount + deferredCount) - readyWorkers.size,
                        deferredCount = deferredCount,
                        waitingChildrenCount = waitingChildrenCount,
                        readyWorkerIds = readyWorkers.map { it.worker.workerId },
                        fairnessMode = SessionWorkerDispatchPolicy.FAIRNESS_MODE,
                        mailboxPriorityMode = SessionWorkerDispatchPolicy.deriveMailboxPriorityMode(readyWorkers),
                        missionSummary = workers.toList().missionMixSummary(),
                        escalationSummary = workers.toList().escalationPressureSummary(),
                        joinSummary = workers.toList().joinPressureSummary(),
                        blockedCoordinatorIds = SessionWorkerDispatchPolicy.blockedCoordinatorIds(workers.toList()),
                        coordinatorLoadSummary = SessionWorkerDispatchPolicy.coordinatorLoadSummary(activeWorkers),
                        dispatchCandidates =
                            readyWorkers.map { candidate ->
                                SessionWorkerDispatchCandidate(
                                    workerId = candidate.worker.workerId,
                                    sessionId = candidate.worker.sessionId,
                                    rootSessionId = candidate.worker.rootSessionId,
                                    coordinatorSessionId = candidate.worker.coordinatorSessionId,
                                    missionType = candidate.worker.missionType.name.lowercase(),
                                    missionLabel = candidate.worker.missionLabelResolved(),
                                    escalationPolicy = candidate.worker.escalationPolicy.name.lowercase(),
                                    ownerKey = candidate.ownerKey,
                                    leaseOwner = candidate.worker.leaseOwner,
                                    leaseExpiresAtMs = candidate.worker.leaseExpiresAtMs,
                                    lane = candidate.lane,
                                    score = candidate.score,
                                    reasons = candidate.reasons,
                                )
                            },
                    ),
                activeSessionIds = activeWorkers.mapNotNull { it.sessionId.takeIf(String::isNotBlank) }.distinct(),
            )
        }

    fun readDispatchableWorkers(
        limit: Int = 4,
        maxConcurrentWorkers: Int = DEFAULT_MAX_CONCURRENT_WORKERS,
    ): List<SessionWorkerRecord> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            SessionWorkerDispatchPolicy
                .selectDispatchable(
                    workers = workers.toList(),
                    limit = limit,
                    maxConcurrentWorkers = maxConcurrentWorkers,
                    graphReader = SessionSessionGraphStore::read,
                ).map { it.worker }
        }

    fun acquireDispatchLease(
        workerId: String,
        owner: String,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            val current = workers.firstOrNull { it.workerId == workerId } ?: return null
            if (current.status != SessionWorkerStatus.QUEUED && current.status != SessionWorkerStatus.DEFERRED) {
                return null
            }
            if (current.hasActiveLease(System.currentTimeMillis())) {
                return null
            }
            val now = System.currentTimeMillis()
            val next =
                updateUnlocked(workerId) {
                    it.copy(
                        leaseOwner = owner,
                        leaseToken = "lease_${now}_${workerId}",
                        leaseAcquiredAtMs = now,
                        leaseExpiresAtMs = now + ttlMs.coerceAtLeast(30_000L),
                        updatedAtMs = now,
                    )
                } ?: return null
            persistUnlocked()
            next
        }

    fun renewDispatchLease(
        workerId: String,
        owner: String,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
        leaseToken: String = "",
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            val current = workers.firstOrNull { it.workerId == workerId } ?: return null
            if (current.leaseToken.isBlank()) return null
            if (owner.isNotBlank() && current.leaseOwner.isNotBlank() && current.leaseOwner != owner) return null
            if (leaseToken.isNotBlank() && current.leaseToken != leaseToken) return null
            val now = System.currentTimeMillis()
            val next =
                updateUnlocked(workerId) {
                    it.copy(
                        leaseOwner = owner.ifBlank { it.leaseOwner },
                        leaseExpiresAtMs = now + ttlMs.coerceAtLeast(30_000L),
                        updatedAtMs = now,
                    )
                } ?: return null
            persistUnlocked()
            next
        }

    fun releaseDispatchLease(
        workerId: String,
        owner: String = "",
        leaseToken: String = "",
        reason: String = "lease_released",
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                if (current.leaseToken.isBlank()) return@update current
                if (owner.isNotBlank() && current.leaseOwner.isNotBlank() && current.leaseOwner != owner) return@update current
                if (leaseToken.isNotBlank() && current.leaseToken != leaseToken) return@update current
                current.copy(
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                    metadata = current.metadata + mapOf("last_lease_event" to reason),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(
                worker = it,
                metadata = mapOf("event" to reason),
            )
        }
        return updated
    }

    fun revokeDispatchLease(
        workerId: String,
        owner: String = "",
        reason: String = "lease_revoked",
    ): SessionWorkerRecord? = releaseDispatchLease(workerId = workerId, owner = owner, reason = reason)

    fun handoffDispatchLease(
        workerId: String,
        fromOwner: String,
        toOwner: String,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            reapExpiredLeasesUnlocked()
            val current = workers.firstOrNull { it.workerId == workerId } ?: return null
            if (current.leaseToken.isBlank()) return null
            if (fromOwner.isNotBlank() && current.leaseOwner.isNotBlank() && current.leaseOwner != fromOwner) return null
            val now = System.currentTimeMillis()
            val next =
                updateUnlocked(workerId) {
                    it.copy(
                        leaseOwner = toOwner.ifBlank { it.leaseOwner },
                        leaseToken = "lease_${now}_${workerId}",
                        leaseAcquiredAtMs = now,
                        leaseExpiresAtMs = now + ttlMs.coerceAtLeast(30_000L),
                        updatedAtMs = now,
                        metadata =
                            it.metadata +
                                mapOf(
                                    "last_lease_event" to "lease_handoff",
                                    "lease_handoff_from" to fromOwner,
                                    "lease_handoff_to" to toOwner,
                                ),
                    )
                } ?: return null
            persistUnlocked()
            next
        }

    fun claimWorkerForCoordinator(
        workerId: String,
        coordinatorSessionId: String,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): SessionWorkerRecord? {
        val owner = coordinatorLeaseOwner(coordinatorSessionId)
        return acquireDispatchLease(workerId = workerId, owner = owner, ttlMs = ttlMs)
            ?: renewDispatchLease(workerId = workerId, owner = owner, ttlMs = ttlMs)
    }

    fun releaseCoordinatorClaim(
        workerId: String,
        coordinatorSessionId: String,
        reason: String = "coordinator_claim_released",
    ): SessionWorkerRecord? =
        releaseDispatchLease(
            workerId = workerId,
            owner = coordinatorLeaseOwner(coordinatorSessionId),
            reason = reason,
        )

    fun markLaunched(
        workerId: String,
        sessionId: String,
        summary: String,
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                val now = System.currentTimeMillis()
                current.copy(
                    sessionId = sessionId,
                    status = SessionWorkerStatus.RUNNING,
                    summary = summary.ifBlank { current.summary },
                    childSessionIds = (current.childSessionIds + sessionId).filter { it.isNotBlank() }.distinct(),
                    completionStatus = "",
                    resultSummary = "",
                    blockedReason = "",
                    nextEligibleAtMs = 0L,
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = now,
                    lastAttemptAtMs = now,
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(
                worker = it,
                metadata = mapOf("event" to "mark_launched"),
            )
            SessionSessionGraphStore.attachWorkerSession(
                sessionId = sessionId,
                worker = it,
                entrySource = it.entrySource,
                metadata =
                    mapOf(
                        "launch_summary" to summary,
                    ),
            )
        }
        return updated
    }

    fun markRunning(
        workerId: String,
        summary: String,
        blockedReason: String = "",
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                current.copy(
                    status = SessionWorkerStatus.RUNNING,
                    summary = summary.ifBlank { current.summary },
                    blockedReason = blockedReason,
                    completionStatus = "",
                    resultSummary = "",
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                    lastAttemptAtMs = System.currentTimeMillis(),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(
                worker = it,
                metadata = mapOf("event" to "mark_running"),
            )
        }
        return updated
    }

    fun markCompleted(
        workerId: String,
        summary: String,
        resultSummary: String = summary,
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                current.copy(
                    status = SessionWorkerStatus.COMPLETED,
                    summary = summary.ifBlank { current.summary },
                    completionStatus = SessionWorkerStatus.COMPLETED.name.lowercase(),
                    resultSummary = resultSummary.ifBlank { current.resultSummary },
                    blockedReason = "",
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(it)
            SessionAgentSidechainStore.recordWorkerResult(it)
        }
        return updated
    }

    fun markDeferred(
        workerId: String,
        summary: String,
        deferByMs: Long,
        blockedReason: String = "",
        incrementRetry: Boolean = true,
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                current.copy(
                    status = SessionWorkerStatus.DEFERRED,
                    summary = summary.ifBlank { current.summary },
                    retryCount =
                        if (incrementRetry) {
                            (current.retryCount + 1).coerceAtMost(current.maxRetries + 1)
                        } else {
                            current.retryCount
                        },
                    nextEligibleAtMs = System.currentTimeMillis() + deferByMs,
                    blockedReason = blockedReason.ifBlank { current.blockedReason.ifBlank { "deferred" } },
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(
                worker = it,
                metadata = mapOf("defer_by_ms" to deferByMs.toString()),
            )
        }
        return updated
    }

    fun markWaitingChildren(
        workerId: String,
        summary: String,
        blockedReason: String,
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                current.copy(
                    status = SessionWorkerStatus.WAITING_CHILDREN,
                    summary = summary.ifBlank { current.summary },
                    blockedReason = blockedReason.ifBlank { "waiting_children" },
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(it)
        }
        return updated
    }

    fun syncPlatformSnapshot(
        snapshot: SessionPlatformSnapshot,
    ) {
        val sessionId = snapshot.sessionId.ifBlank { snapshot.bridgeSnapshot.sessionId }
        if (sessionId.isBlank()) return
        val updatedRecord =
            synchronized(lock) {
                hydrateIfNeededUnlocked()
                val index = workers.indexOfFirst { it.sessionId == sessionId }
                if (index < 0) return
                val current = workers.elementAt(index)
                val workerStatus = snapshot.toWorkerStatus()
                val now = System.currentTimeMillis()
                val next =
                    current.copy(
                        status = workerStatus,
                        summary = snapshot.toWorkerSummary().ifBlank { current.summary },
                        completionStatus =
                            snapshot.toWorkerCompletionStatus().ifBlank {
                                if (workerStatus.isTerminal()) workerStatus.name.lowercase() else current.completionStatus
                            },
                        resultSummary = snapshot.toWorkerResultSummary().ifBlank { current.resultSummary },
                        blockedReason =
                            when (workerStatus) {
                                SessionWorkerStatus.WAITING_APPROVAL -> snapshot.pendingSafetySummary.ifBlank { "waiting_approval" }
                                SessionWorkerStatus.WAITING_EXTERNAL -> snapshot.bridgeSnapshot.statusCode
                                SessionWorkerStatus.PAUSED -> snapshot.bridgeSnapshot.takeoverSummary.ifBlank { "paused" }
                                else -> ""
                            },
                        leaseOwner = "",
                        leaseToken = "",
                        leaseAcquiredAtMs = 0L,
                        leaseExpiresAtMs = 0L,
                        updatedAtMs = now,
                    )
                workers.removeAt(index)
                workers.add(index, next)
                reconcileAncestorsUnlocked(next.workerId, now)
                persistUnlocked()
                next
            }
        SessionAgentSidechainStore.recordWorkerState(updatedRecord)
        if (updatedRecord.status.isTerminal()) {
            SessionAgentSidechainStore.recordWorkerResult(updatedRecord)
        }
    }

    fun markFailed(
        workerId: String,
        summary: String,
    ): SessionWorkerRecord? {
        val updated =
            update(workerId) { current ->
                current.copy(
                    status = SessionWorkerStatus.FAILED,
                    summary = summary.ifBlank { current.summary },
                    completionStatus = SessionWorkerStatus.FAILED.name.lowercase(),
                    resultSummary = summary.ifBlank { current.resultSummary },
                    blockedReason = "",
                    leaseOwner = "",
                    leaseToken = "",
                    leaseAcquiredAtMs = 0L,
                    leaseExpiresAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        updated?.let {
            SessionAgentSidechainStore.recordWorkerState(it)
            SessionAgentSidechainStore.recordWorkerResult(it)
        }
        return updated
    }

    private fun update(
        workerId: String,
        reducer: (SessionWorkerRecord) -> SessionWorkerRecord,
    ): SessionWorkerRecord? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val next = updateUnlocked(workerId, reducer) ?: return null
            reconcileAncestorsUnlocked(next.workerId, next.updatedAtMs)
            persistUnlocked()
            next
        }

    private fun updateUnlocked(
        workerId: String,
        reducer: (SessionWorkerRecord) -> SessionWorkerRecord,
    ): SessionWorkerRecord? {
        val index = workers.indexOfFirst { it.workerId == workerId }
        if (index < 0) return null
        val next = reducer(workers.elementAt(index))
        workers.removeAt(index)
        workers.add(index, next)
        return next
    }

    private fun reconcileAncestorsUnlocked(
        workerId: String,
        now: Long,
    ) {
        var currentId: String? = workerId
        while (!currentId.isNullOrBlank()) {
            reconcileJoinStateUnlocked(currentId, now)
            currentId = workers.firstOrNull { it.workerId == currentId }?.parentWorkerId?.takeIf { it.isNotBlank() }
        }
    }

    private fun reconcileJoinStateUnlocked(
        workerId: String,
        now: Long,
    ) {
        val current = workers.firstOrNull { it.workerId == workerId } ?: return
        if (current.joinPolicy == SessionWorkerJoinPolicy.DETACHED || current.childWorkerIds.isEmpty()) {
            if (current.status == SessionWorkerStatus.WAITING_CHILDREN && current.completionStatus.isNotBlank()) {
                val restored = current.completionStatus.toWorkerStatus()
                updateUnlocked(workerId) {
                    it.copy(
                        status = restored,
                        blockedReason = "",
                        updatedAtMs = now,
                    )
                }
            }
            return
        }
        val indexed = workers.associateBy { it.workerId }
        val childStatuses = current.childWorkerIds.mapNotNull(indexed::get).map { it.status }
        val hasPendingChildren = childStatuses.any { !it.isTerminal() }
        val shouldWait =
            when (current.joinPolicy) {
                SessionWorkerJoinPolicy.DETACHED -> false
                SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN -> hasPendingChildren
                SessionWorkerJoinPolicy.WAIT_ANY_CHILD -> childStatuses.none { it.isTerminal() }
            }
        val fallbackStatus = current.completionStatus.toWorkerStatus()
        if (current.status.isTerminal() && shouldWait) {
            updateUnlocked(workerId) {
                it.copy(
                    status = SessionWorkerStatus.WAITING_CHILDREN,
                    blockedReason = "waiting_for_children",
                    updatedAtMs = now,
                )
            }
            return
        }
        if (current.status == SessionWorkerStatus.WAITING_CHILDREN && !shouldWait) {
            updateUnlocked(workerId) {
                it.copy(
                    status = fallbackStatus,
                    blockedReason = "",
                    updatedAtMs = now,
                )
            }
        }
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = workerFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("workers") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                workers.addLast(json.toWorkerRecord())
            }
        }
    }

    private fun persistUnlocked() {
        val file = workerFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "workers",
                    JSONArray().apply {
                        workers.forEach { worker ->
                            put(
                                JSONObject().apply {
                                    put("worker_id", worker.workerId)
                                    put("agent_id", worker.agentId)
                                    put("parent_session_id", worker.parentSessionId)
                                    put("parent_worker_id", worker.parentWorkerId)
                                    put("parent_agent_id", worker.parentAgentId)
                                    put("root_session_id", worker.rootSessionId)
                                    put("coordinator_session_id", worker.coordinatorSessionId)
                                    put("session_id", worker.sessionId)
                                    put("depth", worker.depth)
                                    put("child_worker_ids", JSONArray(worker.childWorkerIds))
                                    put("child_session_ids", JSONArray(worker.childSessionIds))
                                    put("mission_type", worker.missionType.name)
                                    put("escalation_policy", worker.escalationPolicy.name)
                                    put("join_expectation", worker.joinExpectation.name)
                                    put("mission_label", worker.missionLabel)
                                    put("mission_summary", worker.missionSummary)
                                    put("task", worker.task)
                                    put("entry_source", worker.entrySource)
                                    put("status", worker.status.name)
                                    put("priority", worker.priority)
                                    put("retry_count", worker.retryCount)
                                    put("max_retries", worker.maxRetries)
                                    put("next_eligible_at_ms", worker.nextEligibleAtMs)
                                    put("blocked_reason", worker.blockedReason)
                                    put("join_policy", worker.joinPolicy.name)
                                    put("summary", worker.summary)
                                    put("completion_status", worker.completionStatus)
                                    put("result_summary", worker.resultSummary)
                                    put("source", worker.source)
                                    put("lease_owner", worker.leaseOwner)
                                    put("lease_token", worker.leaseToken)
                                    put("lease_acquired_at_ms", worker.leaseAcquiredAtMs)
                                    put("lease_expires_at_ms", worker.leaseExpiresAtMs)
                                    put("created_at_ms", worker.createdAtMs)
                                    put("updated_at_ms", worker.updatedAtMs)
                                    put("last_attempt_at_ms", worker.lastAttemptAtMs)
                                    put(
                                        "metadata",
                                        JSONObject().apply {
                                            worker.metadata.forEach { (key, value) ->
                                                put(key, value)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (workers.size > MAX_WORKERS) {
            workers.removeLast()
        }
    }

    private fun reapExpiredLeasesUnlocked(
        now: Long = System.currentTimeMillis(),
    ) {
        var changed = false
        for (index in workers.indices) {
            val current = workers.elementAt(index)
            if (!current.hasActiveLease(now) && current.leaseToken.isNotBlank()) {
                val next =
                    current.copy(
                        leaseOwner = "",
                        leaseToken = "",
                        leaseAcquiredAtMs = 0L,
                        leaseExpiresAtMs = 0L,
                        updatedAtMs = maxOf(current.updatedAtMs, now),
                    )
                workers.removeAt(index)
                workers.add(index, next)
                changed = true
            }
        }
        if (changed) {
            persistUnlocked()
        }
    }

    private fun workerFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, WORKER_DIR), WORKER_FILE)
    }

    private fun coordinatorLeaseOwner(
        coordinatorSessionId: String,
    ): String = "coordinator:${coordinatorSessionId.ifBlank { "unknown" }}"

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun SessionWorkerRecord.hasActiveLease(
        now: Long,
    ): Boolean = leaseToken.isNotBlank() && leaseExpiresAtMs > now

    private fun SessionWorkerRecord.toTreeNode(
        indexed: Map<String, SessionWorkerRecord>,
    ): SessionWorkerTreeNode =
        SessionWorkerTreeNode(
            record = this,
            children =
                childWorkerIds
                    .mapNotNull(indexed::get)
                    .sortedByDescending { it.updatedAtMs }
                    .map { it.toTreeNode(indexed) },
        )

    private fun SessionWorkerStatus.isActive(): Boolean =
        this == SessionWorkerStatus.RUNNING ||
            this == SessionWorkerStatus.WAITING_EXTERNAL ||
            this == SessionWorkerStatus.WAITING_APPROVAL ||
            this == SessionWorkerStatus.PAUSED

    private fun SessionWorkerStatus.isTerminal(): Boolean =
        this == SessionWorkerStatus.COMPLETED ||
            this == SessionWorkerStatus.FAILED ||
            this == SessionWorkerStatus.STOPPED

    private fun String?.toJoinPolicy(): SessionWorkerJoinPolicy =
        runCatching {
            SessionWorkerJoinPolicy.valueOf(this.orEmpty().ifBlank { SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN.name })
        }.getOrDefault(SessionWorkerJoinPolicy.WAIT_ALL_CHILDREN)

    private fun String.toWorkerStatus(): SessionWorkerStatus =
        runCatching { SessionWorkerStatus.valueOf(uppercase()) }.getOrDefault(SessionWorkerStatus.COMPLETED)

    private fun JSONObject.toWorkerRecord(): SessionWorkerRecord {
        val metadata = optJSONObject("metadata").toStringMap()
        val joinPolicy = optString("join_policy").toJoinPolicy()
        val task = optString("task")
        val entrySource = optString("entry_source")
        val missionProfile =
            deriveSessionWorkerMissionProfile(
                task = task,
                entrySource = entrySource,
                joinPolicy = joinPolicy,
                metadata = metadata,
                hasParent = optString("parent_worker_id").isNotBlank(),
            )
        return SessionWorkerRecord(
            workerId = optString("worker_id"),
            agentId = optString("agent_id"),
            parentSessionId = optString("parent_session_id"),
            parentWorkerId = optString("parent_worker_id"),
            parentAgentId = optString("parent_agent_id"),
            rootSessionId = optString("root_session_id"),
            coordinatorSessionId = optString("coordinator_session_id"),
            sessionId = optString("session_id"),
            depth = optInt("depth", 0),
            childWorkerIds = optJSONArray("child_worker_ids").toStringList(),
            childSessionIds = optJSONArray("child_session_ids").toStringList(),
            missionType =
                runCatching { SessionWorkerMissionType.valueOf(optString("mission_type")) }
                    .getOrDefault(missionProfile.missionType),
            escalationPolicy =
                runCatching { SessionWorkerEscalationPolicy.valueOf(optString("escalation_policy")) }
                    .getOrDefault(missionProfile.escalationPolicy),
            joinExpectation =
                runCatching { SessionWorkerJoinExpectation.valueOf(optString("join_expectation")) }
                    .getOrDefault(missionProfile.joinExpectation),
            missionLabel = optString("mission_label").ifBlank { missionProfile.missionLabel },
            missionSummary = optString("mission_summary").ifBlank { missionProfile.missionSummary },
            task = task,
            entrySource = entrySource,
            status =
                runCatching { SessionWorkerStatus.valueOf(optString("status")) }
                    .getOrDefault(SessionWorkerStatus.QUEUED),
            priority = optInt("priority", 0),
            retryCount = optInt("retry_count", 0),
            maxRetries = optInt("max_retries", 2),
            nextEligibleAtMs = optLong("next_eligible_at_ms"),
            blockedReason = optString("blocked_reason"),
            joinPolicy = joinPolicy,
            summary = optString("summary"),
            completionStatus = optString("completion_status"),
            resultSummary = optString("result_summary"),
            source = optString("source"),
            leaseOwner = optString("lease_owner"),
            leaseToken = optString("lease_token"),
            leaseAcquiredAtMs = optLong("lease_acquired_at_ms"),
            leaseExpiresAtMs = optLong("lease_expires_at_ms"),
            createdAtMs = optLong("created_at_ms"),
            updatedAtMs = optLong("updated_at_ms"),
            lastAttemptAtMs = optLong("last_attempt_at_ms"),
            metadata = metadata,
        )
    }

    private fun SessionPlatformSnapshot.toWorkerStatus(): SessionWorkerStatus {
        if (pendingSafetySummary.isNotBlank()) {
            return SessionWorkerStatus.WAITING_APPROVAL
        }
        return when {
            bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.COMPLETED -> SessionWorkerStatus.COMPLETED
            bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.STOPPED -> SessionWorkerStatus.STOPPED
            bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.FAILED ||
                bridgeSnapshot.statusTerminalReason == AgentUiTerminalReason.MAX_TURNS_REACHED ||
                bridgeSnapshot.statusBlockedReason == AgentUiBlockedReason.ROUTING_FAILED ||
                bridgeSnapshot.statusBlockedReason == AgentUiBlockedReason.APP_UNAVAILABLE -> SessionWorkerStatus.FAILED
            bridgeSnapshot.statusTakeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL -> SessionWorkerStatus.WAITING_EXTERNAL
            bridgeSnapshot.statusCategory == AgentUiStatusCategory.TAKEOVER -> SessionWorkerStatus.PAUSED
            bridgeSnapshot.statusCategory == AgentUiStatusCategory.ACTIVE_EXECUTION ||
                bridgeSnapshot.statusBlockedReason == AgentUiBlockedReason.ROUTING -> SessionWorkerStatus.RUNNING
            else -> SessionWorkerStatus.QUEUED
        }
    }

    private fun SessionPlatformSnapshot.toWorkerSummary(): String =
        pendingSafetySummary.ifBlank {
            bridgeSnapshot.resultSummary.ifBlank {
                bridgeSnapshot.errorSummary.ifBlank {
                    bridgeSnapshot.takeoverSummary.ifBlank {
                        healthSummary.summary
                    }
                }
            }
        }

    private fun SessionPlatformSnapshot.toWorkerCompletionStatus(): String =
        when (toWorkerStatus()) {
            SessionWorkerStatus.COMPLETED,
            SessionWorkerStatus.FAILED,
            SessionWorkerStatus.STOPPED,
            -> toWorkerStatus().name.lowercase()

            else -> ""
        }

    private fun SessionPlatformSnapshot.toWorkerResultSummary(): String =
        bridgeSnapshot.resultSummary.ifBlank {
            bridgeSnapshot.errorSummary.ifBlank {
                pendingSafetySummary.ifBlank {
                    bridgeSnapshot.takeoverSummary
                }
            }
        }
}
