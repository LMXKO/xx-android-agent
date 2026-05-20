package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTask
import com.lmx.xiaoxuanagent.harness.HarnessStore
import com.lmx.xiaoxuanagent.memory.MemoryRecallIndexStore
import com.lmx.xiaoxuanagent.memory.PersonalMemoryStore
import com.lmx.xiaoxuanagent.skills.SkillInvocationStore

internal data class DebugDashboardSnapshot(
    val memorySummary: String = "暂无个人记忆。",
    val harnessSummary: String = "暂无回归统计。",
    val routingSummary: String = "暂无路由回归统计。",
    val takeoverSummary: String = "暂无人工接管回归统计。",
    val recentSkillInvocations: List<String> = emptyList(),
    val recentSessionSummaries: List<String> = emptyList(),
)

internal fun readDebugDashboardSnapshot(): DebugDashboardSnapshot =
    DebugDashboardSnapshot(
        memorySummary = PersonalMemoryStore.dashboardSummary(),
        harnessSummary = HarnessStore.dashboardSummary(),
        routingSummary = HarnessStore.routePolicySummary(),
        takeoverSummary = HarnessStore.takeoverSummary(),
        recentSkillInvocations = SkillInvocationStore.recentSummary(),
        recentSessionSummaries = ReplayStore.recentSessionSummaries(),
    )

internal fun DebugUiState.withDashboardSnapshot(
    snapshot: DebugDashboardSnapshot,
): DebugUiState =
    copy(
        memorySummary = snapshot.memorySummary,
        harnessSummary = snapshot.harnessSummary,
        routingSummary = snapshot.routingSummary,
        takeoverSummary = snapshot.takeoverSummary,
        recentSkillInvocations = snapshot.recentSkillInvocations,
        recentSessionSummaries = snapshot.recentSessionSummaries,
    )

internal fun formatPlatformSummaryLines(
    snapshot: SessionPlatformSnapshot,
): List<String> {
    val bridge = snapshot.bridgeSnapshot
    val replay = snapshot.sessionSnapshot
    val resume = snapshot.resumeSnapshot
    val latestTurn = replay?.latestTurn
    val health = snapshot.healthSummary
    val metrics = snapshot.metricsSnapshot
    return buildList {
        add("session=${bridge.sessionId.ifBlank { "-" }}")
        add("status=${bridge.statusCode}")
        add("resumable=${bridge.resumable}")
        add("targetReturn=${bridge.targetAppReturnEligible}")
        add("health=${health.status} | ${health.summary.ifBlank { "-" }}")
        add("deterministicReplay=${health.deterministicReplayReady}")
        add("staleRuntime=${health.staleRuntime}")
        add("resumeSnapshot=${resume != null}")
        add("bridgeFeed=${health.bridgeFeedPresent}")
        add("runtimeLedgerReady=${health.runtimeLedgerPresent}")
        add("replayTurns=${replay?.turnCount ?: 0}")
        add("replayEvents=${replay?.recentEvents?.size ?: 0}")
        add("runtimeLedger=${snapshot.recentRuntimeEvents.size}")
        add("bridgeEvents=${snapshot.recentBridgeEvents.size}")
        add("artifacts=${snapshot.recentArtifacts.size}")
        add("pendingSafety=${snapshot.pendingSafetySummary.ifBlank { "-" }}")
        add("approvalTickets=${health.pendingApprovalCount}")
        add("remotePending=${health.remotePendingCount}")
        add("workerQueue=${health.workerQueueCount}")
        add("mailboxPending=${health.mailboxPendingCount}")
        add("memoryQueue=${health.memoryQueuePendingCount}")
        add("proactiveTasks=${health.proactiveTaskCount}")
        add("metric.commands=${metrics.commandCounters.take(3).joinToString(" / ") { "${it.key}:${it.count}" }.ifBlank { "-" }}")
        add("metric.bridge=${metrics.bridgeEventCounters.take(3).joinToString(" / ") { "${it.key}:${it.count}" }.ifBlank { "-" }}")
        add("metric.hooks=${metrics.hookCounters.take(3).joinToString(" / ") { "${it.key}:${it.count}" }.ifBlank { "-" }}")
        add("route=${replay?.routeReason.orEmpty().ifBlank { bridge.routeReason.ifBlank { "-" } }}")
        add("latestReplayAction=${latestTurn?.actionLabel.orEmpty().ifBlank { "-" }}")
        add("latestReplayResult=${latestTurn?.result.orEmpty().takeIf { it.isNotBlank() }?.take(48) ?: "-"}")
    }
}

internal fun formatReplayVerification(
    verification: SessionReplayVerification,
): String =
    buildString {
        append("replayable=").append(verification.replayable)
        append(" matches=").append(verification.matches)
        append(" commands=").append(verification.commandCount)
        append(" expected=").append(verification.expectedStatus.ifBlank { "-" })
        append(" replayed=").append(verification.replayedStatus.ifBlank { "-" })
        if (verification.mismatches.isNotEmpty()) {
            append(" mismatches=").append(verification.mismatches.joinToString(" / ").take(180))
        }
    }

internal fun formatApprovalTicketLines(
    tickets: List<SessionApprovalTicket>,
): List<String> =
    tickets.take(6).map { ticket ->
        buildString {
            append(ticket.statusCode)
            append(" | ")
            append(ticket.task.ifBlank { ticket.sessionId })
            append(" | ")
            append(ticket.approvalSummary.ifBlank { "-" }.take(96))
        }
    }

