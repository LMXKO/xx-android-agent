package com.lmx.xiaoxuanagent.assistantos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.RemoteBridgeStore
import com.lmx.xiaoxuanagent.runtime.RemoteTransportEnvelopeType
import com.lmx.xiaoxuanagent.runtime.RemoteTransportSyncEngine
import com.lmx.xiaoxuanagent.runtime.RemoteTransportStore
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryExtractor
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityBus
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityRequest
import com.lmx.xiaoxuanagent.runtime.SessionExecutionCoordinatorStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionResumeStore
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.SessionRuntimeStore
import com.lmx.xiaoxuanagent.runtime.SessionWorkerStore
import kotlinx.coroutines.runBlocking

object PersistentAssistantEngine {
    private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L
    private const val EXACT_WAKE_REQUEST_CODE = 4102
    private const val WORKER_RETRY_DEFER_MS = 10 * 60 * 1000L
    private const val PROVIDER_FAILURE_COOLDOWN_MS = 15 * 60 * 1000L
    private const val WORKER_LEASE_OWNER = "assistant_engine_proactive"
    private val autonomyCycleLock = Any()
    @Volatile
    private var autonomyCycleRunning = false
    @Volatile
    private var pendingAutonomyCycle: PendingAutonomyCycle? = null

    private data class AssistantAutonomyExecutionContext(
        val assistantSnapshot: AssistantOsSnapshot,
        val productShell: AssistantProductShellSnapshot,
        val adaptivePolicy: AssistantAdaptivePolicySnapshot,
    ) {
        val userModel: com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
            get() = productShell.userModel

        val autonomyPlan: AssistantAutonomyPlanSnapshot
            get() = productShell.autonomyPlan
    }

    private data class PendingAutonomyCycle(
        val reason: String,
        val includeRestore: Boolean,
        val includeRetention: Boolean,
    )

    fun start(
        reason: String = "engine_start",
    ) {
        val context = AppRuntimeContext.get() ?: return
        scheduleHeartbeat(context)
        requestAutonomyCycle(reason = reason, includeRestore = false, includeRetention = false)
    }

    fun onPlatformStateChanged() {
        requestAutonomyCycle(reason = "platform_state_changed", includeRestore = false, includeRetention = false)
    }

    fun onExternalSignalRecorded(
        signalId: String,
        reason: String = "external_signal_recorded",
    ) {
        val signal = AssistantExternalSignalStore.read(signalId) ?: return
        if (!signal.enabled || signal.fireAtMs > System.currentTimeMillis()) return
        val context = buildAutonomyExecutionContext(reason)
        AssistantProductShellStore.replace(context.productShell)
        processExternalSignal(
            signal = signal,
            activeSessionId = context.assistantSnapshot.activeSession.sessionId,
            reason = reason,
            routingContext = context,
        )
    }

    fun onHeartbeat() {
        AppRuntimeContext.get()?.let(::scheduleHeartbeat)
        requestAutonomyCycle(reason = "assistant_heartbeat", includeRestore = true, includeRetention = true)
    }

    fun onSystemBooted(
        reason: String,
        context: Context,
    ) {
        AppRuntimeContext.init(context)
        AssistantOsController.recordEntry(
            surface = AssistantEntrySurface.SYSTEM,
            action = reason,
            summary = "系统事件触发 assistant engine 恢复",
        )
        start(reason = reason)
        tryRestoreResumableSession()
        AssistantOsController.refreshProjection(reason = reason)
    }

