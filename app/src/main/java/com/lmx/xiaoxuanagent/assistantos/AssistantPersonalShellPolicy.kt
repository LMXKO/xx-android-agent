package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionWorkerRecord
import com.lmx.xiaoxuanagent.runtime.SessionWorkerStatus
import com.lmx.xiaoxuanagent.runtime.SessionWorkerMissionType
import com.lmx.xiaoxuanagent.runtime.escalationPressureSummary
import com.lmx.xiaoxuanagent.runtime.joinPressureSummary
import com.lmx.xiaoxuanagent.runtime.missionLabelResolved
import com.lmx.xiaoxuanagent.runtime.missionMixSummary
import com.lmx.xiaoxuanagent.runtime.missionSummaryResolved
import com.lmx.xiaoxuanagent.runtime.requiresEscalationAttention

internal object AssistantPersonalShellPolicy {
    private const val UPCOMING_WINDOW_MS = 2L * 60L * 60L * 1000L

    fun deriveAgendaShell(
        now: Long,
        assistantSnapshot: AssistantOsSnapshot,
        proactiveTasks: List<AssistantProactiveTask>,
        proactiveQueue: AssistantProactiveTaskQueueSnapshot,
        externalSignals: List<AssistantExternalSignal>,
        workers: List<SessionWorkerRecord>,
    ): AssistantAgendaShellSnapshot {
        val dueTasks = proactiveQueue.dispatchCandidates.map { it.task }
        val upcomingTasks =
            proactiveTasks.filter { it.enabled && it.fireAtMs > now && it.fireAtMs <= now + UPCOMING_WINDOW_MS }.sortedBy { it.fireAtMs }
        val dueSignals = externalSignals.filter { it.enabled && it.fireAtMs in 1..now }.sortedBy { it.fireAtMs }
        val focusTitle =
            assistantSnapshot.activeSession.task
                .ifBlank { assistantSnapshot.approvalSessions.firstOrNull()?.task.orEmpty() }
                .ifBlank { dueTasks.firstOrNull()?.title.orEmpty() }
                .ifBlank { dueSignals.firstOrNull()?.title.orEmpty() }
        return AssistantAgendaShellSnapshot(
            mode =
                when {
                    assistantSnapshot.approvalSessions.isNotEmpty() -> "approval_queue"
                    dueTasks.isNotEmpty() || dueSignals.isNotEmpty() -> "due_now"
                    upcomingTasks.isNotEmpty() -> "upcoming"
                    focusTitle.isNotBlank() -> "tracking"
                    else -> "idle"
                },
            summary =
                when {
                    assistantSnapshot.approvalSessions.isNotEmpty() -> "当前有 ${assistantSnapshot.approvalSessions.size} 条审批/确认需要优先进入日程面。"
                    dueTasks.isNotEmpty() || dueSignals.isNotEmpty() ->
                        "当前有 ${dueTasks.size} 条主动任务、${dueSignals.size} 条外部信号已经到点，应该进入统一 agenda。"
                    upcomingTasks.isNotEmpty() -> "接下来两小时内还有 ${upcomingTasks.size} 条待执行主动任务。"
                    focusTitle.isNotBlank() -> "当前 agenda 以 \"$focusTitle\" 为主。"
                    else -> "当前没有到点 agenda，助手处于轻量待命。"
                },
            dueNowCount = dueTasks.size,
            upcomingCount = upcomingTasks.size,
            signalCount = dueSignals.size,
            focusTitle = focusTitle,
            agendaLines =
                buildList {
                    assistantSnapshot.approvalSessions.take(2).forEach { session ->
                        add("approval | ${session.task.ifBlank { session.sessionId }} | ${session.summary.ifBlank { session.statusCode }}")
                    }
                    dueTasks.take(3).forEach { task ->
                        add("due_task | ${task.title} | ${task.summary.ifBlank { timeDeltaLabel(now, task.fireAtMs) }}")
                    }
                    proactiveQueue.dispatchCandidates.take(2).forEach { candidate ->
                        add("dispatch | ${candidate.lane} | ${candidate.task.title} | score=${candidate.score}")
                    }
                    dueSignals.take(3).forEach { signal ->
                        add("due_signal | ${signal.title} | ${signal.summary.ifBlank { signal.type.name.lowercase() }}")
                    }
                    if (isEmpty() && assistantSnapshot.activeSession.sessionId.isNotBlank()) {
                        add("focus | ${assistantSnapshot.activeSession.task.ifBlank { assistantSnapshot.activeSession.sessionId }} | ${assistantSnapshot.activeSession.summary.ifBlank { assistantSnapshot.activeSession.statusCode }}")
                    }
                },
            recommendedCommands =
                buildList {
                    if (assistantSnapshot.approvalSessions.isNotEmpty()) add("/approve")
                    if (assistantSnapshot.activeSession.sessionId.isNotBlank()) add("/resume")
                    if (dueTasks.isNotEmpty() || dueSignals.isNotEmpty()) add("/product-shell")
                    if (workers.any { it.requiresEscalationAttention() }) add("/worker-reply")
                }.distinct(),
            updatedAtMs = now,
        )
    }

