package com.lmx.xiaoxuanagent.assistantos

data class AssistantTaskOsSnapshot(
    val activeSessionId: String = "",
    val enabledTaskCount: Int = 0,
    val overdueCount: Int = 0,
    val approvalCount: Int = 0,
    val blockedByActiveSessionCount: Int = 0,
    val summary: String = "",
    val lines: List<String> = emptyList(),
)

data class AssistantTaskOsSessionSignal(
    val sessionId: String = "",
    val overdueCount: Int = 0,
    val approvalCount: Int = 0,
    val dueCount: Int = 0,
    val blockedByActiveSession: Boolean = false,
    val scoreBonus: Int = 0,
    val summary: String = "",
    val recommendedCommands: List<String> = emptyList(),
)

object AssistantTaskOsStore {
    fun derive(
        tasks: List<AssistantProactiveTask>,
        activeSessionId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): AssistantTaskOsSnapshot {
        val enabled = tasks.filter { it.enabled }
        val overdue = enabled.filter { it.fireAtMs in 1..nowMs }
        val approvals = enabled.filter { it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
        val blockedByActive =
            enabled.filter {
                activeSessionId.isNotBlank() &&
                    it.sessionId.isNotBlank() &&
                    it.sessionId != activeSessionId &&
                    it.fireAtMs in 1..nowMs
            }
        return AssistantTaskOsSnapshot(
            activeSessionId = activeSessionId,
            enabledTaskCount = enabled.size,
            overdueCount = overdue.size,
            approvalCount = approvals.size,
            blockedByActiveSessionCount = blockedByActive.size,
            summary =
                buildString {
                    append("enabled=").append(enabled.size)
                    append(" overdue=").append(overdue.size)
                    append(" approvals=").append(approvals.size)
                    append(" blocked=").append(blockedByActive.size)
                },
            lines =
                buildList {
                    overdue.take(3).forEach { add("overdue | ${it.summary.ifBlank { it.title }}") }
                    approvals.take(2).forEach { add("approval | ${it.summary.ifBlank { it.title }}") }
                    blockedByActive.take(2).forEach { add("blocked | ${it.summary.ifBlank { it.title }} | active=$activeSessionId") }
                },
        )
    }

    fun deriveSessionSignals(
        tasks: List<AssistantProactiveTask>,
        activeSessionId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, AssistantTaskOsSessionSignal> {
        val enabled = tasks.filter { it.enabled }
        return enabled
            .mapNotNull { task ->
                val sessionId =
                    task.sessionId
                        .ifBlank { task.parentSessionId }
                        .trim()
                if (sessionId.isBlank()) {
                    null
                } else {
                    sessionId to task
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (sessionId, sessionTasks) ->
                val overdue = sessionTasks.filter { it.fireAtMs in 1..nowMs }
                val approvals = sessionTasks.filter { it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
                val blockedByActive =
                    activeSessionId.isNotBlank() &&
                        activeSessionId != sessionId &&
                        overdue.isNotEmpty()
                val recommendedCommands =
                    sessionTasks
                        .mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) }
                        .distinct()
                        .take(3)
                AssistantTaskOsSessionSignal(
                    sessionId = sessionId,
                    overdueCount = overdue.size,
                    approvalCount = approvals.size,
                    dueCount = sessionTasks.count { it.fireAtMs in 1..nowMs },
                    blockedByActiveSession = blockedByActive,
                    scoreBonus =
                        approvals.size * 120 +
                            overdue.size * 80 +
                            sessionTasks.count { it.fireAtMs in 1..nowMs } * 40 -
                            if (blockedByActive) 30 else 0,
                    summary =
                        buildString {
                            append("due=").append(sessionTasks.count { it.fireAtMs in 1..nowMs })
                            append(" overdue=").append(overdue.size)
                            append(" approvals=").append(approvals.size)
                            append(" blocked=").append(if (blockedByActive) 1 else 0)
                        },
                    recommendedCommands = recommendedCommands,
                )
            }
    }
}
