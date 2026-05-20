package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskQueueSnapshot

data class SessionSwarmCoordinationSnapshot(
    val mode: String = "idle",
    val summary: String = "",
    val activeSessionId: String = "",
    val maxConcurrentWorkers: Int = 3,
    val mailboxBatchSize: Int = 4,
    val mailboxPriorityMode: String = "balanced",
    val fairnessMode: String = "coordinator_fair",
    val coordinationMode: String = "scheduler_mailbox_trace_governance",
    val leasePressureSummary: String = "",
    val parallelismPressureSummary: String = "",
    val mailboxCoordinationSummary: String = "",
    val workerDispatchSummary: String = "",
    val proactiveDispatchSummary: String = "",
    val ownerFairnessSummary: String = "",
    val missionSummary: String = "",
    val escalationSummary: String = "",
    val joinSummary: String = "",
    val traceAttentionSummary: String = "",
    val dispatchCandidates: List<String> = emptyList(),
    val recommendedWorkerIds: List<String> = emptyList(),
    val focusSessionIds: List<String> = emptyList(),
    val blockedCoordinatorIds: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    val pendingPermissionRequests: Int = 0,
    val pendingPermissionResponses: Int = 0,
    val pendingMailboxMessages: Int = 0,
    val replyChainCount: Int = 0,
    val controlCount: Int = 0,
    val attentionCount: Int = 0,
    val traceCount: Int = 0,
    val dueProactiveCount: Int = 0,
    val recentLines: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
)

