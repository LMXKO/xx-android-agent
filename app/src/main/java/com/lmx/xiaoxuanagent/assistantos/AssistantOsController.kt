package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.AgentUiStatusCategory
import com.lmx.xiaoxuanagent.runtime.AgentUiTerminalReason
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.RemoteBridgeRequest
import com.lmx.xiaoxuanagent.runtime.RemoteBridgeStore
import com.lmx.xiaoxuanagent.runtime.ReplaySessionSnapshot
import com.lmx.xiaoxuanagent.runtime.ReplayStore
import com.lmx.xiaoxuanagent.runtime.SessionBridgeSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionRuntime
import com.lmx.xiaoxuanagent.runtime.TargetAppReturnTrigger
import com.lmx.xiaoxuanagent.runtime.SessionWorkerRecord
import com.lmx.xiaoxuanagent.runtime.SessionWorkerStore
import com.lmx.xiaoxuanagent.runtime.isTerminalReason
import com.lmx.xiaoxuanagent.runtime.resolveAgentUiStatusModel
import com.lmx.xiaoxuanagent.runtime.toStatusSnapshot
import com.lmx.xiaoxuanagent.safety.PermissionModeOrchestrator

object AssistantOsController {
    private const val MAX_VISIBLE_ENTRIES = 10
    private const val MAX_VISIBLE_PENDING_ACTIONS = 5
    private const val MAX_VISIBLE_TASK_INBOX = 8
    private const val MAX_VISIBLE_SESSION_BACKLOG = 6
    private const val STALE_RUNTIME_THRESHOLD_MS = 20 * 60 * 1000L
    @Volatile
    private var lastRuntimeFingerprint: String = ""

    fun snapshot(): AssistantOsSnapshot =
        syncDerivedState(AssistantOsStore.read())