    private fun syncTriggers(
        reason: String,
    ) {
        val assistantSnapshot = AssistantOsController.snapshot()
        val resumableSnapshots = SessionResumeStore.readResumableSnapshots()
        val triggers =
            TriggerRegistry.syncDerivedTriggers(
                resumeSnapshots = resumableSnapshots,
                assistantSnapshot = assistantSnapshot,
                externalSignals = AssistantExternalSignalStore.readAll(limit = 48),
            )
        // 登记长程等待：挂起会话 → 目标 App 的 APP_FOREGROUND 信号绑定 + 到点定时恢复，
        // 并按最近一个定时任务排一个精确闹钟（修复"被杀后/跨天不会自动续跑"）。
        val waitSync = SessionWaitScheduler.syncSuspendedSessionBindings(resumableSnapshots)
        AppRuntimeContext.get()?.let { scheduleNextExactWake(it) }
        if (triggers.isNotEmpty() || waitSync.signalBindings > 0 || waitSync.timeResumes > 0) {
            AssistantOsController.recordEntry(
                surface = AssistantEntrySurface.SYSTEM,
                action = "trigger_sync",
                summary =
                    "$reason | triggers=${triggers.size} " +
                        "signal_waits=${waitSync.signalBindings} time_resumes=${waitSync.timeResumes}",
            )
        }
    }

    private fun requestAutonomyCycle(
        reason: String,
        includeRestore: Boolean,
        includeRetention: Boolean,
    ) {
        val cycle =
            synchronized(autonomyCycleLock) {
                val next =
                    PendingAutonomyCycle(
                        reason = reason,
                        includeRestore = includeRestore,
                        includeRetention = includeRetention,
                    )
                if (autonomyCycleRunning) {
                    pendingAutonomyCycle =
                        pendingAutonomyCycle?.let { pending ->
                            pending.copy(
                                reason = next.reason,
                                includeRestore = pending.includeRestore || next.includeRestore,
                                includeRetention = pending.includeRetention || next.includeRetention,
                            )
                        } ?: next
                    return
                }
                autonomyCycleRunning = true
                next
            }
        drainAutonomyCycles(initial = cycle)
    }

    private fun drainAutonomyCycles(
        initial: PendingAutonomyCycle,
    ) {
        var cycle: PendingAutonomyCycle? = initial
        while (cycle != null) {
            runAutonomyCycle(
                reason = cycle.reason,
                includeRestore = cycle.includeRestore,
                includeRetention = cycle.includeRetention,
            )
            cycle =
                synchronized(autonomyCycleLock) {
                    val next = pendingAutonomyCycle
                    pendingAutonomyCycle = null
                    if (next == null) {
                        autonomyCycleRunning = false
                    }
                    next
                }
        }
    }

    private fun runAutonomyCycle(
        reason: String,
        includeRestore: Boolean,
        includeRetention: Boolean,
    ) {
        val context = buildAutonomyExecutionContext(reason)
        AssistantProductShellStore.replace(context.productShell)
        val plan = context.autonomyPlan
        val phaseHandlers =
            mapOf<String, () -> Unit>(
                "sync_providers" to {
                    AppRuntimeContext.get()?.let {
                        AssistantSystemSignalProviders.ensureRegistered(it)
                        AssistantSystemSignalProviders.pollDynamicProviders(it, reason = reason)
                    }
                    SessionExecutionCoordinatorStore.sync(reason = reason)
                    AssistantOsController.syncOverlaySurface()
                    syncTriggers(reason = reason)
                },
                "remote_inbound" to { ingestRemoteTransportInbound() },
                "remote_bridge" to { processRemoteBridgeRequests() },
                "mailbox" to {
                    SessionPlatformFacade.processWorkerMailbox(
                        limit = resolveMailboxBatchSize(plan),
                        priorityMode = resolveMailboxPriorityMode(plan),
                    )
                },
                "restore_session" to {
                    if (includeRestore) {
                        val resumed = tryRestoreResumableSession()
                        if (resumed) {
                            DebugAgentStore.refreshEntrySurfaces()
                        }
                    }
                },
                "proactive" to { processDueProactiveTasks(plan) },
                "external_signals" to { processDueExternalSignals(context = context, reason = reason) },
                "refresh_projection" to {
                    val projection = AssistantOsController.refreshProjection(reason = reason)
                    AssistantProductShellController.sync(assistantSnapshot = projection, reason = reason)
                },
                "remote_sync" to { syncRemoteTransport(reason = reason) },
                "memory_drain" to { BackgroundMemoryExtractor.drainAsync(reason = reason) },
                "retention" to {
                    if (includeRetention) {
                        SessionPlatformFacade.runArtifactRetentionSweep()
                    }
                },
            )
        plan.enginePhaseOrder.forEach { phase ->
            phaseHandlers[phase]?.invoke()
        }
    }

