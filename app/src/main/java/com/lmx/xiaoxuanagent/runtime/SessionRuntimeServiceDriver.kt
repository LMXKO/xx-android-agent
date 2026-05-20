package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

data class SessionRuntimeClaimExecutionResult(
    val releaseReason: String = "service_turn_finished",
)

interface SessionExecutionSchedulerGateway {
    fun readParallelismBudget(): Int

    fun acquirePlanningClaims(
        indexedObservation: IndexedScreenObservation,
        owner: String,
        limit: Int,
    ): SessionExecutionClaimBatch

    fun releaseLease(
        owner: String,
        sessionId: String = "",
        reason: String = "release_lease",
    )
}

object DefaultSessionExecutionSchedulerGateway : SessionExecutionSchedulerGateway {
    override fun readParallelismBudget(): Int = SessionExecutionCoordinatorStore.readSnapshot().parallelismBudget

    override fun acquirePlanningClaims(
        indexedObservation: IndexedScreenObservation,
        owner: String,
        limit: Int,
    ): SessionExecutionClaimBatch =
        SessionExecutionCoordinatorStore.acquirePlanningClaims(
            indexedObservation = indexedObservation,
            owner = owner,
            limit = limit,
        )

    override fun releaseLease(
        owner: String,
        sessionId: String,
        reason: String,
    ) {
        SessionExecutionCoordinatorStore.releaseLease(
            owner = owner,
            sessionId = sessionId,
            reason = reason,
        )
    }
}

interface SessionRuntimeDispatchExecutor {
    suspend fun executeClaim(
        claim: SessionExecutionClaim,
        seedObservation: IndexedScreenObservation,
        dependencies: SessionRuntimeTurnDependencies,
    ): SessionRuntimeClaimExecutionResult
}

object DefaultSessionRuntimeDispatchExecutor : SessionRuntimeDispatchExecutor {
    override suspend fun executeClaim(
        claim: SessionExecutionClaim,
        seedObservation: IndexedScreenObservation,
        dependencies: SessionRuntimeTurnDependencies,
    ): SessionRuntimeClaimExecutionResult {
        val currentObservation = dependencies.observeCurrentScreen() ?: seedObservation
        if (
            SessionExecutionCoordinatorStore.readSnapshot().candidates.none { candidate ->
                candidate.sessionId == claim.sessionId
            }
        ) {
            dependencies.logLine("execution scheduler claim 丢失: ${claim.sessionId}")
            return SessionRuntimeClaimExecutionResult(releaseReason = "service_claim_missing")
        }
        if (!SessionRuntime.bootstrapFromResumeSnapshot(claim.sessionId)) {
            dependencies.logLine("execution scheduler 恢复会话失败: ${claim.sessionId}")
            return SessionRuntimeClaimExecutionResult(releaseReason = "service_restore_failed")
        }
        if (SessionRuntime.shouldAutoContinueBootstrappedSession()) {
            SessionRuntime.continueAgent()
        }
        if (!SessionRuntime.handleExternalWaitObservation(currentObservation)) {
            return SessionRuntimeClaimExecutionResult(releaseReason = "external_wait_gate")
        }
        return when (val directive = SessionRuntime.acquirePlanningDirective(currentObservation)) {
            is PlanningDirective.ExecuteTurn -> {
                if (directive.logMessage.isNotBlank()) {
                    dependencies.logLine(directive.logMessage)
                }
                SessionRuntimeTurnOrchestrator.runPlanningTurn(
                    planningContext = directive.context,
                    dependencies = dependencies,
                )
                SessionRuntimeClaimExecutionResult(releaseReason = "service_turn_finished")
            }

            PlanningDirective.NoOp -> SessionRuntimeClaimExecutionResult(releaseReason = "planning_directive_idle")

            PlanningDirective.MaxTurnsReached -> {
                SessionRuntime.handleMaxTurnsReached()
                SessionRuntimeClaimExecutionResult(releaseReason = "service_max_turns")
            }
        }
    }
}

