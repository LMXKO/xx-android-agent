package com.lmx.xiaoxuanagent.runtime

import android.util.Log
import com.lmx.xiaoxuanagent.agent.ActionVerifier
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.AgentPlanner
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.AgentToolRuntimeProtocol
import com.lmx.xiaoxuanagent.agent.AgentToolRuntimeRegistry
import com.lmx.xiaoxuanagent.agent.GroundingVerificationFrame
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.ObservationAugmenter
import com.lmx.xiaoxuanagent.agent.RecoveryEngine
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.TaskCompletionVerifier
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.safety.SafetyPolicyEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class SessionRuntimeTurnDependencies(
    val planner: AgentPlanner,
    val plannerTimeoutMs: Long,
    val captureScreenshotPayload: suspend () -> ScreenshotPayload?,
    val buildVisualContext: suspend (ScreenshotPayload?, ScreenObservation) -> VisualPerceptionContext,
    val observeCurrentScreen: suspend () -> IndexedScreenObservation?,
    val executeAction: suspend (IndexedScreenObservation, AgentAction) -> AgentExecutionResult,
    val logLine: (String) -> Unit = {},
    val updateScreenshotStatus: (String) -> Unit = {},
)

internal object SessionRuntimeTurnOrchestrator {
    private const val TAG = "TbAgent"

    suspend fun runPlanningTurn(
        planningContext: AgentPlanningContext,
        dependencies: SessionRuntimeTurnDependencies,
    ) {
        logPlanningTurnStart(planningContext, dependencies)
        val preparedDecision = preparePlanningDecision(planningContext, dependencies) ?: return
        val skillRefinedDecision = preparedDecision.planningDecisionDirective.decision
        preparedDecision.planningDecisionDirective.logMessages.forEach(dependencies.logLine)
        Log.d(TAG, "planner decision action=${skillRefinedDecision.action.label} reason=${skillRefinedDecision.reason}")
        try {
            executePreparedDecision(
                planningContext = planningContext,
                preparedDecision = preparedDecision,
                dependencies = dependencies,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "planner/action failed", error)
            dependencies.logLine("执行阶段异常: ${error.javaClass.simpleName}: ${error.message}")
            SessionRuntime.Execution.recordError(
                planningContext = planningContext,
                error = "Agent 执行失败：${error.message ?: error.javaClass.simpleName}",
                keepRunning = true,
            )
        }
    }

    private fun logPlanningTurnStart(
        planningContext: AgentPlanningContext,
        dependencies: SessionRuntimeTurnDependencies,
    ) {
        Log.d(
            TAG,
            "planning turn=${planningContext.turn} sig=${planningContext.observation.signature} " +
                "page=${planningContext.observation.pageState} pkg=${planningContext.observation.packageName} " +
                "summary=${planningContext.observation.screenSummary}",
        )
        if (planningContext.observation.elements.isEmpty()) {
            dependencies.logLine(
                "观测为空: pkg=${planningContext.observation.packageName}, page=${planningContext.observation.pageState}, " +
                    "topTexts=${planningContext.observation.topTexts.joinToString(limit = 4, truncated = "...").ifBlank { "-" }}",
            )
        }
    }

