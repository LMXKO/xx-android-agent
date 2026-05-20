package com.lmx.xiaoxuanagent.agent

data class TaskStackFrame(
    val id: String,
    val title: String,
    val status: String,
    val resumeHint: String = "",
    val frameType: String = "step",
    val depth: Int = 0,
    val active: Boolean = false,
    val blockingReason: String = "",
    val childIds: List<String> = emptyList(),
)

object TaskStackBuilder {
    fun from(
        planType: String,
        currentStage: String,
        stageSummary: String,
        nextObjective: String,
        steps: List<TaskPlanStep>,
        waitConditions: List<TaskWaitCondition>,
    ): List<TaskStackFrame> {
        val activeStep =
            steps.firstOrNull { it.status == "in_progress" || it.status == "ready" }
                ?: steps.firstOrNull { it.id == currentStage }
                ?: steps.firstOrNull { it.status == "pending" }
        val activeLaneId = activeStep?.let { resolveLaneId(it) }.orEmpty()
        val activeLaneSteps = steps.filter { resolveLaneId(it) == activeLaneId }
        val blockingWait =
            waitConditions.firstOrNull { condition ->
                condition.status == "blocking" &&
                    (condition.blockingNodeId.isBlank() || condition.blockingNodeId == activeStep?.id)
            } ?: waitConditions.firstOrNull { it.status == "ready" && it.blockingNodeId == activeStep?.id }
        val rootFrame =
            TaskStackFrame(
                id = "${planType}_root",
                title = planType,
                status = if (blockingWait?.type == "external_wait") "blocked" else "active",
                resumeHint = stageSummary.ifBlank { nextObjective },
                frameType = "root",
                depth = 0,
                active = true,
                blockingReason = blockingWait?.reason.orEmpty(),
                childIds = activeLaneSteps.map { it.id },
            )
        val laneFrame =
            activeStep?.let {
                TaskStackFrame(
                    id = "${activeLaneId}_lane",
                    title = laneTitle(activeLaneId),
                    status = it.status,
                    resumeHint = it.evidence.ifBlank { nextObjective },
                    frameType = "lane",
                    depth = 1,
                    active = true,
                    blockingReason = blockingWait?.reason.orEmpty(),
                    childIds = activeLaneSteps.map(TaskPlanStep::id),
                )
            }
        val activeFrame =
            activeStep?.let { step ->
                TaskStackFrame(
                    id = step.id,
                    title = step.title,
                    status = step.status,
                    resumeHint = step.evidence.ifBlank { nextObjective },
                    frameType = "step",
                    depth = 2,
                    active = true,
                    blockingReason = blockingWait?.reason.orEmpty(),
                )
            }
        val waitFrame =
            blockingWait?.let { condition ->
                TaskStackFrame(
                    id = condition.id,
                    title = condition.title,
                    status = condition.status,
                    resumeHint = condition.resumeHint.ifBlank { condition.reason },
                    frameType = "wait",
                    depth = 3,
                    active = condition.status != "resolved",
                    blockingReason = condition.reason,
                )
            }
        return listOfNotNull(rootFrame, laneFrame, activeFrame, waitFrame)
    }

    private fun resolveLaneId(
        step: TaskPlanStep,
    ): String {
        val semantic = "${step.id} ${step.title}".lowercase()
        return when {
            semantic.contains("launch") ||
                semantic.contains("enter") ||
                semantic.contains("query") ||
                semantic.contains("find") ||
                semantic.contains("destination") -> "entry"
            semantic.contains("browse") ||
                semantic.contains("candidate") ||
                semantic.contains("search") -> "discovery"
            semantic.contains("inspect") ||
                semantic.contains("review") ||
                semantic.contains("detail") -> "evidence"
            semantic.contains("compose") ||
                semantic.contains("confirm") ||
                semantic.contains("route") ||
                semantic.contains("send") -> "action"
            semantic.contains("summarize") ||
                semantic.contains("result") -> "handoff"
            else -> "discovery"
        }
    }

    private fun laneTitle(
        laneId: String,
    ): String =
        when (laneId) {
            "entry" -> "Entry"
            "discovery" -> "Discovery"
            "evidence" -> "Evidence"
            "action" -> "Action"
            "handoff" -> "Handoff"
            else -> "Flow"
        }
}