class SessionRuntimeServiceDriver(
    private val owner: String,
    private val serviceScope: CoroutineScope,
    private val dependencies: SessionRuntimeTurnDependencies,
    private val schedulerGateway: SessionExecutionSchedulerGateway = DefaultSessionExecutionSchedulerGateway,
    private val dispatchExecutor: SessionRuntimeDispatchExecutor = DefaultSessionRuntimeDispatchExecutor,
) {
    @Volatile
    private var lastIgnoredObservationLogKey: String = ""
    @Volatile
    private var lastIgnoredObservationLogAtMs: Long = 0L
    private val coordinationLock = Any()
    private val activeJobs = LinkedHashMap<String, Job>()
    private var dispatchPumpJob: Job? = null
    private var latestObservation: IndexedScreenObservation? = null

    fun onObservation(
        indexedObservation: IndexedScreenObservation,
    ) {
        if (shouldIgnoreObservation(indexedObservation)) {
            synchronized(coordinationLock) {
                latestObservation = null
            }
            return
        }
        synchronized(coordinationLock) {
            latestObservation = indexedObservation
            launchDispatchPumpIfNeededUnlocked()
        }
    }

    private fun shouldIgnoreObservation(
        indexedObservation: IndexedScreenObservation,
    ): Boolean {
        val assistantPackageName =
            runCatching {
                AppRuntimeContext.get()?.packageName.orEmpty()
            }.getOrDefault("")
        val foregroundPackageName = indexedObservation.observation.packageName
        if (
            assistantPackageName.isBlank() ||
            foregroundPackageName.isBlank() ||
            foregroundPackageName != assistantPackageName
        ) {
            return false
        }
        val session = SessionRuntimeStore.session()
        val statusModel = resolveAgentUiStatusModel(status = session.status, snapshot = session.statusSnapshot)
        if (!session.running || !statusModel.isTakeoverReason(AgentUiTakeoverReason.WAITING_EXTERNAL)) {
            return false
        }
        val targetPackageName = session.targetPackageName
        if (
            targetPackageName.isBlank() ||
            targetPackageName == assistantPackageName ||
            targetPackageName == foregroundPackageName
        ) {
            return false
        }
        maybeLogIgnoredObservation(
            logKey = "$foregroundPackageName|$targetPackageName|${session.sessionId}",
            message = "execution scheduler ignore assistant shell observation: foreground=$foregroundPackageName waiting_target=$targetPackageName",
        )
        return true
    }

    private fun maybeLogIgnoredObservation(
        logKey: String,
        message: String,
    ) {
        val now = System.currentTimeMillis()
        if (logKey == lastIgnoredObservationLogKey && now - lastIgnoredObservationLogAtMs < 3_000L) {
            return
        }
        lastIgnoredObservationLogKey = logKey
        lastIgnoredObservationLogAtMs = now
        dependencies.logLine(message)
    }

    private fun launchDispatchPumpIfNeededUnlocked() {
        if (dispatchPumpJob?.isActive == true) return
        dispatchPumpJob =
            serviceScope.launch {
                val currentJob = currentCoroutineContext()[Job]
                try {
                    pumpDispatchQueue()
                } finally {
                    synchronized(coordinationLock) {
                        if (dispatchPumpJob == currentJob) {
                            dispatchPumpJob = null
                        }
                        if (latestObservation != null && activeJobs.size < schedulerGateway.readParallelismBudget()) {
                            launchDispatchPumpIfNeededUnlocked()
                        }
                    }
                }
            }
    }

    private suspend fun pumpDispatchQueue() {
        while (true) {
            val seedObservation =
                synchronized(coordinationLock) {
                    latestObservation
                } ?: return
            val availableSlots =
                synchronized(coordinationLock) {
                    (schedulerGateway.readParallelismBudget() - activeJobs.size).coerceAtLeast(0)
                }
            if (availableSlots <= 0) {
                return
            }
            val batch =
                schedulerGateway.acquirePlanningClaims(
                    indexedObservation = seedObservation,
                    owner = owner,
                    limit = availableSlots,
                )
            batch.logMessages.forEach(dependencies.logLine)
            if (batch.claims.isEmpty()) {
                return
            }
            var launchedCount = 0
            synchronized(coordinationLock) {
                batch.claims.forEach { claim ->
                    if (claim.sessionId.isBlank() || activeJobs.containsKey(claim.sessionId)) return@forEach
                    val job =
                        serviceScope.launch(SessionRuntimeStore.sessionContext(claim.sessionId)) {
                            executeClaim(claim, seedObservation)
                        }
                    activeJobs[claim.sessionId] = job
                    launchedCount += 1
                }
            }
            if (launchedCount <= 0 || launchedCount < availableSlots) {
                return
            }
        }
    }

    private suspend fun executeClaim(
        claim: SessionExecutionClaim,
        seedObservation: IndexedScreenObservation,
    ) {
        val releaseReason =
            try {
                dispatchExecutor.executeClaim(
                    claim = claim,
                    seedObservation = seedObservation,
                    dependencies = dependencies,
                ).releaseReason
            } catch (error: Throwable) {
                dependencies.logLine("service dispatch 异常: ${error.javaClass.simpleName}: ${error.message}")
                "service_turn_failed"
            }
        schedulerGateway.releaseLease(
            owner = owner,
            sessionId = claim.sessionId,
            reason = releaseReason,
        )
        synchronized(coordinationLock) {
            activeJobs.remove(claim.sessionId)
            launchDispatchPumpIfNeededUnlocked()
        }
    }
}