internal object SessionSwarmCoordinationPolicy {
    fun buildSnapshot(
        schedulerSnapshot: SessionExecutionSchedulerSnapshot,
        workerGraph: SessionWorkerGraphSnapshot,
        mailboxSnapshot: SessionWorkerMailboxSnapshot,
        proactiveQueue: AssistantProactiveTaskQueueSnapshot,
        traceSnapshot: PlatformTraceSnapshot,
        activeSessionId: String = schedulerSnapshot.activeSessionId,
    ): SessionSwarmCoordinationSnapshot {
        val pendingPermissionRequests = mailboxSnapshot.permissionRequestCount
        val pendingReplyMessages = mailboxSnapshot.permissionResponseCount
        val waitingChildrenCount = workerGraph.scheduler.waitingChildrenCount
        val starvationRecovery = workerGraph.scheduler.dispatchCandidates.any { it.lane == "starvation_recovery" }
        val mailboxUnblock = workerGraph.scheduler.dispatchCandidates.any { it.lane == "mailbox_unblock" }
        val hasReplyChain = mailboxSnapshot.replyChainCount > 0 && (pendingReplyMessages > 0 || mailboxSnapshot.controlCount > 0)
        val parallelismSaturated =
            schedulerSnapshot.parallelismBudget > 0 &&
                schedulerSnapshot.activeLeases.size >= schedulerSnapshot.parallelismBudget &&
                workerGraph.scheduler.readyWorkerIds.isNotEmpty()
        val mode =
            when {
                pendingPermissionRequests > 0 -> "approval_first"
                proactiveQueue.priorityMode == "approval_first" -> "approval_follow_up"
                hasReplyChain -> "reply_chain_flush"
                starvationRecovery -> "fair_recovery"
                mailboxUnblock -> "mailbox_unblock"
                waitingChildrenCount > 0 -> "unblock_children"
                parallelismSaturated -> "parallelism_saturated"
                workerGraph.scheduler.readyWorkerIds.size >= 2 -> "throughput"
                proactiveQueue.dispatchCandidates.isNotEmpty() -> "proactive_ready"
                traceSnapshot.attentionCount > 0 -> "trace_attention"
                workerGraph.scheduler.queuedCount > 0 || schedulerSnapshot.dispatchPlan.isNotEmpty() -> "observe_queue"
                else -> "idle"
            }
        val parallelismPressureSummary =
            buildString {
                append("budget=").append(schedulerSnapshot.parallelismBudget)
                append(" active_leases=").append(schedulerSnapshot.activeLeases.size)
                append(" runnable_sessions=").append(schedulerSnapshot.runnableSessionIds.size)
                append(" ready_workers=").append(workerGraph.scheduler.readyWorkerIds.size)
                append(" active_workers=").append(workerGraph.scheduler.activeCount)
                append(" queued_workers=").append(workerGraph.scheduler.queuedCount)
            }
        val mailboxCoordinationSummary =
            listOf(
                mailboxSnapshot.categorySummary.takeIf { it.isNotBlank() },
                mailboxSnapshot.coordinationSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ")
        val workerDispatchSummary =
            buildList {
                addAll(
                    schedulerSnapshot.dispatchPlan.take(3).map { plan ->
                        "session:${plan.sessionId.takeLast(8)}:${plan.lane}:${plan.score}"
                    },
                )
                addAll(
                    workerGraph.scheduler.dispatchCandidates.take(4).map { candidate ->
                        "worker:${candidate.lane}:${candidate.missionLabel.ifBlank { candidate.missionType }}:${candidate.workerId.takeLast(8)}:${candidate.score}"
                    },
                )
            }.joinToString(" | ").ifBlank { "dispatch=none" }
        val proactiveDispatchSummary =
            buildList {
                proactiveQueue.summary.takeIf { it.isNotBlank() }?.let(::add)
                addAll(
                    proactiveQueue.dispatchCandidates.take(3).map { candidate ->
                        "proactive:${candidate.lane}:${candidate.task.title.ifBlank { candidate.task.type.name.lowercase() }}:${candidate.score}"
                    },
                )
            }.joinToString(" | ").ifBlank { proactiveQueue.summary.ifBlank { "due=0" } }
        val ownerFairnessSummary =
            listOf(
                schedulerSnapshot.fairnessSummary.takeIf { it.isNotBlank() },
                schedulerSnapshot.ownerQueueSummary.take(2).joinToString(" | ").takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { "fairness=balanced" }
        val traceAttentionSummary =
            listOf(
                traceSnapshot.categorySummary.takeIf { it.isNotBlank() },
                traceSnapshot.coverageSummary.takeIf { it.isNotBlank() },
                traceSnapshot.attentionSummary.takeIf { traceSnapshot.attentionCount > 0 && it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { "trace=none" }
        val focusSessionIds =
            buildList {
                activeSessionId.takeIf { it.isNotBlank() }?.let(::add)
                addAll(schedulerSnapshot.dispatchPlan.map { it.sessionId })
                addAll(workerGraph.scheduler.dispatchCandidates.mapNotNull { it.sessionId.ifBlank { it.rootSessionId }.takeIf(String::isNotBlank) })
            }.distinct().take(4)
        val dispatchCandidates =
            buildList {
                addAll(
                    workerGraph.scheduler.dispatchCandidates.take(6).map { candidate ->
                        "${candidate.workerId}:${candidate.lane}:${candidate.missionType}:${candidate.score}"
                    },
                )
                addAll(
                    proactiveQueue.dispatchCandidates.take(4).map { candidate ->
                        "${candidate.task.id}:${candidate.lane}:${candidate.task.type.name.lowercase()}:${candidate.score}"
                    },
                )
            }
        val recommendedActions =
            buildList {
                if (pendingPermissionRequests > 0) add("/mailbox")
                if (proactiveQueue.priorityMode == "approval_first") add("/approve")
                if (workerGraph.scheduler.dispatchCandidates.isNotEmpty()) add("/worker-dispatch")
                if (hasReplyChain) add("/worker-reply")
                if (mailboxSnapshot.controlCount > 0) add("/mailbox")
                if (proactiveQueue.blockedByActiveSessionCount > 0 && activeSessionId.isNotBlank()) add("/operator --session-id $activeSessionId")
                if (waitingChildrenCount > 0 || mailboxUnblock) add("/operator")
                if (traceSnapshot.attentionCount > 0) add("/operator")
            }.distinct()
        val summary =
            when (mode) {
                "approval_first" -> "当前有 $pendingPermissionRequests 条 worker 权限请求待处理，优先收口审批链。"
                "approval_follow_up" -> "proactive queue 已切到审批跟进模式，先把确认和回执链处理完。"
                "reply_chain_flush" -> "mailbox 中存在 reply/control 回链，先清空协作消息再继续扩并发。"
                "fair_recovery" -> "调度器检测到 owner 饥饿，先做公平恢复，避免某条 session graph 长时间饿死。"
                "mailbox_unblock" -> "worker 调度已转入 mailbox unblock，优先释放卡住的协作链路。"
                "unblock_children" -> "当前有 $waitingChildrenCount 个 worker 等待子任务完成，先清阻塞。"
                "parallelism_saturated" -> "当前并发预算已打满，但仍有 ${workerGraph.scheduler.readyWorkerIds.size} 个 worker 待运行，需要先释放 lease。"
                "throughput" -> "当前有 ${workerGraph.scheduler.readyWorkerIds.size} 个 worker 可并发执行，进入吞吐优先模式。"
                "proactive_ready" -> "当前有 ${proactiveQueue.dueCount} 条 proactive task 到点，其中 ${proactiveQueue.dispatchCandidates.size} 条已进入可派发队列。"
                "trace_attention" -> "trace 流中有 ${traceSnapshot.attentionCount} 条异常/降级信号，建议先做 operator 排查。"
                "observe_queue" -> "当前调度队列仍有积压，继续观察 owner 公平性、mailbox 与 dispatch 释放情况。"
                else -> "当前没有需要立刻干预的 swarm 协调阻塞。"
            }
        val recentLines =
            buildList {
                workerDispatchSummary.takeIf { it.isNotBlank() }?.let { add("dispatch | $it") }
                mailboxSnapshot.recentLines.take(2).forEach { add("mailbox | $it") }
                proactiveQueue.dispatchCandidates.take(2).forEach { candidate ->
                    add("proactive | ${candidate.lane} | ${candidate.task.title.ifBlank { candidate.task.task.ifBlank { candidate.task.id } }}")
                }
                traceSnapshot.recentLines.take(2).forEach { add("trace | $it") }
            }
        val attentionCount =
            pendingPermissionRequests +
                traceSnapshot.attentionCount +
                mailboxSnapshot.attentionCount +
                proactiveQueue.blockedByActiveSessionCount +
                proactiveQueue.blockedByWorkerCount +
                if (waitingChildrenCount > 0) 1 else 0
        val mailboxBatchSize =
            when {
                mailboxSnapshot.controlCount + pendingReplyMessages >= 4 -> 12
                mailboxSnapshot.pendingCount >= 12 -> 12
                mailboxSnapshot.pendingCount >= 6 -> 8
                else -> 4
            }
        val maxConcurrentWorkers =
            when {
                pendingPermissionRequests > 0 -> 2
                starvationRecovery -> 2
                workerGraph.scheduler.readyWorkerIds.size >= 3 -> 4
                else -> 3
            }
        return SessionSwarmCoordinationSnapshot(
            mode = mode,
            summary = summary,
            activeSessionId = activeSessionId,
            maxConcurrentWorkers = maxConcurrentWorkers,
            mailboxBatchSize = mailboxBatchSize,
            mailboxPriorityMode =
                when {
                    pendingPermissionRequests > 0 -> "approval_first"
                    proactiveQueue.priorityMode != "balanced" -> proactiveQueue.priorityMode
                    hasReplyChain -> "reply_chain_first"
                    starvationRecovery -> "stale_first"
                    workerGraph.scheduler.mailboxPriorityMode.isNotBlank() -> workerGraph.scheduler.mailboxPriorityMode
                    mailboxSnapshot.pendingCount >= 10 -> "high_throughput"
                    else -> "balanced"
                },
            fairnessMode = workerGraph.scheduler.fairnessMode,
            coordinationMode = "scheduler_mailbox_trace_governance",
            leasePressureSummary = parallelismPressureSummary,
            parallelismPressureSummary = parallelismPressureSummary,
            mailboxCoordinationSummary = mailboxCoordinationSummary,
            workerDispatchSummary = workerDispatchSummary,
            proactiveDispatchSummary = proactiveDispatchSummary,
            ownerFairnessSummary = ownerFairnessSummary,
            missionSummary = workerGraph.scheduler.missionSummary,
            escalationSummary = workerGraph.scheduler.escalationSummary,
            joinSummary = workerGraph.scheduler.joinSummary,
            traceAttentionSummary = traceAttentionSummary,
            dispatchCandidates = dispatchCandidates,
            recommendedWorkerIds = workerGraph.scheduler.readyWorkerIds.take(3),
            focusSessionIds = focusSessionIds,
            blockedCoordinatorIds = workerGraph.scheduler.blockedCoordinatorIds,
            recommendedActions = recommendedActions,
            pendingPermissionRequests = pendingPermissionRequests,
            pendingPermissionResponses = pendingReplyMessages,
            pendingMailboxMessages = mailboxSnapshot.pendingCount,
            replyChainCount = mailboxSnapshot.replyChainCount,
            controlCount = mailboxSnapshot.controlCount,
            attentionCount = attentionCount,
            traceCount = traceSnapshot.totalCount,
            dueProactiveCount = proactiveQueue.dueCount,
            recentLines = recentLines,
            lines =
                buildList {
                    add("mode=$mode")
                    add(summary)
                    add("parallelism=$parallelismPressureSummary")
                    mailboxCoordinationSummary.takeIf { it.isNotBlank() }?.let { add("mailbox=$it") }
                    add("dispatch=$workerDispatchSummary")
                    add("proactive=$proactiveDispatchSummary")
                    add("fairness=$ownerFairnessSummary")
                    add("trace=$traceAttentionSummary")
                    recentLines.forEach { add("recent=$it") }
                },
        )
    }
}