    fun deriveDailyRhythm(
        now: Long,
        assistantSnapshot: AssistantOsSnapshot,
        providers: List<AssistantSignalProviderState>,
        proactiveTasks: List<AssistantProactiveTask>,
        proactiveQueue: AssistantProactiveTaskQueueSnapshot,
        externalSignals: List<AssistantExternalSignal>,
        workers: List<SessionWorkerRecord>,
    ): AssistantDailyRhythmSnapshot {
        val dueTasks = proactiveQueue.dueCount
        val upcomingTasks = proactiveTasks.count { it.enabled && it.fireAtMs > now && it.fireAtMs <= now + UPCOMING_WINDOW_MS }
        val activeSignals = externalSignals.count { it.enabled && it.fireAtMs in 1..now }
        val hotSignals =
            externalSignals
                .filter { now - it.updatedAtMs <= 90L * 60L * 1000L }
                .groupBy { it.type }
                .entries
                .sortedByDescending { it.value.size }
                .joinToString(" | ") { (type, entries) -> "${type.name.lowercase()}:${entries.size}" }
                .ifBlank { "-" }
        val providersInCooldown = providers.count { it.cooldownUntilMs > now }
        val followUps =
            workers.count {
                it.missionType == SessionWorkerMissionType.FOLLOW_UP ||
                    it.missionType == SessionWorkerMissionType.RECOVERY
            }
        return AssistantDailyRhythmSnapshot(
            mode =
                when {
                    assistantSnapshot.approvalSessions.isNotEmpty() || workers.any { it.requiresEscalationAttention() } -> "interrupt_heavy"
                    activeSignals >= 3 || dueTasks >= 3 -> "signal_heavy"
                    assistantSnapshot.activeSession.waitingForExternal -> "waiting_window"
                    dueTasks > 0 || upcomingTasks > 0 -> "structured_follow_up"
                    else -> "steady"
                },
            summary =
                when {
                    assistantSnapshot.approvalSessions.isNotEmpty() -> "当前日节奏被审批/确认打断，应该优先把高风险节点收口。"
                    workers.any { it.requiresEscalationAttention() } -> "当前 swarm 里存在升级中的 worker，助手节奏偏向协同收口。"
                    activeSignals >= 3 -> "外部信号进入高压窗口，助手应该转入事件驱动节奏。"
                    dueTasks > 0 -> "当前已有主动任务到点，适合进入 follow-up 节奏。"
                    else -> "当前节奏平稳，可以维持轻量伴随。"
                },
            nextWindowSummary =
                proactiveTasks
                    .filter { it.enabled && it.fireAtMs > now }
                    .minByOrNull { it.fireAtMs }
                    ?.let { "${it.title} @ ${timeDeltaLabel(now, it.fireAtMs)}" }
                    ?: "next_window=-",
            attentionSummary =
                "approvals=${assistantSnapshot.approvalSessions.size} backlog=${assistantSnapshot.sessionBacklog.size} focus_waiting=${assistantSnapshot.activeSession.waitingForExternal}",
            signalPressureSummary = hotSignals,
            proactivePressureSummary = "due=$dueTasks upcoming=$upcomingTasks",
            followUpSummary = "follow_up_workers=$followUps | ${workers.joinPressureSummary()}",
            rhythmLines =
                buildList {
                    add("signals | $hotSignals")
                    add("providers | cooldown=$providersInCooldown unhealthy=${providers.count { it.healthScore < 50 || it.failureCount >= 3 }}")
                    add("proactive | ${proactiveQueue.summary.ifBlank { "due=$dueTasks upcoming=$upcomingTasks" }}")
                    add("workers | ${workers.missionMixSummary()}")
                    add("workers | ${workers.escalationPressureSummary()}")
                },
            updatedAtMs = now,
        )
    }