    private fun buildAutonomyExecutionContext(
        reason: String,
    ): AssistantAutonomyExecutionContext {
        val assistantSnapshot = AssistantOsController.snapshot()
        val currentShell = AssistantProductShellStore.read()
        val productShell =
            AssistantProductShellController.derive(
                currentSnapshot = currentShell,
                assistantSnapshot = assistantSnapshot,
                reason = reason,
            )
        val adaptivePolicy = AssistantAdaptivePolicyStore.read()
        AssistantAnalyticsStore.logEvent(
            name = "assistant_autonomy_cycle",
            source = "assistant_engine",
            metadata =
                mapOf(
                    "reason" to reason,
                    "mode" to productShell.autonomyPlan.mode,
                    "restore_mode" to productShell.autonomyPlan.restoreMode,
                    "trigger_policy" to productShell.autonomyPlan.triggerPolicyMode,
                ),
        )
        return AssistantAutonomyExecutionContext(
            assistantSnapshot = assistantSnapshot,
            productShell = productShell,
            adaptivePolicy = adaptivePolicy,
        )
    }

    private fun resolveMailboxBatchSize(
        plan: AssistantAutonomyPlanSnapshot = AssistantProductShellStore.read().autonomyPlan,
    ): Int =
        SessionPlatformFacade.readProductShellSnapshot()
            .swarmStrategy
            .mailboxBatchSize
            .let { current ->
                if (plan.mode == "approval_guard") maxOf(current, 8) else current
            }
            .coerceIn(4, 16)

    private fun resolveMailboxPriorityMode(
        plan: AssistantAutonomyPlanSnapshot = AssistantProductShellStore.read().autonomyPlan,
    ): String =
        when {
            plan.mode == "approval_guard" -> "approval_first"
            plan.mode == "event_driven" -> "reply_chain_first"
            else ->
                SessionPlatformFacade.readProductShellSnapshot()
                    .swarmStrategy
                    .mailboxPriorityMode
                    .ifBlank { "balanced" }
        }

    private fun ingestRemoteTransportInbound() {
        RemoteTransportStore.readPendingInbound().forEach { envelope ->
            if (envelope.type != RemoteTransportEnvelopeType.CAPABILITY_REQUEST || envelope.capability == null) {
                RemoteTransportStore.markFailed(envelope.envelopeId, "仅支持 capability request 入站信封。")
                return@forEach
            }
            RemoteBridgeStore.enqueueRequest(
                capability = envelope.capability,
                sessionId = envelope.sessionId,
                task = envelope.task,
                query = envelope.query,
                entrySource = envelope.entrySource.ifBlank { "remote_transport" },
                userCorrection = envelope.userCorrection,
                payload = envelope.payload,
                summary = envelope.summary,
            )
            RemoteTransportStore.markConsumed(envelope.envelopeId, "已转入 remote bridge 请求队列")
        }
    }

