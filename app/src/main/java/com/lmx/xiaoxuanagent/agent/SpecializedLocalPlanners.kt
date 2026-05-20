package com.lmx.xiaoxuanagent.agent

class SemanticLocalPlanner(
    override val modeLabel: String = "SEMANTIC_LOCAL",
) : AgentPlanner {
    private val fallback = LocalHeuristicPlanner("${modeLabel}_fallback")

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

                "inspect_information", "summarize" ->
                    AgentAction.OpenBestCandidate(
                        targetText = constraints.objectiveHint.orEmpty(),
                        roleHint =
                            when {
                                task.contains("评论") || task.contains("评价") -> "comment"
                                task.contains("路线") || task.contains("导航") -> "route"
                                else -> "detail"
                            },
                    )

                "confirm_send" -> AgentAction.SubmitPrimaryAction("发送")
                "confirm_route" -> AgentAction.SubmitPrimaryAction("导航")
                else ->
                    when {
                        observation.interruptiveHints.isNotEmpty() || !observation.primaryInterruptActionId.isNullOrBlank() ->
                            AgentAction.DismissInterrupt()
                        taskPlanState.resumeContext.active && !observation.primaryEditableId.isNullOrBlank() ->
                            AgentAction.FocusPrimaryInput
                        observation.defaultScrollableId != null && history.takeLast(2).all { it.action == AgentAction.Wait.label } ->
                            AgentAction.ScrollForMore("down")
                        else -> null
                    }
            }
        if (action != null) {
            return AgentDecision(
                action = action,
                reason = "语义本地 planner 按当前阶段 ${taskPlanState.currentStage.ifBlank { "observe" }} 选择 ${action.label}。",
                rawResponse =
                    """{"planner":"semantic_local","stage":"${taskPlanState.currentStage}","action":"${action.label}","target_package":"$targetPackageName"}""",
            )
        }
        return fallback.plan(
            task = task,
            observation = observation,
            history = history,
            artifactHints = artifactHints,
            memoryContext = memoryContext,
            activeSkills = activeSkills,
            taskPlanState = taskPlanState,
            visualContext = visualContext,
            screenshot = screenshot,
            targetPackageName = targetPackageName,
        )
    }
}

class RecoveryHeuristicPlanner(
    override val modeLabel: String = "RECOVERY_HEURISTIC",
) : AgentPlanner {
    private val fallback = LocalHeuristicPlanner("${modeLabel}_fallback")

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
        val latestTurn = history.lastOrNull()
        val action =
            when (latestTurn?.recoveryCategory?.uppercase()) {
                RecoveryCategory.POPUP_BLOCKING.name,
                RecoveryCategory.PERMISSION_REQUIRED.name,
                -> AgentAction.DismissInterrupt()

                RecoveryCategory.REPEATED_BACKTRACK.name,
                RecoveryCategory.NO_PROGRESS.name,
                -> AgentAction.ScrollForMore("down")

                RecoveryCategory.OVERSCROLLED.name -> AgentAction.ScrollForMore("up")
                RecoveryCategory.FINISH_REJECTED.name -> AgentAction.OpenBestCandidate(roleHint = "detail")
                else ->
                    when {
                        taskPlanState.resumeContext.active && taskPlanState.currentStage == "submit_query" ->
                            AgentAction.SubmitPrimaryAction("搜索")
                        taskPlanState.resumeContext.active && taskPlanState.currentStage == "confirm_send" ->
                            AgentAction.SubmitPrimaryAction("发送")
                        taskPlanState.resumeContext.active && taskPlanState.currentStage == "confirm_route" ->
                            AgentAction.SubmitPrimaryAction("导航")
                        else -> null
                    }
            }
        if (action != null) {
            return AgentDecision(
                action = action,
                reason =
                    "恢复本地 planner 基于恢复态 ${latestTurn?.recoveryCategory?.lowercase().orEmpty().ifBlank { taskPlanState.currentStage }} " +
                        "选择 ${action.label}。",
                rawResponse =
                    """{"planner":"recovery_heuristic","stage":"${taskPlanState.currentStage}","recovery":"${latestTurn?.recoveryCategory.orEmpty()}","action":"${action.label}"}""",
            )
        }
        return fallback.plan(
            task = task,
            observation = observation,
            history = history,
            artifactHints = artifactHints,
            memoryContext = memoryContext,
            activeSkills = activeSkills,
            taskPlanState = taskPlanState,
            visualContext = visualContext,
            screenshot = screenshot,
            targetPackageName = targetPackageName,
        )
    }
}

