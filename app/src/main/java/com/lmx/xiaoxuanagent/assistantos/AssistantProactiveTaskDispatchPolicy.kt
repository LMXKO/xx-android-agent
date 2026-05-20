package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey

internal data class AssistantProactiveTaskDispatchCandidate(
    val task: AssistantProactiveTask,
    val lane: String,
    val score: Int,
    val reasons: List<String> = emptyList(),
)

internal data class AssistantProactiveTaskQueueSnapshot(
    val priorityMode: String = "balanced",
    val dueCount: Int = 0,
    val blockedByActiveSessionCount: Int = 0,
    val blockedByWorkerCount: Int = 0,
    val dispatchCandidates: List<AssistantProactiveTaskDispatchCandidate> = emptyList(),
    val summary: String = "",
)

internal object AssistantProactiveTaskDispatchPolicy {
    fun buildSnapshot(
        tasks: List<AssistantProactiveTask>,
        activeSessionId: String,
        dispatchableWorkerIds: Set<String>,
        nowMs: Long,
        limit: Int,
    ): AssistantProactiveTaskQueueSnapshot {
        val dueTasks =
            tasks
                .asSequence()
                .filter { it.enabled && it.fireAtMs in 1..nowMs }
                .toList()
        if (dueTasks.isEmpty()) {
            return AssistantProactiveTaskQueueSnapshot(summary = "due=0")
        }
        val evaluated = dueTasks.map { evaluate(it, activeSessionId, dispatchableWorkerIds, nowMs) }
        val blockedByActiveSessionCount = evaluated.count { it.blockedByActiveSession }
        val blockedByWorkerCount = evaluated.count { it.blockedByWorker }
        val dispatchCandidates =
            evaluated
                .filterNot { it.blockedByActiveSession || it.blockedByWorker }
                .sortedWith(
                        compareByDescending<EvaluatedTask> { it.score }
                            .thenByDescending { it.overdueMinutes }
                            .thenByDescending { it.task.priority }
                            .thenBy { it.task.deadlineAtMs.takeIf { deadline -> deadline > 0L } ?: Long.MAX_VALUE }
                            .thenBy { it.task.fireAtMs }
                            .thenByDescending { it.task.updatedAtMs },
                ).take(limit.coerceAtLeast(1))
                .map { evaluation ->
                    AssistantProactiveTaskDispatchCandidate(
                        task = evaluation.task,
                        lane = evaluation.lane,
                        score = evaluation.score,
                        reasons = evaluation.reasons,
                    )
                }
        val priorityMode =
            when {
                dispatchCandidates.any { it.lane == "approval_follow_up" } -> "approval_first"
                dispatchCandidates.any { it.lane == "worker_dispatch" } -> "worker_first"
                dispatchCandidates.any { it.lane == "remote_request" } -> "remote_first"
                dispatchCandidates.any { it.lane == "session_follow_up" } -> "session_first"
                dispatchCandidates.any { it.task.type == AssistantProactiveTaskType.MEMORY_NUDGE } -> "memory_then_background"
                else -> "balanced"
            }
        return AssistantProactiveTaskQueueSnapshot(
            priorityMode = priorityMode,
            dueCount = dueTasks.size,
            blockedByActiveSessionCount = blockedByActiveSessionCount,
            blockedByWorkerCount = blockedByWorkerCount,
            dispatchCandidates = dispatchCandidates,
            summary =
                buildString {
                    append("mode=").append(priorityMode)
                    append(" due=").append(dueTasks.size)
                    append(" ready=").append(dispatchCandidates.size)
                    append(" blocked_active=").append(blockedByActiveSessionCount)
                    append(" blocked_worker=").append(blockedByWorkerCount)
                    dispatchCandidates.firstOrNull()?.let { first ->
                        append(" top=").append(first.task.title.ifBlank { first.task.task.ifBlank { first.task.id } }.take(24))
                    }
                },
        )
    }

    private data class EvaluatedTask(
        val task: AssistantProactiveTask,
        val score: Int,
        val lane: String,
        val overdueMinutes: Int,
        val blockedByActiveSession: Boolean,
        val blockedByWorker: Boolean,
        val reasons: List<String>,
    )