    private fun tryRestoreResumableSession(): Boolean {
        val activeSessionId = AssistantOsController.snapshot().activeSession.sessionId
        if (activeSessionId.isNotBlank()) {
            return false
        }
        val dueTriggers = TriggerRegistry.readDue()
        val preferredSessionIds =
            dueTriggers
                .mapNotNull { trigger -> trigger.sessionId.takeIf(String::isNotBlank) }
                .plus(AssistantOsStrategyPolicy.selectPreferredRestoreSessionIds())
                .toSet()
        val restoredSessionId =
            SessionExecutionCoordinatorStore.tryBootstrapNextRunnableSession(
                preferredSessionIds = preferredSessionIds,
                reason = "assistant_restore_resumable",
            )
        if (restoredSessionId.isBlank()) {
            return false
        }
        val latestResumable =
            SessionResumeStore.readSessionSnapshot(restoredSessionId)
                ?: SessionResumeStore.readLatestSnapshot()
        dueTriggers.forEach { trigger ->
            TriggerRegistry.markTriggered(trigger.id)
        }
        AssistantOsController.recordEntry(
            surface = AssistantEntrySurface.SYSTEM,
            action = "auto_restore_session",
            summary = latestResumable?.task?.ifBlank { restoredSessionId } ?: restoredSessionId,
        )
        AssistantOsController.syncOverlaySurface()
        return true
    }

    private fun processRemoteBridgeRequests() {
        RemoteBridgeStore.readPendingRequests().forEach { request ->
            if (request.capability == SessionCapabilityKey.START_SESSION &&
                AssistantOsController.snapshot().activeSession.sessionId.isNotBlank()
            ) {
                return@forEach
            }
            val result =
                runBlocking {
                    SessionCapabilityBus.dispatch(
                        SessionCapabilityRequest(
                            id = request.requestId,
                            key = request.capability,
                            sessionId = request.sessionId,
                            task = request.task,
                            query = request.query,
                            entrySource = request.entrySource,
                            userCorrection = request.userCorrection,
                            payload = request.payload,
                        ),
                    )
                }
            if (result.success) {
                RemoteBridgeStore.markProcessed(request.requestId, result.summary, result.payloadLines)
                RemoteTransportStore.publishPlatformEvent(
                    sessionId = result.sessionId.ifBlank { request.sessionId },
                    summary = "remote ${request.capability.name.lowercase()} | ${result.summary}",
                    payload =
                        mapOf(
                            "request_id" to request.requestId,
                            "capability" to request.capability.name.lowercase(),
                            "status" to "processed",
                        ),
                )
                AssistantOsController.recordEntry(
                    surface = AssistantEntrySurface.SYSTEM,
                    action = "remote_${request.capability.name.lowercase()}",
                    summary = result.summary,
                )
                if (request.capability == SessionCapabilityKey.START_SESSION) {
                    return
                }
            } else {
                RemoteBridgeStore.markFailed(request.requestId, result.summary)
                RemoteTransportStore.publishPlatformEvent(
                    sessionId = request.sessionId,
                    summary = "remote ${request.capability.name.lowercase()} failed | ${result.summary}",
                    payload =
                        mapOf(
                            "request_id" to request.requestId,
                            "capability" to request.capability.name.lowercase(),
                            "status" to "failed",
                        ),
                )
            }
        }
    }