    fun derivePersonalFocus(
        now: Long,
        assistantSnapshot: AssistantOsSnapshot,
        proactiveTasks: List<AssistantProactiveTask>,
        proactiveQueue: AssistantProactiveTaskQueueSnapshot,
        externalSignals: List<AssistantExternalSignal>,
        workers: List<SessionWorkerRecord>,
        userModel: PersonalAssistantUserModelSnapshot,
        autonomyPlan: AssistantAutonomyPlanSnapshot,
    ): AssistantPersonalFocusSnapshot {
        val focusWorker = selectFocusWorker(workers)
        val focusSessionId =
            assistantSnapshot.approvalSessions.firstOrNull()?.sessionId
                .orEmpty()
                .ifBlank { assistantSnapshot.activeSession.sessionId }
                .ifBlank { focusWorker?.sessionId.orEmpty() }
                .ifBlank { proactiveQueue.dispatchCandidates.firstOrNull()?.task?.sessionId.orEmpty() }
        val focusTask =
            assistantSnapshot.approvalSessions.firstOrNull()?.task
                .orEmpty()
                .ifBlank { assistantSnapshot.activeSession.task }
                .ifBlank { focusWorker?.task.orEmpty() }
                .ifBlank { proactiveQueue.dispatchCandidates.firstOrNull()?.task?.task.orEmpty() }
                .ifBlank { externalSignals.firstOrNull { it.enabled }?.task.orEmpty() }
        val focusReason =
            when {
                assistantSnapshot.approvalSessions.isNotEmpty() -> "approval_session"
                assistantSnapshot.activeSession.sessionId.isNotBlank() -> "active_session"
                focusWorker?.requiresEscalationAttention() == true -> "worker_escalation"
                focusWorker != null -> "worker_mission"
                proactiveTasks.any { it.enabled && it.fireAtMs in 1..now } -> "due_task"
                externalSignals.any { it.enabled && it.fireAtMs in 1..now } -> "due_signal"
                else -> "idle"
            }
        val missionSummary =
            focusWorker?.missionSummaryResolved()
                ?: workers.missionMixSummary()
        val coordinationSummary =
            buildString {
                append("session=").append(focusSessionId.ifBlank { "-" })
                append(" | ")
                append(workers.escalationPressureSummary())
                append(" | ")
                append(workers.joinPressureSummary())
                focusWorker?.let {
                    append(" | worker=").append(it.workerId.takeLast(10))
                    append(":").append(it.missionLabelResolved())
                    append(":").append(it.status.name.lowercase())
                }
            }
        return AssistantPersonalFocusSnapshot(
            mode =
                when {
                    focusReason == "approval_session" -> "approval_focus"
                    focusReason == "worker_escalation" -> "escalation_focus"
                    focusReason == "active_session" -> "active_session_focus"
                    focusTask.isNotBlank() -> "task_focus"
                    else -> "idle"
                },
            summary =
                when {
                    focusTask.isNotBlank() -> "当前最该盯住的是 \"$focusTask\"，原因是 $focusReason。"
                    else -> "当前没有需要持续盯住的个人焦点。"
                },
            focusSessionId = focusSessionId,
            focusTask = focusTask,
            focusReason = focusReason,
            missionSummary = missionSummary,
            coordinationSummary = coordinationSummary,
            userModelSummary =
                listOf(
                    userModel.identitySummary.takeIf { it.isNotBlank() },
                    userModel.preferenceSummary.takeIf { it.isNotBlank() },
                    autonomyPlan.summary.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" | "),
            nextBestActions =
                buildList {
                    if (assistantSnapshot.approvalSessions.isNotEmpty()) add("/approve")
                    if (focusSessionId.isNotBlank()) add("/inspect-replay-breakpoint")
                    if (assistantSnapshot.activeSession.sessionId.isNotBlank()) add("/resume")
                    if (workers.any { it.requiresEscalationAttention() }) add("/worker-reply")
                    if (proactiveTasks.any { it.enabled && it.fireAtMs in 1..now }) add("/product-shell")
                    addAll(autonomyPlan.recommendedCommands)
                }.distinct(),
            updatedAtMs = now,
        )
    }

    private fun selectFocusWorker(
        workers: List<SessionWorkerRecord>,
    ): SessionWorkerRecord? =
        workers.maxByOrNull { worker ->
            buildList {
                if (worker.requiresEscalationAttention()) add(120)
                if (worker.status == SessionWorkerStatus.WAITING_APPROVAL) add(100)
                if (worker.status == SessionWorkerStatus.WAITING_CHILDREN) add(70)
                if (worker.status == SessionWorkerStatus.RUNNING) add(45)
                if (worker.missionType == SessionWorkerMissionType.APPROVAL) add(40)
                if (worker.missionType == SessionWorkerMissionType.RECOVERY) add(30)
                add(worker.priority * 10)
                add(worker.depth * -2)
            }.sum()
        }

    private fun timeDeltaLabel(
        now: Long,
        fireAtMs: Long,
    ): String {
        val deltaMinutes = ((fireAtMs - now) / 60_000L).toInt()
        return when {
            deltaMinutes <= 0 -> "now"
            deltaMinutes < 60 -> "in_${deltaMinutes}m"
            else -> "in_${deltaMinutes / 60}h"
        }
    }
}