    private fun evaluate(
        task: AssistantProactiveTask,
        activeSessionId: String,
        dispatchableWorkerIds: Set<String>,
        nowMs: Long,
    ): EvaluatedTask {
        val workerId = task.metadata["worker_id"].orEmpty()
        val blockedByActiveSession =
            activeSessionId.isNotBlank() &&
                task.capability == SessionCapabilityKey.START_SESSION &&
                task.type != AssistantProactiveTaskType.WORKER_TASK
        val blockedByWorker =
            task.type == AssistantProactiveTaskType.WORKER_TASK &&
                workerId.isNotBlank() &&
                workerId !in dispatchableWorkerIds
        val overdueMinutes = ((nowMs - task.fireAtMs).coerceAtLeast(0L) / 60_000L).toInt().coerceAtMost(240)
        val basePriority = task.priority.coerceIn(-5, 10)
        val sameSessionBoost =
            when {
                activeSessionId.isBlank() -> 0
                task.sessionId == activeSessionId -> 35
                task.parentSessionId == activeSessionId -> 20
                else -> 0
            }
        val lane =
            when {
                task.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP -> "approval_follow_up"
                task.type == AssistantProactiveTaskType.WORKER_TASK -> "worker_dispatch"
                task.type == AssistantProactiveTaskType.REMOTE_REQUEST -> "remote_request"
                task.sessionId.isNotBlank() || task.parentSessionId.isNotBlank() -> "session_follow_up"
                task.type == AssistantProactiveTaskType.MEMORY_NUDGE -> "memory_nudge"
                else -> "scheduled"
            }
        val score =
            buildList {
                add(basePriority * 25)
                add(sameSessionBoost)
                add((overdueMinutes / 2).coerceAtMost(90))
                add(
                    when (task.type) {
                        AssistantProactiveTaskType.APPROVAL_FOLLOW_UP -> 160
                        AssistantProactiveTaskType.WORKER_TASK -> 135
                        AssistantProactiveTaskType.REMOTE_REQUEST -> 110
                        AssistantProactiveTaskType.SCHEDULED_TASK -> 80
                        AssistantProactiveTaskType.MEMORY_NUDGE -> 45
                    },
                )
                add(
                    when (task.capability) {
                        SessionCapabilityKey.APPROVE_CAPABILITY_REQUEST,
                        SessionCapabilityKey.POST_WORKER_MESSAGE,
                        SessionCapabilityKey.ACK_WORKER_MESSAGE -> 60
                        SessionCapabilityKey.START_SESSION -> 20
                        else -> 30
                    },
                )
                if (task.summary.contains("审批") || task.summary.contains("确认")) add(24)
                if (task.source.contains("worker")) add(20)
                if (task.source.contains("remote")) add(12)
                if (blockedByActiveSession) add(-1_000)
                if (blockedByWorker) add(-1_000)
            }.sum()
        val reasons =
            buildList {
                add("type=${task.type.name.lowercase()}")
                add("capability=${task.capability.name.lowercase()}")
                task.sessionId.takeIf { it.isNotBlank() }?.let { add("session=$it") }
                task.parentSessionId.takeIf { it.isNotBlank() }?.let { add("parent=$it") }
                workerId.takeIf { it.isNotBlank() }?.let { add("worker=$it") }
                if (basePriority != 0) add("priority=$basePriority")
                task.deadlineAtMs.takeIf { it > 0L }?.let { add("deadline_at_ms=$it") }
                if (task.deferCount > 0) add("defer_count=${task.deferCount}")
                if (overdueMinutes > 0) add("overdue_min=$overdueMinutes")
                if (sameSessionBoost > 0) add("same_session_boost=$sameSessionBoost")
                if (blockedByActiveSession) add("blocked=active_session")
                if (blockedByWorker) add("blocked=worker_not_dispatchable")
            }
        return EvaluatedTask(
            task = task,
            score = score,
            lane = lane,
            overdueMinutes = overdueMinutes,
            blockedByActiveSession = blockedByActiveSession,
            blockedByWorker = blockedByWorker,
            reasons = reasons,
        )
    }
}
