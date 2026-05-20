package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.BuildConfig

internal fun selectExecutionCandidate(
    snapshot: SessionExecutionSchedulerSnapshot,
    preferredPackageName: String = "",
    preferredSessionIds: Set<String> = emptySet(),
): SessionExecutionCandidate? =
    selectExecutionCandidates(
        snapshot = snapshot,
        preferredPackageName = preferredPackageName,
        preferredSessionIds = preferredSessionIds,
        limit = 1,
    ).firstOrNull()

internal fun selectExecutionCandidates(
    snapshot: SessionExecutionSchedulerSnapshot,
    preferredPackageName: String = "",
    preferredSessionIds: Set<String> = emptySet(),
    limit: Int,
    excludedSessionIds: Set<String> = emptySet(),
): List<SessionExecutionCandidate> {
    if (limit <= 0) return emptyList()
    val rankedCandidates =
        snapshot.candidates
            .asSequence()
            .filter { it.runnable }
            .filterNot { it.sessionId in excludedSessionIds }
            .sortedByDescending { candidate ->
                candidateSelectionScore(
                    candidate = candidate,
                    preferredPackageName = preferredPackageName,
                    preferredSessionIds = preferredSessionIds,
                    activeSessionId = snapshot.activeSessionId,
                )
            }.toList()
    if (rankedCandidates.isEmpty()) return emptyList()
    val selected = mutableListOf<SessionExecutionCandidate>()

    fun packageCompatible(candidate: SessionExecutionCandidate): Boolean =
        preferredPackageName.isBlank() ||
            candidate.targetPackageName.isBlank() ||
            candidate.targetPackageName == preferredPackageName ||
            candidate.sessionId in preferredSessionIds

    fun tryAdd(candidate: SessionExecutionCandidate?) {
        if (candidate == null) return
        if (candidate.sessionId.isBlank() || candidate.sessionId in excludedSessionIds) return
        if (candidate in selected) return
        if (!packageCompatible(candidate)) return
        selected += candidate
    }

    snapshot.dispatchPlan
        .asSequence()
        .mapNotNull { plan -> rankedCandidates.firstOrNull { it.sessionId == plan.sessionId } }
        .forEach(::tryAdd)
    rankedCandidates.forEach(::tryAdd)
    if (selected.size < limit) {
        rankedCandidates.forEach { candidate ->
            if (candidate !in selected && candidate.sessionId !in excludedSessionIds) {
                selected += candidate
            }
        }
    }
    return selected.take(limit)
}

internal fun buildExecutionDispatchPlan(
    candidates: List<SessionExecutionCandidate>,
    preferredPackageName: String,
    preferredSessionIds: Set<String>,
    activeSessionId: String,
    limit: Int,
    maxDispatchPlanItems: Int,
    excludedSessionIds: Set<String> = emptySet(),
): List<SessionExecutionDispatchPlanItem> {
    if (limit <= 0) return emptyList()
    val runnableCandidates = candidates.filter { it.runnable && it.sessionId !in excludedSessionIds }
    if (runnableCandidates.isEmpty()) return emptyList()
    val cappedLimit = limit.coerceAtMost(maxDispatchPlanItems)
    val ranked =
        runnableCandidates.sortedByDescending { candidate ->
            candidateSelectionScore(
                candidate = candidate,
                preferredPackageName = preferredPackageName,
                preferredSessionIds = preferredSessionIds,
                activeSessionId = activeSessionId,
            ) + lanePriorityBonus(candidate.lane)
        }
    val selected = mutableListOf<SessionExecutionCandidate>()
    val selectedOwners = mutableSetOf<String>()
    ranked.forEach { candidate ->
        if (selected.size >= cappedLimit) return@forEach
        if (candidate.ownerKey.isBlank() || selectedOwners.add(candidate.ownerKey)) {
            selected += candidate
        }
    }
    if (selected.size < cappedLimit) {
        ranked.forEach { candidate ->
            if (selected.size >= cappedLimit) return@forEach
            if (candidate !in selected) {
                selected += candidate
            }
        }
    }
    return selected.map { candidate ->
        SessionExecutionDispatchPlanItem(
            sessionId = candidate.sessionId,
            ownerKey = candidate.ownerKey,
            lane = candidate.lane,
            targetPackageName = candidate.targetPackageName,
            score =
                candidateSelectionScore(
                    candidate = candidate,
                    preferredPackageName = preferredPackageName,
                    preferredSessionIds = preferredSessionIds,
                    activeSessionId = activeSessionId,
                ) + lanePriorityBonus(candidate.lane),
            reason = "${candidate.source.name.lowercase()}:${candidate.lane}:${candidate.task.ifBlank { candidate.sessionId }}",
        )
    }
}

internal fun calculateExecutionParallelismBudget(
    candidates: List<SessionExecutionCandidate>,
    activeLeases: List<SessionExecutionLease>,
): Int {
    val runnableCount = candidates.count { it.runnable }
    val baseBudget =
        when {
            runnableCount >= 4 -> 3
            runnableCount >= 2 -> 2
            runnableCount >= 1 -> 1
            else -> 0
        }
    return maxOf(baseBudget, activeLeases.size).coerceAtLeast(1)
}

