package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import java.io.File
import org.json.JSONObject

enum class SessionExecutionCandidateSource {
    ACTIVE_RUNTIME,
    WORKER_SESSION,
    RESUMABLE_CHILD,
    RESUMABLE_ROOT,
}

data class SessionExecutionLease(
    val leaseId: String = "",
    val sessionId: String = "",
    val owner: String = "",
    val reason: String = "",
    val acquiredAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
)

data class SessionExecutionCandidate(
    val sessionId: String,
    val rootSessionId: String = "",
    val parentSessionId: String = "",
    val coordinatorSessionId: String = "",
    val workerId: String = "",
    val agentId: String = "",
    val task: String = "",
    val entrySource: String = "",
    val targetPackageName: String = "",
    val status: String = "",
    val source: SessionExecutionCandidateSource = SessionExecutionCandidateSource.RESUMABLE_ROOT,
    val ownerKey: String = "",
    val lane: String = "",
    val priority: Int = 0,
    val runnable: Boolean = false,
    val blockedReason: String = "",
    val sortScore: Int = 0,
    val updatedAtMs: Long = 0L,
)

data class SessionExecutionDispatchPlanItem(
    val sessionId: String,
    val ownerKey: String = "",
    val lane: String = "",
    val targetPackageName: String = "",
    val score: Long = 0L,
    val reason: String = "",
)

