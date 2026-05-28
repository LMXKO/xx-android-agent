package com.lmx.xiaoxuanagent.agent

class LocalHeuristicPlanner(
    override val modeLabel: String = "LOCAL_HEURISTIC",
) : AgentPlanner {
    override suspend fun plan(
        task: String,
        observation: ScreenObservation,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
        activeSkills: List<SkillContext>,
        taskPlanState: TaskPlanState,
        visualContext: VisualPerceptionContext,
        screenshot: ScreenshotPayload?,
        targetPackageName: String,
    ): AgentDecision {
        val constraints = TaskIntentParser.parse(task)
        val action =
            when (taskPlanState.currentStage) {
                "launch_target_app" ->
                    targetPackageName.takeIf { it.isNotBlank() }?.let { AgentAction.LaunchApp(it) }

                "enter_query", "enter_destination" ->
                    observation.primaryEditableId
                        ?.takeIf { constraints.entryQuery.isNotBlank() || !constraints.destination.isNullOrBlank() }
                        ?.let {
                            AgentAction.PopulatePrimaryInput(
                                constraints.destination?.takeIf(String::isNotBlank)
                                    ?: constraints.entryQuery,
                            )
                        }

                "find_conversation" ->
                    observation.primaryEditableId
                        ?.takeIf { !constraints.recipientName.isNullOrBlank() }
                        ?.let { AgentAction.PopulatePrimaryInput(constraints.recipientName.orEmpty()) }
                            ?: AgentAction.OpenBestCandidate(
                                targetText = constraints.recipientName.orEmpty(),
                                roleHint = "conversation",
                            )

                "submit_query" -> AgentAction.SubmitPrimaryAction("搜索")

                "browse_candidates" ->
                    AgentAction.OpenBestCandidate(
                        targetText =
                            listOf(
                                constraints.entryQuery,
                                constraints.destination.orEmpty(),
                                constraints.recipientName.orEmpty(),
                                constraints.objectiveHint.orEmpty(),
                            ).firstOrNull { it.isNotBlank() }.orEmpty(),
                        roleHint = "entry",
                    )

                "inspect_information" ->
                    AgentAction.OpenBestCandidate(roleHint = "detail")

                "confirm_send" -> AgentAction.SubmitPrimaryAction("发送")

                "confirm_route" -> AgentAction.SubmitPrimaryAction("导航")

                "summarize" ->
                    AgentAction.Finish(taskPlanState.nextObjective.ifBlank { taskPlanState.stageSummary.ifBlank { task.take(40) } })

                else ->
                    if (!observation.primaryInterruptActionId.isNullOrBlank() || observation.interruptiveHints.isNotEmpty()) {
                        AgentAction.DismissInterrupt()
                    } else {
                        AgentAction.OpenBestCandidate(
                            targetText = listOf(constraints.entryQuery, constraints.recipientName.orEmpty()).firstOrNull { it.isNotBlank() }.orEmpty(),
                            roleHint = "entry",
                        )
                    }
            } ?: AgentAction.Wait

        return AgentDecision(
            action = action,
            reason = "本地启发式 planner 基于当前阶段 ${taskPlanState.currentStage.ifBlank { "observe" }} 生成最小推进动作 ${action.label}。",
            rawResponse =
                """{"planner":"local_heuristic","stage":"${taskPlanState.currentStage}","action":"${action.label}","target_package":"$targetPackageName"}""",
        )
    }

    private fun bestCandidate(
        observation: ScreenObservation,
        keywords: List<String>,
    ): UiElementObservation? {
        val normalizedKeywords = keywords.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedKeywords.isEmpty()) return null
        return observation.elements
            .asSequence()
            .filter { it.enabled && it.clickable }
            .map { element -> element to scoreCandidate(element, normalizedKeywords) }
            .filter { pair -> pair.second > 0 }
            .sortedWith(
                compareByDescending<Pair<UiElementObservation, Int>> { pair -> pair.second }
                    .thenByDescending { pair -> pair.first.text.length }
                    .thenBy { pair -> pair.first.id },
            ).map { pair -> pair.first }
            .firstOrNull()
    }

    private fun scoreCandidate(
        element: UiElementObservation,
        keywords: List<String>,
    ): Int =
        keywords.fold(0) { total, keyword ->
            total +
                when {
                    element.text.equals(keyword, ignoreCase = true) -> 30
                    element.text.contains(keyword, ignoreCase = true) -> 18
                    element.viewId.contains(keyword, ignoreCase = true) -> 12
                    else -> 0
                }
        }
}