internal fun buildExecutionFairnessSummary(
    candidates: List<SessionExecutionCandidate>,
    observedPackageName: String,
    activeLeases: List<SessionExecutionLease>,
): String {
    val runnable = candidates.filter { it.runnable }
    val owners = runnable.map { it.ownerKey.ifBlank { it.sessionId } }.distinct().size
    val foregroundMatches =
        runnable.count { candidate ->
            observedPackageName.isNotBlank() && candidate.targetPackageName == observedPackageName
        }
    return buildString {
        append("owner_balanced")
        append(" | owners=").append(owners)
        append(" | runnable=").append(runnable.size)
        append(" | fg_match=").append(foregroundMatches)
        append(" | leases=").append(activeLeases.size)
        append(" | lease=").append(activeLeases.firstOrNull()?.sessionId?.takeLast(8) ?: "-")
    }
}

internal fun buildExecutionOwnerQueueSummary(
    candidates: List<SessionExecutionCandidate>,
    maxItems: Int,
): List<String> =
    candidates
        .groupBy { it.ownerKey.ifBlank { it.sessionId } }
        .entries
        .sortedByDescending { entry -> entry.value.count { candidate -> candidate.runnable } * 10 + entry.value.size }
        .take(maxItems)
        .map { (ownerKey, ownerCandidates) ->
            val runnable = ownerCandidates.count { it.runnable }
            val blocked = ownerCandidates.size - runnable
            val topLane = ownerCandidates.firstOrNull { it.runnable }?.lane ?: ownerCandidates.firstOrNull()?.lane.orEmpty()
            "$ownerKey | runnable=$runnable blocked=$blocked lane=${topLane.ifBlank { "-" }}"
        }

internal fun candidateSelectionScore(
    candidate: SessionExecutionCandidate,
    preferredPackageName: String,
    preferredSessionIds: Set<String>,
    activeSessionId: String,
): Long {
    var score = candidate.sortScore.toLong() * 1_000L + (candidate.updatedAtMs / 1_000L)
    if (candidate.sessionId == activeSessionId) {
        score += 600_000L
    }
    if (candidate.sessionId in preferredSessionIds) {
        score += 900_000L
    }
    if (preferredPackageName.isNotBlank() && candidate.targetPackageName == preferredPackageName) {
        score += 1_200_000L
    }
    if (candidate.ownerKey.isNotBlank()) {
        score += 80_000L
    }
    score += lanePriorityBonus(candidate.lane)
    return score
}

internal fun lanePriorityBonus(
    lane: String,
): Long =
    when (lane) {
        "approval_lane" -> 220_000L
        "follow_up_lane" -> 200_000L
        "foreground_match" -> 180_000L
        "active_runtime" -> 140_000L
        "child_resume" -> 110_000L
        "worker_lane" -> 90_000L
        else -> 50_000L
    }

internal fun SessionRuntimeState.toExecutionCandidate(
    graphNode: SessionGraphNode?,
    worker: SessionWorkerRecord?,
    taskOsSignal: com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsSessionSignal?,
    now: Long,
): SessionExecutionCandidate =
    buildExecutionCandidate(
        sessionId = session.sessionId,
        task = session.task,
        entrySource = session.entrySource,
        targetPackageName = session.targetPackageName,
        status = session.status,
        running = session.running,
        planning = session.planning,
        paused = session.paused,
        nextPlanEligibleAtMs = session.nextPlanEligibleAtMs,
        turns = session.turns,
        awaitingConfirmation = safety.awaitingConfirmation,
        updatedAtMs = updatedAtMs,
        graphNode = graphNode,
        worker = worker,
        taskOsSignal = taskOsSignal,
        activeRuntime = true,
        now = now,
    )

internal fun SessionResumeSnapshot.toExecutionCandidate(
    graphNode: SessionGraphNode?,
    worker: SessionWorkerRecord?,
    taskOsSignal: com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsSessionSignal?,
    now: Long,
): SessionExecutionCandidate =
    buildExecutionCandidate(
        sessionId = sessionId,
        task = task,
        entrySource = entrySource,
        targetPackageName = targetPackageName,
        status = status,
        running = running,
        planning = planning,
        paused = paused,
        nextPlanEligibleAtMs = nextPlanEligibleAtMs,
        turns = turns,
        awaitingConfirmation = safety.awaitingConfirmation,
        updatedAtMs = updatedAtMs,
        graphNode = graphNode,
        worker = worker,
        taskOsSignal = taskOsSignal,
        activeRuntime = false,
        now = now,
    )

