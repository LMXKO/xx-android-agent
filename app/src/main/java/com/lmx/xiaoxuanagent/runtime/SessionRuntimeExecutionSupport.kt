package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ActionVerificationResult
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.MultiStepTaskEngine
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.TaskCompletionVerifier
import com.lmx.xiaoxuanagent.agent.TaskResultExtractor
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.TaskStageVerificationResult
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskStore
import com.lmx.xiaoxuanagent.assistantos.AssistantProactiveTaskType
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.safety.RiskLevel
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyGrantStore
import com.lmx.xiaoxuanagent.safety.SafetyReview
import kotlinx.coroutines.TimeoutCancellationException

internal object SessionRuntimeExecutionSupport {
    fun recordPlanningScreenArtifacts(
        planningContext: AgentPlanningContext,
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
    ) {
        val uiXmlArtifact =
            ArtifactStore.recordUiXmlSnapshot(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                observation = planningContext.observation,
            )
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(uiXmlArtifactId = uiXmlArtifact?.artifactId.orEmpty())
        }
        uiXmlArtifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }

        val screenshotArtifact =
            screenshot?.let {
                ArtifactStore.recordPlanningScreenshot(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    task = planningContext.task,
                    observation = planningContext.observation,
                    screenshot = it,
                    visualContext = visualContext,
                )
            }
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(screenshotArtifactId = screenshotArtifact?.artifactId.orEmpty())
        }
        screenshotArtifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        SessionRuntime.publishRuntimeState(planningContext.sessionId)
    }

    fun recordVerificationArtifact(
        planningContext: AgentPlanningContext,
        actionLabel: String,
        verified: Boolean,
        shouldImmediateReplan: Boolean,
        beforeSignature: String,
        afterSignature: String,
        actionMessage: String,
        taskMessage: String,
        diagnosisSummary: String,
        suggestedRecoveryAction: String,
        finalMessage: String,
    ) {
        val artifact =
            ArtifactStore.recordVerificationSummary(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                actionLabel = actionLabel,
                verified = verified,
                shouldImmediateReplan = shouldImmediateReplan,
                beforeSignature = beforeSignature,
                afterSignature = afterSignature,
                actionMessage = actionMessage,
                taskMessage = taskMessage,
                diagnosisSummary = diagnosisSummary,
                suggestedRecoveryAction = suggestedRecoveryAction,
                finalMessage = finalMessage,
            )
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(verificationArtifactId = artifact?.artifactId.orEmpty())
        }
        artifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        SessionRuntime.publishRuntimeState(planningContext.sessionId)
    }

    fun resolveObservedExecutionCompletion(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        latestObservation: IndexedScreenObservation,
        afterObservation: IndexedScreenObservation?,
        executionResult: AgentExecutionResult,
        verification: ActionVerificationResult,
        taskVerification: TaskStageVerificationResult,
    ): ExecutionCompletion {
        val semanticDetourDiagnosis =
            afterObservation?.observation?.let { after ->
                com.lmx.xiaoxuanagent.agent.RecoveryEngine.diagnoseSemanticDetour(
                    task = planningContext.task,
                    taskPlanState = planningContext.taskPlanState,
                    action = action,
                    beforeObservation = beforeObservation,
                    afterObservation = after,
                    executionResult = executionResult,
                    history = planningContext.history,
                )
            }
        val finalVerified = (verification.verified || taskVerification.verified) && semanticDetourDiagnosis == null
        val finalShouldImmediateReplan =
            !finalVerified && (verification.shouldImmediateReplan || taskVerification.shouldImmediateReplan)
        val finalMessage =
            listOf(executionResult.message, verification.message, taskVerification.message)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")

        val diagnosis =
            if (semanticDetourDiagnosis != null) {
                semanticDetourDiagnosis
            } else if (!finalVerified) {
                com.lmx.xiaoxuanagent.agent.RecoveryEngine.diagnoseExecutionFailure(
                    task = planningContext.task,
                    targetPackageName = planningContext.targetPackageName,
                    action = action,
                    beforeObservation = beforeObservation,
                    afterObservation = afterObservation?.observation,
                    executionResult = executionResult,
                    history = planningContext.history,
                )
            } else {
                null
            }
        val recoveryPlan =
            SessionRuntime.resolveRecoveryPlan(
                planningContext = planningContext,
                observation = afterObservation?.observation ?: latestObservation.observation,
                diagnosis = diagnosis,
            )
        val resolvedMessage =
            listOfNotNull(
                finalMessage.takeIf { it.isNotBlank() },
                diagnosis?.let { "恢复诊断=${it.category.name.lowercase()}：${it.summary}" },
                recoveryPlan.recoveryDecision?.let { "建议下一步=${it.action.label}" },
            ).joinToString(" ")

        return if (finalVerified) {
            val weakObservation =
                beforeObservation.elements.isEmpty() ||
                    (
                        beforeObservation.pageState.equals("UNKNOWN", ignoreCase = true) &&
                            beforeObservation.topTexts.isEmpty()
                    )
            val shouldContinuePlanning =
                afterObservation != null &&
                    (
                        action is AgentAction.LaunchApp ||
                            afterObservation.observation.signature == beforeObservation.signature ||
                            taskVerification.verified ||
                            weakObservation
                    )
            val shouldResetObservationSignature =
                afterObservation != null &&
                    afterObservation.observation.signature != beforeObservation.signature &&
                    !shouldContinuePlanning
            recordVerificationArtifact(
                planningContext = planningContext,
                actionLabel = action.label,
                verified = true,
                shouldImmediateReplan = shouldContinuePlanning,
                beforeSignature = beforeObservation.signature,
                afterSignature = afterObservation?.observation?.signature.orEmpty(),
                actionMessage = verification.message,
                taskMessage = taskVerification.message,
                diagnosisSummary = "",
                suggestedRecoveryAction = "",
                finalMessage = finalMessage,
            )
            ExecutionCompletion(
                finalMessage = finalMessage,
                keepRunning = true,
                shouldImmediateReplan = shouldContinuePlanning,
                shouldResetObservationSignature = shouldResetObservationSignature,
                replanObservation = afterObservation,
                recoveryDiagnosis = null,
                suggestedRecoveryAction = "",
                taskResult = null,
            )
        } else {
            val suggestedRecoveryAction = recoveryPlan.recoveryDecision?.action?.label.orEmpty()
            val shouldRecoverImmediately =
                afterObservation != null &&
                    (
                        finalShouldImmediateReplan ||
                            diagnosis != null ||
                            suggestedRecoveryAction.isNotBlank()
                    )
            recordVerificationArtifact(
                planningContext = planningContext,
                actionLabel = action.label,
                verified = false,
                shouldImmediateReplan = shouldRecoverImmediately,
                beforeSignature = beforeObservation.signature,
                afterSignature = afterObservation?.observation?.signature.orEmpty(),
                actionMessage = verification.message,
                taskMessage = taskVerification.message,
                diagnosisSummary = diagnosis?.summary.orEmpty(),
                suggestedRecoveryAction = suggestedRecoveryAction,
                finalMessage = resolvedMessage,
            )
            ExecutionCompletion(
                finalMessage = resolvedMessage,
                keepRunning = true,
                shouldImmediateReplan = shouldRecoverImmediately,
                shouldResetObservationSignature = shouldRecoverImmediately,
                replanObservation = afterObservation ?: latestObservation,
                recoveryDiagnosis = diagnosis,
                suggestedRecoveryAction = suggestedRecoveryAction,
                taskResult = null,
            )
        }
    }

    fun resolveFinishExecutionCompletion(
        planningContext: AgentPlanningContext,
        action: AgentAction.Finish,
        beforeObservation: ScreenObservation,
        latestObservation: IndexedScreenObservation,
        executionResult: AgentExecutionResult,
    ): ExecutionCompletion {
        val finishCheck =
            TaskCompletionVerifier.verifyFinish(
                task = planningContext.task,
                taskPlanState = planningContext.taskPlanState,
                observation = beforeObservation,
            )
        val diagnosis =
            if (!finishCheck.verified) {
                com.lmx.xiaoxuanagent.agent.RecoveryEngine.diagnoseExecutionFailure(
                    task = planningContext.task,
                    targetPackageName = planningContext.targetPackageName,
                    action = action,
                    beforeObservation = beforeObservation,
                    afterObservation = latestObservation.observation,
                    executionResult = executionResult,
                    history = planningContext.history,
                    finishRejected = true,
                )
            } else {
                null
            }
        val recoveryPlan =
            SessionRuntime.resolveRecoveryPlan(
                planningContext = planningContext,
                observation = latestObservation.observation,
                diagnosis = diagnosis,
            )
        val extractedResult =
            if (finishCheck.verified) {
                TaskResultExtractor.extract(
                    task = planningContext.task,
                    observation = beforeObservation,
                    taskPlanState = planningContext.taskPlanState,
                )
            } else {
                null
            }
        val suggestedRecoveryAction = recoveryPlan.recoveryDecision?.action?.label.orEmpty()
        val finishFinalMessage =
            if (finishCheck.verified) {
                buildString {
                    append("${executionResult.message} ${finishCheck.message}".trim())
                    extractedResult?.let {
                        append(" 结果摘要：").append(TaskResultExtractor.renderBriefSummary(it))
                    }
                }
            } else {
                buildString {
                    append("finish 被拒绝：").append(finishCheck.message)
                    diagnosis?.let { append(" 恢复诊断=").append(it.category.name.lowercase()).append("，").append(it.summary) }
                    recoveryPlan.recoveryDecision?.let { append(" 建议下一步=").append(it.action.label) }
                }
            }

        recordVerificationArtifact(
            planningContext = planningContext,
            actionLabel = action.label,
            verified = finishCheck.verified,
            shouldImmediateReplan = !finishCheck.verified,
            beforeSignature = beforeObservation.signature,
            afterSignature = latestObservation.observation.signature,
            actionMessage = executionResult.message,
            taskMessage = finishCheck.message,
            diagnosisSummary = diagnosis?.summary.orEmpty(),
            suggestedRecoveryAction = suggestedRecoveryAction,
            finalMessage = finishFinalMessage,
        )
        return ExecutionCompletion(
            finalMessage = finishFinalMessage,
            keepRunning = !finishCheck.verified,
            shouldImmediateReplan = !finishCheck.verified,
            shouldResetObservationSignature = finishCheck.verified,
            replanObservation = latestObservation,
            recoveryDiagnosis = diagnosis,
            suggestedRecoveryAction = suggestedRecoveryAction,
            taskResult = extractedResult,
        )
    }

    fun resolveExecutionEntryDirective(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        executionBaselineObservation: ScreenObservation,
        executableObservation: IndexedScreenObservation,
        nextPlanDelayMs: Long,
    ): ExecutionEntryDirective {
        val action = decision.action
        if (
            action is AgentAction.LaunchApp &&
            executableObservation.observation.packageName == action.packageName
        ) {
            return ExecutionEntryDirective.SkipAndReplan(
                logMessage = "跳过重复启动: 当前前台已是 ${action.packageName}，直接进入下一轮规划。",
                continuation =
                    finalizeTurnForContinuation(
                        planningContext = planningContext,
                        decision = decision,
                        result = "当前已在目标应用 ${action.packageName}，跳过重复启动。",
                        keepRunning = true,
                        resetObservationSignatureForNextPlan = true,
                        nextPlanDelayMs = nextPlanDelayMs,
                        immediateReplanObservation = executableObservation,
                        immediateReplanReason = ImmediateReplanReason.SKIP_REDUNDANT_LAUNCH,
                    ),
            )
        }
        if (
            requiresStableObservation(action) &&
            !isExecutionContextStillCompatible(
                action = action,
                beforeObservation = executionBaselineObservation,
                latestObservation = executableObservation.observation,
            )
        ) {
            return ExecutionEntryDirective.AbortAndWait(
                logMessage =
                    "执行前上下文已变化: old=${executionBaselineObservation.signature}/${executionBaselineObservation.pageState}, " +
                        "new=${executableObservation.observation.signature}/${executableObservation.observation.pageState}",
                errorMessage = "页面已变化，放弃旧动作并等待重新规划。",
            )
        }
        return ExecutionEntryDirective.Proceed(
            logMessage = "开始执行动作: ${action.label}, stableSig=${executableObservation.observation.signature}",
        )
    }

    fun resolveSafetyEntryDirective(
        review: SafetyReview,
    ): SafetyEntryDirective =
        when {
            review.approvalKey.isNotBlank() &&
                SessionRuntime.consumeGrantedRiskApproval(review.approvalKey) -> {
                SafetyEntryDirective.Proceed(
                    logMessage = "安全确认已放行: ${review.actionLabel}",
                )
            }

            review.level == RiskLevel.CONFIRM -> {
                SessionRuntime.requestSafetyConfirmation(
                    PendingSafetyConfirmation(
                        approvalKey = review.approvalKey,
                        title = review.title,
                        summary = review.summary,
                        actionLabel = review.actionLabel,
                        targetPackageName = review.targetPackageName,
                        actionFamily = review.actionFamily,
                        riskLevelLabel = review.riskLevelLabel,
                        grantScopeHint = review.grantScopeHint,
                    ),
                )
                SafetyEntryDirective.AwaitConfirmation()
            }

            review.level == RiskLevel.BLOCK ->
                SafetyEntryDirective.StopAgent(
                    reason = "安全策略已阻止动作：${review.summary}",
                )

            else -> SafetyEntryDirective.Proceed()
        }

    fun beforeAction(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
    ): AgentHookDispatchResult =
        SessionRuntime.dispatchHook(
            stage = AgentHookStage.BEFORE_ACTION,
            payload =
                AgentHookPayload(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    turn = planningContext.turn,
                    observationSignature = planningContext.observation.signature,
                    pageState = planningContext.observation.pageState,
                    actionLabel = decision.action.label,
                    metadata =
                        mapOf(
                            "profile_id" to planningContext.profileId,
                            "page_state" to planningContext.observation.pageState,
                        ),
                ),
        )

    fun completeTurn(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        activeSkills: List<SkillContext> = planningContext.activeSkills,
        result: String,
        taskResult: TaskResultPayload? = null,
        keepRunning: Boolean,
        resetObservationSignatureForNextPlan: Boolean = false,
        nextPlanDelayMs: Long = 0L,
        recoveryDiagnosis: RecoveryDiagnosis? = null,
        suggestedRecoveryAction: String = "",
    ): TurnCompletion {
        val executionArtifact =
            ArtifactStore.recordExecutionTrace(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                observation = planningContext.observation,
                decision = decision,
                result = result,
                taskResult = taskResult,
                keepRunning = keepRunning,
                recoveryDiagnosis = recoveryDiagnosis,
                suggestedRecoveryAction = suggestedRecoveryAction,
            )
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(executionTraceArtifactId = executionArtifact?.artifactId.orEmpty())
        }
        executionArtifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        val taskResultArtifact =
            taskResult?.let {
                ArtifactStore.recordTaskResultSummary(
                    sessionId = planningContext.sessionId,
                    turn = planningContext.turn,
                    task = planningContext.task,
                    taskResult = it,
                )
            }
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(taskResultSummaryArtifactId = taskResultArtifact?.artifactId.orEmpty())
        }
        taskResultArtifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        SessionRuntime.dispatchHook(
            stage = AgentHookStage.AFTER_ACTION,
            payload =
                AgentHookPayload(
                    sessionId = planningContext.sessionId,
                    task = planningContext.task,
                    turn = planningContext.turn,
                    observationSignature = planningContext.observation.signature,
                    pageState = planningContext.observation.pageState,
                    actionLabel = decision.action.label,
                    result = result,
                    metadata =
                        mapOf(
                            "keep_running" to keepRunning.toString(),
                            "recovery_category" to recoveryDiagnosis?.category?.name.orEmpty(),
                        ),
                ),
        )
        recoveryDiagnosis?.let { diagnosis ->
            SessionRuntime.dispatchHook(
                stage = AgentHookStage.ON_FAILURE,
                payload =
                    AgentHookPayload(
                        sessionId = planningContext.sessionId,
                        task = planningContext.task,
                        turn = planningContext.turn,
                        observationSignature = planningContext.observation.signature,
                        pageState = planningContext.observation.pageState,
                        actionLabel = decision.action.label,
                        result = diagnosis.summary,
                        metadata =
                            mapOf(
                                "recovery_category" to diagnosis.category.name,
                                "suggested_recovery_action" to suggestedRecoveryAction,
                                "keep_running" to keepRunning.toString(),
                            ),
                    ),
            )
        }
        val artifactRefs = SessionRuntimeStateSupport.consumeTurnArtifactRefs(planningContext.sessionId, planningContext.turn)
        SessionCompensationPlanner
            .derivePlan(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                beforeObservation = planningContext.observation,
                action = decision.action,
            )?.let(SessionCompensationStore::recordPlan)

        val turnRecord =
            AgentTurnRecord(
                observationSignature = planningContext.observation.signature,
                pageState = planningContext.observation.pageState,
                action = decision.action.label,
                result = result,
                decisionReason = decision.reason,
                recoveryCategory = recoveryDiagnosis?.category?.name.orEmpty(),
                recoverySummary = recoveryDiagnosis?.summary.orEmpty(),
                suggestedRecoveryAction = suggestedRecoveryAction,
            )
        val previousSession = SessionRuntimeStore.session()
        val appliedResumeContext = previousSession.resumeContext
        val nextFingerprint = "${planningContext.observation.signature}|${decision.action.label}"
        val nextFingerprints = (previousSession.recentFingerprints + nextFingerprint).takeLast(4)
        val loopDetected =
            keepRunning &&
                nextFingerprints.size == 4 &&
                nextFingerprints.distinct().size == 1

        val finalKeepRunning = keepRunning && !loopDetected
        val finalResult =
            if (loopDetected) {
                "$result 已触发死循环保护，停止自动任务。"
            } else {
                result
            }
        val finalDisposition =
            if (finalKeepRunning) {
                SessionTurnDisposition.ContinueExecution()
            } else {
                SessionTurnDisposition.Terminate(deriveFinishTerminalReason(decision, loopDetected))
            }
        val finalStatus = finalDisposition.toStatusModel().code
        SessionRuntimeStore.dispatch(
            SessionCommand.FinishTurn(
                turnRecord = turnRecord,
                disposition = finalDisposition,
                nextPlanEligibleAtMs = System.currentTimeMillis() + nextPlanDelayMs.coerceAtLeast(0L),
                clearLastObservationSignature = resetObservationSignatureForNextPlan,
                clearResumeContext = true,
                keepRunning = finalKeepRunning,
                clearSafety = !finalKeepRunning,
                resultSnapshot =
                    SessionRuntime.buildRuntimeResultSnapshot(
                        lastAction = decision.action.label,
                        finalResult = finalResult,
                        taskResult = taskResult,
                        lastError = "",
                        hint = finalResult,
                    ),
                takeoverSnapshot = RuntimeTakeoverSnapshot(),
                reason = "finish_turn",
            ),
        )
        val session = SessionRuntimeStore.session()

        if (session.sessionId.isNotBlank()) {
            val workingMemorySnapshot =
                SessionWorkingMemoryStore.recordTurn(
                sessionId = session.sessionId,
                task = session.task,
                targetPackageName = session.targetPackageName,
                turn = session.turns,
                planStage = planningContext.taskPlanState.currentStage,
                nextObjective = planningContext.taskPlanState.nextObjective,
                turnRecord = turnRecord.copy(result = finalResult),
                taskResult = taskResult,
                keepRunning = finalKeepRunning,
            )
            val memoryPolicyDecision =
                SessionMemoryPolicyStore.recordTurn(
                    sessionId = session.sessionId,
                    turn = session.turns,
                    toolName = decision.action.toolName,
                    summary = finalResult,
                    forceNotebookUpdate = !finalKeepRunning || loopDetected || recoveryDiagnosis != null,
                )
            if (memoryPolicyDecision.shouldEnqueueNotebookUpdate) {
                val worker =
                    SessionMemoryCuratorWorkerBridge.ensureQueued(
                        sessionId = session.sessionId,
                        task = session.task,
                        profileId = session.profileId,
                        summary = "session notebook curator 已因 turn 收口排队。",
                        policyReason = memoryPolicyDecision.snapshot.lastReason,
                    )
                SessionMemoryCuratorStore.markQueued(
                    sessionId = session.sessionId,
                    workerId = worker?.workerId.orEmpty(),
                    policyReason = memoryPolicyDecision.snapshot.lastReason,
                    summary = "session notebook curator 已因 turn 收口排队。",
                )
                SessionMemoryForkRuntimeStore.refresh(session.sessionId)
                BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
                    sessionId = session.sessionId,
                    task = session.task,
                    profileId = session.profileId,
                )
            }
            val compactSnapshot =
                SessionConversationCompactStore.recordTurn(
                sessionId = session.sessionId,
                task = session.task,
                turn = session.turns,
                turnRecord = turnRecord.copy(result = finalResult),
                keepRunning = finalKeepRunning,
            )
            SessionMemoryNotebookStore.recordTurnCheckpoint(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                turn = session.turns,
                workingMemory = workingMemorySnapshot,
                compactSnapshot = compactSnapshot,
                memoryPolicy = memoryPolicyDecision.snapshot,
                latestResult = finalResult,
                keepRunning = finalKeepRunning,
            )
            SessionMemoryFileMaintainer.refreshIfNeeded(
                sessionId = session.sessionId,
                task = session.task,
                profileId = session.profileId,
                turn = session.turns,
                trigger = "turn_finalize:${decision.action.toolName}",
                history = session.history,
            )
            ReplayStore.appendTurn(
                sessionId = session.sessionId,
                turn = session.turns,
                observation = planningContext.observation,
                decision = decision,
                taskPlanState =
                    MultiStepTaskEngine.buildPlanState(
                        task = session.task,
                        profileId = session.profileId,
                        observation = planningContext.observation,
                        history = session.history,
                        resumeContext = appliedResumeContext,
                    ),
                activeSkills = activeSkills,
                result = finalResult,
                taskResult = taskResult,
                keepRunning = finalKeepRunning,
                recoveryDiagnosis = recoveryDiagnosis,
                suggestedRecoveryAction = suggestedRecoveryAction,
                artifactRefs = artifactRefs,
            )
            if (!finalKeepRunning) {
                AssistantProactiveTaskStore.markCompletedMatching(
                    type = AssistantProactiveTaskType.APPROVAL_FOLLOW_UP,
                    sessionId = session.sessionId,
                )
                SessionConversationCompactStore.markFinished(
                    sessionId = session.sessionId,
                    finalStatus = finalStatus,
                    finalResult = finalResult,
                )
                val finalWorkingMemorySnapshot =
                    SessionWorkingMemoryStore.markFinished(
                    sessionId = session.sessionId,
                    finalStatus = finalStatus,
                    finalResult = finalResult,
                )
                val finalCompactSnapshot = SessionConversationCompactStore.readSnapshot(session.sessionId)
                SessionMemoryNotebookStore.recordTurnCheckpoint(
                    sessionId = session.sessionId,
                    task = session.task,
                    profileId = session.profileId,
                    turn = session.turns,
                    workingMemory = finalWorkingMemorySnapshot ?: workingMemorySnapshot,
                    compactSnapshot = finalCompactSnapshot ?: compactSnapshot,
                    memoryPolicy = SessionMemoryPolicyStore.readSnapshot(session.sessionId) ?: memoryPolicyDecision.snapshot,
                    latestResult = finalResult,
                    keepRunning = false,
                )
                ReplayStore.finishSession(session.sessionId, finalStatus, finalResult, taskResult)
                SessionRuntime.persistMemoryOutcome(session.sessionId, session.task, session.profileId, finalStatus, finalResult, taskResult)
                SessionSessionGraphStore.syncPlatformSnapshot(SessionPlatformFacade.readCurrentSnapshot())
                PlatformCapabilityApprovalStore.expireSessionGrantsForSession(
                    sessionId = session.sessionId,
                    note = "session_terminal:${finalStatus.lowercase()}",
                )
                RuntimeSafetyGrantStore.expireSessionGrantsForSession(
                    sessionId = session.sessionId,
                    note = "session_terminal:${finalStatus.lowercase()}",
                )
                SessionRuntime.dispatchSessionLifecycleHook(
                    session = session,
                    stage = AgentHookStage.STOP_CLEANUP,
                    result = finalResult,
                    actionLabel = decision.action.label,
                    metadata =
                        mapOf(
                            "profile_id" to session.profileId,
                            "final_status" to finalStatus,
                            "tool_name" to decision.action.toolName,
                        ),
                )
            } else if (taskResult != null) {
                BackgroundMemoryExtractor.enqueueTaskProgress(
                    sessionId = session.sessionId,
                    turn = session.turns,
                    task = session.task,
                    profileId = session.profileId,
                    taskResult = taskResult,
                )
            }
        }

        SessionRuntime.publishRuntimeState(planningContext.sessionId)
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.TurnCompleted(
                sessionId = planningContext.sessionId,
                turn = session.turns,
                actionLabel = decision.action.label,
                finalStatus = finalStatus,
                finalResult = finalResult,
                taskResult = taskResult,
                keepRunning = finalKeepRunning,
                loopDetected = loopDetected,
            ),
        )

        return TurnCompletion(
            keepRunning = finalKeepRunning,
            loopDetected = loopDetected,
            turn = session.turns,
            finalResult = finalResult,
        )
    }

    fun finalizeTurnForContinuation(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        activeSkills: List<SkillContext> = planningContext.activeSkills,
        result: String,
        taskResult: TaskResultPayload? = null,
        keepRunning: Boolean,
        resetObservationSignatureForNextPlan: Boolean = false,
        nextPlanDelayMs: Long = 0L,
        recoveryDiagnosis: RecoveryDiagnosis? = null,
        suggestedRecoveryAction: String = "",
        immediateReplanObservation: IndexedScreenObservation? = null,
        immediateReplanReason: ImmediateReplanReason? = null,
    ): TurnContinuation {
        val turnCompletion =
            completeTurn(
                planningContext = planningContext,
                decision = decision,
                activeSkills = activeSkills,
                result = result,
                taskResult = taskResult,
                keepRunning = keepRunning,
                resetObservationSignatureForNextPlan = resetObservationSignatureForNextPlan,
                nextPlanDelayMs = nextPlanDelayMs,
                recoveryDiagnosis = recoveryDiagnosis,
                suggestedRecoveryAction = suggestedRecoveryAction,
            )
        val replanObservation =
            immediateReplanObservation?.takeIf {
                turnCompletion.keepRunning && !turnCompletion.loopDetected
            }
        return TurnContinuation(
            turnCompletion = turnCompletion,
            settleDelayMs = nextPlanDelayMs.coerceAtLeast(0L),
            replanObservation = replanObservation,
            immediateReplanReason =
                immediateReplanReason?.takeIf { replanObservation != null },
        )
    }

    fun tryImmediateReplan(
        continuation: TurnContinuation,
    ): PlanningAcquireResult {
        val replanObservation = continuation.replanObservation ?: return PlanningAcquireResult.Idle
        if (!continuation.shouldAttemptImmediateReplan) {
            return PlanningAcquireResult.Idle
        }
        return SessionRuntime.acquirePlanningContext(replanObservation)
    }

    fun tryImmediateReplanDirective(
        continuation: TurnContinuation,
    ): PlanningDirective =
        SessionRuntime.toPlanningDirective(
            result = tryImmediateReplan(continuation),
            logMessageBuilder = { context ->
                describeImmediateReplan(
                    continuation = continuation,
                    observationSignature = context.observation.signature,
                )
            },
        )

    fun describeTurnContinuationDelay(
        continuation: TurnContinuation,
    ): String? {
        if (!continuation.shouldAttemptImmediateReplan || continuation.settleDelayMs <= 0L) {
            return null
        }
        return "动作后稳定等待 ${continuation.settleDelayMs}ms，再进入下一轮规划。"
    }

    fun describeImmediateReplan(
        continuation: TurnContinuation,
        observationSignature: String,
    ): String =
        when (continuation.immediateReplanReason) {
            ImmediateReplanReason.SKIP_REDUNDANT_LAUNCH ->
                "跳过重复启动后，基于 observation=$observationSignature 立即进入下一轮规划。"

            ImmediateReplanReason.POST_ACTION_CONTINUATION ->
                "动作完成后，基于 observation=$observationSignature 立即进入下一轮规划。"

            null -> "基于 observation=$observationSignature 立即进入下一轮规划。"
        }

    fun resolvePlanningFailureDirective(
        error: Throwable,
        plannerTimeoutMs: Long,
    ): PlanningFailureDirective =
        if (error is TimeoutCancellationException) {
            PlanningFailureDirective(
                logMessage = "规划阶段超时: ${plannerTimeoutMs}ms 内未拿到 planner 决策。",
                errorMessage = "Planner 规划超时，等待下一次 observation 后重试。",
            )
        } else {
            val detail = error.message ?: error.javaClass.simpleName
            PlanningFailureDirective(
                logMessage = "规划阶段异常: ${error.javaClass.simpleName}: $detail",
                errorMessage = "Agent 规划失败：$detail",
            )
        }

    fun describePlanningScreenTelemetry(
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
        baseObservation: ScreenObservation,
        augmentedObservation: ScreenObservation? = null,
        skippedByEarlySkill: Boolean = false,
    ): PlanningScreenTelemetry {
        val screenshotStatus =
            when {
                skippedByEarlySkill -> "skipped(skill)"
                screenshot == null -> "fail"
                else -> "ok(${screenshot.width}x${screenshot.height})"
            }
        val logMessages =
            buildList {
                if (visualContext.summary.isNotBlank()) {
                    add("视觉感知: ${visualContext.summary}")
                }
                val augmentedCount = augmentedObservation?.elements?.size ?: baseObservation.elements.size
                if (augmentedCount > baseObservation.elements.size) {
                    add("视觉补位: ${baseObservation.elements.size} -> $augmentedCount 个候选目标。")
                }
            }
        return PlanningScreenTelemetry(
            screenshotStatus = screenshotStatus,
            logMessages = logMessages,
        )
    }

    fun recordError(
        planningContext: AgentPlanningContext?,
        error: String,
        keepRunning: Boolean,
    ) {
        val previousSession = SessionRuntimeStore.session()
        val sessionId = previousSession.sessionId

        planningContext?.let { context ->
            val artifact =
                ArtifactStore.recordTurnFailure(
                    sessionId = context.sessionId,
                    turn = context.turn,
                    task = context.task,
                    observation = context.observation,
                    error = error,
                    keepRunning = keepRunning,
                )
            SessionRuntimeStateSupport.updateTurnArtifacts(context.sessionId, context.turn) { current ->
                current.copy(failureArtifactId = artifact?.artifactId.orEmpty())
            }
            artifact?.let {
                SessionRuntimeBridgeSupport.publishArtifactRecorded(context.sessionId, context.turn, it)
            }
            SessionRuntime.dispatchHook(
                stage = AgentHookStage.ON_ERROR,
                payload =
                    AgentHookPayload(
                        sessionId = context.sessionId,
                        task = context.task,
                        turn = context.turn,
                        observationSignature = context.observation.signature,
                        pageState = context.observation.pageState,
                        result = error,
                        metadata = mapOf("keep_running" to keepRunning.toString()),
                    ),
            )
            SessionRuntimeStateSupport.clearTurnArtifacts(context.sessionId, context.turn)
        }

        SessionRuntimeStore.dispatch(
            if (keepRunning) {
                SessionCommand.RecordRecoverableError(
                    clearSafety = false,
                    resultSnapshot =
                        RuntimeResultSnapshot(
                            lastError = error,
                            hint = error,
                        ),
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    reason = "record_recoverable_error_state",
                )
            } else {
                SessionCommand.TerminateSession(
                    terminalReason = AgentUiTerminalReason.FAILED,
                    clearSafety = true,
                    resultSnapshot =
                        RuntimeResultSnapshot(
                            lastError = error,
                            hint = error,
                        ),
                    takeoverSnapshot = RuntimeTakeoverSnapshot(),
                    reason = "record_terminal_error_state",
                )
            }
        )
        if (sessionId.isNotBlank()) {
            ReplayStore.appendEvent(sessionId, "error", error)
            if (!keepRunning) {
                ReplayStore.finishSession(sessionId, AgentUiStatus.FAILED, error)
                SessionRuntime.persistMemoryOutcome(sessionId, previousSession.task, previousSession.profileId, AgentUiStatus.FAILED, error)
                SessionSessionGraphStore.syncPlatformSnapshot(SessionPlatformFacade.readCurrentSnapshot())
                PlatformCapabilityApprovalStore.expireSessionGrantsForSession(
                    sessionId = sessionId,
                    note = "session_terminal:failed",
                )
            }
        }
        SessionRuntime.publishRuntimeState()
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.ErrorRecorded(
                sessionId = sessionId,
                error = error,
                keepRunning = keepRunning,
            ),
        )
    }

    private fun deriveFinishTerminalReason(
        decision: AgentDecision,
        loopDetected: Boolean,
    ): AgentUiTerminalReason =
        when {
            decision.action is AgentAction.Finish -> AgentUiTerminalReason.COMPLETED
            loopDetected -> AgentUiTerminalReason.FAILED
            else -> AgentUiTerminalReason.STOPPED
        }

    private fun requiresStableObservation(action: AgentAction): Boolean =
        when (action) {
            is AgentAction.Click,
            is AgentAction.SetText,
            is AgentAction.Scroll,
            is AgentAction.LongClick,
            is AgentAction.PasteClipboard,
            AgentAction.PressEnter,
            AgentAction.FocusPrimaryInput,
            is AgentAction.PopulatePrimaryInput,
            is AgentAction.SubmitPrimaryAction,
            is AgentAction.DismissInterrupt,
            is AgentAction.OpenBestCandidate,
            is AgentAction.ScrollForMore,
            -> true

            is AgentAction.LaunchApp,
            is AgentAction.CopyText,
            is AgentAction.ReadSessionHistory,
            is AgentAction.SearchArtifacts,
            is AgentAction.RecallMemory,
            AgentAction.ReadSessionNotebook,
            is AgentAction.WriteSessionNote,
            is AgentAction.SearchTools,
            is AgentAction.WebSearch,
            is AgentAction.WebFetch,
            is AgentAction.ReadConnectedAppCapabilities,
            is AgentAction.ExecuteConnectedAppAction,
            AgentAction.ReadTodoBoard,
            is AgentAction.WriteTodoBoard,
            is AgentAction.DelegateLocalAgent,
            AgentAction.ReadWorkerRoles,
            is AgentAction.ReadWorkerMailbox,
            is AgentAction.ReplyWorkerMessage,
            is AgentAction.AcknowledgeWorkerMessage,
            AgentAction.ReadSessionMemoryFile,
            is AgentAction.ReadWorkerStatus,
            is AgentAction.MergeWorkerResult,
            is AgentAction.ReturnToTargetApp,
            is AgentAction.NavigateBack,
            AgentAction.Back,
            AgentAction.Home,
            AgentAction.Notifications,
            AgentAction.QuickSettings,
            AgentAction.Recents,
            is AgentAction.OpenSettings,
            is AgentAction.ShareText,
            is AgentAction.CreateAlarm,
            is AgentAction.CreateTimer,
            AgentAction.OpenStopwatch,
            is AgentAction.InsertCalendarEvent,
            is AgentAction.DialNumber,
            is AgentAction.DraftSms,
            is AgentAction.LookupContact,
            is AgentAction.ReadNotifications,
            is AgentAction.ReplyNotification,
            is AgentAction.MediaControl,
            is AgentAction.AdjustVolume,
            is AgentAction.ReadDeviceStatus,
            is AgentAction.SetBrightness,
            is AgentAction.SetDoNotDisturb,
            is AgentAction.SetBatterySaver,
            AgentAction.OpenPowerDialog,
            is AgentAction.OpenDevicePanel,
            is AgentAction.CaptureScreenshot,
            is AgentAction.CapturePhoto,
            is AgentAction.TapPoint,
            is AgentAction.Swipe,
            AgentAction.Wait,
            is AgentAction.Finish,
            is AgentAction.Fail,
            -> false
        }

    private fun isExecutionContextStillCompatible(
        action: AgentAction,
        beforeObservation: ScreenObservation,
        latestObservation: ScreenObservation,
    ): Boolean {
        if (beforeObservation.packageName != latestObservation.packageName) {
            return false
        }
        if (beforeObservation.pageState != latestObservation.pageState) {
            return false
        }

        return when (action) {
            is AgentAction.Click -> areElementsCompatible(action.elementId, beforeObservation, latestObservation)
            is AgentAction.SetText -> areElementsCompatible(action.elementId, beforeObservation, latestObservation)
            is AgentAction.LongClick -> areElementsCompatible(action.elementId, beforeObservation, latestObservation)
            is AgentAction.PasteClipboard -> areElementsCompatible(action.elementId, beforeObservation, latestObservation)
            is AgentAction.PopulatePrimaryInput ->
                beforeObservation.primaryEditableId == latestObservation.primaryEditableId &&
                    !latestObservation.primaryEditableId.isNullOrBlank()
            AgentAction.FocusPrimaryInput ->
                beforeObservation.primaryEditableId == latestObservation.primaryEditableId &&
                    !latestObservation.primaryEditableId.isNullOrBlank()
            is AgentAction.SubmitPrimaryAction,
            is AgentAction.DismissInterrupt,
            is AgentAction.OpenBestCandidate,
            -> true
            is AgentAction.Scroll -> {
                if (action.elementId.isNullOrBlank()) true else areElementsCompatible(action.elementId, beforeObservation, latestObservation)
            }
            is AgentAction.ScrollForMore -> true

            AgentAction.PressEnter -> true

            is AgentAction.LaunchApp,
            is AgentAction.CopyText,
            is AgentAction.ReadSessionHistory,
            is AgentAction.SearchArtifacts,
            is AgentAction.RecallMemory,
            AgentAction.ReadSessionNotebook,
            is AgentAction.WriteSessionNote,
            is AgentAction.SearchTools,
            is AgentAction.WebSearch,
            is AgentAction.WebFetch,
            is AgentAction.ReadConnectedAppCapabilities,
            is AgentAction.ExecuteConnectedAppAction,
            AgentAction.ReadTodoBoard,
            is AgentAction.WriteTodoBoard,
            is AgentAction.DelegateLocalAgent,
            AgentAction.ReadWorkerRoles,
            is AgentAction.ReadWorkerMailbox,
            is AgentAction.ReplyWorkerMessage,
            is AgentAction.AcknowledgeWorkerMessage,
            AgentAction.ReadSessionMemoryFile,
            is AgentAction.ReadWorkerStatus,
            is AgentAction.MergeWorkerResult,
            is AgentAction.ReturnToTargetApp,
            is AgentAction.NavigateBack,
            AgentAction.Back,
            AgentAction.Home,
            AgentAction.Notifications,
            AgentAction.QuickSettings,
            AgentAction.Recents,
            is AgentAction.OpenSettings,
            is AgentAction.ShareText,
            is AgentAction.CreateAlarm,
            is AgentAction.CreateTimer,
            AgentAction.OpenStopwatch,
            is AgentAction.InsertCalendarEvent,
            is AgentAction.DialNumber,
            is AgentAction.DraftSms,
            is AgentAction.LookupContact,
            is AgentAction.ReadNotifications,
            is AgentAction.ReplyNotification,
            is AgentAction.MediaControl,
            is AgentAction.AdjustVolume,
            is AgentAction.ReadDeviceStatus,
            is AgentAction.SetBrightness,
            is AgentAction.SetDoNotDisturb,
            is AgentAction.SetBatterySaver,
            AgentAction.OpenPowerDialog,
            is AgentAction.OpenDevicePanel,
            is AgentAction.CaptureScreenshot,
            is AgentAction.CapturePhoto,
            is AgentAction.TapPoint,
            is AgentAction.Swipe,
            AgentAction.Wait,
            is AgentAction.Finish,
            is AgentAction.Fail,
            -> true
        }
    }

    private fun areElementsCompatible(
        elementId: String,
        beforeObservation: ScreenObservation,
        latestObservation: ScreenObservation,
    ): Boolean {
        val before = beforeObservation.elements.firstOrNull { it.id == elementId } ?: return false
        val latest = latestObservation.elements.firstOrNull { it.id == elementId } ?: return false

        if (before.viewId.isNotBlank() && latest.viewId.isNotBlank() && before.viewId == latest.viewId) {
            return true
        }

        val sameClass = before.className == latest.className
        val sameText = before.text.isNotBlank() && before.text == latest.text
        val sameBounds = before.bounds == latest.bounds
        val sameRole =
            before.clickable == latest.clickable &&
                before.editable == latest.editable &&
                before.scrollable == latest.scrollable

        return sameRole && sameClass && (sameText || sameBounds)
    }
}