internal fun formatCapabilityLines(
    capabilities: List<SessionCapabilityDescriptor>,
): List<String> =
    capabilities.take(8).map { capability ->
        "${capability.key.name.lowercase()} | ${capability.description}"
    }

internal fun formatRemoteBridgeLines(
    snapshot: RemoteBridgeSnapshot,
): List<String> =
    buildList {
        snapshot.pendingRequests.take(4).forEach { request ->
            add("pending | ${request.capability.name.lowercase()} | ${request.summary.ifBlank { "-" }}")
        }
        snapshot.recentEvents.take(4).forEach { event ->
            add("${event.eventType} | ${event.sessionId.ifBlank { "-" }} | ${event.summary.ifBlank { "-" }}")
        }
    }.ifEmpty { listOf("-") }

internal fun formatRemoteTransportLines(
    snapshot: RemoteTransportSnapshot,
): List<String> =
    buildList {
        snapshot.pendingInbound.take(3).forEach { envelope ->
            add("inbound | ${envelope.capability?.name?.lowercase() ?: envelope.type.name.lowercase()} | ${envelope.summary.ifBlank { "-" }}")
        }
        snapshot.recentOutbound.take(3).forEach { envelope ->
            add("outbound | ${envelope.type.name.lowercase()} | ${envelope.summary.ifBlank { "-" }}")
        }
    }.ifEmpty { listOf("-") }

internal fun formatWorkerLines(
    workers: List<SessionWorkerRecord>,
): List<String> =
    workers.take(6).map { worker ->
        buildString {
            append("d=").append(worker.depth)
            append(" | ").append(worker.status.name.lowercase())
            append(" | ").append(worker.missionLabelResolved())
            append(" | ").append(worker.task.ifBlank { worker.workerId })
            if (worker.requiresEscalationAttention()) {
                append(" | escalate=").append(worker.escalationPolicy.name.lowercase())
            }
            worker.resultSummary.takeIf { it.isNotBlank() }?.let {
                append(" | result=").append(it.take(48))
            } ?: append(" | ").append(worker.summary.ifBlank { "-" })
        }
    }.ifEmpty { listOf("-") }

internal fun formatProactiveTaskLines(
    tasks: List<AssistantProactiveTask>,
): List<String> =
    tasks.take(6).map { task ->
        "${task.type.name.lowercase()} | ${task.title} | ${task.summary.ifBlank { "-" }}"
    }.ifEmpty { listOf("-") }

internal fun formatExternalSignalLines(
    signals: List<AssistantExternalSignal>,
): List<String> =
    signals.take(6).map { signal ->
        "${signal.type.name.lowercase()} | ${signal.title} | ${signal.summary.ifBlank { "-" }}"
    }.ifEmpty { listOf("-") }

internal fun formatMemoryRecallLines(
    query: String,
    profileId: String,
): List<String> =
    MemoryRecallIndexStore.search(query = query, profileId = profileId, limit = 6)
        .map { "${it.type} | ${it.preview}" }
        .ifEmpty { listOf("-") }

internal fun formatTraceLines(
    traceSnapshot: PlatformTraceSnapshot,
): List<String> =
    buildList {
        add(traceSnapshot.categorySummary.ifBlank { "trace_count=0" })
        add(traceSnapshot.coverageSummary.ifBlank { "-" })
        if (traceSnapshot.attentionCount > 0) {
            add(traceSnapshot.attentionSummary.ifBlank { "-" })
        }
        addAll(traceSnapshot.recentLines.take(6))
    }.ifEmpty { listOf("-") }

internal fun formatCompareLines(
    sessionId: String,
): List<String> {
    if (sessionId.isBlank()) return listOf("-")
    val peerSessionId =
        ReplayStore.listSessionIds(limit = 8)
            .firstOrNull { it.isNotBlank() && it != sessionId }
            ?: return listOf("-")
    return SessionPlatformFacade.compareSessions(sessionId, peerSessionId).lines.take(8)
}

internal fun formatBatchReplayLines(): List<String> {
    val sessionIds = ReplayStore.listSessionIds(limit = 4)
    if (sessionIds.isEmpty()) return listOf("-")
    return SessionPlatformFacade.runBatchDeterministicReplay(sessionIds).lines.take(6)
}

internal fun formatStepReplayLines(
    sessionId: String,
): List<String> {
    if (sessionId.isBlank()) return listOf("-")
    val timeline = SessionPlatformFacade.readReplayTimeline(sessionId = sessionId, limit = 12)
    if (timeline.isEmpty()) return listOf("-")
    val report =
        SessionPlatformFacade.runStepReplay(
            sessionId = sessionId,
            uptoCommandCount = timeline.size,
        )
    return report.lines.take(8)
}

internal fun formatBridgeEventLines(
    events: List<SessionBridgeProtocolEntry>,
): List<String> =
    events.take(8).map { entry ->
        buildString {
            append(entry.eventType)
            append(" | ")
            append(entry.sessionId.ifBlank { "-" })
            append(" | ")
            append(entry.summary.take(96))
        }
    }

internal fun formatRuntimeEventLines(
    events: List<RuntimeEventEntry>,
): List<String> =
    events.take(8).map { entry ->
        buildString {
            append(entry.commandType)
            append(" | ")
            append(entry.statusCode)
            append(" | turn=")
            append(entry.turn)
            append(" | ")
            append(entry.transition.ifBlank { "-" })
            append(" | ")
            append(entry.summary.take(96))
        }
    }
