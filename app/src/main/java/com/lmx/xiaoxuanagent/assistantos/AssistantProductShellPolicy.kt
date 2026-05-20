package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.ArtifactLifecycleSnapshot
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionReport
import com.lmx.xiaoxuanagent.runtime.PlatformTraceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionExecutionSchedulerSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionGraphNode
import com.lmx.xiaoxuanagent.runtime.SessionReplayTimelineEntry
import com.lmx.xiaoxuanagent.runtime.SessionSwarmCoordinationSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionWorkerRecord
import com.lmx.xiaoxuanagent.runtime.escalationPressureSummary
import com.lmx.xiaoxuanagent.runtime.joinPressureSummary
import com.lmx.xiaoxuanagent.runtime.missionMixSummary

internal object AssistantProductShellPolicy {
    fun deriveSwarmStrategy(
        now: Long,
        activeSessionId: String,
        swarmCoordination: SessionSwarmCoordinationSnapshot,
        sessionGraph: List<SessionGraphNode>,
        retentionPreview: ArtifactRetentionReport,
    ): AssistantSwarmStrategy =
        AssistantSwarmStrategy(
            mode = swarmCoordination.mode,
            summary = swarmCoordination.summary,
            maxConcurrentWorkers = swarmCoordination.maxConcurrentWorkers,
            mailboxBatchSize = swarmCoordination.mailboxBatchSize,
            mailboxPriorityMode = swarmCoordination.mailboxPriorityMode,
            fairnessMode = swarmCoordination.fairnessMode,
            leasePolicyMode = "runtime_lease_protocol",
            coordinationMode = swarmCoordination.coordinationMode,
            leasePressureSummary = swarmCoordination.leasePressureSummary,
            dispatchSummary =
                listOf(
                    swarmCoordination.workerDispatchSummary.takeIf { it.isNotBlank() },
                    swarmCoordination.proactiveDispatchSummary.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" | "),
            missionSummary = swarmCoordination.missionSummary,
            escalationSummary = swarmCoordination.escalationSummary,
            joinSummary = swarmCoordination.joinSummary,
            collaboratorSummary =
                listOf(
                    swarmCoordination.ownerFairnessSummary.takeIf { it.isNotBlank() },
                    swarmCoordination.parallelismPressureSummary.takeIf { it.isNotBlank() },
                    sessionGraph.firstOrNull()?.let { "focus=${it.sessionId.takeLast(8)}:${it.status}" },
                ).filterNotNull().joinToString(" | "),
            dispatchCandidates = swarmCoordination.dispatchCandidates,
            recommendedWorkerIds = swarmCoordination.recommendedWorkerIds,
            focusSessionIds =
                sessionGraph
                    .sortedWith(compareByDescending<SessionGraphNode> { it.pendingApprovalCount }.thenByDescending { it.mailboxPendingCount })
                    .take(3)
                    .map { it.sessionId }
                    .ifEmpty { swarmCoordination.focusSessionIds },
            blockedCoordinatorIds = swarmCoordination.blockedCoordinatorIds,
            laneLines =
                buildList {
                    sessionGraph
                        .sortedWith(compareByDescending<SessionGraphNode> { it.pendingApprovalCount }.thenByDescending { it.mailboxPendingCount })
                        .take(3)
                        .forEach { node ->
                            add(
                                "${node.sessionId.takeLast(8)} | depth=${node.depth} | approvals=${node.pendingApprovalCount} | mailbox=${node.mailboxPendingCount}",
                            )
                        }
                    swarmCoordination.dispatchCandidates.take(3).forEach { candidate ->
                        add("dispatch | $candidate")
                    }
                },
            handoffActions =
                buildList {
                    if (swarmCoordination.pendingPermissionRequests > 0) add("先处理 worker 权限协作")
                    if (swarmCoordination.pendingMailboxMessages > 0) add("收口 mailbox reply/control")
                    if (sessionGraph.any { it.pendingChildSessionIds.isNotEmpty() }) add("关注 parent-child handoff")
                },
            recommendedCommands =
                buildList {
                    addAll(swarmCoordination.recommendedActions)
                    if (swarmCoordination.pendingPermissionRequests > 0 && activeSessionId.isNotBlank()) add("/operator --session-id $activeSessionId")
                    if (swarmCoordination.blockedCoordinatorIds.isNotEmpty()) add("/worker-lease")
                    if (retentionPreview.deletedArtifacts > 0) add("/artifact-retention")
                }.distinct(),
            pendingPermissionRequests = swarmCoordination.pendingPermissionRequests,
            pendingMailboxMessages = swarmCoordination.pendingMailboxMessages,
            updatedAtMs = now,
        )

