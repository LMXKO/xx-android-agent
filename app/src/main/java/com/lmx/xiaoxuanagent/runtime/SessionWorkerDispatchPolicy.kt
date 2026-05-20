package com.lmx.xiaoxuanagent.runtime

internal data class SessionWorkerDispatchEvaluation(
    val worker: SessionWorkerRecord,
    val ownerKey: String,
    val score: Int,
    val lane: String,
    val reasons: List<String>,
    val queueAgeMinutes: Int,
    val starvationMinutes: Int,
)

internal object SessionWorkerDispatchPolicy {
    const val FAIRNESS_MODE = "coordinator_fair_starvation_aware"

    fun selectDispatchable(
        workers: List<SessionWorkerRecord>,
        limit: Int,
        maxConcurrentWorkers: Int,
        graphReader: (String) -> SessionGraphNode?,
    ): List<SessionWorkerDispatchEvaluation> {
        val activeCount = workers.count { it.status.isActiveForDispatch() }
        val availableSlots = (maxConcurrentWorkers - activeCount).coerceAtLeast(0)
        if (availableSlots <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val indexed = workers.associateBy(SessionWorkerRecord::workerId)
        val activeCoordinatorLoads =
            workers
                .filter { it.status.isActiveForDispatch() }
                .groupingBy { it.ownerKey() }
                .eachCount()
        val queuedCoordinatorLoads =
            workers
                .filter { it.status == SessionWorkerStatus.QUEUED || it.status == SessionWorkerStatus.DEFERRED }
                .groupingBy { it.ownerKey() }
                .eachCount()
        val oldestQueuedByOwner =
            workers
                .filter { it.status == SessionWorkerStatus.QUEUED || it.status == SessionWorkerStatus.DEFERRED }
                .groupBy { it.ownerKey() }
                .mapValues { (_, ownerWorkers) ->
                    ownerWorkers.minOfOrNull { worker ->
                        worker.lastAttemptAtMs
                            .takeIf { it > 0L }
                            ?: worker.nextEligibleAtMs.takeIf { it > 0L }
                            ?: worker.createdAtMs
                    } ?: now
                }
        val candidates =
            workers
                .asSequence()
                .filter { it.status == SessionWorkerStatus.QUEUED || it.status == SessionWorkerStatus.DEFERRED }
                .filter { !it.hasActiveLease(now) }
                .filter { it.nextEligibleAtMs <= 0L || it.nextEligibleAtMs <= now }
                .filter { worker -> worker.hasRunnableAncestorChain(indexed) }
                .map { worker ->
                    val parentWorker = indexed[worker.parentWorkerId]
                    val ownerKey = worker.ownerKey()
                    val coordinatorNode = ownerKey.takeIf { it.isNotBlank() }?.let(graphReader)
                    val rootNode =
                        worker.rootSessionId
                            .ifBlank { ownerKey }
                            .takeIf { it.isNotBlank() }
                            ?.let(graphReader)
                    val queueAnchorMs =
                        worker.lastAttemptAtMs
                            .takeIf { it > 0L }
                            ?: worker.nextEligibleAtMs.takeIf { it > 0L }
                            ?: worker.createdAtMs
                    val queueAgeMinutes = ((now - queueAnchorMs).coerceAtLeast(0L) / 60_000L).toInt().coerceAtMost(180)
                    val starvationMinutes =
                        ownerKey.takeIf { it.isNotBlank() }
                            ?.let { queuedOwner ->
                                ((now - (oldestQueuedByOwner[queuedOwner] ?: now)).coerceAtLeast(0L) / 60_000L).toInt().coerceAtMost(180)
                            } ?: queueAgeMinutes
                    val ownerActiveLoad = activeCoordinatorLoads[ownerKey] ?: 0
                    val ownerQueuedLoad = queuedCoordinatorLoads[ownerKey] ?: 0
                    val score =
                        buildList {
                            add(worker.priority * 100)
                            if (worker.status == SessionWorkerStatus.DEFERRED) add(20)
                            if (worker.requiresEscalationAttention()) add(80)
                            if (worker.escalationPolicy == SessionWorkerEscalationPolicy.SAFETY_CONFIRMATION) add(55)
                            if (worker.missionType == SessionWorkerMissionType.APPROVAL) add(40)
                            if (worker.missionType == SessionWorkerMissionType.RECOVERY) add(22)
                            if (parentWorker?.status == SessionWorkerStatus.WAITING_CHILDREN) add(60)
                            if ((coordinatorNode?.pendingApprovalCount ?: 0) > 0) add(90)
                            if ((coordinatorNode?.mailboxPendingCount ?: 0) > 0) add(35)
                            if ((rootNode?.pendingChildSessionIds?.isNotEmpty() == true)) add(25)
                            if (ownerActiveLoad <= 0 && ownerQueuedLoad > 0) add(15)
                            if (worker.retryCount > 0) add(-8 * worker.retryCount)
                            add(-5 * worker.depth)
                            add(-20 * ownerActiveLoad)
                            add((queueAgeMinutes / 2).coerceAtMost(30))
                            add((starvationMinutes / 2).coerceAtMost(40))
                            add((ownerQueuedLoad * 4).coerceAtMost(20))
                            rootNode?.descendantSessionCount?.takeIf { it > 0 }?.let {
                                add((it / 2).coerceAtMost(18))
                            }
                            if (worker.nextEligibleAtMs > 0L) {
                                add(((now - worker.nextEligibleAtMs).coerceAtLeast(0L) / 60_000L).toInt().coerceAtMost(12))
                            }
                        }.sum()
                    val lane =
                        when {
                            worker.requiresEscalationAttention() -> "escalation_unblock"
                            starvationMinutes >= 20 && ownerActiveLoad <= 0 -> "starvation_recovery"
                            (coordinatorNode?.pendingApprovalCount ?: 0) > 0 -> "approval_unblock"
                            (coordinatorNode?.mailboxPendingCount ?: 0) >= 4 -> "mailbox_unblock"
                            parentWorker?.status == SessionWorkerStatus.WAITING_CHILDREN -> "child_unblock"
                            worker.priority >= 80 -> "priority"
                            else -> "balanced"
                        }
                    val reasons =
                        buildList {
                            add("priority=${worker.priority}")
                            add("mission=${worker.missionType.name.lowercase()}")
                            add("join=${worker.joinExpectation.name.lowercase()}")
                            worker.escalationPolicy.takeIf { it != SessionWorkerEscalationPolicy.NONE }?.let {
                                add("escalation=${it.name.lowercase()}")
                            }
                            ownerKey.takeIf { it.isNotBlank() }?.let { add("owner=$it") }
                            if (worker.status == SessionWorkerStatus.DEFERRED) add("deferred_retry=${worker.retryCount}")
                            if (parentWorker?.status == SessionWorkerStatus.WAITING_CHILDREN) add("parent_waiting_children")
                            coordinatorNode?.pendingApprovalCount?.takeIf { it > 0 }?.let { add("approvals=$it") }
                            coordinatorNode?.mailboxPendingCount?.takeIf { it > 0 }?.let { add("mailbox=$it") }
                            if (queueAgeMinutes > 0) add("queue_age_min=$queueAgeMinutes")
                            if (starvationMinutes > 0) add("starvation_min=$starvationMinutes")
                            ownerQueuedLoad.takeIf { it > 0 }?.let { add("owner_queue=$it") }
                            ownerActiveLoad.takeIf { it > 0 }?.let { add("active_owner_load=$it") }
                            worker.blockedReason.takeIf { it.isNotBlank() }?.let { add("blocked=$it") }
                        }
                    SessionWorkerDispatchEvaluation(
                        worker = worker,
                        ownerKey = ownerKey,
                        score = score,
                        lane = lane,
                        reasons = reasons,
                        queueAgeMinutes = queueAgeMinutes,
                        starvationMinutes = starvationMinutes,
                    )
                }.sortedWith(
                    compareByDescending<SessionWorkerDispatchEvaluation> { it.score }
                        .thenByDescending { it.starvationMinutes }
                        .thenByDescending { it.queueAgeMinutes }
                        .thenByDescending { it.worker.priority }
                        .thenBy { if (it.worker.nextEligibleAtMs <= 0L) 0L else it.worker.nextEligibleAtMs }
                        .thenBy { it.worker.depth }
                        .thenBy { it.worker.createdAtMs },
                ).toList()
        if (candidates.isEmpty()) return emptyList()
        val targetCount = limit.coerceAtMost(availableSlots)
        val selected = mutableListOf<SessionWorkerDispatchEvaluation>()
        val selectedOwners = mutableSetOf<String>()
        candidates
            .sortedWith(
                compareByDescending<SessionWorkerDispatchEvaluation> { ownerUrgencyScore(it) }
                    .thenByDescending { it.score },
            ).forEach { candidate ->
            if (selected.size >= targetCount) return@forEach
            if (candidate.ownerKey.isBlank() || selectedOwners.add(candidate.ownerKey)) {
                selected += candidate
            }
        }
        if (selected.size < targetCount) {
            candidates.forEach { candidate ->
                if (selected.size >= targetCount) return@forEach
                if (candidate !in selected) {
                    selected += candidate
                }
            }
        }
        return selected
    }

    fun deriveMailboxPriorityMode(
        readyWorkers: List<SessionWorkerDispatchEvaluation>,
    ): String =
        when {
            readyWorkers.any { it.lane == "escalation_unblock" } -> "approval_first"
            readyWorkers.any { it.lane == "approval_unblock" } -> "approval_first"
            readyWorkers.any { it.lane == "child_unblock" } -> "reply_chain_first"
            readyWorkers.any { it.lane == "starvation_recovery" } -> "stale_first"
            readyWorkers.any { it.lane == "mailbox_unblock" } -> "mailbox_first"
            else -> "balanced"
        }

    fun blockedCoordinatorIds(
        workers: List<SessionWorkerRecord>,
    ): List<String> =
        workers
            .filter { it.status == SessionWorkerStatus.WAITING_CHILDREN || it.status == SessionWorkerStatus.WAITING_APPROVAL }
            .mapNotNull { it.coordinatorSessionId.ifBlank { it.rootSessionId }.takeIf(String::isNotBlank) }
            .distinct()

    fun coordinatorLoadSummary(
        activeWorkers: List<SessionWorkerRecord>,
    ): List<String> =
        activeWorkers
            .groupBy { it.ownerKey() }
            .mapNotNull { (ownerKey, ownerWorkers) ->
                ownerKey.takeIf { it.isNotBlank() }?.let { "$it:${ownerWorkers.size}" }
            }.sorted()

    private fun SessionWorkerRecord.hasRunnableAncestorChain(
        indexed: Map<String, SessionWorkerRecord>,
    ): Boolean =
        generateSequence(parentWorkerId.takeIf { it.isNotBlank() }) { parentId ->
            indexed[parentId]?.parentWorkerId?.takeIf { it.isNotBlank() }
        }.mapNotNull(indexed::get)
            .none { ancestor ->
                ancestor.status == SessionWorkerStatus.FAILED || ancestor.status == SessionWorkerStatus.STOPPED
            }

    private fun SessionWorkerRecord.ownerKey(): String =
        coordinatorSessionId.ifBlank { rootSessionId.ifBlank { parentSessionId } }

    private fun SessionWorkerRecord.hasActiveLease(
        now: Long,
    ): Boolean = leaseToken.isNotBlank() && leaseExpiresAtMs > now

    private fun SessionWorkerStatus.isActiveForDispatch(): Boolean =
        this == SessionWorkerStatus.RUNNING ||
            this == SessionWorkerStatus.WAITING_EXTERNAL ||
            this == SessionWorkerStatus.WAITING_APPROVAL ||
            this == SessionWorkerStatus.PAUSED

    private fun ownerUrgencyScore(
        evaluation: SessionWorkerDispatchEvaluation,
    ): Int {
        val laneBase =
            when (evaluation.lane) {
                "escalation_unblock" -> 130
                "approval_unblock" -> 120
                "starvation_recovery" -> 110
                "mailbox_unblock" -> 95
                "child_unblock" -> 90
                "priority" -> 80
                else -> 60
            }
        return laneBase + evaluation.starvationMinutes + (evaluation.queueAgeMinutes / 2)
    }
}