    private suspend fun preparePlanningDecision(
        planningContext: AgentPlanningContext,
        dependencies: SessionRuntimeTurnDependencies,
    ): PreparedTurnDecision? {
        SessionTurnLoopStore.refreshFromRuntime(
            sessionId = planningContext.sessionId,
            task = planningContext.task,
            profileId = planningContext.profileId,
            turn = planningContext.turn,
            phase = "planning_start",
            summary = planningContext.taskPlanState.stageSummary.ifBlank { planningContext.task },
        )
        SessionLoopInboxStore.refresh(
            sessionId = planningContext.sessionId,
            task = planningContext.task,
        )
        SessionLoopRuntimeStore.refresh(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            task = planningContext.task,
            profileId = planningContext.profileId,
            phase = "planning_start",
        )
        SessionMemoryForkRuntimeStore.refresh(planningContext.sessionId)
        SessionPermissionProductStore.refresh(sessionId = planningContext.sessionId, limit = 8)
        SessionMainLoopStore.refresh(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "planning_start",
            summary = planningContext.taskPlanState.stageSummary.ifBlank { planningContext.task },
        )
        val runtimeResumeDecision = SessionRuntime.resolveRuntimeResumeIntervention(planningContext)
        val prePlannedDecision =
            runtimeResumeDecision?.decision ?: RecoveryEngine.resolveEarlySkillDecision(
                task = planningContext.task,
                profileId = planningContext.profileId,
                observation = planningContext.observation,
                memoryContext = planningContext.memoryContext,
                taskPlanState = planningContext.taskPlanState,
                activeSkillIds = planningContext.activeSkills.map { it.id },
                history = planningContext.history,
            )
        var plannerVisualContext = VisualPerceptionContext()
        var plannerScreenshot: ScreenshotPayload? = null
        var executionBaselineObservation = planningContext.observation
        val profile = SessionRuntime.Planning.currentTaskProfile()
        val planningDecisionDirective =
            try {
                if (prePlannedDecision != null) {
                    val screenTelemetry =
                        SessionRuntime.describePlanningScreenTelemetry(
                            screenshot = null,
                            visualContext = plannerVisualContext,
                            baseObservation = planningContext.observation,
                            skippedByEarlySkill = true,
                        )
                    dependencies.updateScreenshotStatus(screenTelemetry.screenshotStatus)
                    SessionRuntime.recordPlanningScreenArtifacts(
                        planningContext = planningContext,
                        screenshot = null,
                        visualContext = plannerVisualContext,
                    )
                    val directive =
                        SessionRuntime.publishPlanningDecision(
                            planningContext = planningContext,
                            candidate =
                                if (runtimeResumeDecision != null) {
                                    PlanningDecisionCandidate.SystemIntervention(
                                        decision = prePlannedDecision,
                                        resumeDecisionSnapshot = runtimeResumeDecision.snapshot,
                                    )
                                } else {
                                    PlanningDecisionCandidate.EarlySkill(prePlannedDecision)
                                },
                        )
                    SessionExplanationStore.record(
                        sessionId = planningContext.sessionId,
                        turn = planningContext.turn,
                        phase = "planning",
                        title = "Early decision",
                        summary = prePlannedDecision.reason.ifBlank { prePlannedDecision.action.label },
                        actionLabel = prePlannedDecision.action.label,
                        detailLines = listOfNotNull(runtimeResumeDecision?.snapshot?.policy?.wireName),
                    )
                    directive
                } else {
                    val screenshot = dependencies.captureScreenshotPayload()
                    plannerScreenshot = screenshot
                    val visualContext = dependencies.buildVisualContext(screenshot, planningContext.observation)
                    plannerVisualContext = visualContext
                    SessionRuntime.recordPlanningScreenArtifacts(
                        planningContext = planningContext,
                        screenshot = screenshot,
                        visualContext = visualContext,
                    )
                    val augmentedPlannerObservation =
                        ObservationAugmenter.augmentObservation(
                            observation = planningContext.observation,
                            visualContext = visualContext,
                            blockedTexts = buildTaskEchoBlockedTexts(planningContext.task),
                        )
                    executionBaselineObservation = augmentedPlannerObservation
                    val screenTelemetry =
                        SessionRuntime.describePlanningScreenTelemetry(
                            screenshot = screenshot,
                            visualContext = visualContext,
                            baseObservation = planningContext.observation,
                            augmentedObservation = augmentedPlannerObservation,
                        )
                    dependencies.updateScreenshotStatus(screenTelemetry.screenshotStatus)
                    screenTelemetry.logMessages.forEach(dependencies.logLine)
                    val systemDecision =
                        RecoveryEngine.resolveSystemIntervention(
                            task = planningContext.task,
                            observation = planningContext.observation,
                            visualContext = visualContext,
                        )
                    if (systemDecision != null) {
                        val directive =
                            SessionRuntime.publishPlanningDecision(
                                planningContext = planningContext,
                                candidate = PlanningDecisionCandidate.SystemIntervention(systemDecision),
                            )
                        SessionExplanationStore.record(
                            sessionId = planningContext.sessionId,
                            turn = planningContext.turn,
                            phase = "planning",
                            title = "System intervention",
                            summary = systemDecision.reason.ifBlank { systemDecision.action.label },
                            actionLabel = systemDecision.action.label,
                            detailLines = screenTelemetry.logMessages.take(3),
                        )
                        directive
                    } else {
                        val plannerStartedAt = System.currentTimeMillis()
                        val plannedDecision = withTimeout(dependencies.plannerTimeoutMs) {
                            dependencies.planner.plan(
                                task = planningContext.task,
                                observation = augmentedPlannerObservation,
                                history = planningContext.history,
                                artifactHints = SessionRuntime.peekPlannerArtifactHints(planningContext.sessionId, planningContext.turn),
                                memoryContext = planningContext.memoryContext,
                                activeSkills = planningContext.activeSkills,
                                taskPlanState = planningContext.taskPlanState,
                                visualContext = visualContext,
                                screenshot = screenshot,
                                targetPackageName = planningContext.targetPackageName,
                            )
                        }
                        Log.d(TAG, "planner completed in ${System.currentTimeMillis() - plannerStartedAt} ms")
                        val refinement =
                            RecoveryEngine.refineDecision(
                                task = planningContext.task,
                                profile = profile,
                                profileId = planningContext.profileId,
                                observation = augmentedPlannerObservation,
                                memoryContext = planningContext.memoryContext,
                                taskPlanState = planningContext.taskPlanState,
                                decision = plannedDecision,
                                activeSkillIds = planningContext.activeSkills.map { it.id },
                                history = planningContext.history,
                            )
                        val directive =
                            SessionRuntime.publishPlanningDecision(
                                planningContext = planningContext,
                                candidate =
                                    PlanningDecisionCandidate.PlannerPipeline(
                                        plannedDecision = plannedDecision,
                                        refinement = refinement,
                                    ),
                            )
                        SessionExplanationStore.record(
                            sessionId = planningContext.sessionId,
                            turn = planningContext.turn,
                            phase = "planning",
                            title = "Planner decision",
                            summary = plannedDecision.reason.ifBlank { plannedDecision.action.label },
                            actionLabel = plannedDecision.action.label,
                            detailLines =
                                buildList {
                                    add(refinement.policyDecision.action.toolName)
                                    refinement.policyChanged.takeIf { it }?.let { add("policy_rewrite") }
                                    refinement.skillChanged.takeIf { it }?.let { add("skill_rewrite") }
                                },
                        )
                        directive
                    }
                }
            } catch (error: Throwable) {
                val failureDirective =
                    SessionRuntime.resolvePlanningFailureDirective(
                        error = error,
                        plannerTimeoutMs = dependencies.plannerTimeoutMs,
                    )
                dependencies.logLine(failureDirective.logMessage)
                SessionRuntime.Execution.recordError(
                    planningContext = planningContext,
                    error = failureDirective.errorMessage,
                    keepRunning = true,
                )
                return null
            }
        return PreparedTurnDecision(
            planningDecisionDirective = planningDecisionDirective,
            plannerVisualContext = plannerVisualContext,
            plannerScreenshot = plannerScreenshot,
            executionBaselineObservation = executionBaselineObservation,
        ).also {
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = planningContext.sessionId,
                task = planningContext.task,
                profileId = planningContext.profileId,
                turn = planningContext.turn,
                phase = "planning_ready",
                summary = planningDecisionDirective.decision.reason.ifBlank { planningDecisionDirective.decision.action.label },
                recommendedCommands = listOf(
                    "/turn-loop --session-id ${planningContext.sessionId}",
                    "/why --session-id ${planningContext.sessionId}",
                ),
            )
            SessionMainLoopStore.refresh(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                phase = "planning_ready",
                summary = planningDecisionDirective.decision.reason.ifBlank { planningDecisionDirective.decision.action.label },
                recommendedCommands = listOf("/main-loop --session-id ${planningContext.sessionId}", "/turn-loop --session-id ${planningContext.sessionId}"),
            )
            SessionActionLifecycleStore.record(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                action = planningDecisionDirective.decision.action,
                phase = "planning",
                status = "planned",
                summary = planningDecisionDirective.decision.reason.ifBlank { planningDecisionDirective.decision.action.label },
                recommendedCommands = listOf("/action-lifecycle --session-id ${planningContext.sessionId}", "/main-loop --session-id ${planningContext.sessionId}"),
            )
        }
    }

    private suspend fun executePreparedDecision(
        planningContext: AgentPlanningContext,
        preparedDecision: PreparedTurnDecision,
        dependencies: SessionRuntimeTurnDependencies,
    ) {
        val initialDecision = preparedDecision.planningDecisionDirective.decision
        val nextPlanDelayMs = recommendedPostActionDelayMs(initialDecision.action)
        val executableIndexedObservation =
            buildExecutableObservation(
                planningContext = planningContext,
                screenshot = preparedDecision.plannerScreenshot,
                visualContext = preparedDecision.plannerVisualContext,
                logLine = dependencies.logLine,
            )
        if (
            !handleExecutionEntry(
                planningContext = planningContext,
                preparedDecision = preparedDecision,
                executableIndexedObservation = executableIndexedObservation,
                nextPlanDelayMs = nextPlanDelayMs,
                dependencies = dependencies,
            )
        ) {
            return
        }
        val hookDispatch = SessionRuntime.beforeAction(planningContext, initialDecision)
        if (hookDispatch.shouldBlock) {
            SessionToolUseLedgerStore.record(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                action = initialDecision.action,
                status = SessionToolUseStatus.BLOCKED,
                summary = hookDispatch.blockReason,
                permissionOutcome = "runtime_hook",
            )
            SessionTurnLoopStore.refreshFromRuntime(
                sessionId = planningContext.sessionId,
                task = planningContext.task,
                profileId = planningContext.profileId,
                turn = planningContext.turn,
                phase = "execution_hook_blocked",
                summary = hookDispatch.blockReason,
                blockers = listOf("hook_blocked"),
            )
            SessionMainLoopStore.refresh(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                phase = "execution_hook_blocked",
                summary = hookDispatch.blockReason,
                recommendedCommands = listOf("/main-loop --session-id ${planningContext.sessionId}"),
            )
            SessionActionLifecycleStore.record(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                action = initialDecision.action,
                phase = "execution_hook",
                status = "blocked",
                summary = hookDispatch.blockReason,
                recommendedCommands = listOf("/action-lifecycle --session-id ${planningContext.sessionId}", "/main-loop --session-id ${planningContext.sessionId}"),
            )
            dependencies.logLine("执行前 hook 已阻断动作: ${hookDispatch.blockReason}")
            SessionRuntime.Execution.recordError(
                planningContext = planningContext,
                error = hookDispatch.blockReason,
                keepRunning = true,
            )
            return
        }
        val effectiveDecision = hookDispatch.overrideDecision ?: initialDecision
        if (effectiveDecision.action.label != initialDecision.action.label) {
            dependencies.logLine("执行前 hook 改写动作: ${initialDecision.action.label} -> ${effectiveDecision.action.label}")
        }
        val runtimeObject = com.lmx.xiaoxuanagent.agent.AgentToolRuntimeObjectRegistry.resolve(effectiveDecision.action)
        val toolValidation =
            runtimeObject.validateInput(
                action = effectiveDecision.action,
                observation = executableIndexedObservation.observation,
            )
        val toolPermission =
            runtimeObject.checkPermissions(
                action = effectiveDecision.action,
                observation = executableIndexedObservation.observation,
            )
        val toolInspection =
            runtimeObject.inspect(
                action = effectiveDecision.action,
                observation = executableIndexedObservation.observation,
            )
        val toolContract =
            AgentToolRuntimeRegistry.resolve(
                action = effectiveDecision.action,
                observation = executableIndexedObservation.observation,
            )
        val toolQueuedMessage = runtimeObject.queuedMessage(effectiveDecision.action)
        val toolProgressMessage = runtimeObject.progressMessage(effectiveDecision.action, executableIndexedObservation.observation)
        SessionActionLifecycleStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = effectiveDecision.action,
            phase = "tool_runtime",
            status = "queued",
            runtimeState = "queued",
            uiMessage = toolQueuedMessage,
            summary = toolQueuedMessage,
            recommendedCommands =
                listOf(
                    "/action-lifecycle --session-id ${planningContext.sessionId}",
                    "/tool-runtime --session-id ${planningContext.sessionId}",
                ),
            detailLines = listOf("permission_behavior=${toolPermission.behavior}"),
        )
        SessionActionLifecycleStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = effectiveDecision.action,
            phase = "tool_protocol",
            status =
                when {
                    !toolValidation.valid -> "invalid"
                    toolPermission.behavior == "deny" -> "denied"
                    toolPermission.behavior == "ask" -> "ask"
                    else -> "ready"
                },
            runtimeState =
                when {
                    !toolValidation.valid || toolPermission.behavior == "deny" -> "rejected"
                    toolPermission.behavior == "ask" -> "awaiting_approval"
                    else -> "ready"
                },
            uiMessage =
                when {
                    !toolValidation.valid || toolPermission.behavior == "deny" ->
                        runtimeObject.rejectedMessage(
                            action = effectiveDecision.action,
                            validation = toolValidation,
                            permission = toolPermission,
                        )
                    else -> toolProgressMessage
                },
            summary =
                if (toolValidation.valid) {
                    toolInspection.summary.ifBlank { effectiveDecision.action.toolName }
                } else {
                    toolValidation.reason.ifBlank { "工具协议校验未通过" }
                },
            recommendedCommands =
                listOf(
                    "/action-lifecycle --session-id ${planningContext.sessionId}",
                    "/tool-runtime --session-id ${planningContext.sessionId}",
                    "/tool-catalog",
                ),
            detailLines =
                buildList {
                    toolValidation.prompt.takeIf { it.isNotBlank() }?.let { add("prompt=$it") }
                    add("permission_behavior=${toolPermission.behavior}")
                    toolPermission.preview.takeIf { it.isNotBlank() }?.let { add("permission_preview=$it") }
                    addAll(toolValidation.detailLines.take(4))
                    addAll(toolPermission.detailLines.take(2))
                    addAll(toolContract.detailLines.take(2))
                },
        )
        SessionExplanationStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "tool_protocol",
            title = "Tool protocol",
            summary =
                if (toolValidation.valid) {
                    toolInspection.summary.ifBlank { effectiveDecision.action.toolName }
                } else {
                    toolValidation.reason.ifBlank { "工具协议校验未通过。" }
                },
            actionLabel = effectiveDecision.action.label,
            detailLines =
                buildList {
                    toolValidation.prompt.takeIf { it.isNotBlank() }?.let(::add)
                    add("permission_behavior=${toolPermission.behavior}")
                    toolPermission.preview.takeIf { it.isNotBlank() }?.let(::add)
                    addAll(toolValidation.detailLines.take(4))
                    addAll(toolPermission.detailLines.take(2))
                    addAll(toolContract.detailLines.take(2))
                },
        )
        if (!toolValidation.valid || toolPermission.behavior == "deny") {
            val rejectedMessage =
                runtimeObject.rejectedMessage(
                    action = effectiveDecision.action,
                    validation = toolValidation,
                    permission = toolPermission,
                )
            val syntheticResult =
                AgentExecutionResult(
                    message = rejectedMessage,
                    keepRunning = toolPermission.behavior != "deny",
                    shouldImmediateReplan = toolPermission.behavior != "deny",
                    toolRuntimePrompt = toolValidation.prompt,
                    toolRuntimeSummary = toolInspection.summary,
                    toolRuntimeDetailLines = (toolValidation.detailLines + toolPermission.detailLines).distinct().take(8),
                    toolRuntimeState = "rejected",
                    toolRuntimeQueuedMessage = toolQueuedMessage,
                    toolRuntimeProgressMessage = toolProgressMessage,
                    toolRuntimeRejectedMessage = rejectedMessage,
                )
            finalizeExecutedDecision(
                planningContext = planningContext,
                decision = effectiveDecision,
                executableIndexedObservation = executableIndexedObservation,
                executionResult = syntheticResult,
                executionStartedAt = System.currentTimeMillis(),
                nextPlanDelayMs = nextPlanDelayMs,
                dependencies = dependencies,
            )
            return
        }
        if (
            !handleToolPermissionGate(
                planningContext = planningContext,
                action = effectiveDecision.action,
                permission = toolPermission,
                permissionFamily = toolContract.permissionFamily,
                queuedMessage = toolQueuedMessage,
                progressMessage = toolProgressMessage,
                logLine = dependencies.logLine,
            )
        ) {
            return
        }
        if (
            !handleSafetyGate(
                planningContext = planningContext,
                action = effectiveDecision.action,
                executableIndexedObservation = executableIndexedObservation,
                queuedMessage = toolQueuedMessage,
                progressMessage = toolProgressMessage,
                logLine = dependencies.logLine,
            )
        ) {
            return
        }
        SessionActionLifecycleStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = effectiveDecision.action,
            phase = "tool_runtime",
            status = "running",
            runtimeState = "running",
            uiMessage = toolProgressMessage,
            summary = toolProgressMessage,
            recommendedCommands =
                listOf(
                    "/tool-runtime --session-id ${planningContext.sessionId}",
                    "/action-lifecycle --session-id ${planningContext.sessionId}",
                ),
            detailLines =
                buildList {
                    add("permission_preview=${toolContract.permissionPreview}")
                    addAll(toolContract.detailLines.take(2))
                },
        )
        val executionStartedAt = System.currentTimeMillis()
        val result =
            (hookDispatch.injectedExecutionResult ?: dependencies.executeAction(executableIndexedObservation, effectiveDecision.action)).copy(
                toolRuntimePrompt = toolValidation.prompt.ifBlank { runtimeObject.progressMessage(effectiveDecision.action, executableIndexedObservation.observation) },
                toolRuntimeSummary = toolInspection.summary,
                toolRuntimeQueuedMessage =
                    (hookDispatch.injectedExecutionResult?.toolRuntimeQueuedMessage ?: "").ifBlank { toolQueuedMessage },
                toolRuntimeProgressMessage =
                    (hookDispatch.injectedExecutionResult?.toolRuntimeProgressMessage ?: "").ifBlank { toolProgressMessage },
                toolRuntimeSuccessMessage =
                    (hookDispatch.injectedExecutionResult?.toolRuntimeSuccessMessage ?: "").ifBlank {
                        (hookDispatch.injectedExecutionResult?.message ?: "").ifBlank { "" }
                    },
                toolRuntimeDetailLines =
                    (
                        toolValidation.detailLines +
                            toolPermission.detailLines +
                            (hookDispatch.injectedExecutionResult?.toolRuntimeDetailLines ?: emptyList())
                    ).distinct().take(8),
            )
        Log.d(TAG, "action result action=${effectiveDecision.action.label} keepRunning=${result.keepRunning} msg=${result.message}")
        finalizeExecutedDecision(
            planningContext = planningContext,
            decision = effectiveDecision,
            executableIndexedObservation = executableIndexedObservation,
            executionResult = result,
            executionStartedAt = executionStartedAt,
            nextPlanDelayMs = nextPlanDelayMs,
            dependencies = dependencies,
        )
    }

    private suspend fun handleExecutionEntry(
        planningContext: AgentPlanningContext,
        preparedDecision: PreparedTurnDecision,
        executableIndexedObservation: IndexedScreenObservation,
        nextPlanDelayMs: Long,
        dependencies: SessionRuntimeTurnDependencies,
    ): Boolean {
        when (
            val entryDirective =
                SessionRuntime.Execution.resolveExecutionEntryDirective(
                    planningContext = planningContext,
                    decision = preparedDecision.planningDecisionDirective.decision,
                    executionBaselineObservation = preparedDecision.executionBaselineObservation,
                    executableObservation = executableIndexedObservation,
                    nextPlanDelayMs = nextPlanDelayMs,
                )
        ) {
            is ExecutionEntryDirective.SkipAndReplan -> {
                dependencies.logLine(entryDirective.logMessage)
                return continueWithImmediateReplan(entryDirective.continuation, dependencies)
            }

            is ExecutionEntryDirective.AbortAndWait -> {
                dependencies.logLine(entryDirective.logMessage)
                SessionRuntime.Execution.recordError(
                    planningContext = planningContext,
                    error = entryDirective.errorMessage,
                    keepRunning = true,
                )
                return false
            }

            is ExecutionEntryDirective.Proceed -> {
                dependencies.logLine(entryDirective.logMessage)
                return true
            }
        }
    }

    private fun handleToolPermissionGate(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        permission: com.lmx.xiaoxuanagent.agent.AgentToolPermissionDecision,
        permissionFamily: String,
        queuedMessage: String,
        progressMessage: String,
        logLine: (String) -> Unit,
    ): Boolean {
        if (permission.behavior != "ask") return true
        val approvalKey = "tool_perm_${planningContext.sessionId}_${planningContext.turn}_${action.toolName.hashCode()}"
        val summary = permission.summary.ifBlank { "工具运行时要求人工确认。" }
        SessionRuntime.requestSafetyConfirmation(
            PendingSafetyConfirmation(
                approvalKey = approvalKey,
                title = "工具运行时需要确认",
                summary = summary,
                actionLabel = action.label,
                planStage = planningContext.taskPlanState.currentStage,
                subgoalId = planningContext.taskPlanState.currentSubgoalId,
                nextObjective = planningContext.taskPlanState.nextObjective,
                targetPackageName = planningContext.observation.packageName,
                actionFamily = permissionFamily.ifBlank { "general" },
                riskLevelLabel = "confirm",
                grantScopeHint = "session",
            ),
        )
        logLine("工具运行时要求确认: ${action.toolName} | ${permission.preview.ifBlank { summary }}")
        SessionToolUseLedgerStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = action,
            status = SessionToolUseStatus.AWAITING_APPROVAL,
            summary = summary,
            permissionOutcome = "tool_runtime_review",
            runtimeState = "awaiting_approval",
            queuedMessage = queuedMessage,
            progressMessage = progressMessage,
            rejectedMessage = summary,
        )
        SessionSafetyDecisionStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            actionLabel = action.label,
            actionFamily = permissionFamily.ifBlank { "general" },
            targetPackageName = planningContext.observation.packageName,
            outcome = "tool_runtime_review",
            summary = summary,
            detailLines = permission.detailLines.take(4),
        )
        SessionExplanationStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "tool_permission",
            title = "Tool permission needed",
            summary = summary,
            actionLabel = action.label,
            detailLines = permission.detailLines.take(4),
        )
        SessionTurnLoopStore.refreshFromRuntime(
            sessionId = planningContext.sessionId,
            task = planningContext.task,
            profileId = planningContext.profileId,
            turn = planningContext.turn,
            phase = "tool_permission_wait",
            summary = summary,
            blockers = listOf("tool_runtime_approval"),
            recommendedCommands = listOf("/approve --session-id ${planningContext.sessionId}", "/tool-runtime --session-id ${planningContext.sessionId}"),
        )
        SessionMainLoopStore.refresh(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "tool_permission_wait",
            summary = summary,
            recommendedCommands = listOf("/approve --session-id ${planningContext.sessionId}", "/permission-center --session-id ${planningContext.sessionId}"),
        )
        SessionActionLifecycleStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = action,
            phase = "tool_permission",
            status = "await_confirmation",
            runtimeState = "awaiting_approval",
            uiMessage = progressMessage,
            summary = summary,
            recommendedCommands = listOf("/tool-runtime --session-id ${planningContext.sessionId}"),
        )
        return false
    }

    private fun handleSafetyGate(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        executableIndexedObservation: IndexedScreenObservation,
        queuedMessage: String,
        progressMessage: String,
        logLine: (String) -> Unit,
    ): Boolean {
        val safetyReview =
            SafetyPolicyEngine.review(
                task = planningContext.task,
                indexedObservation = executableIndexedObservation,
                action = action,
            )
        return when (val safetyDirective = SessionRuntime.resolveSafetyEntryDirective(safetyReview)) {
            is SafetyEntryDirective.Proceed -> {
                SessionSafetyDecisionStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    actionLabel = action.label,
                    actionFamily = safetyReview.actionFamily,
                    targetPackageName = safetyReview.targetPackageName,
                    outcome = "proceed",
                    summary = safetyReview.summary.ifBlank { "安全检查通过" },
                    detailLines = listOfNotNull(safetyReview.riskLevelLabel, safetyReview.grantScopeHint),
                )
                SessionExplanationStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "safety",
                    title = "Safety proceed",
                    summary = safetyReview.summary.ifBlank { "动作 ${action.toolName} 已通过安全检查。" },
                    actionLabel = action.label,
                    detailLines = listOfNotNull(safetyReview.actionFamily, safetyReview.grantScopeHint),
                )
                if (safetyDirective.logMessage.isNotBlank()) {
                    logLine(safetyDirective.logMessage)
                }
                SessionTurnLoopStore.refreshFromRuntime(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    profileId = planningContext.profileId,
                    turn = planningContext.turn,
                    phase = "safety_proceed",
                    summary = safetyReview.summary.ifBlank { action.label },
                )
                SessionMainLoopStore.refresh(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "safety_proceed",
                    summary = safetyReview.summary.ifBlank { action.label },
                )
                SessionActionLifecycleStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    action = action,
                    phase = "safety",
                    status = "proceed",
                    runtimeState = "running",
                    uiMessage = progressMessage,
                    summary = safetyReview.summary.ifBlank { "安全检查通过" },
                    recommendedCommands = listOf("/action-lifecycle --session-id ${planningContext.sessionId}"),
                )
                true
            }

            is SafetyEntryDirective.AwaitConfirmation -> {
                SessionToolUseLedgerStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    action = action,
                    status = SessionToolUseStatus.AWAITING_APPROVAL,
                    summary = safetyReview.summary.ifBlank { "动作需要人工确认" },
                    permissionOutcome = "safety_approval",
                    runtimeState = "awaiting_approval",
                    queuedMessage = queuedMessage,
                    progressMessage = progressMessage,
                    rejectedMessage = safetyReview.summary.ifBlank { "动作需要人工确认" },
                )
                SessionSafetyDecisionStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    actionLabel = action.label,
                    actionFamily = safetyReview.actionFamily,
                    targetPackageName = safetyReview.targetPackageName,
                    outcome = "await_confirmation",
                    summary = safetyReview.summary.ifBlank { "动作需要人工确认" },
                    detailLines = listOfNotNull(safetyReview.riskLevelLabel, safetyReview.grantScopeHint),
                )
                SessionExplanationStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "safety",
                    title = "Safety approval needed",
                    summary = safetyReview.summary.ifBlank { "动作需要人工确认。" },
                    actionLabel = action.label,
                    detailLines = listOfNotNull(safetyReview.actionFamily, safetyReview.grantScopeHint, safetyReview.riskLevelLabel),
                )
                if (safetyDirective.logMessage.isNotBlank()) {
                    logLine(safetyDirective.logMessage)
                }
                SessionTurnLoopStore.refreshFromRuntime(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    profileId = planningContext.profileId,
                    turn = planningContext.turn,
                    phase = "awaiting_approval",
                    summary = safetyReview.summary.ifBlank { action.label },
                    blockers = listOf("awaiting_confirmation"),
                    recommendedCommands = listOf("/approve --session-id ${planningContext.sessionId}", "/reject --session-id ${planningContext.sessionId}"),
                )
                SessionMainLoopStore.refresh(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "awaiting_approval",
                    summary = safetyReview.summary.ifBlank { action.label },
                    recommendedCommands = listOf("/approve --session-id ${planningContext.sessionId}", "/permission-center --session-id ${planningContext.sessionId}"),
                )
                SessionActionLifecycleStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    action = action,
                    phase = "safety",
                    status = "awaiting_approval",
                    runtimeState = "awaiting_approval",
                    uiMessage = safetyReview.summary.ifBlank { progressMessage },
                    summary = safetyReview.summary.ifBlank { "动作需要人工确认" },
                    recommendedCommands = listOf("/approve --session-id ${planningContext.sessionId}", "/permission-center --session-id ${planningContext.sessionId}", "/action-lifecycle --session-id ${planningContext.sessionId}"),
                )
                false
            }

            is SafetyEntryDirective.StopAgent -> {
                SessionToolUseLedgerStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    action = action,
                    status = SessionToolUseStatus.BLOCKED,
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    permissionOutcome = "safety_block",
                    runtimeState = "blocked",
                    queuedMessage = queuedMessage,
                    progressMessage = progressMessage,
                    rejectedMessage = safetyReview.summary.ifBlank { safetyDirective.reason },
                )
                SessionSafetyDecisionStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    actionLabel = action.label,
                    actionFamily = safetyReview.actionFamily,
                    targetPackageName = safetyReview.targetPackageName,
                    outcome = "blocked",
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    detailLines = listOfNotNull(safetyReview.riskLevelLabel, safetyReview.grantScopeHint),
                )
                SessionExplanationStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "safety",
                    title = "Safety blocked",
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    actionLabel = action.label,
                    detailLines = listOfNotNull(safetyReview.actionFamily, safetyReview.riskLevelLabel),
                )
                SessionTurnLoopStore.refreshFromRuntime(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    profileId = planningContext.profileId,
                    turn = planningContext.turn,
                    phase = "safety_blocked",
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    blockers = listOf("safety_blocked"),
                )
                SessionMainLoopStore.refresh(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    phase = "safety_blocked",
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    recommendedCommands = listOf("/permission-center --session-id ${planningContext.sessionId}"),
                )
                SessionActionLifecycleStore.record(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    action = action,
                    phase = "safety",
                    status = "blocked",
                    runtimeState = "blocked",
                    uiMessage = safetyReview.summary.ifBlank { safetyDirective.reason },
                    summary = safetyReview.summary.ifBlank { safetyDirective.reason },
                    recommendedCommands = listOf("/permission-center --session-id ${planningContext.sessionId}", "/action-lifecycle --session-id ${planningContext.sessionId}"),
                )
                SessionRuntime.Lifecycle.stopAgent(safetyDirective.reason)
                false
            }
        }
    }

    private suspend fun finalizeExecutedDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        executableIndexedObservation: IndexedScreenObservation,
        executionResult: AgentExecutionResult,
        executionStartedAt: Long,
        nextPlanDelayMs: Long,
        dependencies: SessionRuntimeTurnDependencies,
    ) {
        val toolContract =
            AgentToolRuntimeRegistry.resolve(
                action = decision.action,
                observation = executableIndexedObservation.observation,
            )
        val completion =
            completeExecutionWithVerification(
                planningContext = planningContext,
                action = decision.action,
                beforeObservation = planningContext.observation,
                latestObservation = executableIndexedObservation,
                executionResult = executionResult,
                dependencies = dependencies,
            )
        val durationMs = System.currentTimeMillis() - executionStartedAt
        val toolStatus =
            when {
                executionResult.toolRuntimeState == "rejected" -> SessionToolUseStatus.BLOCKED
                executionResult.toolRuntimeState == "error" -> SessionToolUseStatus.FAILED
                decision.action is AgentAction.Finish && completion.recoveryDiagnosis == null -> SessionToolUseStatus.SUCCEEDED
                decision.action is AgentAction.Fail -> SessionToolUseStatus.FAILED
                !completion.keepRunning -> SessionToolUseStatus.FAILED
                completion.recoveryDiagnosis != null -> SessionToolUseStatus.FAILED
                completion.finalMessage.contains("需要人工确认") -> SessionToolUseStatus.AWAITING_APPROVAL
                else -> SessionToolUseStatus.SUCCEEDED
            }
        val runtimeState =
            executionResult.toolRuntimeState.ifBlank {
                when (toolStatus) {
                    SessionToolUseStatus.SUCCEEDED -> "succeeded"
                    SessionToolUseStatus.AWAITING_APPROVAL -> "awaiting_approval"
                    SessionToolUseStatus.BLOCKED -> "blocked"
                    SessionToolUseStatus.FAILED -> "failed"
                    else -> "attempted"
                }
            }
        val lifecycleMessage =
            listOf(
                executionResult.toolRuntimeSuccessMessage,
                executionResult.toolRuntimeErrorMessage,
                executionResult.toolRuntimeRejectedMessage,
                executionResult.toolRuntimeProgressMessage,
                executionResult.toolRuntimeQueuedMessage,
                completion.finalMessage,
                executionResult.message,
            ).firstOrNull { it.isNotBlank() }.orEmpty()
        SessionToolUseLedgerStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = decision.action,
            status = toolStatus,
            summary = completion.finalMessage.ifBlank { executionResult.message },
            permissionOutcome = if (toolStatus == SessionToolUseStatus.SUCCEEDED) "executed" else "verification_guard",
            durationMs = durationMs,
            readOnly = executionResult.toolRuntimeSummary.contains("read_only=true"),
            progressLabel = toolContract.progressLabel,
            interruptBehavior = toolContract.interruptBehavior,
            protocolSummary = executionResult.toolRuntimeSummary,
            runtimeState = runtimeState,
            queuedMessage = executionResult.toolRuntimeQueuedMessage,
            progressMessage = executionResult.toolRuntimeProgressMessage,
            rejectedMessage = executionResult.toolRuntimeRejectedMessage,
            errorMessage = executionResult.toolRuntimeErrorMessage,
            successMessage = executionResult.toolRuntimeSuccessMessage.ifBlank {
                if (toolStatus == SessionToolUseStatus.SUCCEEDED) lifecycleMessage else ""
            },
            detailLines =
                listOfNotNull(
                    executionResult.toolRuntimePrompt.takeIf { it.isNotBlank() }?.let { "prompt=$it" },
                    "keep_running=${completion.keepRunning}",
                    "immediate_replan=${completion.shouldImmediateReplan}",
                    "permission_preview=${toolContract.permissionPreview}",
                    completion.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let { "suggested=$it" },
                ) + executionResult.toolRuntimeDetailLines.take(3),
        )
        SessionGroundingHealthStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            packageName = executableIndexedObservation.observation.packageName,
            pageState = executableIndexedObservation.observation.pageState,
            toolName = decision.action.toolName,
            actionLabel = decision.action.label,
            outcome =
                when {
                    toolStatus == SessionToolUseStatus.SUCCEEDED -> "success"
                    completion.recoveryDiagnosis != null -> "recovery"
                    completion.shouldImmediateReplan -> "retry"
                    else -> "failed"
                },
            groundingSource = executionResult.groundingSource,
            targetText = executionResult.expectedEntityHint.ifBlank { executionResult.resolvedTargetText },
            targetContainerId = executionResult.resolvedContainerId,
            entityType = executionResult.entityFingerprintType,
            recoveryCategory = completion.recoveryDiagnosis?.category?.name?.lowercase().orEmpty(),
            summary = completion.finalMessage.ifBlank { executionResult.message },
        )
        SessionTurnLoopStore.refreshFromRuntime(
            sessionId = planningContext.sessionId,
            task = planningContext.task,
            profileId = planningContext.profileId,
            turn = planningContext.turn,
            phase = if (toolStatus == SessionToolUseStatus.SUCCEEDED) "execution_done" else "execution_recovery",
            summary = completion.finalMessage.ifBlank { executionResult.message },
            blockers = listOfNotNull(completion.recoveryDiagnosis?.category?.name?.lowercase()),
        )
        SessionMainLoopStore.refresh(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = if (toolStatus == SessionToolUseStatus.SUCCEEDED) "execution_done" else "execution_recovery",
            summary = completion.finalMessage.ifBlank { executionResult.message },
            recommendedCommands =
                listOfNotNull(
                    "/main-loop --session-id ${planningContext.sessionId}",
                    "/tool-runtime --session-id ${planningContext.sessionId}",
                    if (completion.recoveryDiagnosis != null || toolStatus != SessionToolUseStatus.SUCCEEDED) "/grounding-health --session-id ${planningContext.sessionId}" else null,
                ),
        )
        SessionActionLifecycleStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            action = decision.action,
            phase = "execution",
            status =
                when (toolStatus) {
                    SessionToolUseStatus.SUCCEEDED -> "succeeded"
                    SessionToolUseStatus.AWAITING_APPROVAL -> "awaiting_approval"
                    SessionToolUseStatus.BLOCKED -> "blocked"
                    SessionToolUseStatus.FAILED -> if (completion.recoveryDiagnosis != null) "recovery" else "failed"
                    else -> "attempted"
                },
            runtimeState = runtimeState,
            uiMessage = lifecycleMessage,
            summary = completion.finalMessage.ifBlank { executionResult.message },
            recommendedCommands =
                listOfNotNull(
                    "/action-lifecycle --session-id ${planningContext.sessionId}",
                    "/tool-runtime --session-id ${planningContext.sessionId}",
                    if (completion.recoveryDiagnosis != null || toolStatus != SessionToolUseStatus.SUCCEEDED) "/grounding-health --session-id ${planningContext.sessionId}" else null,
                ),
            detailLines =
                listOfNotNull(
                    completion.recoveryDiagnosis?.category?.name?.lowercase(),
                    completion.suggestedRecoveryAction.takeIf { it.isNotBlank() },
                ),
        )
        SessionExplanationStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "execution",
            title = "Tool execution",
            summary = completion.finalMessage.take(160).ifBlank { executionResult.message.take(160) },
            actionLabel = decision.action.label,
            detailLines =
                listOf(
                    "tool=${decision.action.toolName}",
                    "status=${toolStatus.name.lowercase()}",
                    "duration_ms=$durationMs",
                    "permission_preview=${toolContract.permissionPreview}",
                ) + executionResult.toolRuntimeDetailLines.take(2),
        )
        val turnContinuation =
            SessionRuntime.Execution.finalizeTurnForContinuation(
                planningContext = planningContext,
                decision = decision,
                result = completion.finalMessage,
                taskResult = completion.taskResult,
                keepRunning = completion.keepRunning,
                resetObservationSignatureForNextPlan =
                    if (completion.shouldImmediateReplan) {
                        true
                    } else {
                        completion.shouldResetObservationSignature
                    },
                nextPlanDelayMs = nextPlanDelayMs,
                recoveryDiagnosis = completion.recoveryDiagnosis,
                suggestedRecoveryAction = completion.suggestedRecoveryAction,
                immediateReplanObservation = completion.replanObservation.takeIf { completion.shouldImmediateReplan },
                immediateReplanReason =
                    completion.replanObservation
                        ?.takeIf { completion.shouldImmediateReplan }
                        ?.let { ImmediateReplanReason.POST_ACTION_CONTINUATION },
            )
        continueWithImmediateReplan(turnContinuation, dependencies)
    }

    private suspend fun completeExecutionWithVerification(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        latestObservation: IndexedScreenObservation,
        executionResult: AgentExecutionResult,
        dependencies: SessionRuntimeTurnDependencies,
    ): ExecutionCompletion {
        if (action is AgentAction.Finish) {
            return SessionRuntime.resolveFinishExecutionCompletion(
                planningContext = planningContext,
                action = action,
                beforeObservation = beforeObservation,
                latestObservation = latestObservation,
                executionResult = executionResult,
            )
        }
        if (executionResult.shouldImmediateReplan) {
            val afterObservation = dependencies.observeCurrentScreen() ?: latestObservation
            return ExecutionCompletion(
                finalMessage = executionResult.message,
                keepRunning = true,
                shouldImmediateReplan = true,
                shouldResetObservationSignature = true,
                replanObservation = afterObservation,
                recoveryDiagnosis = null,
                suggestedRecoveryAction = "",
                taskResult = null,
            )
        }
        if (action is AgentAction.Wait) {
            if (executionResult.recommendedWaitMs > 0) {
                delay(executionResult.recommendedWaitMs)
            }
            val afterObservation = dependencies.observeCurrentScreen()
            val shouldReplan =
                afterObservation != null &&
                    (
                        beforeObservation.elements.isEmpty() ||
                            beforeObservation.pageState.equals("UNKNOWN", ignoreCase = true) ||
                            afterObservation.observation.signature != beforeObservation.signature ||
                            afterObservation.observation.elements.size != beforeObservation.elements.size
                    )
            return ExecutionCompletion(
                finalMessage =
                    if (shouldReplan) {
                        "${executionResult.message} 已重新采样当前页面，继续规划。"
                    } else {
                        executionResult.message
                    },
                keepRunning = true,
                shouldImmediateReplan = shouldReplan,
                shouldResetObservationSignature = shouldReplan,
                replanObservation = afterObservation ?: latestObservation,
                recoveryDiagnosis = null,
                suggestedRecoveryAction = "",
                taskResult = null,
            )
        }
        if (!executionResult.keepRunning || !executionResult.requiresObservationCheck) {
            return ExecutionCompletion(
                finalMessage = executionResult.message,
                keepRunning = executionResult.keepRunning,
                shouldImmediateReplan = false,
                shouldResetObservationSignature = false,
                replanObservation = null,
                recoveryDiagnosis = null,
                suggestedRecoveryAction = "",
                taskResult = null,
            )
        }
        if (executionResult.recommendedWaitMs > 0) {
            delay(executionResult.recommendedWaitMs)
        }
        val afterObservation = dependencies.observeCurrentScreen()
        val afterScreenshot = afterObservation?.let { dependencies.captureScreenshotPayload() }
        val afterVisualContext =
            afterObservation?.let { indexed ->
                dependencies.buildVisualContext(afterScreenshot, indexed.observation)
            }
        val verificationFrames =
            collectVerificationFrames(
                initialObservation = afterObservation,
                initialScreenshot = afterScreenshot,
                initialVisualContext = afterVisualContext,
                dependencies = dependencies,
            )
        val verification =
            ActionVerifier.verify(
                action = action,
                before = beforeObservation,
                afterIndexedObservation = afterObservation,
                resolvedElementId = executionResult.resolvedElementId,
                executionResult = executionResult,
                afterScreenshot = afterScreenshot,
                afterVisualContext = afterVisualContext,
                afterFrames = verificationFrames,
            )
        val taskVerification =
            TaskCompletionVerifier.verifyProgress(
                task = planningContext.task,
                taskPlanState = planningContext.taskPlanState,
                action = action,
                before = beforeObservation,
                after = afterObservation?.observation,
                executionResult = executionResult,
            )
        val completion =
            SessionRuntime.resolveObservedExecutionCompletion(
                planningContext = planningContext,
                action = action,
                beforeObservation = beforeObservation,
                latestObservation = latestObservation,
                afterObservation = afterObservation,
                executionResult = executionResult,
                verification = verification,
                taskVerification = taskVerification,
            )
        if (completion.recoveryDiagnosis != null) {
            dependencies.logLine(
                "恢复诊断: ${completion.recoveryDiagnosis.category.name.lowercase()} | ${completion.recoveryDiagnosis.summary}" +
                    completion.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let { " | 建议动作=$it" }.orEmpty(),
            )
        }
        SessionExplanationStore.record(
            sessionId = planningContext.sessionId,
            turn = planningContext.turn,
            phase = "execution",
            title = "Post action verification",
            summary = completion.finalMessage.take(160),
            actionLabel = action.label,
            detailLines =
                listOfNotNull(
                    "keep_running=${completion.keepRunning}",
                    completion.recoveryDiagnosis?.let { "recovery=${it.category.name.lowercase()}" },
                    completion.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let { "suggested=$it" },
                ),
        )
        if (completion.shouldImmediateReplan && completion.recoveryDiagnosis == null) {
            val weakObservation =
                beforeObservation.elements.isEmpty() ||
                    (
                        beforeObservation.pageState.equals("UNKNOWN", ignoreCase = true) &&
                            beforeObservation.topTexts.isEmpty()
                    )
            dependencies.logLine(
                "验证通过后主动续跑: weak=$weakObservation, taskProgress=${taskVerification.verified}, " +
                    "afterSig=${afterObservation?.observation?.signature ?: "-"}",
            )
        }
        maybeAutoCompensate(planningContext, action, completion, dependencies)
        return completion
    }

    private val sideEffectingActionTypes =
        setOf(
            AgentAction.SetText::class,
            AgentAction.PopulatePrimaryInput::class,
            AgentAction.PasteClipboard::class,
            AgentAction.Click::class,
            AgentAction.LongClick::class,
            AgentAction.TapPoint::class,
            AgentAction.SubmitPrimaryAction::class,
        )

    private fun isSideEffectingAction(action: AgentAction): Boolean = action::class in sideEffectingActionTypes

    internal fun debugIsSideEffectingAction(action: AgentAction): Boolean = isSideEffectingAction(action)

    /**
     * 失败收口处的自动回滚（#3）。仅在"有副作用动作 + 验证失败诊断存在 + 恢复引擎无可行下一步（死胡同）"
     * 时触发，避免与重试/恢复抢方向。只自动执行**低风险可逆**计划（approvalRequired=false，如 restore_text /
     * backtrack）；高风险计划（支付/不可逆）不自动跑，记日志/trace 交人工，符合既有确认策略。
     * 每个回滚动作同样过 [handleSafetyGate]，保证回滚本身也受安全策略约束。
     */
    private suspend fun maybeAutoCompensate(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        completion: ExecutionCompletion,
        dependencies: SessionRuntimeTurnDependencies,
    ) {
        if (!isSideEffectingAction(action)) return
        if (completion.recoveryDiagnosis == null) return
        if (completion.suggestedRecoveryAction.isNotBlank()) return
        val plan =
            SessionCompensationPlanner.derivePlan(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                beforeObservation = planningContext.observation,
                action = action,
            ) ?: return
        val plannedSteps = plan.steps.filter { it.status == "planned" }
        if (plannedSteps.isEmpty()) return
        if (plan.approvalRequired) {
            dependencies.logLine("检测到失败且回滚为高风险(${plan.safetyTier})，不自动执行，建议人工撤销：${plan.summary}")
            PlatformTraceStore.record(
                category = "auto_compensation_requires_approval",
                sessionId = planningContext.sessionId,
                summary = "turn=${planningContext.turn} | ${plan.summary}",
            )
            return
        }
        val currentObservation = dependencies.observeCurrentScreen() ?: return
        var applied = 0
        for (step in plannedSteps) {
            val gatePassed =
                handleSafetyGate(
                    planningContext = planningContext,
                    action = step.action,
                    executableIndexedObservation = currentObservation,
                    queuedMessage = "",
                    progressMessage = "自动回滚：${step.title}",
                    logLine = dependencies.logLine,
                )
            if (!gatePassed) {
                dependencies.logLine("回滚步骤被安全策略阻断：${step.action.label}")
                break
            }
            val result = runCatching { dependencies.executeAction(currentObservation, step.action) }.getOrNull()
            if (result?.keepRunning == true) {
                applied += 1
            } else if (plan.transactionMode == "ordered_strict") {
                break
            }
        }
        dependencies.logLine("自动回滚完成：applied=$applied/${plannedSteps.size} | ${plan.summary}")
        PlatformTraceStore.record(
            category = "auto_compensation",
            sessionId = planningContext.sessionId,
            summary = "turn=${planningContext.turn} applied=$applied/${plannedSteps.size} | ${plan.summary}",
        )
    }

    private suspend fun continueWithImmediateReplan(
        continuation: TurnContinuation,
        dependencies: SessionRuntimeTurnDependencies,
    ): Boolean {
        if (!continuation.shouldAttemptImmediateReplan) {
            val sessionId = SessionRuntime.State.runtimeState().session.sessionId
            val autoDirective =
                SessionRecursiveLoopScheduler.derive(
                    sessionId = sessionId,
                    continuation = continuation,
                )
            if (!autoDirective.shouldContinueNow) {
                return false
            }
            autoDirective.summary.takeIf { it.isNotBlank() }?.let(dependencies.logLine)
            val followUpObservation = dependencies.observeCurrentScreen() ?: return false
            SessionMainLoopStore.refresh(
                sessionId = sessionId,
                turn = SessionRuntime.State.runtimeState().session.turns,
                phase = "recursive_continue",
                summary = autoDirective.summary,
                recommendedCommands = autoDirective.recommendedCommands,
            )
            when (val directive = SessionRuntime.toPlanningDirective(SessionRuntime.acquirePlanningContext(followUpObservation))) {
                is PlanningDirective.ExecuteTurn -> {
                    directive.logMessage.takeIf { it.isNotBlank() }?.let(dependencies.logLine)
                    runPlanningTurn(directive.context, dependencies)
                    return true
                }
                PlanningDirective.NoOp -> return false
                PlanningDirective.MaxTurnsReached -> {
                    SessionRuntime.handleMaxTurnsReached()
                    return true
                }
            }
        }
        if (!continuation.shouldAttemptImmediateReplan) {
            return false
        }
        SessionRuntime.describeTurnContinuationDelay(continuation)?.let(dependencies.logLine)
        if (continuation.settleDelayMs > 0) {
            delay(continuation.settleDelayMs)
        }
        when (val directive = SessionRuntime.Execution.tryImmediateReplanDirective(continuation)) {
            is PlanningDirective.ExecuteTurn -> {
                if (directive.logMessage.isNotBlank()) {
                    dependencies.logLine(directive.logMessage)
                }
                runPlanningTurn(directive.context, dependencies)
            }

            PlanningDirective.NoOp -> Unit
            PlanningDirective.MaxTurnsReached -> SessionRuntime.handleMaxTurnsReached()
        }
        return true
    }

    private fun buildExecutableObservation(
        planningContext: AgentPlanningContext,
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
        logLine: (String) -> Unit,
    ): IndexedScreenObservation {
        val baseIndexedObservation = planningContext.indexedObservation
        if (hasActionableVisualSignals(visualContext)) {
            logLine(
                "执行阶段复用规划快照: sig=${baseIndexedObservation.observation.signature}, pkg=${baseIndexedObservation.observation.packageName}",
            )
            return ObservationAugmenter.augmentIndexedObservation(
                indexedObservation = baseIndexedObservation,
                visualContext = visualContext,
                blockedTexts = buildTaskEchoBlockedTexts(planningContext.task),
            ).copy(
                screenshot = screenshot,
                visualContext = visualContext,
            )
        }
        logLine(
            "执行阶段使用规划快照: sig=${baseIndexedObservation.observation.signature}, pkg=${baseIndexedObservation.observation.packageName}",
        )
        return baseIndexedObservation.copy(
            screenshot = screenshot,
            visualContext = visualContext,
        )
    }

    private fun buildTaskEchoBlockedTexts(task: String): List<String> =
        buildList {
            val normalizedTask = task.trim()
            if (normalizedTask.length >= 6) {
                add(normalizedTask)
            }
            val compactTask = normalizedTask.replace("\\s+".toRegex(), "")
            if (compactTask.length >= 6 && compactTask != normalizedTask) {
                add(compactTask)
            }
        }

    private fun recommendedPostActionDelayMs(
        action: AgentAction,
    ): Long =
        when (action) {
            is AgentAction.Scroll,
            is AgentAction.Swipe,
            is AgentAction.ScrollForMore,
            -> 650L
            is AgentAction.Click,
            is AgentAction.TapPoint,
            is AgentAction.SubmitPrimaryAction,
            is AgentAction.DismissInterrupt,
            is AgentAction.OpenBestCandidate,
            AgentAction.Back,
            -> 320L
            is AgentAction.SetText,
            AgentAction.PressEnter,
            AgentAction.FocusPrimaryInput,
            -> 220L
            is AgentAction.LaunchApp,
            is AgentAction.ReturnToTargetApp,
            -> 450L
            else -> 0L
        }

    private fun hasActionableVisualSignals(
        visualContext: VisualPerceptionContext,
    ): Boolean =
        visualContext.captureAvailable &&
            (
                visualContext.groundedTexts.isNotEmpty() ||
                    visualContext.ocrTexts.isNotEmpty() ||
                    visualContext.visualHints.isNotEmpty()
            )

    private suspend fun collectVerificationFrames(
        initialObservation: IndexedScreenObservation?,
        initialScreenshot: ScreenshotPayload?,
        initialVisualContext: VisualPerceptionContext?,
        dependencies: SessionRuntimeTurnDependencies,
    ): List<GroundingVerificationFrame> {
        val frames = mutableListOf<GroundingVerificationFrame>()
        initialObservation?.let { observation ->
            frames +=
                GroundingVerificationFrame(
                    indexedObservation = observation,
                    screenshot = initialScreenshot,
                    visualContext = initialVisualContext,
                    frameTag = "frame_1",
                )
        }
        repeat(2) { index ->
            delay(140L + index * 80L)
            val indexedObservation = dependencies.observeCurrentScreen() ?: return@repeat
            val signature = indexedObservation.observation.signature
            if (frames.any { it.indexedObservation.observation.signature == signature }) {
                return@repeat
            }
            val screenshot = dependencies.captureScreenshotPayload()
            val visualContext = dependencies.buildVisualContext(screenshot, indexedObservation.observation)
            frames +=
                GroundingVerificationFrame(
                    indexedObservation = indexedObservation,
                    screenshot = screenshot,
                    visualContext = visualContext,
                    frameTag = "frame_${frames.size + 1}",
                )
        }
        return frames
    }

    private data class PreparedTurnDecision(
        val planningDecisionDirective: PlanningDecisionDirective,
        val plannerVisualContext: VisualPerceptionContext,
        val plannerScreenshot: ScreenshotPayload?,
        val executionBaselineObservation: ScreenObservation,
    )
}