    fun deriveOperatorShell(
        now: Long,
        providers: List<AssistantSignalProviderState>,
        workers: List<SessionWorkerRecord>,
        schedulerSnapshot: SessionExecutionSchedulerSnapshot,
        retentionPreview: ArtifactRetentionReport,
        sessionGraph: List<SessionGraphNode>,
        blockedGraphNodes: List<SessionGraphNode>,
        pendingPermissionRequests: Int,
        unhealthyProviders: List<AssistantSignalProviderState>,
        focusSessionId: String,
        lifecycleSnapshot: ArtifactLifecycleSnapshot?,
        focusPlatformSnapshot: SessionPlatformSnapshot?,
        replayTimeline: List<SessionReplayTimelineEntry>,
        swarmCoordination: SessionSwarmCoordinationSnapshot,
        traceSnapshot: PlatformTraceSnapshot,
    ): AssistantOperatorShellSnapshot {
        val providersInCooldown = providers.count { it.cooldownUntilMs > now }
        val pendingControlMessages = swarmCoordination.controlCount
        val pendingReplyMessages = swarmCoordination.replyChainCount.coerceAtLeast(swarmCoordination.pendingPermissionResponses)
        val operatorUrgentLines =
            buildList {
                if (pendingPermissionRequests > 0) {
                    add("当前有 $pendingPermissionRequests 条 worker 权限协作待处理。")
                }
                if (pendingReplyMessages > 0 || pendingControlMessages > 0) {
                    add("mailbox 中仍有 $pendingReplyMessages 条 reply-chain、$pendingControlMessages 条 control 消息待收口。")
                }
                if (unhealthyProviders.isNotEmpty()) {
                    add("有 ${unhealthyProviders.size} 个 provider 健康度下降，需要关注路由与冷却。")
                }
                if (providersInCooldown > 0) {
                    add("有 $providersInCooldown 个 provider 正处于 cooldown，外部信号链路存在降级。")
                }
                if (retentionPreview.deletedArtifacts > 0 || retentionPreview.deletedSessions > 0) {
                    add("artifact retention 预览显示可清理 ${retentionPreview.deletedArtifacts} 个 artifact、${retentionPreview.deletedSessions} 个 session。")
                }
                lifecycleSnapshot?.deletionCandidateCount?.takeIf { it > 0 }?.let { count ->
                    add("focus session 当前按 retention policy 预计有 $count 个 artifact 会进入清理候选。")
                }
                if (blockedGraphNodes.isNotEmpty()) {
                    add("session graph 中有 ${blockedGraphNodes.size} 个会话处于阻塞/待收口状态。")
                }
                if (focusPlatformSnapshot?.healthSummary?.staleRuntime == true) {
                    add("当前 focus session 已出现 stale runtime，建议先做 replay/operator 检查。")
                }
                if (schedulerSnapshot.dispatchPlan.size >= 2) {
                    add("execution scheduler 已形成 ${schedulerSnapshot.dispatchPlan.size} 条 batch dispatch plan，可按 owner 分批推进。")
                }
                if (swarmCoordination.dueProactiveCount > 0) {
                    add("proactive queue 当前有 ${swarmCoordination.dueProactiveCount} 条任务到点，需与 swarm 调度一起收口。")
                }
                if (traceSnapshot.attentionCount > 0) {
                    add("trace 流中有 ${traceSnapshot.attentionCount} 条失败/超时/降级信号，建议优先检查 operator 与 provider 路由。")
                }
            }
        return AssistantOperatorShellSnapshot(
            mode =
                when {
                    operatorUrgentLines.isNotEmpty() -> "attention"
                    schedulerSnapshot.dispatchPlan.isNotEmpty() -> "batched_dispatch"
                    schedulerSnapshot.runnableSessionIds.isNotEmpty() -> "dispatch_ready"
                    else -> "idle"
                },
            summary =
                when {
                    operatorUrgentLines.isNotEmpty() -> operatorUrgentLines.first()
                    schedulerSnapshot.dispatchPlan.isNotEmpty() -> "当前已生成 ${schedulerSnapshot.dispatchPlan.size} 条 session dispatch plan。"
                    schedulerSnapshot.runnableSessionIds.isNotEmpty() -> "当前有 ${schedulerSnapshot.runnableSessionIds.size} 个 session 可调度。"
                    else -> "当前平台没有需要立即处理的操作阻塞。"
                },
            providerHealthSummary =
                buildString {
                    append("providers=").append(providers.size)
                    append(" unhealthy=").append(unhealthyProviders.size)
                    append(" cooldown=").append(providersInCooldown)
                    val hottest = providers.maxByOrNull { it.routingPriority }
                    hottest?.let { append(" top=").append(it.providerId) }
                },
            providerCoverageSummary =
                providers.joinToString(" | ") { provider ->
                    buildString {
                        append(provider.providerId)
                        append(":")
                        append(provider.supportedSignalTypes.joinToString(",") { it.name.lowercase() }.ifBlank { "*" })
                        provider.deliveryMode.takeIf { it.isNotBlank() }?.let {
                            append(" mode=").append(it)
                        }
                        provider.routingTags.takeIf { it.isNotEmpty() }?.let {
                            append(" tags=").append(it.joinToString(","))
                        }
                    }
                },
            providerDiagnosticsSummary =
                providers.joinToString(" | ") { provider ->
                    buildString {
                        append(provider.providerId)
                        append(":").append(provider.lastFailureMode.ifBlank { "ok" })
                        append(":").append(provider.diagnosticsSummary.ifBlank { provider.lastReason.ifBlank { "-" } })
                        if (provider.cooldownUntilMs > now) {
                            append(":cooldown=").append(((provider.cooldownUntilMs - now).coerceAtLeast(0L) / 1000L)).append("s")
                        }
                    }
                },
            artifactHealthSummary =
                buildString {
                    append("preview_deleted_artifacts=").append(retentionPreview.deletedArtifacts)
                    append(" preview_deleted_sessions=").append(retentionPreview.deletedSessions)
                    append(" pinned=").append(retentionPreview.pinnedArtifacts)
                    append(" hot=").append(retentionPreview.hotArtifacts)
                    lifecycleSnapshot?.turnWindowSummary?.takeIf { it.isNotBlank() }?.let {
                        append(" focus=").append(it.take(72))
                    }
                    lifecycleSnapshot?.deletionRiskSummary?.takeIf { it.isNotBlank() }?.let {
                        append(" ").append(it.take(96))
                    }
                },
            replayHealthSummary =
                buildString {
                    append("focus_session=").append(focusSessionId.ifBlank { "-" })
                    focusPlatformSnapshot?.healthSummary?.let { health ->
                        append(" health=").append(health.status)
                        append(" replay=").append(health.deterministicReplayReady)
                    }
                    replayTimeline.takeLast(3).takeIf { it.isNotEmpty() }?.let { timeline ->
                        append(" tail=")
                        append(timeline.joinToString(" / ") { entry -> "${entry.commandType}:${entry.statusCode}" }.take(120))
                    }
                    lifecycleSnapshot?.recentArtifactLines?.firstOrNull()?.let { recent ->
                        append(" artifact=").append(recent.take(72))
                    }
                },
            workerHealthSummary =
                buildString {
                    append("runnable_sessions=").append(schedulerSnapshot.runnableSessionIds.size)
                    append(" blocked_sessions=").append(schedulerSnapshot.blockedSessionIds.size)
                    append(" runnable_workers=").append(schedulerSnapshot.runnableWorkerIds.size)
                    append(" active_leases=").append(schedulerSnapshot.activeLeases.size)
                    append(" mailbox=").append(swarmCoordination.pendingMailboxMessages)
                    schedulerSnapshot.fairnessSummary.takeIf { it.isNotBlank() }?.let {
                        append(" fairness=").append(it)
                    }
                },
            workerMissionSummary =
                "${workers.missionMixSummary()} | ${workers.escalationPressureSummary()} | ${workers.joinPressureSummary()}",
            workerLeaseSummary =
                buildString {
                    append("blocked_coordinators=").append(sessionGraph.count { it.mailboxPendingCount > 0 || it.pendingApprovalCount > 0 })
                    append(" reply=").append(pendingReplyMessages)
                    append(" control=").append(pendingControlMessages)
                    append(" focus=").append(focusSessionId.ifBlank { "-" })
                    append(" leases=").append(schedulerSnapshot.activeLeases.size)
                    schedulerSnapshot.dispatchPlan.firstOrNull()?.let { first ->
                        append(" plan=").append(first.sessionId.takeLast(8)).append(":").append(first.lane)
                    }
                },
            graphHealthSummary =
                buildString {
                    append("nodes=").append(sessionGraph.size)
                    append(" blocked=").append(blockedGraphNodes.size)
                    append(" runnable=").append(schedulerSnapshot.runnableSessionIds.size)
                    blockedGraphNodes.maxByOrNull { it.pendingApprovalCount * 10 + it.mailboxPendingCount }?.let { hottestNode ->
                        append(" hottest=").append(hottestNode.sessionId.takeLast(8))
                    }
                    schedulerSnapshot.ownerQueueSummary.firstOrNull()?.let { ownerSummary ->
                        append(" owner=").append(ownerSummary.take(72))
                    }
                },
            swarmPolicySummary =
                buildString {
                    append("mode=").append(swarmCoordination.mode)
                    append(" | ").append(swarmCoordination.parallelismPressureSummary.ifBlank { "-" })
                    swarmCoordination.ownerFairnessSummary.takeIf { it.isNotBlank() }?.let {
                        append(" | fairness=").append(it)
                    }
                    swarmCoordination.traceAttentionSummary.takeIf { it.isNotBlank() }?.let {
                        append(" | trace=").append(it)
                    }
                },
            urgentLines = operatorUrgentLines,
            recommendedCommands =
                buildList {
                    add("/operator")
                    if (pendingPermissionRequests > 0) add("/worker-reply")
                    if (swarmCoordination.pendingMailboxMessages > 0) add("/mailbox")
                    if (unhealthyProviders.isNotEmpty()) add("/provider-policy")
                    if (sessionGraph.any { it.mailboxPendingCount > 0 }) add("/mailbox")
                    if (retentionPreview.deletedArtifacts > 0) add("/run-artifact-retention")
                    if (focusSessionId.isNotBlank()) add("/inspect-replay-breakpoint")
                    if (focusPlatformSnapshot?.healthSummary?.deterministicReplayReady == true) add("/run-step-replay")
                }.distinct(),
            updatedAtMs = now,
        )
    }
}