    private fun processDueProactiveTasks(
        autonomyPlan: AssistantAutonomyPlanSnapshot = AssistantProductShellStore.read().autonomyPlan,
    ) {
        if (autonomyPlan.proactiveMode == "defer_until_approval") {
            return
        }
        val activeSessionId = AssistantOsController.snapshot().activeSession.sessionId
        val schedulerSnapshot = SessionExecutionCoordinatorStore.sync(reason = "assistant_process_due_proactive_tasks")
        val dispatchableWorkerIds = schedulerSnapshot.runnableWorkerIds.toSet()
        val taskQueueSnapshot =
            AssistantProactiveTaskStore.readDispatchSnapshot(
                activeSessionId = activeSessionId,
                dispatchableWorkerIds = dispatchableWorkerIds,
                limit = 8,
            )
        taskQueueSnapshot.dispatchCandidates.forEach { candidate ->
            val task = candidate.task
            // 定时恢复（SCHEDULED_TASK / time_resume）：经协调器 bootstrap 续跑，
            // 复用后台自动拉起目标 App 的路径（#2A），而非走只翻状态的 capability 派发。
            if (SessionWaitScheduler.isTimeResumeTask(task)) {
                val resumedSessionId =
                    task.sessionId.takeIf { it.isNotBlank() }?.let { sid ->
                        SessionExecutionCoordinatorStore.tryBootstrapNextRunnableSession(
                            preferredSessionIds = setOf(sid),
                            reason = "wait_time_due",
                        )
                    }.orEmpty()
                if (resumedSessionId.isNotBlank()) {
                    AssistantSignalWaitStore.clearSession(task.sessionId)
                    AssistantProactiveTaskStore.markCompleted(task.id)
                    AssistantOsController.recordEntry(
                        surface = AssistantEntrySurface.SYSTEM,
                        action = "proactive_time_resume",
                        summary = "${candidate.lane} | resumed ${task.sessionId}",
                    )
                } else {
                    AssistantProactiveTaskStore.defer(task.id, deferByMs = WORKER_RETRY_DEFER_MS)
                }
                return@forEach
            }
            // APPROVAL_FOLLOW_UP：仅当目标会话仍在等待确认时才发提醒，否则视为已了结。
            // （此前两类任务被无条件 return@forEach 跳过，等于定时跟进从不真正执行。）
            if (task.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP) {
                val stillAwaiting =
                    task.sessionId.isNotBlank() &&
                        SessionRuntimeStore.readSession(task.sessionId).safety.awaitingConfirmation
                if (!stillAwaiting) {
                    AssistantProactiveTaskStore.markCompleted(task.id)
                    return@forEach
                }
            }
            // MEMORY_NUDGE 无前置条件，直接走下面的 dispatch。
            val workerId = task.metadata["worker_id"].orEmpty()
            val worker = workerId.takeIf { it.isNotBlank() }?.let { id ->
                SessionWorkerStore.readAll(limit = 80).firstOrNull { it.workerId == id }
            }
            if (
                task.type == AssistantProactiveTaskType.WORKER_TASK &&
                workerId.isNotBlank() &&
                workerId !in dispatchableWorkerIds
            ) {
                return@forEach
            }
            val leasedWorker =
                if (task.type == AssistantProactiveTaskType.WORKER_TASK && workerId.isNotBlank()) {
                    SessionWorkerStore.acquireDispatchLease(
                        workerId = workerId,
                        owner = WORKER_LEASE_OWNER,
                    ) ?: return@forEach
                } else {
                    null
                }
            if (
                activeSessionId.isNotBlank() &&
                task.capability == SessionCapabilityKey.START_SESSION &&
                task.type != AssistantProactiveTaskType.WORKER_TASK
            ) {
                return@forEach
            }
            val result =
                runBlocking {
                    SessionCapabilityBus.dispatch(
                        SessionCapabilityRequest(
                            key = task.capability,
                            sessionId = task.sessionId,
                            task = task.task,
                            entrySource = "proactive:${task.type.name.lowercase()}",
                            payload = task.metadata,
                        ),
                    )
                }
            if (result.success) {
                workerId.takeIf { it.isNotBlank() }?.let {
                    SessionWorkerStore.markLaunched(
                        workerId = it,
                        sessionId = result.sessionId,
                        summary = task.summary.ifBlank { task.task },
                    )
                }
                AssistantProactiveTaskStore.markCompleted(task.id)
                AssistantOsController.recordEntry(
                    surface = AssistantEntrySurface.SYSTEM,
                    action = "proactive_${task.type.name.lowercase()}",
                    summary = "${candidate.lane} | ${result.summary}",
                )
                if (task.capability == SessionCapabilityKey.START_SESSION) {
                    return
                }
            } else {
                AssistantProactiveTaskStore.defer(task.id, deferByMs = WORKER_RETRY_DEFER_MS)
                (leasedWorker ?: worker)?.let {
                    if (it.retryCount < it.maxRetries) {
                        SessionWorkerStore.markDeferred(
                            workerId = it.workerId,
                            summary = result.summary,
                            deferByMs = WORKER_RETRY_DEFER_MS,
                            blockedReason = "retry_scheduled",
                        )
                    } else {
                        SessionWorkerStore.markFailed(it.workerId, result.summary)
                    }
                }
            }
        }
    }