data class SessionExecutionSchedulerSnapshot(
    val activeSessionId: String = "",
    val lease: SessionExecutionLease? = null,
    val activeLeases: List<SessionExecutionLease> = emptyList(),
    val lastDispatchSessionId: String = "",
    val lastDispatchReason: String = "",
    val lastObservedPackageName: String = "",
    val parallelismBudget: Int = 1,
    val fairnessSummary: String = "",
    val ownerQueueSummary: List<String> = emptyList(),
    val runnableSessionIds: List<String> = emptyList(),
    val blockedSessionIds: List<String> = emptyList(),
    val runnableWorkerIds: List<String> = emptyList(),
    val dispatchPlan: List<SessionExecutionDispatchPlanItem> = emptyList(),
    val candidates: List<SessionExecutionCandidate> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class SessionExecutionDispatch(
    val directive: PlanningDirective = PlanningDirective.NoOp,
    val selectedSessionId: String = "",
    val lease: SessionExecutionLease? = null,
    val logMessages: List<String> = emptyList(),
)

data class SessionExecutionClaim(
    val sessionId: String = "",
    val ownerKey: String = "",
    val lane: String = "",
    val targetPackageName: String = "",
    val lease: SessionExecutionLease? = null,
)

data class SessionExecutionClaimBatch(
    val claims: List<SessionExecutionClaim> = emptyList(),
    val logMessages: List<String> = emptyList(),
)

object SessionExecutionCoordinatorStore {
    private const val COORDINATOR_DIR = "runtime"
    private const val COORDINATOR_FILE = "session_execution_scheduler.json"
    private const val MAX_CANDIDATES = 48
    private const val LEASE_TTL_MS = 90_000L
    private const val MAX_DISPATCH_PLAN_ITEMS = 4
    private const val MAX_ACTIVE_LEASES = 4
    private const val WAITING_MISMATCH_LOG_THROTTLE_MS = 3_000L

    private val lock = Any()
    private var hydrated = false
    private var snapshot = SessionExecutionSchedulerSnapshot()
    private var lastWaitingMismatchLogKey: String = ""
    private var lastWaitingMismatchLogAtMs: Long = 0L

    fun sync(
        reason: String = "manual_sync",
    ): SessionExecutionSchedulerSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot = buildSnapshotUnlocked(reason = reason)
            persistUnlocked()
            snapshot
        }

    fun readSnapshot(): SessionExecutionSchedulerSnapshot = sync(reason = "read_snapshot")

    fun readNextRunnableSessionId(
        preferredPackageName: String = "",
        preferredSessionIds: Set<String> = emptySet(),
    ): String =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = buildSnapshotUnlocked(reason = "read_next_runnable")
            snapshot = current
            persistUnlocked()
            selectExecutionCandidate(
                snapshot = current,
                preferredPackageName = preferredPackageName,
                preferredSessionIds = preferredSessionIds,
            )?.sessionId.orEmpty()
        }

    fun tryBootstrapNextRunnableSession(
        preferredPackageName: String = "",
        preferredSessionIds: Set<String> = emptySet(),
        reason: String = "bootstrap_next_runnable",
    ): String {
        val selected =
            synchronized(lock) {
                hydrateIfNeededUnlocked()
                val current = buildSnapshotUnlocked(reason = reason)
                snapshot = current
                persistUnlocked()
                selectExecutionCandidate(
                    snapshot = current,
                    preferredPackageName = preferredPackageName,
                    preferredSessionIds = preferredSessionIds,
                )
            } ?: return ""
        if (!ensureSelectedSessionActive(selected, reason)) {
            return ""
        }
        synchronized(lock) {
            snapshot = buildSnapshotUnlocked(reason = "bootstrapped_${selected.sessionId}")
            persistUnlocked()
            return snapshot.activeSessionId.takeIf { it == selected.sessionId }.orEmpty()
        }
    }

    fun acquirePlanningDispatch(
        indexedObservation: IndexedScreenObservation,
        owner: String,
    ): SessionExecutionDispatch {
        val batch = acquirePlanningClaims(indexedObservation = indexedObservation, owner = owner, limit = 1)
        val claim =
            batch.claims.firstOrNull()
                ?: return SessionExecutionDispatch(logMessages = batch.logMessages)
        val selected =
            synchronized(lock) {
                hydrateIfNeededUnlocked()
                buildSnapshotUnlocked(
                    reason = "acquire_planning_dispatch_selected",
                    observedPackageName = indexedObservation.observation.packageName,
                ).candidates.firstOrNull { candidate -> candidate.sessionId == claim.sessionId }
            } ?: return SessionExecutionDispatch(logMessages = batch.logMessages)
        if (!ensureSelectedSessionActive(selected, reason = "observation_dispatch")) {
            releaseLease(owner = owner, sessionId = selected.sessionId, reason = "observation_dispatch_restore_failed")
            synchronized(lock) {
                snapshot = buildSnapshotUnlocked(reason = "observation_dispatch_restore_failed")
                persistUnlocked()
            }
            return SessionExecutionDispatch(
                logMessages = batch.logMessages + "execution scheduler 恢复会话失败: ${selected.sessionId}",
            )
        }

        if (!SessionRuntime.handleExternalWaitObservation(indexedObservation)) {
            releaseLease(owner = owner, sessionId = selected.sessionId, reason = "external_wait_gate")
            return SessionExecutionDispatch(
                logMessages = batch.logMessages,
                selectedSessionId = selected.sessionId,
            )
        }

        val directive = SessionRuntime.Planning.acquirePlanningDirective(indexedObservation)
        return when (directive) {
            is PlanningDirective.ExecuteTurn ->
                SessionExecutionDispatch(
                    directive = directive,
                    selectedSessionId = selected.sessionId,
                    lease = claim.lease,
                    logMessages = batch.logMessages,
                )

            PlanningDirective.NoOp -> {
                releaseLease(owner = owner, sessionId = selected.sessionId, reason = "planning_directive_idle")
                SessionExecutionDispatch(
                    directive = directive,
                    selectedSessionId = selected.sessionId,
                    logMessages = batch.logMessages,
                )
            }

            PlanningDirective.MaxTurnsReached -> {
                releaseLease(owner = owner, sessionId = selected.sessionId, reason = "planning_directive_max_turns")
                SessionExecutionDispatch(
                    directive = directive,
                    selectedSessionId = selected.sessionId,
                    logMessages = batch.logMessages,
                )
            }
        }
    }

    fun acquirePlanningClaims(
        indexedObservation: IndexedScreenObservation,
        owner: String,
        limit: Int = 0,
    ): SessionExecutionClaimBatch {
        val observation = indexedObservation.observation
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current =
                buildSnapshotUnlocked(
                    reason = "acquire_planning_claims",
                    observedPackageName = observation.packageName,
                )
            val availableSlots = (current.parallelismBudget - current.activeLeases.size).coerceAtLeast(0)
            if (availableSlots <= 0) {
                snapshot = current
                persistUnlocked()
                return@synchronized SessionExecutionClaimBatch(
                    logMessages =
                        listOf(
                            "execution scheduler claim busy: active_leases=${current.activeLeases.size}/${current.parallelismBudget}.",
                        ),
                )
            }
            val selected =
                selectExecutionCandidates(
                    snapshot = current,
                    preferredPackageName = observation.packageName,
                    limit = minOf(limit.takeIf { it > 0 } ?: availableSlots, availableSlots),
                    excludedSessionIds = current.activeLeases.map { it.sessionId }.toSet(),
                )
            if (selected.isEmpty()) {
                snapshot = current
                persistUnlocked()
                return@synchronized SessionExecutionClaimBatch(
                    logMessages =
                        listOfNotNull(
                            current.activeSessionId.takeIf { it.isNotBlank() && observation.packageName.isNotBlank() }?.let {
                                val active = current.candidates.firstOrNull { candidate -> candidate.sessionId == it }
                                if (active != null && active.targetPackageName != observation.packageName) {
                                    waitingMismatchLogMessage(
                                        foregroundPackageName = observation.packageName,
                                        targetPackageName = active.targetPackageName,
                                        sessionId = active.sessionId,
                                    )
                                } else {
                                    null
                                }
                            },
                        ),
                )
            }
            val newLeases =
                selected.map { candidate ->
                    createLease(
                        sessionId = candidate.sessionId,
                        owner = owner,
                        reason = "planning_claim",
                    )
                }
            val mergedLeases =
                (current.activeLeases + newLeases)
                    .filter { it.sessionId.isNotBlank() }
                    .distinctBy { it.sessionId }
                    .take(MAX_ACTIVE_LEASES)
            snapshot =
                current.copy(
                    lease = mergedLeases.firstOrNull(),
                    activeLeases = mergedLeases,
                    lastDispatchSessionId = selected.lastOrNull()?.sessionId.orEmpty().ifBlank { current.lastDispatchSessionId },
                    lastDispatchReason = if (selected.size >= 2) "planning_claim_batch" else "planning_claim",
                    lastObservedPackageName = observation.packageName,
                )
            persistUnlocked()
            SessionExecutionClaimBatch(
                claims =
                    selected.zip(newLeases).map { (candidate, lease) ->
                        SessionExecutionClaim(
                            sessionId = candidate.sessionId,
                            ownerKey = candidate.ownerKey,
                            lane = candidate.lane,
                            targetPackageName = candidate.targetPackageName,
                            lease = lease,
                        )
                    },
                logMessages =
                    buildList {
                        if (selected.size >= 2) {
                            add("execution scheduler batch claim=${selected.size} parallelism=${snapshot.parallelismBudget}.")
                        }
                        selected.forEach { candidate ->
                            add(
                                "execution scheduler claim 会话: ${candidate.sessionId} | source=${candidate.source.name.lowercase()} | lane=${candidate.lane} | task=${candidate.task.ifBlank { candidate.sessionId }}",
                            )
                        }
                    },
            )
        }
    }

    private fun waitingMismatchLogMessage(
        foregroundPackageName: String,
        targetPackageName: String,
        sessionId: String,
    ): String? {
        val now = System.currentTimeMillis()
        val key = "$foregroundPackageName|$targetPackageName|$sessionId"
        if (key == lastWaitingMismatchLogKey && now - lastWaitingMismatchLogAtMs < WAITING_MISMATCH_LOG_THROTTLE_MS) {
            return null
        }
        lastWaitingMismatchLogKey = key
        lastWaitingMismatchLogAtMs = now
        return "execution scheduler: 当前前台包=$foregroundPackageName，活跃会话仍等待 ${targetPackageName.ifBlank { "-" }}。"
    }

    fun releaseLease(
        owner: String,
        sessionId: String = "",
        reason: String = "release_lease",
    ) {
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val retainedLeases =
                snapshot.activeLeases
                    .ifEmpty { listOfNotNull(snapshot.lease) }
                    .filterNot { lease ->
                        val ownerMatches = owner.isBlank() || lease.owner.isBlank() || lease.owner == owner
                        val sessionMatches = sessionId.isBlank() || lease.sessionId.isBlank() || lease.sessionId == sessionId
                        ownerMatches && sessionMatches
                    }
            if (retainedLeases.size == snapshot.activeLeases.ifEmpty { listOfNotNull(snapshot.lease) }.size) {
                return
            }
            snapshot =
                buildSnapshotUnlocked(reason = reason).copy(
                    lease = retainedLeases.firstOrNull(),
                    activeLeases = retainedLeases,
                    lastDispatchSessionId = sessionId.ifBlank { snapshot.lastDispatchSessionId },
                    lastDispatchReason = reason,
                )
            persistUnlocked()
        }
    }

    fun exportJson(): JSONObject = readSnapshot().toCoordinatorJson()

    fun importJson(
        json: JSONObject?,
    ) {
        if (json == null || json.length() <= 0) return
        synchronized(lock) {
            snapshot = json.toCoordinatorSchedulerSnapshot()
            hydrated = true
            persistUnlocked()
        }
    }

    private fun ensureSelectedSessionActive(
        candidate: SessionExecutionCandidate,
        reason: String,
    ): Boolean {
        val currentSession = SessionRuntime.State.runtimeState().session
        if (currentSession.sessionId == candidate.sessionId && candidate.sessionId.isNotBlank()) {
            if (SessionRuntime.shouldAutoContinueBootstrappedSession()) {
                SessionRuntime.continueAgent()
            }
            return true
        }
        if (!SessionRuntime.bootstrapFromResumeSnapshot(candidate.sessionId)) {
            PlatformTraceStore.record(
                category = "execution_scheduler_restore_failed",
                sessionId = candidate.sessionId,
                summary = "$reason | restore failed",
            )
            return false
        }
        // 捕获自动拉起 directive：必须在 continueAgent 前读，因为 continue 会改写
        // lastTransition，使 resolveBootstrapAutoLaunchDirective 失效。directive 自带
        // gating（仅 bootstrap_from_resume_snapshot + paused/waiting_external + 未待确认时非空）。
        val launchDirective = SessionRuntime.resolveBootstrapAutoLaunchDirective()
        if (SessionRuntime.shouldAutoContinueBootstrappedSession()) {
            SessionRuntime.continueAgent()
        }
        bringBootstrappedTargetAppToForeground(launchDirective, candidate.sessionId, reason)
        PlatformTraceStore.record(
            category = "execution_scheduler_restore",
            sessionId = candidate.sessionId,
            summary = "$reason | restored ${candidate.source.name.lowercase()}",
        )
        return true
    }

    /**
     * 后台恢复成功后把目标 App 拉到前台，让无障碍事件重新驱动主回路（修复"半自动续跑"）。
     * 仅当 [SessionRuntime.resolveBootstrapAutoLaunchDirective] 返回非空（即刚从 resume 快照
     * 恢复、会话处于 paused/waiting_external 且未待确认）时才拉起。
     */
    private fun bringBootstrappedTargetAppToForeground(
        launchDirective: TargetAppLaunchDirective?,
        sessionId: String,
        reason: String,
    ) {
        val packageName = launchDirective?.targetPackageName?.takeIf { it.isNotBlank() } ?: return
        val launched = SessionTargetAppLauncher.launch(packageName, "$reason | ${launchDirective.reason}")
        PlatformTraceStore.record(
            category =
                if (launched) {
                    "execution_scheduler_target_app_launch"
                } else {
                    "execution_scheduler_target_app_launch_unavailable"
                },
            sessionId = sessionId,
            summary = "$reason | pkg=$packageName launched=$launched",
        )
    }

    private fun createLease(
        sessionId: String,
        owner: String,
        reason: String,
    ): SessionExecutionLease {
        val now = System.currentTimeMillis()
        return SessionExecutionLease(
            leaseId = "lease_${now}_${sessionId.hashCode().toUInt().toString(16)}",
            sessionId = sessionId,
            owner = owner,
            reason = reason,
            acquiredAtMs = now,
            expiresAtMs = now + LEASE_TTL_MS,
        )
    }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = coordinatorFile() ?: return
        if (!file.exists()) return
        runCatching {
            snapshot = JSONObject(file.readText()).toCoordinatorSchedulerSnapshot()
        }
    }

    private fun buildSnapshotUnlocked(
        reason: String,
        observedPackageName: String = snapshot.lastObservedPackageName,
    ): SessionExecutionSchedulerSnapshot {
        val now = System.currentTimeMillis()
        val activeSessionId = SessionRuntimeStore.activeSessionId()
        val loadedRuntimeStates = SessionRuntimeStore.readLoadedStates(limit = MAX_CANDIDATES)
        val workerRecords = SessionWorkerStore.readAll(limit = 80)
        val proactiveTasks = SessionPlatformFacade.readProactiveTasks(limit = MAX_CANDIDATES * 3)
        val taskOsSignals =
            com.lmx.xiaoxuanagent.assistantos.AssistantTaskOsStore.deriveSessionSignals(
                tasks = proactiveTasks,
                activeSessionId = activeSessionId,
                nowMs = now,
            )
        val workerBySessionId =
            workerRecords
                .filter { it.sessionId.isNotBlank() }
                .associateBy { it.sessionId }
        val graphNodes = SessionSessionGraphStore.readAll(limit = MAX_CANDIDATES * 2)
        val graphBySessionId = graphNodes.associateBy { it.sessionId }
        val candidates =
            buildList {
                loadedRuntimeStates.forEach { runtimeState ->
                    runtimeState.session.sessionId.takeIf { it.isNotBlank() }?.let {
                        add(
                            runtimeState.toExecutionCandidate(
                                graphNode = graphBySessionId[it],
                                worker = workerBySessionId[it],
                                taskOsSignal = taskOsSignals[it],
                                now = now,
                            ),
                        )
                    }
                }
                val loadedSessionIds = loadedRuntimeStates.map { it.session.sessionId }.filter { it.isNotBlank() }.toSet()
                SessionResumeStore.readResumableSnapshots(limit = MAX_CANDIDATES * 2)
                    .filter { it.sessionId.isNotBlank() && it.sessionId !in loadedSessionIds }
                    .forEach { snapshot ->
                    add(
                        snapshot.toExecutionCandidate(
                            graphNode = graphBySessionId[snapshot.sessionId],
                            worker = workerBySessionId[snapshot.sessionId],
                            taskOsSignal = taskOsSignals[snapshot.sessionId],
                            now = now,
                        ),
                    )
                    }
            }.distinctBy { it.sessionId }
                .sortedWith(
                    compareByDescending<SessionExecutionCandidate> { it.sortScore }
                        .thenByDescending { it.updatedAtMs }
                        .thenBy { it.sessionId },
                )
                .take(MAX_CANDIDATES)
        val runnableWorkerIds = SessionWorkerStore.readDispatchableWorkers(limit = 8).map { it.workerId }
        val effectiveLeases =
            snapshot.activeLeases
                .ifEmpty { listOfNotNull(snapshot.lease) }
                .filterNot { it.isExpired(now) }
                .filter { lease -> candidates.any { candidate -> candidate.sessionId == lease.sessionId } }
                .distinctBy { it.sessionId }
                .take(MAX_ACTIVE_LEASES)
        return SessionExecutionSchedulerSnapshot(
            activeSessionId = activeSessionId,
            lease = effectiveLeases.firstOrNull(),
            activeLeases = effectiveLeases,
            lastDispatchSessionId = snapshot.lastDispatchSessionId,
            lastDispatchReason = reason.ifBlank { snapshot.lastDispatchReason },
            lastObservedPackageName = observedPackageName,
            parallelismBudget = calculateExecutionParallelismBudget(candidates, effectiveLeases),
            fairnessSummary = buildExecutionFairnessSummary(candidates, observedPackageName, effectiveLeases),
            ownerQueueSummary = buildExecutionOwnerQueueSummary(candidates, maxItems = MAX_DISPATCH_PLAN_ITEMS),
            runnableSessionIds = candidates.filter { it.runnable }.map { it.sessionId },
            blockedSessionIds = candidates.filter { !it.runnable }.map { it.sessionId },
            runnableWorkerIds = runnableWorkerIds,
            dispatchPlan =
                buildExecutionDispatchPlan(
                    candidates = candidates,
                    preferredPackageName = observedPackageName,
                    preferredSessionIds = emptySet(),
                    activeSessionId = activeSessionId,
                    limit = calculateExecutionParallelismBudget(candidates, effectiveLeases),
                    maxDispatchPlanItems = MAX_DISPATCH_PLAN_ITEMS,
                    excludedSessionIds = effectiveLeases.map { it.sessionId }.toSet(),
                ),
            candidates = candidates,
            updatedAtMs = now,
        )
    }

    private fun coordinatorFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, COORDINATOR_DIR), COORDINATOR_FILE)
    }

    private fun persistUnlocked() {
        val file = coordinatorFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toCoordinatorJson().toString(2))
    }
}
