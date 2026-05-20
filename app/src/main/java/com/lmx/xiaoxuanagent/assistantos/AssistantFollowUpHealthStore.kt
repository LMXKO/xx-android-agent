package com.lmx.xiaoxuanagent.assistantos

data class AssistantFollowUpHealthSnapshot(
    val totalEnabled: Int = 0,
    val overdueCount: Int = 0,
    val deferredCount: Int = 0,
    val approvalCount: Int = 0,
    val scheduledCount: Int = 0,
    val summary: String = "",
    val topLines: List<String> = emptyList(),
)

object AssistantFollowUpHealthStore {
    fun derive(
        tasks: List<AssistantProactiveTask>,
        nowMs: Long = System.currentTimeMillis(),
    ): AssistantFollowUpHealthSnapshot {
        val enabled = tasks.filter { it.enabled }
        val overdue = enabled.filter { it.fireAtMs in 1..nowMs }
        val deferred = enabled.filter { it.deferCount > 0 }
        val approvals = enabled.filter { it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
        return AssistantFollowUpHealthSnapshot(
            totalEnabled = enabled.size,
            overdueCount = overdue.size,
            deferredCount = deferred.size,
            approvalCount = approvals.size,
            scheduledCount = enabled.size - overdue.size,
            summary =
                buildString {
                    append("enabled=").append(enabled.size)
                    append(" overdue=").append(overdue.size)
                    append(" deferred=").append(deferred.size)
                    append(" approvals=").append(approvals.size)
                },
            topLines =
                buildList {
                    approvals.take(2).forEach { add("approval | ${it.summary.ifBlank { it.title }}") }
                    overdue.take(3).forEach {
                        add("overdue | ${it.summary.ifBlank { it.title }}${if (it.deferCount > 0) " | defer=${it.deferCount}" else ""}")
                    }
                }.distinct().take(5),
        )
    }
}