internal fun buildExecutionCandidate(
    sessionId: String,
    task: String,
    entrySource: String,
    targetPackageName: String,
    status: String,
    running: Boolean,
    planning: Boolean,
    paused: Boolean,
    nextPlanEligibleAtMs: Long,
    turns: Int,
    awaitingConfirmation: Boolean,
    updatedAtMs: Long,
    graphNode: SessionGraphNode?,
    worker: SessionWorkerRecord?,
    taskOsSignal: com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsSessionSignal?,
    activeRuntime: Boolean,
    now: Long,
): SessionExecutionCandidate {
    val parentSessionId = graphNode?.parentSessionId.orEmpty().ifBlank { worker?.parentSessionId.orEmpty() }
    val rootSessionId =
        graphNode?.rootSessionId.orEmpty().ifBlank {
            worker?.rootSessionId.orEmpty().ifBlank { sessionId }
        }
    val coordinatorSessionId =
        graphNode?.coordinatorSessionId.orEmpty().ifBlank {
            worker?.coordinatorSessionId.orEmpty().ifBlank { rootSessionId }
        }
    val workerId = worker?.workerId.orEmpty().ifBlank { graphNode?.workerId.orEmpty() }
    val agentId = worker?.agentId.orEmpty().ifBlank { graphNode?.agentId.orEmpty() }
    val ownerKey = coordinatorSessionId.ifBlank { rootSessionId.ifBlank { sessionId } }
    val source =
        when {
            activeRuntime -> SessionExecutionCandidateSource.ACTIVE_RUNTIME
            workerId.isNotBlank() -> SessionExecutionCandidateSource.WORKER_SESSION
            parentSessionId.isNotBlank() -> SessionExecutionCandidateSource.RESUMABLE_CHILD
            else -> SessionExecutionCandidateSource.RESUMABLE_ROOT
        }
    val blockedReason =
        when {
            sessionId.isBlank() -> "missing_session_id"
            turns >= BuildConfig.AGENT_MAX_TURNS -> "max_turns_reached"
            AgentUiStatus.isTerminal(status) -> "terminal"
            awaitingConfirmation -> "awaiting_confirmation"
            resolveAgentUiStatusModel(status).isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL) -> "waiting_external"
            planning -> "planning"
            paused -> "paused"
            nextPlanEligibleAtMs > now -> "cooldown"
            !running && !statusAllowsResume(status) -> "inactive"
            else -> ""
        }
    val lane =
        when {
            taskOsSignal != null && taskOsSignal.approvalCount > 0 -> "approval_lane"
            taskOsSignal != null && taskOsSignal.overdueCount > 0 -> "follow_up_lane"
            activeRuntime -> "active_runtime"
            parentSessionId.isNotBlank() -> "child_resume"
            workerId.isNotBlank() -> "worker_lane"
            targetPackageName.isNotBlank() -> "package_resume"
            else -> "root_resume"
        }
    val priority =
        (worker?.priority ?: 0) +
            (taskOsSignal?.scoreBonus ?: 0) / 40 +
            when (source) {
                SessionExecutionCandidateSource.ACTIVE_RUNTIME -> 6
                SessionExecutionCandidateSource.WORKER_SESSION -> 5
                SessionExecutionCandidateSource.RESUMABLE_CHILD -> 4
                SessionExecutionCandidateSource.RESUMABLE_ROOT -> 3
            }
    val statusModel = resolveAgentUiStatusModel(status = status)
    val statusWeight =
        when {
            statusModel.isExecutionPhase(AgentUiExecutionPhase.RUNNING) -> 36
            statusModel.isExecutionPhase(AgentUiExecutionPhase.STARTING) -> 28
            statusModel.isExecutionPhase(AgentUiExecutionPhase.WAITING) -> 22
            statusModel.isExecutionPhase(AgentUiExecutionPhase.PLANNING) -> -12
            else -> 0
        }
    val sortScore =
        priority * 100 +
            statusWeight +
            if (workerId.isNotBlank()) 60 else 0 +
            if (parentSessionId.isNotBlank()) 24 else 0 -
            (worker?.depth ?: 0) * 4 +
            (taskOsSignal?.scoreBonus ?: 0)
    return SessionExecutionCandidate(
        sessionId = sessionId,
        rootSessionId = rootSessionId,
        parentSessionId = parentSessionId,
        coordinatorSessionId = coordinatorSessionId,
        workerId = workerId,
        agentId = agentId,
        task = task,
        entrySource = entrySource,
        targetPackageName = targetPackageName,
        status = status,
        source = source,
        ownerKey = ownerKey,
        lane = lane,
        priority = priority,
        runnable = blockedReason.isBlank(),
        blockedReason = blockedReason,
        sortScore = sortScore,
        updatedAtMs = maxOf(updatedAtMs, graphNode?.updatedAtMs ?: 0L, worker?.updatedAtMs ?: 0L),
    )
}

internal fun statusAllowsResume(
    status: String,
): Boolean {
    val statusModel = resolveAgentUiStatusModel(status = status)
    return statusModel.category == AgentUiStatusCategory.ACTIVE_EXECUTION &&
        (
            statusModel.executionPhase == AgentUiExecutionPhase.RUNNING ||
                statusModel.executionPhase == AgentUiExecutionPhase.STARTING ||
                statusModel.executionPhase == AgentUiExecutionPhase.WAITING ||
                statusModel.executionPhase == AgentUiExecutionPhase.PLANNING
        )
}
