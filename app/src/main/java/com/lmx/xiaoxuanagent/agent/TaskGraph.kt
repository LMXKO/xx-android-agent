package com.lmx.xiaoxuanagent.agent

data class TaskGraphNode(
    val id: String,
    val title: String,
    val status: String,
    val summary: String = "",
    val kind: String = "step",
    val lane: String = "",
    val dependencies: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val capabilityTags: List<String> = emptyList(),
    val children: List<TaskGraphNode> = emptyList(),
)

private data class TaskLaneDefinition(
    val id: String,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val steps: List<TaskPlanStep>,
)

object TaskGraphBuilder {
    fun build(
        planType: String,
        title: String,
        currentStage: String,
        stageSummary: String,
        nextObjective: String,
        steps: List<TaskPlanStep>,
        waitConditions: List<TaskWaitCondition>,
    ): List<TaskGraphNode> {
        val laneDefinitions = buildLaneDefinitions(steps, currentStage)
        val rootId = "${planType}_root"
        val laneNodes =
            laneDefinitions.map { lane ->
                TaskGraphNode(
                    id = lane.id,
                    title = lane.title,
                    status = resolveLaneStatus(lane.steps),
                    summary = lane.summary,
                    kind = "lane",
                    lane = lane.id,
                    capabilityTags = lane.tags,
                    dependencies = resolveLaneDependencies(laneDefinitions, lane.id),
                    children = buildStepNodes(lane, laneDefinitions, waitConditions),
                )
            }
        val waitNodes =
            waitConditions.map { condition ->
                TaskGraphNode(
                    id = condition.id,
                    title = condition.title,
                    status = condition.status,
                    summary = condition.reason.ifBlank { condition.resumeHint },
                    kind = "wait",
                    lane = condition.blockingNodeId.substringBefore(':', missingDelimiterValue = "system"),
                    dependencies = condition.blockingNodeId.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                    capabilityTags = listOf(condition.type, condition.resumeEvent).filter { it.isNotBlank() },
                )
            }
        return listOf(
            TaskGraphNode(
                id = rootId,
                title = title,
                status = resolveRootStatus(steps, waitConditions),
                summary = stageSummary.ifBlank { nextObjective },
                kind = "root",
                capabilityTags =
                    buildList {
                        add(planType)
                        if (currentStage.isNotBlank()) add(currentStage)
                        if (waitConditions.any { it.type == "external_wait" }) add("external_wait")
                    },
                children = laneNodes + waitNodes,
            ),
        )
    }

    private fun buildLaneDefinitions(
        steps: List<TaskPlanStep>,
        currentStage: String,
    ): List<TaskLaneDefinition> {
        val grouped = linkedMapOf<String, MutableList<TaskPlanStep>>()
        steps.forEach { step ->
            grouped.getOrPut(resolveLaneId(step.id, step.title, currentStage)) { mutableListOf() } += step
        }
        return grouped.map { (laneId, laneSteps) ->
            val title =
                when (laneId) {
                    "entry" -> "Entry"
                    "discovery" -> "Discovery"
                    "evidence" -> "Evidence"
                    "action" -> "Action"
                    "handoff" -> "Handoff"
                    else -> "Flow"
                }
            val summary =
                laneSteps.firstOrNull { it.evidence.isNotBlank() }?.evidence
                    ?: laneSteps.joinToString(" -> ") { it.title }
            TaskLaneDefinition(
                id = laneId,
                title = title,
                summary = summary,
                tags = laneTags(laneId),
                steps = laneSteps,
            )
        }
    }

    private fun resolveLaneId(
        stepId: String,
        stepTitle: String,
        currentStage: String,
    ): String {
        val semantic = "$stepId $stepTitle $currentStage".lowercase()
        return when {
            semantic.contains("launch") ||
                semantic.contains("enter_") ||
                semantic.contains("query") ||
                semantic.contains("destination") ||
                semantic.contains("find") -> "entry"
            semantic.contains("browse") ||
                semantic.contains("candidate") ||
                semantic.contains("search") -> "discovery"
            semantic.contains("inspect") ||
                semantic.contains("review") ||
                semantic.contains("evidence") ||
                semantic.contains("detail") -> "evidence"
            semantic.contains("compose") ||
                semantic.contains("route") ||
                semantic.contains("confirm") ||
                semantic.contains("send") -> "action"
            semantic.contains("summarize") ||
                semantic.contains("finish") ||
                semantic.contains("result") -> "handoff"
            else -> "discovery"
        }
    }

    private fun laneTags(
        laneId: String,
    ): List<String> =
        when (laneId) {
            "entry" -> listOf("routing", "surface")
            "discovery" -> listOf("ranking", "selection")
            "evidence" -> listOf("reader", "verification")
            "action" -> listOf("actuation", "checkpoint")
            "handoff" -> listOf("result", "handoff")
            else -> listOf("orchestration")
        }

    private fun resolveLaneDependencies(
        laneDefinitions: List<TaskLaneDefinition>,
        laneId: String,
    ): List<String> {
        val index = laneDefinitions.indexOfFirst { it.id == laneId }
        if (index <= 0) return emptyList()
        return laneDefinitions.take(index).takeLast(1).map { it.id }
    }

    private fun buildStepNodes(
        lane: TaskLaneDefinition,
        laneDefinitions: List<TaskLaneDefinition>,
        waitConditions: List<TaskWaitCondition>,
    ): List<TaskGraphNode> =
        lane.steps.mapIndexed { index, step ->
            val previousStepId =
                if (index > 0) {
                    lane.steps[index - 1].id
                } else {
                    laneDefinitions
                        .takeWhile { it.id != lane.id }
                        .lastOrNull()
                        ?.steps
                        ?.lastOrNull()
                        ?.id
                }
            TaskGraphNode(
                id = step.id,
                title = step.title,
                status = step.status,
                summary = step.evidence,
                kind = "step",
                lane = lane.id,
                dependencies = previousStepId?.let(::listOf).orEmpty(),
                blockers =
                    waitConditions
                        .filter { it.blockingNodeId == step.id }
                        .map { it.id },
                capabilityTags =
                    buildList {
                        add(lane.id)
                        if (step.status == "ready") add("checkpoint")
                        if (step.status == "in_progress") add("focus")
                    },
            )
        }

    private fun resolveLaneStatus(steps: List<TaskPlanStep>): String =
        when {
            steps.isEmpty() -> "pending"
            steps.all { it.status == "done" } -> "done"
            steps.any { it.status == "in_progress" || it.status == "ready" } -> "in_progress"
            else -> "pending"
        }

    private fun resolveRootStatus(
        steps: List<TaskPlanStep>,
        waitConditions: List<TaskWaitCondition>,
    ): String =
        when {
            waitConditions.any { it.type == "external_wait" && it.status == "blocking" } -> "blocked"
            steps.isEmpty() -> "pending"
            steps.all { it.status == "done" } -> "done"
            steps.any { it.status == "in_progress" || it.status == "ready" } -> "in_progress"
            else -> "pending"
        }
}