    fun refreshProjection(
        reason: String = "manual_refresh",
    ): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            val projected = syncDerivedState(current)
            projected.copy(
                health = projected.health.copy(summary = projected.health.summary.ifBlank { reason }),
            )
        }

    fun cyclePermissionMode(): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            val next =
                when (current.permissionMode) {
                    AssistantPermissionMode.PROMPT_EACH_TIME -> AssistantPermissionMode.ASSISTED
                    AssistantPermissionMode.ASSISTED -> AssistantPermissionMode.HANDS_FREE
                    AssistantPermissionMode.HANDS_FREE -> AssistantPermissionMode.PROMPT_EACH_TIME
                }
            syncDerivedState(current.copy(permissionMode = next))
        }.also(::syncOverlaySurface)

    fun cycleSafetyMode(): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            val next =
                when (current.safetyMode) {
                    AssistantSafetyMode.STRICT -> AssistantSafetyMode.BALANCED
                    AssistantSafetyMode.BALANCED -> AssistantSafetyMode.FOCUSED
                    AssistantSafetyMode.FOCUSED -> AssistantSafetyMode.STRICT
                }
            syncDerivedState(current.copy(safetyMode = next))
        }

    fun toggleFeatureFlag(
        key: AssistantFeatureFlagKey,
    ): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            syncDerivedState(
                current.copy(
                    featureFlags =
                        current.featureFlags.map { state ->
                            if (state.key == key) {
                                state.copy(enabled = !state.enabled)
                            } else {
                                state
                            }
                        },
                ),
            )
        }.also(::syncOverlaySurface)

    fun toggleExperiment(
        key: AssistantExperimentKey,
    ): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            syncDerivedState(
                current.copy(
                    experiments =
                        current.experiments.map { state ->
                            if (state.key == key) {
                                state.copy(enabled = !state.enabled)
                            } else {
                                state
                            }
                        },
                ),
            )
        }.also(::syncOverlaySurface)

    fun setAllExperimentsEnabled(
        enabled: Boolean,
    ): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            syncDerivedState(
                current.copy(
                    experiments = current.experiments.map { it.copy(enabled = enabled) },
                ),
            )
        }.also(::syncOverlaySurface)

    fun isFeatureEnabled(
        key: AssistantFeatureFlagKey,
    ): Boolean = snapshot().isFeatureEnabled(key)

    fun areAllExperimentsEnabled(): Boolean =
        snapshot().experiments.all { it.enabled }

    fun currentPermissionMode(): AssistantPermissionMode {
        return PermissionModeOrchestrator.effectivePermissionMode(snapshot())
    }

    fun currentSafetyMode(): AssistantSafetyMode {
        val snapshot = snapshot()
        if (!snapshot.isExperimentEnabled(AssistantExperimentKey.SAFETY_MODE_V1)) {
            return AssistantSafetyMode.BALANCED
        }
        return snapshot.safetyMode
    }

    fun clearInbox(): AssistantOsSnapshot =
        AssistantOsStore.update { current ->
            current.copy(recentEntries = emptyList())
        }

    fun recordEntry(
        surface: AssistantEntrySurface,
        action: String,
        summary: String,
    ) {
        AssistantOsStrategyPolicy.recordAssistantEntry(surface, action, summary)
        AssistantOsStore.update { current ->
            if (!current.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) {
                return@update syncDerivedState(current)
            }
            val record =
                AssistantEntryRecord(
                    surface = surface,
                    action = action,
                    summary = summary.trim().ifBlank { action },
                    createdAtMs = System.currentTimeMillis(),
                )
            syncDerivedState(
                current.copy(recentEntries = (listOf(record) + current.recentEntries).take(MAX_VISIBLE_ENTRIES)),
            )
        }
    }

    fun onRuntimeBridgeSnapshot(
        snapshot: SessionBridgeSnapshot,
    ) {
        val pendingActions = derivePendingActions(snapshot = snapshot)
        val summary =
            listOf(
                snapshot.resultSummary.takeIf { it.isNotBlank() },
                snapshot.errorSummary.takeIf { it.isNotBlank() },
                snapshot.takeoverSummary.takeIf { it.isNotBlank() },
            ).firstOrNull().orEmpty()
        val nextSnapshot =
            AssistantOsStore.update { current ->
                syncDerivedState(
                    current.copy(
                        pendingActions = pendingActions,
                        activeSession =
                            AssistantActiveSession(
                                sessionId = snapshot.sessionId,
                                statusSnapshot = snapshot.toStatusSnapshot(),
                                task = snapshot.task,
                                entrySource = snapshot.entrySource,
                                targetPackageName = snapshot.targetPackageName,
                                routeReason = snapshot.routeReason,
                                summary = summary,
                                resumable = snapshot.resumable,
                                awaitingConfirmation = snapshot.pendingSafetyConfirmation.isNotBlank(),
                                targetAppReturnEligible = snapshot.targetAppReturnEligible,
                                waitingForExternal = snapshot.statusTakeoverReason == com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason.WAITING_EXTERNAL,
                                turn = snapshot.turn,
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                    ),
                )
            }
        syncOverlaySurface(nextSnapshot)
        publishRuntimeInboxItems(snapshot, summary)
    }

    fun onPlatformSnapshot(
        snapshot: SessionPlatformSnapshot,
    ) {
        val previousSnapshot = AssistantOsStore.read()
        val bridgeSnapshot = snapshot.bridgeSnapshot
        val pendingActions =
            derivePendingActions(
                snapshot = bridgeSnapshot,
                hasOverlayPermission = canDrawOverlays(),
            )
        val summary =
            listOf(
                bridgeSnapshot.resultSummary.takeIf { it.isNotBlank() },
                bridgeSnapshot.errorSummary.takeIf { it.isNotBlank() },
                bridgeSnapshot.takeoverSummary.takeIf { it.isNotBlank() },
                snapshot.pendingSafetySummary.takeIf { it.isNotBlank() },
            ).firstOrNull().orEmpty()
        val nextSnapshot =
            AssistantOsStore.update { current ->
                syncDerivedState(
                    current.copy(
                        pendingActions = pendingActions,
                        activeSession =
                            AssistantActiveSession(
                                sessionId = bridgeSnapshot.sessionId,
                                statusSnapshot = bridgeSnapshot.toStatusSnapshot(),
                                task = bridgeSnapshot.task,
                                entrySource = bridgeSnapshot.entrySource,
                                targetPackageName = bridgeSnapshot.targetPackageName,
                                routeReason = bridgeSnapshot.routeReason,
                                summary = summary,
                                resumable = bridgeSnapshot.resumable,
                                awaitingConfirmation = bridgeSnapshot.pendingSafetyConfirmation.isNotBlank(),
                                targetAppReturnEligible = bridgeSnapshot.targetAppReturnEligible,
                                waitingForExternal =
                                    bridgeSnapshot.statusTakeoverReason == com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason.WAITING_EXTERNAL,
                                turn = bridgeSnapshot.turn,
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                    ),
                )
        }
        syncOverlaySurface(nextSnapshot)
        publishRuntimeInboxItems(bridgeSnapshot, summary)
        if (!AssistantOsStore.isEquivalent(previousSnapshot, nextSnapshot)) {
            PersistentAssistantEngine.onPlatformStateChanged()
        }
    }

    fun shouldAcceptShareEntry(): Boolean =
        snapshot().isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
            snapshot().isFeatureEnabled(AssistantFeatureFlagKey.SHARE_SHEET_ENTRY)

    fun shouldShowNotificationControlCenter(): Boolean =
        snapshot().isExperimentEnabled(AssistantExperimentKey.ENTRY_CONTROL_CENTER_V1) &&
            snapshot().isFeatureEnabled(AssistantFeatureFlagKey.NOTIFICATION_CONTROL_CENTER)

    fun shouldAutoReturnToTargetApp(
        trigger: TargetAppReturnTrigger,
        explicitReturnRequest: Boolean = false,
    ): Boolean =
        PermissionModeOrchestrator.shouldAutoReturnToTargetApp(snapshot(), trigger, explicitReturnRequest)

    fun canDrawOverlays(): Boolean = PermissionModeOrchestrator.canDrawOverlays()

    fun buildControlCenterSummary(): String {
        val snapshot = snapshot()
        val active = snapshot.activeSession
        val flagsSummary =
            snapshot.featureFlags
                .joinToString(" / ") { "${it.key.name.lowercase()}=${if (it.enabled) "on" else "off"}" }
                .ifBlank { "-" }
        val experimentsSummary =
            snapshot.experiments
                .joinToString(" / ") { "${it.key.name.lowercase()}=${if (it.enabled) "on" else "off"}" }
                .ifBlank { "-" }
        val surfacesSummary =
            snapshot.surfaces
                .joinToString(separator = "\n") { surface ->
                    "surface.${surface.surface.name.lowercase()}=" +
                        when {
                            !surface.supported -> "unsupported"
                            !surface.enabled -> "disabled"
                            !surface.available -> "blocked(${surface.summary.ifBlank { "-" }})"
                            else -> "ready(${surface.summary.ifBlank { "ok" }})"
                        }
                }
        val pendingSummary =
            snapshot.pendingActions
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { action ->
                    "pending.${action.type.name.lowercase()}=${action.title} | ${action.summary.ifBlank { "-" }}"
                }
                .ifBlank { "pending=-" }
        val triggerSummary =
            TriggerRegistry.readAll()
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { trigger ->
                    "trigger.${trigger.type.name.lowercase()}=${trigger.title} | ${trigger.summary.ifBlank { "-" }}"
                }
                .ifBlank { "trigger=-" }
        val remoteSummary =
            RemoteBridgeStore.readSnapshot(requestLimit = MAX_VISIBLE_PENDING_ACTIONS, eventLimit = MAX_VISIBLE_PENDING_ACTIONS)
                .let { remote ->
                    buildList {
                        remote.pendingRequests.forEach { request ->
                            add("remote.pending=${request.capability.name.lowercase()} | ${request.summary.ifBlank { "-" }}")
                        }
                        remote.recentEvents.take(2).forEach { event ->
                            add("remote.event=${event.eventType} | ${event.summary.ifBlank { "-" }}")
                        }
                    }.joinToString(separator = "\n").ifBlank { "remote=-" }
                }
        val workerSummary =
            SessionWorkerStore.readAll(limit = MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { worker ->
                    "worker.${worker.status.name.lowercase()}=${worker.task.ifBlank { worker.workerId }} | ${worker.summary.ifBlank { "-" }}"
                }
                .ifBlank { "worker=-" }
        val proactiveSummary =
            AssistantProactiveTaskStore.readAll(limit = MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { task ->
                    "proactive.${task.type.name.lowercase()}=${task.title} | ${task.summary.ifBlank { "-" }}"
                }
                .ifBlank { "proactive=-" }
        val inboxSummary =
            snapshot.taskInbox
                .take(MAX_VISIBLE_TASK_INBOX)
                .joinToString(separator = "\n") { item ->
                    "inbox.${item.type.name.lowercase()}=${item.title} | ${item.summary.ifBlank { "-" }}"
                }
                .ifBlank { "inbox=-" }
        val backlogSummary =
            snapshot.sessionBacklog
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { session ->
                    "backlog=${session.statusCode} | ${session.task.ifBlank { session.sessionId }} | ${session.summary.ifBlank { "-" }}"
                }
                .ifBlank { "backlog=-" }
        val approvalSummary =
            snapshot.approvalSessions
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { session ->
                    "approval=${session.task.ifBlank { session.sessionId }} | ${session.summary.ifBlank { "-" }}"
                }
                .ifBlank { "approval=-" }
        val failedSummary =
            snapshot.failedSessions
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { session ->
                    "failed=${session.statusCode} | ${session.task.ifBlank { session.sessionId }} | ${session.summary.ifBlank { "-" }}"
                }
                .ifBlank { "failed=-" }
        val productShell = AssistantProductShellStore.read()
        val onboardingSummary =
            buildString {
                append("product.onboarding=").append(productShell.onboarding.status.ifBlank { "-" })
                append(" | ").append(productShell.onboarding.summary.ifBlank { "-" })
            }
        val tipSummary =
            productShell.tips
                .take(3)
                .joinToString(separator = "\n") { tip ->
                    "tip=${tip.title} | ${tip.summary.ifBlank { "-" }}"
                }
                .ifBlank { "tip=-" }
        val swarmSummary =
            buildString {
                append("swarm=").append(productShell.swarmStrategy.mode.ifBlank { "-" })
                append(" | ").append(productShell.swarmStrategy.summary.ifBlank { "-" })
            }
        val agendaSummary =
            buildString {
                append("agenda=").append(productShell.agendaShell.mode.ifBlank { "-" })
                append(" | ").append(productShell.agendaShell.summary.ifBlank { "-" })
            }
        val focusSummary =
            buildString {
                append("focus=").append(productShell.personalFocus.mode.ifBlank { "-" })
                append(" | ").append(productShell.personalFocus.summary.ifBlank { "-" })
            }
        val rhythmSummary =
            buildString {
                append("rhythm=").append(productShell.dailyRhythm.mode.ifBlank { "-" })
                append(" | ").append(productShell.dailyRhythm.summary.ifBlank { "-" })
            }
        return buildString {
            append("permissionMode=").append(snapshot.permissionMode.name.lowercase()).append('\n')
            append("safetyMode=").append(snapshot.safetyMode.name.lowercase()).append('\n')
            append("health=").append(snapshot.health.status).append(" | ").append(snapshot.health.summary.ifBlank { "-" }).append('\n')
            append("health.backlogCount=").append(snapshot.health.backlogCount).append('\n')
            append("health.resumable=").append(snapshot.health.resumableSessionCount).append('\n')
            append("health.failed=").append(snapshot.health.failedSessionCount).append('\n')
            append("health.approvals=").append(snapshot.health.approvalSessionCount).append('\n')
            append("health.dueTriggers=").append(snapshot.health.dueTriggerCount).append('\n')
            append("flags=").append(flagsSummary).append('\n')
            append("experiments=").append(experimentsSummary).append('\n')
            append("activeSession=").append(active.sessionId.ifBlank { "-" }).append('\n')
            append("activeStatus=").append(active.statusCode.ifBlank { "-" }).append('\n')
            append("activeTurn=").append(active.turn).append('\n')
            append("activeTask=").append(active.task.ifBlank { "-" }).append('\n')
            append("activeRoute=").append(active.routeReason.ifBlank { "-" }).append('\n')
            append("activeSummary=").append(active.summary.ifBlank { "-" }).append('\n')
            append(pendingSummary).append('\n')
            append(triggerSummary).append('\n')
            append(remoteSummary).append('\n')
            append(workerSummary).append('\n')
            append(proactiveSummary).append('\n')
            append(inboxSummary).append('\n')
            append(backlogSummary).append('\n')
            append(approvalSummary).append('\n')
            append(failedSummary).append('\n')
            append(onboardingSummary).append('\n')
            append(swarmSummary).append('\n')
            append(agendaSummary).append('\n')
            append(focusSummary).append('\n')
            append(rhythmSummary).append('\n')
            append(tipSummary).append('\n')
            append(surfacesSummary)
        }
    }

    fun buildEntryInboxSummary(): String {
        val snapshot = snapshot()
        if (!snapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) {
            return "助手收件箱已关闭。"
        }
        val pendingSummary =
            snapshot.pendingActions
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { action ->
                    "待处理 | ${action.title} | ${action.summary.ifBlank { "-" }}"
                }
        val taskInboxSummary =
            snapshot.taskInbox
                .take(MAX_VISIBLE_TASK_INBOX)
                .joinToString(separator = "\n") { item ->
                    "任务箱 | ${item.title} | ${item.summary.ifBlank { "-" }}"
                }
        val backlogSummary =
            snapshot.sessionBacklog
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { session ->
                    "挂起任务 | ${session.task.ifBlank { session.sessionId }} | ${session.statusCode} | ${session.summary.ifBlank { "-" }}"
                }
        val failedSummary =
            snapshot.failedSessions
                .take(MAX_VISIBLE_PENDING_ACTIONS)
                .joinToString(separator = "\n") { session ->
                    "失败回看 | ${session.task.ifBlank { session.sessionId }} | ${session.summary.ifBlank { "-" }}"
                }
        val entrySummary =
            snapshot.recentEntries
                .take(MAX_VISIBLE_ENTRIES)
                .joinToString(separator = "\n") { record ->
                    "${record.surface.name.lowercase()} | ${record.action} | ${record.summary}"
                }
        val productShell = AssistantProductShellStore.read()
        val tipSummary =
            productShell.tips
                .take(3)
                .joinToString(separator = "\n") { tip ->
                    "系统提示 | ${tip.title} | ${tip.summary.ifBlank { "-" }}"
                }
        return listOf(
            tipSummary.takeIf { it.isNotBlank() },
            pendingSummary.takeIf { it.isNotBlank() },
            taskInboxSummary.takeIf { it.isNotBlank() },
            backlogSummary.takeIf { it.isNotBlank() },
            failedSummary.takeIf { it.isNotBlank() },
            entrySummary.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(separator = "\n").ifBlank { "暂无最近入口或系统收件。" }
    }

    private fun publishRuntimeInboxItems(
        snapshot: SessionBridgeSnapshot,
        summary: String,
    ) {
        if (!snapshot().isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) {
            return
        }
        val fingerprint =
            listOf(
                snapshot.sessionId,
                snapshot.statusCode,
                snapshot.pendingSafetyConfirmation,
                snapshot.takeoverSummary,
                summary,
            ).joinToString("|")
        if (fingerprint == lastRuntimeFingerprint) {
            return
        }
        lastRuntimeFingerprint = fingerprint
        if (snapshot.sessionId.isBlank()) {
            return
        }
        when {
            snapshot.pendingSafetyConfirmation.isNotBlank() ->
                recordEntry(
                    surface = AssistantEntrySurface.SYSTEM,
                    action = "pending_safety_confirmation",
                    summary = snapshot.pendingSafetyConfirmation,
                )

            snapshot.statusCategory == AgentUiStatusCategory.TERMINAL ->
                recordEntry(
                    surface = AssistantEntrySurface.SYSTEM,
                    action = "session_terminal",
                    summary = "${snapshot.statusCode} ${snapshot.task}".trim(),
                )

            snapshot.takeoverSummary.isNotBlank() ->
                recordEntry(
                    surface = AssistantEntrySurface.SYSTEM,
                    action = "session_takeover",
                    summary = snapshot.takeoverSummary,
                )
        }
    }

    private fun syncDerivedState(
        snapshot: AssistantOsSnapshot,
    ): AssistantOsSnapshot =
        snapshot.let { current ->
            val resumableSnapshots = SessionPlatformFacade.readResumablePlatformSnapshots(limit = MAX_VISIBLE_SESSION_BACKLOG)
            val replaySnapshots = ReplayStore.readRecentSessionSnapshots(limit = MAX_VISIBLE_SESSION_BACKLOG * 2)
            val remoteSnapshot = RemoteBridgeStore.readSnapshot(requestLimit = MAX_VISIBLE_PENDING_ACTIONS, eventLimit = MAX_VISIBLE_PENDING_ACTIONS)
            val workers = SessionWorkerStore.readAll(limit = MAX_VISIBLE_PENDING_ACTIONS)
            val proactiveTasks = AssistantProactiveTaskStore.readAll(limit = MAX_VISIBLE_PENDING_ACTIONS)
            val externalSignals = AssistantExternalSignalStore.readAll(limit = MAX_VISIBLE_PENDING_ACTIONS)
            val taskInbox =
                deriveTaskInbox(
                    pendingActions = current.pendingActions,
                    triggers = TriggerRegistry.readAll(),
                    sessionBacklog = deriveSessionCards(resumableSnapshots),
                    approvalSessions = deriveApprovalSessionCards(resumableSnapshots, current.activeSession),
                    failedSessions = deriveFailedSessionCards(replaySnapshots),
                    workers = workers,
                    proactiveTasks = proactiveTasks,
                    remoteRequests = remoteSnapshot.pendingRequests,
                    externalSignals = externalSignals,
                )
            val sessionBacklog = deriveSessionCards(resumableSnapshots)
            val approvalSessions = deriveApprovalSessionCards(resumableSnapshots, current.activeSession)
            val failedSessions = deriveFailedSessionCards(replaySnapshots)
            current.copy(
                surfaces = deriveSurfaceStates(current),
                pendingActions =
                    current.pendingActions
                        .sortedByDescending { it.createdAtMs }
                        .take(MAX_VISIBLE_PENDING_ACTIONS),
                taskInbox = taskInbox,
                sessionBacklog = sessionBacklog,
                approvalSessions = approvalSessions,
                failedSessions = failedSessions,
                health =
                    deriveHealthSnapshot(
                        activeSession = current.activeSession,
                        taskInbox = taskInbox,
                        sessionBacklog = sessionBacklog,
                        approvalSessions = approvalSessions,
                        failedSessions = failedSessions,
                    ),
            )
        }

    private fun deriveTaskInbox(
        pendingActions: List<AssistantPendingAction>,
        triggers: List<AssistantTrigger>,
        sessionBacklog: List<AssistantSessionCard>,
        approvalSessions: List<AssistantSessionCard>,
        failedSessions: List<AssistantSessionCard>,
        workers: List<SessionWorkerRecord>,
        proactiveTasks: List<AssistantProactiveTask>,
        remoteRequests: List<RemoteBridgeRequest>,
        externalSignals: List<AssistantExternalSignal>,
    ): List<AssistantWorkQueueItem> {
        val now = System.currentTimeMillis()
        return buildList {
            pendingActions.forEach { action ->
                add(
                    AssistantWorkQueueItem(
                        id = "pending:${action.type.name}:${action.sessionId}:${action.createdAtMs}",
                        type = AssistantWorkQueueItemType.PENDING_ACTION,
                        title = action.title,
                        summary = action.summary,
                        sessionId = action.sessionId,
                        source = action.surface.name.lowercase(),
                        createdAtMs = action.createdAtMs,
                        priority = 80,
                        recommendedCommand =
                            when (action.type) {
                                AssistantPendingActionType.RESUME_SESSION -> "/resume --session-id ${action.sessionId}"
                                AssistantPendingActionType.APPROVE_SAFETY -> "/approve --session-id ${action.sessionId}"
                                AssistantPendingActionType.REJECT_SAFETY -> "/reject --session-id ${action.sessionId}"
                                AssistantPendingActionType.STOP_SESSION -> "/stop --session-id ${action.sessionId}"
                                else -> "/today"
                            },
                    ),
                )
            }
            triggers.forEach { trigger ->
                add(
                    AssistantWorkQueueItem(
                        id = trigger.id,
                        type = AssistantWorkQueueItemType.TRIGGER,
                        title = trigger.title,
                        summary = trigger.summary,
                        sessionId = trigger.sessionId,
                        statusCode = trigger.metadata["status"].orEmpty(),
                        source = trigger.type.name.lowercase(),
                        createdAtMs = trigger.nextFireAtMs,
                        priority = trigger.metadata["priority"]?.toIntOrNull() ?: 30,
                        deadlineAtMs = trigger.nextFireAtMs,
                        recommendedCommand = "/today",
                    ),
                )
            }
            workers.forEach { worker ->
                add(
                    AssistantWorkQueueItem(
                        id = "worker:${worker.workerId}",
                        type = AssistantWorkQueueItemType.WORKER_SESSION,
                        title = "Worker ${worker.task.ifBlank { worker.workerId }}",
                        summary = worker.summary,
                        sessionId = worker.sessionId,
                        statusCode = worker.status.name.lowercase(),
                        source = worker.source.ifBlank { "worker_queue" },
                        createdAtMs = worker.updatedAtMs,
                        priority = if (worker.status.name.equals("BLOCKED", ignoreCase = true)) 75 else 55,
                        recommendedCommand = "/worker-mailbox --target ${worker.workerId}",
                    ),
                )
            }
            proactiveTasks.forEach { task ->
                val overdueMinutes = ((now - task.fireAtMs).coerceAtLeast(0L) / 60_000L).toInt()
                add(
                    AssistantWorkQueueItem(
                        id = task.id,
                        type = AssistantWorkQueueItemType.PROACTIVE_TASK,
                        title = task.title,
                        summary =
                            buildString {
                                append(task.summary)
                                if (overdueMinutes > 0) append(" | 逾期 ${overdueMinutes} 分钟")
                                if (task.deferCount > 0) append(" | 已延后 ${task.deferCount} 次")
                            }.ifBlank { task.title },
                        sessionId = task.sessionId,
                        statusCode = task.capability.name.lowercase(),
                        source = task.source.ifBlank { task.type.name.lowercase() },
                        createdAtMs = task.fireAtMs,
                        priority = task.priority + (overdueMinutes / 10).coerceAtMost(20),
                        deadlineAtMs = task.deadlineAtMs,
                        deferCount = task.deferCount,
                        recommendedCommand = task.recommendedCommand,
                    ),
                )
            }
            remoteRequests.forEach { request ->
                add(
                    AssistantWorkQueueItem(
                        id = request.requestId,
                        type = AssistantWorkQueueItemType.REMOTE_REQUEST,
                        title = "远程请求 ${request.capability.name.lowercase()}",
                        summary = request.summary,
                        sessionId = request.sessionId,
                        statusCode = request.status.name.lowercase(),
                        source = request.entrySource,
                        createdAtMs = request.createdAtMs,
                        priority = 45,
                        recommendedCommand = "/command-center",
                    ),
                )
            }
            externalSignals.forEach { signal ->
                add(
                    AssistantWorkQueueItem(
                        id = "signal:${signal.id}",
                        type = AssistantWorkQueueItemType.TRIGGER,
                        title = signal.title,
                        summary = signal.summary,
                        sessionId = signal.sessionId,
                        statusCode = signal.type.name.lowercase(),
                        source = signal.source.ifBlank { "external_signal" },
                        createdAtMs = signal.fireAtMs,
                        priority = signal.payload["priority"]?.toIntOrNull() ?: 35,
                        deadlineAtMs = signal.fireAtMs,
                        recommendedCommand = "/today",
                    ),
                )
            }
            sessionBacklog.forEach { session ->
                add(
                    AssistantWorkQueueItem(
                        id = "resume:${session.sessionId}",
                        type = AssistantWorkQueueItemType.RESUMABLE_SESSION,
                        title = "恢复任务 ${session.task.ifBlank { session.sessionId }}",
                        summary = session.summary,
                        sessionId = session.sessionId,
                        statusCode = session.statusCode,
                        source = "session_backlog",
                        createdAtMs = session.updatedAtMs,
                        priority = 70,
                        recommendedCommand = "/resume --session-id ${session.sessionId}",
                    ),
                )
            }
            approvalSessions.forEach { session ->
                add(
                    AssistantWorkQueueItem(
                        id = "approval:${session.sessionId}",
                        type = AssistantWorkQueueItemType.APPROVAL_SESSION,
                        title = "处理高风险确认",
                        summary = session.summary,
                        sessionId = session.sessionId,
                        statusCode = session.statusCode,
                        source = "approval_queue",
                        createdAtMs = session.updatedAtMs,
                        priority = 95,
                        recommendedCommand = "/approve --session-id ${session.sessionId}",
                    ),
                )
            }
            failedSessions.forEach { session ->
                add(
                    AssistantWorkQueueItem(
                        id = "failed:${session.sessionId}",
                        type = AssistantWorkQueueItemType.FAILED_SESSION,
                        title = "回看失败任务",
                        summary = session.summary,
                        sessionId = session.sessionId,
                        statusCode = session.statusCode,
                        source = "failed_queue",
                        createdAtMs = session.updatedAtMs,
                        priority = 60,
                        recommendedCommand = "/viewer --session-id ${session.sessionId}",
                    ),
                )
            }
        }
            .sortedWith(
                compareByDescending<AssistantWorkQueueItem> { it.priority }
                    .thenBy { it.deadlineAtMs.takeIf { deadline -> deadline > 0L } ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAtMs },
            )
            .distinctBy { it.id }
            .take(MAX_VISIBLE_TASK_INBOX)
    }

    private fun deriveSessionCards(
        snapshots: List<SessionPlatformSnapshot>,
    ): List<AssistantSessionCard> =
        snapshots
            .map { snapshot ->
                snapshot.toAssistantSessionCard()
            }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_VISIBLE_SESSION_BACKLOG)

    private fun deriveApprovalSessionCards(
        snapshots: List<SessionPlatformSnapshot>,
        activeSession: AssistantActiveSession,
    ): List<AssistantSessionCard> =
        buildList {
            if (activeSession.awaitingConfirmation && activeSession.sessionId.isNotBlank()) {
                add(
                    AssistantSessionCard(
                        sessionId = activeSession.sessionId,
                        statusSnapshot = activeSession.statusSnapshot,
                        task = activeSession.task,
                        summary = activeSession.summary,
                        routeReason = activeSession.routeReason,
                        entrySource = activeSession.entrySource,
                        targetPackageName = activeSession.targetPackageName,
                        turn = activeSession.turn,
                        resumable = activeSession.resumable,
                        awaitingConfirmation = true,
                        waitingForExternal = activeSession.waitingForExternal,
                        updatedAtMs = activeSession.updatedAtMs,
                    ),
                )
            }
            snapshots.forEach { snapshot ->
                if (snapshot.pendingSafetySummary.isNotBlank()) {
                    add(snapshot.toAssistantSessionCard(summaryOverride = snapshot.pendingSafetySummary))
                }
            }
        }
            .distinctBy { it.sessionId }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_VISIBLE_PENDING_ACTIONS)

    private fun deriveFailedSessionCards(
        snapshots: List<ReplaySessionSnapshot>,
    ): List<AssistantSessionCard> =
        snapshots
            .filter { snapshot ->
                snapshot.statusModel.isTerminalReason(
                    AgentUiTerminalReason.FAILED,
                    AgentUiTerminalReason.MAX_TURNS_REACHED,
                    AgentUiTerminalReason.STOPPED,
                )
            }
            .map { snapshot ->
                AssistantSessionCard(
                    sessionId = snapshot.sessionId,
                    statusSnapshot = snapshot.statusSnapshot,
                    task = snapshot.task,
                    summary =
                        snapshot.finalTaskResult?.summary.orEmpty().ifBlank {
                            snapshot.finalMessage.ifBlank {
                                snapshot.latestTurn?.result.orEmpty()
                            }
                        },
                    routeReason = snapshot.routeReason,
                    entrySource = snapshot.entrySource,
                    targetPackageName = snapshot.targetPackageName,
                    turn = snapshot.turnCount,
                    resumable = false,
                    awaitingConfirmation = snapshot.pendingSafetyConfirmation.isNotBlank(),
                    waitingForExternal = snapshot.resume.waitingForExternal,
                    updatedAtMs = snapshot.updatedAt,
                )
            }
            .take(MAX_VISIBLE_PENDING_ACTIONS)

    private fun deriveHealthSnapshot(
        activeSession: AssistantActiveSession,
        taskInbox: List<AssistantWorkQueueItem>,
        sessionBacklog: List<AssistantSessionCard>,
        approvalSessions: List<AssistantSessionCard>,
        failedSessions: List<AssistantSessionCard>,
    ): AssistantHealthSnapshot {
        val now = System.currentTimeMillis()
        val dueTriggerCount = TriggerRegistry.readDue(now).size
        val staleRuntime =
            activeSession.sessionId.isNotBlank() &&
                activeSession.updatedAtMs > 0L &&
                now - activeSession.updatedAtMs >= STALE_RUNTIME_THRESHOLD_MS &&
                (
                    resolveAgentUiStatusModel(activeSession.statusCode).category == AgentUiStatusCategory.ACTIVE_EXECUTION ||
                        activeSession.awaitingConfirmation ||
                        activeSession.waitingForExternal
                )
        val status =
            when {
                approvalSessions.isNotEmpty() -> "needs_attention"
                failedSessions.isNotEmpty() -> "degraded"
                staleRuntime -> "stale"
                dueTriggerCount > 0 || sessionBacklog.isNotEmpty() -> "queued"
                taskInbox.isNotEmpty() -> "queued"
                activeSession.sessionId.isNotBlank() -> "active"
                else -> "idle"
            }
        val summary =
            when {
                approvalSessions.isNotEmpty() -> "有 ${approvalSessions.size} 个高风险确认待处理"
                failedSessions.isNotEmpty() -> "最近有 ${failedSessions.size} 个失败/终止任务待回看"
                staleRuntime -> "当前活跃会话超过 20 分钟未刷新，建议检查现场"
                dueTriggerCount > 0 -> "有 $dueTriggerCount 个 trigger 已到期"
                sessionBacklog.isNotEmpty() -> "有 ${sessionBacklog.size} 个可恢复任务"
                taskInbox.isNotEmpty() -> "有 ${taskInbox.size} 个后台待办"
                activeSession.sessionId.isNotBlank() -> "当前会话运行中"
                else -> "当前没有待处理任务"
            }
        return AssistantHealthSnapshot(
            status = status,
            summary = summary,
            activeSessionId = activeSession.sessionId,
            staleRuntime = staleRuntime,
            backlogCount = taskInbox.size,
            resumableSessionCount = sessionBacklog.size,
            failedSessionCount = failedSessions.size,
            approvalSessionCount = approvalSessions.size,
            dueTriggerCount = dueTriggerCount,
            lastProjectionAtMs = now,
        )
    }

    private fun SessionPlatformSnapshot.toAssistantSessionCard(
        summaryOverride: String = "",
    ): AssistantSessionCard {
        val bridge = bridgeSnapshot
        return AssistantSessionCard(
            sessionId = bridge.sessionId,
            statusSnapshot = bridge.toStatusSnapshot(),
            task = bridge.task,
            summary =
                summaryOverride.ifBlank {
                    bridge.resultSummary.ifBlank {
                        bridge.errorSummary.ifBlank {
                            bridge.takeoverSummary.ifBlank {
                                pendingSafetySummary
                            }
                        }
                    }
                },
            routeReason = bridge.routeReason,
            entrySource = bridge.entrySource,
            targetPackageName = bridge.targetPackageName,
            turn = bridge.turn,
            resumable = bridge.resumable,
            awaitingConfirmation = bridge.pendingSafetyConfirmation.isNotBlank() || pendingSafetySummary.isNotBlank(),
            waitingForExternal = bridge.statusTakeoverReason == com.lmx.xiaoxuanagent.runtime.AgentUiTakeoverReason.WAITING_EXTERNAL,
            updatedAtMs = state.updatedAtMs,
        )
    }

    private fun deriveSurfaceStates(
        snapshot: AssistantOsSnapshot,
    ): List<AssistantSurfaceState> {
        return AssistantEntrySurface.entries.map { surface ->
            val availability = PermissionModeOrchestrator.resolveSurfaceAvailability(snapshot, surface)
            AssistantSurfaceState(
                surface = surface,
                supported = availability.supported,
                enabled = availability.enabled,
                available = availability.available,
                summary = availability.summary,
            )
        }
    }

    private fun derivePendingActions(
        snapshot: SessionBridgeSnapshot,
        hasOverlayPermission: Boolean = canDrawOverlays(),
    ): List<AssistantPendingAction> {
        val now = System.currentTimeMillis()
        return buildList {
            if (snapshot.pendingSafetyConfirmation.isNotBlank()) {
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.APPROVE_SAFETY,
                        title = "确认高风险动作",
                        summary = snapshot.pendingSafetyConfirmation,
                        surface = AssistantEntrySurface.NOTIFICATION,
                        sessionId = snapshot.sessionId,
                        createdAtMs = now,
                    ),
                )
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.REJECT_SAFETY,
                        title = "拒绝当前高风险动作",
                        summary = snapshot.pendingSafetyConfirmation,
                        surface = AssistantEntrySurface.NOTIFICATION,
                        sessionId = snapshot.sessionId,
                        createdAtMs = now - 1,
                    ),
                )
            }
            if (snapshot.resumable && snapshot.statusCategory == AgentUiStatusCategory.TAKEOVER) {
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.RESUME_SESSION,
                        title = "继续当前任务",
                        summary = snapshot.takeoverSummary.ifBlank { snapshot.task.ifBlank { "恢复当前任务" } },
                        surface = AssistantEntrySurface.NOTIFICATION,
                        sessionId = snapshot.sessionId,
                        createdAtMs = now - 2,
                    ),
                )
            }
            if (snapshot.targetAppReturnEligible) {
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.RETURN_TO_TARGET_APP,
                        title = "回到目标 App",
                        summary = snapshot.targetPackageName.ifBlank { snapshot.task.ifBlank { "返回现场继续" } },
                        surface = AssistantEntrySurface.NOTIFICATION,
                        sessionId = snapshot.sessionId,
                        createdAtMs = now - 3,
                    ),
                )
            }
            if (snapshot.sessionId.isNotBlank()) {
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.STOP_SESSION,
                        title = "停止当前任务",
                        summary = snapshot.task.ifBlank { snapshot.statusCode },
                        surface = AssistantEntrySurface.NOTIFICATION,
                        sessionId = snapshot.sessionId,
                        createdAtMs = now - 4,
                    ),
                )
            }
            val overlayEnabled = snapshot().isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB)
            if (overlayEnabled && !hasOverlayPermission) {
                add(
                    AssistantPendingAction(
                        type = AssistantPendingActionType.ENABLE_OVERLAY_PERMISSION,
                        title = "开启悬浮入口权限",
                        summary = "SYSTEM_ALERT_WINDOW 权限未开启，悬浮入口还不能拉起控制台。",
                        surface = AssistantEntrySurface.MAIN_APP,
                        createdAtMs = now - 5,
                    ),
                )
            }
        }.take(MAX_VISIBLE_PENDING_ACTIONS)
    }

    fun syncOverlaySurface(snapshot: AssistantOsSnapshot = snapshot()) {
        val enabled =
            snapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB) &&
                snapshot.isExperimentEnabled(AssistantExperimentKey.PERSISTENT_ASSISTANT_OS_V1) &&
                canDrawOverlays()
        val context = AppRuntimeContext.get() ?: return
        if (enabled) {
            AssistantOverlayService.start(context)
        } else {
            AssistantOverlayService.stop(context)
        }
    }
}