class MemoryLocalPlanner(
    override val modeLabel: String = "MEMORY_LOCAL",
) : AgentPlanner {
    private val fallback = SemanticLocalPlanner("${modeLabel}_fallback")

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
        val rememberedTarget =
            listOf(
                constraints.recipientName.orEmpty().takeIf { memoryContext.contacts.isNotEmpty() },
                constraints.destination.orEmpty().takeIf { memoryContext.locations.isNotEmpty() },
                constraints.entryQuery.takeIf { memoryContext.appPreferences.isNotEmpty() },
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val action =
            when {
                rememberedTarget.isBlank() -> null
                taskPlanState.currentStage in setOf("find_conversation", "enter_destination", "enter_query") ->
                    AgentSemanticActionComposer.entryAlignmentAction(
                        targetText = rememberedTarget,
                        stage = taskPlanState.currentStage,
                        observation = observation,
                    )

                taskPlanState.currentStage in setOf("browse_candidates", "inspect_information") ->
                    AgentAction.OpenBestCandidate(
                        targetText = rememberedTarget,
                        roleHint = AgentSemanticActionComposer.roleHintForStage(taskPlanState.currentStage),
                    )

                else -> null
            }
        if (action != null) {
            return AgentDecision(
                action = action,
                reason = "记忆本地 planner 根据联系人/地点/偏好记忆优先对齐 $rememberedTarget。",
                rawResponse =
                    """{"planner":"memory_local","stage":"${taskPlanState.currentStage}","target":"$rememberedTarget","action":"${action.label}"}""",
            )
        }
        return fallback.plan(
            task = task,
            observation = observation,
            history = history,
            artifactHints = artifactHints,
            memoryContext = memoryContext,
            activeSkills = activeSkills,
            taskPlanState = taskPlanState,
            visualContext = visualContext,
            screenshot = screenshot,
            targetPackageName = targetPackageName,
        )
    }
}

class RoutineLocalPlanner(
    override val modeLabel: String = "ROUTINE_LOCAL",
) : AgentPlanner {
    private val fallback = SemanticLocalPlanner("${modeLabel}_fallback")

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
        val routineHeavy =
            listOf("提醒", "待办", "今天", "跟进", "回顾", "节奏", "agenda", "routine").any {
                task.contains(it, ignoreCase = true)
            }
        val action =
            when {
                !routineHeavy -> null
                observation.interruptiveHints.isNotEmpty() || !observation.primaryInterruptActionId.isNullOrBlank() ->
                    AgentAction.DismissInterrupt()
                taskPlanState.currentStage in setOf("browse_candidates", "inspect_information", "summarize") ->
                    AgentSemanticActionComposer.progressAction(
                        targetText = TaskIntentParser.parse(task).objectiveHint.orEmpty(),
                        roleHint = "detail",
                        observation = observation,
                    )

                taskPlanState.waitingForExternal && observation.defaultScrollableId != null ->
                    AgentAction.ScrollForMore("down")

                else -> null
            }
        if (action != null) {
            return AgentDecision(
                action = action,
                reason = "例行本地 planner 识别到日程/跟进任务，优先走 routine 专项策略。",
                rawResponse =
                    """{"planner":"routine_local","stage":"${taskPlanState.currentStage}","action":"${action.label}"}""",
            )
        }
        return fallback.plan(
            task = task,
            observation = observation,
            history = history,
            artifactHints = artifactHints,
            memoryContext = memoryContext,
            activeSkills = activeSkills,
            taskPlanState = taskPlanState,
            visualContext = visualContext,
            screenshot = screenshot,
            targetPackageName = targetPackageName,
        )
    }
}

class ArtifactLocalPlanner(
    override val modeLabel: String = "ARTIFACT_LOCAL",
) : AgentPlanner {
    private val fallback = SemanticLocalPlanner("${modeLabel}_fallback")

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
        val action =
            when {
                artifactHints.isEmpty() -> null
                taskPlanState.currentStage in setOf("inspect_information", "summarize") ->
                    AgentSemanticActionComposer.progressAction(
                        targetText = artifactHints.firstOrNull()?.summary.orEmpty(),
                        roleHint = "detail",
                        observation = observation,
                    )

                taskPlanState.currentStage == "browse_candidates" ->
                    AgentAction.OpenBestCandidate(
                        targetText = artifactHints.firstOrNull()?.summary.orEmpty(),
                        roleHint = "entry",
                    )

                else -> null
            }
        if (action != null) {
            return AgentDecision(
                action = action,
                reason = "artifact 本地 planner 检测到已有结果线索，优先围绕 artifact 收口。",
                rawResponse =
                    """{"planner":"artifact_local","stage":"${taskPlanState.currentStage}","artifact_hints":${artifactHints.size},"action":"${action.label}"}""",
            )
        }
        return fallback.plan(
            task = task,
            observation = observation,
            history = history,
            artifactHints = artifactHints,
            memoryContext = memoryContext,
            activeSkills = activeSkills,
            taskPlanState = taskPlanState,
            visualContext = visualContext,
            screenshot = screenshot,
            targetPackageName = targetPackageName,
        )
    }
}