    private fun processDueExternalSignals(
        context: AssistantAutonomyExecutionContext,
        reason: String,
    ) {
        AssistantExternalSignalStore.readDue().forEach { signal ->
            val activeSessionId = AssistantOsController.snapshot().activeSession.sessionId
            processExternalSignal(
                signal = signal,
                activeSessionId = activeSessionId,
                reason = reason,
                routingContext = context,
            )
            if (AssistantOsController.snapshot().activeSession.sessionId.isNotBlank()) {
                return
            }
        }
    }

    private fun processExternalSignal(
        signal: AssistantExternalSignal,
        activeSessionId: String,
        reason: String,
        routingContext: AssistantAutonomyExecutionContext? = null,
    ): Boolean {
        val providerId = signal.providerId()
        val providerGate = AssistantSignalProviderStore.evaluateSignal(signal)
        when (providerGate.action) {
            AssistantSignalProviderGateAction.DEFER -> {
                AssistantExternalSignalStore.defer(
                    signal.id,
                    deferByMs = providerGate.deferByMs.coerceAtLeast(60_000L),
                )
                AssistantSignalProviderStore.markSignalRejected(
                    providerId = providerId,
                    reason = providerGate.reason,
                )
                return false
            }

            AssistantSignalProviderGateAction.DENY -> {
                AssistantExternalSignalStore.markConsumed(signal.id)
                AssistantSignalProviderStore.markSignalRejected(
                    providerId = providerId,
                    reason = providerGate.reason,
                )
                return false
            }

            AssistantSignalProviderGateAction.ALLOW -> Unit
        }
        // 信号未携带 sessionId 时，尝试解析等待绑定，把它定向到正在等该 App 的挂起会话并直接经
        // 协调器恢复（修复"通知/前台事件无法唤醒挂起会话"）。
        if (signal.sessionId.isBlank()) {
            val binding = SessionWaitScheduler.resolveSignalBinding(signal)
            if (binding != null) {
                val resumedSessionId =
                    SessionExecutionCoordinatorStore.tryBootstrapNextRunnableSession(
                        preferredSessionIds = setOf(binding.sessionId),
                        reason = "signal_wait:${signal.type.name.lowercase()}",
                    )
                if (resumedSessionId.isNotBlank()) {
                    AssistantSignalWaitStore.clearSession(binding.sessionId)
                    AssistantExternalSignalStore.markConsumed(signal.id)
                    AssistantSignalProviderStore.markSignalProcessed(
                        providerId = providerId,
                        success = true,
                        reason = "resume_bound_session:${binding.sessionId}",
                    )
                    AssistantOsController.recordEntry(
                        surface = AssistantEntrySurface.SYSTEM,
                        action = "external_${signal.type.name.lowercase()}_resume_bound",
                        summary = "resumed ${binding.sessionId} | ${signal.summary}",
                    )
                    return true
                }
            }
        }
        val providerState = AssistantSignalProviderStore.read(providerId)
        val routingDecision =
            AssistantTriggerRoutingPolicy.route(
                signal = signal,
                activeSessionId = activeSessionId,
                providerState = providerState,
                adaptivePolicy = routingContext?.adaptivePolicy ?: AssistantAdaptivePolicyStore.read(),
                userModel = routingContext?.userModel ?: PersonalMemoryStore.readUserModelSnapshot(limit = 4),
                autonomyPlan = routingContext?.autonomyPlan ?: AssistantProductShellStore.read().autonomyPlan,
            )
        val result =
            runBlocking {
                SessionCapabilityBus.dispatch(
                    SessionCapabilityRequest(
                        key = routingDecision.capability,
                        sessionId = signal.sessionId,
                        task = routingDecision.task.ifBlank { signal.task },
                        query = routingDecision.query.ifBlank { signal.query },
                        userCorrection = routingDecision.userCorrection,
                        entrySource =
                            providerState?.preferredEntrySource
                                ?.ifBlank { "external_signal:${signal.type.name.lowercase()}" }
                                ?: "external_signal:${signal.type.name.lowercase()}",
                        payload = routingDecision.payload + mapOf("signal_reason" to reason),
                    ),
                )
            }
        if (result.success) {
            if (routingDecision.shouldConsume) {
                AssistantExternalSignalStore.markConsumed(signal.id)
            }
            PersonalMemoryStore.recordExternalSignalMemory(signal)
            AssistantSignalProviderStore.markSignalProcessed(
                providerId = providerId,
                success = true,
                reason = routingDecision.summary.ifBlank { result.summary },
            )
            AssistantOsController.recordEntry(
                surface = AssistantEntrySurface.SYSTEM,
                action = "external_${signal.type.name.lowercase()}",
                summary = routingDecision.summary.ifBlank { signal.summary.ifBlank { result.summary } },
            )
            RemoteTransportStore.publishPlatformEvent(
                sessionId = result.sessionId.ifBlank { signal.sessionId },
                summary = "external ${signal.type.name.lowercase()} | ${routingDecision.capability.name.lowercase()} | ${result.summary}",
                payload =
                    mapOf(
                        "signal_id" to signal.id,
                        "signal_type" to signal.type.name.lowercase(),
                        "capability" to routingDecision.capability.name.lowercase(),
                        "reason" to reason,
                    ),
            )
            val projection = AssistantOsController.refreshProjection(reason = reason)
            AssistantProductShellController.sync(assistantSnapshot = projection, reason = reason)
            return true
        }
        AssistantExternalSignalStore.defer(signal.id, deferByMs = 15 * 60 * 1000L)
        AssistantSignalProviderStore.markSignalProcessed(
            providerId = providerId,
            success = false,
            reason = result.summary,
            cooldownMs = PROVIDER_FAILURE_COOLDOWN_MS,
        )
        return false
    }

