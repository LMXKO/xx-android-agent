package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ActionVerificationResult
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskStageVerificationResult
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.safety.SafetyReview

internal object SessionRuntimeExecutionFacadeSupport {
    fun recordPlanningScreenArtifacts(
        planningContext: AgentPlanningContext,
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
    ) =
        SessionRuntimeExecutionSupport.recordPlanningScreenArtifacts(
            planningContext = planningContext,
            screenshot = screenshot,
            visualContext = visualContext,
        )

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
    ) =
        SessionRuntimeExecutionSupport.recordVerificationArtifact(
            planningContext = planningContext,
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

    fun resolveObservedExecutionCompletion(
        planningContext: AgentPlanningContext,
        action: AgentAction,
        beforeObservation: ScreenObservation,
        latestObservation: IndexedScreenObservation,
        afterObservation: IndexedScreenObservation?,
        executionResult: AgentExecutionResult,
        verification: ActionVerificationResult,
        taskVerification: TaskStageVerificationResult,
    ): ExecutionCompletion =
        SessionRuntimeExecutionSupport.resolveObservedExecutionCompletion(
            planningContext = planningContext,
            action = action,
            beforeObservation = beforeObservation,
            latestObservation = latestObservation,
            afterObservation = afterObservation,
            executionResult = executionResult,
            verification = verification,
            taskVerification = taskVerification,
        )

    fun resolveFinishExecutionCompletion(
        planningContext: AgentPlanningContext,
        action: AgentAction.Finish,
        beforeObservation: ScreenObservation,
        latestObservation: IndexedScreenObservation,
        executionResult: AgentExecutionResult,
    ): ExecutionCompletion =
        SessionRuntimeExecutionSupport.resolveFinishExecutionCompletion(
            planningContext = planningContext,
            action = action,
            beforeObservation = beforeObservation,
            latestObservation = latestObservation,
            executionResult = executionResult,
        )

    fun resolveExecutionEntryDirective(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        executionBaselineObservation: ScreenObservation,
        executableObservation: IndexedScreenObservation,
        nextPlanDelayMs: Long,
    ): ExecutionEntryDirective =
        SessionRuntimeExecutionSupport.resolveExecutionEntryDirective(
            planningContext = planningContext,
            decision = decision,
            executionBaselineObservation = executionBaselineObservation,
            executableObservation = executableObservation,
            nextPlanDelayMs = nextPlanDelayMs,
        )

    fun resolveSafetyEntryDirective(
        review: SafetyReview,
    ): SafetyEntryDirective =
        SessionRuntimeExecutionSupport.resolveSafetyEntryDirective(review)

    fun describePlanningScreenTelemetry(
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
        baseObservation: ScreenObservation,
        augmentedObservation: ScreenObservation?,
        skippedByEarlySkill: Boolean,
    ): PlanningScreenTelemetry =
        SessionRuntimeExecutionSupport.describePlanningScreenTelemetry(
            screenshot = screenshot,
            visualContext = visualContext,
            baseObservation = baseObservation,
            augmentedObservation = augmentedObservation,
            skippedByEarlySkill = skippedByEarlySkill,
        )

    fun resolvePlanningFailureDirective(
        error: Throwable,
        plannerTimeoutMs: Long,
    ): PlanningFailureDirective =
        SessionRuntimeExecutionSupport.resolvePlanningFailureDirective(
            error = error,
            plannerTimeoutMs = plannerTimeoutMs,
        )

    fun beforeAction(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
    ): AgentHookDispatchResult =
        SessionRuntimeExecutionSupport.beforeAction(
            planningContext = planningContext,
            decision = decision,
        )

    fun completeTurn(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        activeSkills: List<SkillContext>,
        result: String,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
        resetObservationSignatureForNextPlan: Boolean,
        nextPlanDelayMs: Long,
        recoveryDiagnosis: RecoveryDiagnosis?,
        suggestedRecoveryAction: String,
    ): TurnCompletion =
        SessionRuntimeExecutionSupport.completeTurn(
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

    fun finalizeTurnForContinuation(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        activeSkills: List<SkillContext>,
        result: String,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
        resetObservationSignatureForNextPlan: Boolean,
        nextPlanDelayMs: Long,
        recoveryDiagnosis: RecoveryDiagnosis?,
        suggestedRecoveryAction: String,
        immediateReplanObservation: IndexedScreenObservation?,
        immediateReplanReason: ImmediateReplanReason?,
    ): TurnContinuation =
        SessionRuntimeExecutionSupport.finalizeTurnForContinuation(
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
            immediateReplanObservation = immediateReplanObservation,
            immediateReplanReason = immediateReplanReason,
        )

    fun tryImmediateReplan(continuation: TurnContinuation): PlanningAcquireResult =
        SessionRuntimeExecutionSupport.tryImmediateReplan(continuation)

    fun tryImmediateReplanDirective(continuation: TurnContinuation): PlanningDirective =
        SessionRuntimeExecutionSupport.tryImmediateReplanDirective(continuation)

    fun describeTurnContinuationDelay(continuation: TurnContinuation): String? =
        SessionRuntimeExecutionSupport.describeTurnContinuationDelay(continuation)

    fun describeImmediateReplan(
        continuation: TurnContinuation,
        observationSignature: String,
    ): String =
        SessionRuntimeExecutionSupport.describeImmediateReplan(
            continuation = continuation,
            observationSignature = observationSignature,
        )

    fun recordError(
        planningContext: AgentPlanningContext?,
        error: String,
        keepRunning: Boolean,
    ) =
        SessionRuntimeExecutionSupport.recordError(
            planningContext = planningContext,
            error = error,
            keepRunning = keepRunning,
        )
}