    private fun syncRemoteTransport(
        reason: String,
    ) {
        val sessionIds =
            buildList {
                SessionRuntime.State.runtimeState().session.sessionId.takeIf { it.isNotBlank() }?.let(::add)
                SessionResumeStore.readResumableSnapshots(limit = 3)
                    .map { it.sessionId }
                    .filter { it.isNotBlank() && it !in this }
                    .forEach(::add)
            }
        sessionIds.forEach { sessionId ->
            val bundle = SessionPlatformFacade.exportRemoteViewerBundle(sessionId = sessionId, eventLimit = 12)
            RemoteTransportStore.publishViewerBundle(
                sessionId = sessionId,
                body = bundle.toString(),
                summary = "$reason | viewer bundle",
                payload = mapOf("reason" to reason, "schema_version" to bundle.optInt("schema_version").toString()),
            )
        }
        RemoteTransportSyncEngine.sync(reason = reason)
    }

    private fun scheduleHeartbeat(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                AssistantHeartbeatReceiver.REQUEST_CODE,
                Intent(context, AssistantHeartbeatReceiver::class.java).setAction(AssistantHeartbeatReceiver.ACTION_HEARTBEAT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            pendingIntent,
        )
        scheduleNextExactWake(context)
    }

    /**
     * 为最近一个未来定时任务（如到点续跑会话）排一个精确闹钟，避免只能等 15min 粗轮询。
     * Android 12+ 若未授予精确闹钟权限则回落到 setAndAllowWhileIdle（Doze 下仍可唤醒，精度略降）。
     */
    private fun scheduleNextExactWake(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val nextWakeAtMs = SessionWaitScheduler.computeNextWakeAtMs() ?: return
        if (nextWakeAtMs <= System.currentTimeMillis()) return
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                EXACT_WAKE_REQUEST_CODE,
                Intent(context, AssistantHeartbeatReceiver::class.java).setAction(AssistantHeartbeatReceiver.ACTION_HEARTBEAT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWakeAtMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWakeAtMs, pendingIntent)
            }
        }
    }
}
