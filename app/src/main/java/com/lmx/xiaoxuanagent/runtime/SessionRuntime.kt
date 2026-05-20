package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.ActionVerificationResult
import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentExecutionResult
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.RecoveryPlanHint
import com.lmx.xiaoxuanagent.agent.RecoveryRefinementResult
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.ScreenshotPayload
import com.lmx.xiaoxuanagent.agent.SkillContext
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.agent.TaskStageVerificationResult
import com.lmx.xiaoxuanagent.agent.VisualPerceptionContext
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation
import com.lmx.xiaoxuanagent.safety.SafetyReview
import com.lmx.xiaoxuanagent.taskprofile.TaskProfile
import com.lmx.xiaoxuanagent.taskprofile.TaskRouteResolution

object SessionRuntime {
    object Planning {
        fun currentTaskProfile(): TaskProfile = SessionRuntimePlanningFacadeSupport.currentTaskProfile()

        fun currentTask(): String = SessionRuntimePlanningFacadeSupport.currentTask()

        fun bootstrapFromLatestResumeSnapshot(): Boolean =
            SessionRuntimePlanningFacadeSupport.bootstrapFromLatestResumeSnapshot()

        fun bootstrapFromResumeSnapshot(sessionId: String): Boolean =
            SessionRuntimePlanningFacadeSupport.bootstrapFromResumeSnapshot(sessionId)

        fun acquirePlanningDirective(indexedObservation: IndexedScreenObservation): PlanningDirective =
            SessionRuntimePlanningFacadeSupport.acquirePlanningDirective(indexedObservation)
    }

    object Execution {
        fun resolveExecutionEntryDirective(
            planningContext: AgentPlanningContext,
            decision: AgentDecision,
            executionBaselineObservation: ScreenObservation,
            executableObservation: IndexedScreenObservation,
            nextPlanDelayMs: Long,
        ): ExecutionEntryDirective =
            SessionRuntimeExecutionFacadeSupport.resolveExecutionEntryDirective(
                planningContext = planningContext,
                decision = decision,
                executionBaselineObservation = executionBaselineObservation,
                executableObservation = executableObservation,
                nextPlanDelayMs = nextPlanDelayMs,
            )

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
        ): TurnContinuation =
            SessionRuntimeExecutionFacadeSupport.finalizeTurnForContinuation(
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

        fun tryImmediateReplanDirective(continuation: TurnContinuation): PlanningDirective =
            SessionRuntimeExecutionFacadeSupport.tryImmediateReplanDirective(continuation)

        fun recordError(
            planningContext: AgentPlanningContext?,
            error: String,
            keepRunning: Boolean,
        ) =
            SessionRuntimeExecutionFacadeSupport.recordError(
                planningContext = planningContext,
                error = error,
                keepRunning = keepRunning,
            )
    }

    object Lifecycle {
        fun pauseAgent() = SessionRuntimeLifecycleFacadeSupport.pauseAgent()

        fun resumeAgent(userCorrection: String = "") =
            SessionRuntimeLifecycleFacadeSupport.resumeAgent(userCorrection)

        fun stopAgent(reason: String) =
            SessionRuntimeLifecycleFacadeSupport.stopAgent(reason)

        fun approvePendingSafetyConfirmation(userCorrection: String = ""): PendingSafetyConfirmation? =
            SessionRuntimeLifecycleFacadeSupport.approvePendingSafetyConfirmation(userCorrection)

        fun requestSafetyConfirmation(confirmation: PendingSafetyConfirmation) =
            SessionRuntimeLifecycleFacadeSupport.requestSafetyConfirmation(confirmation)
    }

    object State {
        fun runtimeState(): SessionRuntimeState = SessionRuntimeStateFacadeSupport.runtimeState()
    }

    fun currentTaskProfile(): TaskProfile =
        Planning.currentTaskProfile()

    fun currentTask(): String =
        Planning.currentTask()

    fun resolveTargetAppReturnDirective(trigger: TargetAppReturnTrigger): TargetAppReturnDirective? =
        SessionRuntimePlanningFacadeSupport.resolveTargetAppReturnDirective(trigger)

    fun resolveTargetAppLaunchDirective(): TargetAppLaunchDirective? =
        SessionRuntimePlanningFacadeSupport.resolveTargetAppLaunchDirective()

    fun resolveBootstrapAutoLaunchDirective(): TargetAppLaunchDirective? =
        SessionRuntimePlanningFacadeSupport.resolveBootstrapAutoLaunchDirective()

    fun shouldAutoContinueBootstrappedSession(): Boolean =
        SessionRuntimePlanningFacadeSupport.shouldAutoContinueBootstrappedSession()

    fun bootstrapFromLatestResumeSnapshot(): Boolean =
        Planning.bootstrapFromLatestResumeSnapshot()

    fun bootstrapFromResumeSnapshot(sessionId: String): Boolean =
        Planning.bootstrapFromResumeSnapshot(sessionId)

    fun peekPlannerArtifactHints(sessionId: String, turn: Int): List<PlannerArtifactHint> =
        SessionRuntimePlanningFacadeSupport.peekPlannerArtifactHints(sessionId, turn)

    fun recordPlanningScreenArtifacts(
        planningContext: AgentPlanningContext,
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
    ) =
        SessionRuntimeExecutionFacadeSupport.recordPlanningScreenArtifacts(
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
        SessionRuntimeExecutionFacadeSupport.recordVerificationArtifact(
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
        SessionRuntimeExecutionFacadeSupport.resolveObservedExecutionCompletion(
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
        SessionRuntimeExecutionFacadeSupport.resolveFinishExecutionCompletion(
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
        Execution.resolveExecutionEntryDirective(
            planningContext = planningContext,
            decision = decision,
            executionBaselineObservation = executionBaselineObservation,
            executableObservation = executableObservation,
            nextPlanDelayMs = nextPlanDelayMs,
        )

    fun resolveSafetyEntryDirective(review: SafetyReview): SafetyEntryDirective =
        SessionRuntimeExecutionFacadeSupport.resolveSafetyEntryDirective(review)

    fun installBridge(sessionBridge: SessionBridge) {
        SessionRuntimeLifecycleFacadeSupport.installBridge(sessionBridge)
    }

    fun runtimeState(): SessionRuntimeState =
        State.runtimeState()

    fun handleExternalWaitObservation(indexedObservation: IndexedScreenObservation): Boolean =
        SessionRuntimePlanningFacadeSupport.handleExternalWaitObservation(indexedObservation)

    fun enterExternalWait(
        observation: ScreenObservation,
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String> = emptyList(),
        enteredAtMs: Long = System.currentTimeMillis(),
        reason: String = "runtime_enter_external_wait",
    ) =
        SessionRuntimePlanningFacadeSupport.enterExternalWait(
            observation = observation,
            taskPlanState = taskPlanState,
            activeSkillTitles = activeSkillTitles,
            enteredAtMs = enteredAtMs,
            reason = reason,
        )

    fun acquirePlanningContext(indexedObservation: IndexedScreenObservation): PlanningAcquireResult =
        SessionRuntimePlanningFacadeSupport.acquirePlanningContext(indexedObservation)

    fun acquirePlanningDirective(indexedObservation: IndexedScreenObservation): PlanningDirective =
        Planning.acquirePlanningDirective(indexedObservation)

    fun resolveRuntimeResumeIntervention(
        planningContext: AgentPlanningContext,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimePlanningFacadeSupport.resolveRuntimeResumeIntervention(planningContext)

    fun resolveRuntimeResumeRecoveryIntervention(
        planningContext: AgentPlanningContext,
        diagnosis: RecoveryDiagnosis,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimePlanningFacadeSupport.resolveRuntimeResumeRecoveryIntervention(
            planningContext = planningContext,
            diagnosis = diagnosis,
            observation = observation,
        )

    internal fun resolveRecoveryPlan(
        planningContext: AgentPlanningContext,
        observation: ScreenObservation,
        diagnosis: RecoveryDiagnosis?,
    ): RecoveryPlanHint =
        SessionRuntimePlanningFacadeSupport.resolveRecoveryPlan(
            planningContext = planningContext,
            observation = observation,
            diagnosis = diagnosis,
        )

    fun handleMaxTurnsReached() =
        SessionRuntimeLifecycleFacadeSupport.handleMaxTurnsReached()

    suspend fun startAgent(
        sessionId: String,
        task: String,
        entrySource: String,
    ): Boolean =
        SessionRuntimeLifecycleFacadeSupport.startAgent(
            sessionId = sessionId,
            task = task,
            entrySource = entrySource,
        )

    fun recordDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot? = null,
    ) =
        SessionRuntimeCoordinatorFacadeSupport.recordDecision(
            planningContext = planningContext,
            decision = decision,
            resumeDecisionSnapshot = resumeDecisionSnapshot,
        )

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        candidate: PlanningDecisionCandidate,
    ): PlanningDecisionDirective =
        SessionRuntimeCoordinatorFacadeSupport.publishPlanningDecision(
            planningContext = planningContext,
            candidate = candidate,
        )

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        source: PlanningDecisionSource,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot? = null,
        refinement: RecoveryRefinementResult? = null,
        plannedDecisionLabel: String = "",
    ): PlanningDecisionDirective =
        SessionRuntimeCoordinatorFacadeSupport.publishPlanningDecision(
            planningContext = planningContext,
            decision = decision,
            source = source,
            resumeDecisionSnapshot = resumeDecisionSnapshot,
            refinement = refinement,
            plannedDecisionLabel = plannedDecisionLabel,
        )

    fun describePlanningScreenTelemetry(
        screenshot: ScreenshotPayload?,
        visualContext: VisualPerceptionContext,
        baseObservation: ScreenObservation,
        augmentedObservation: ScreenObservation? = null,
        skippedByEarlySkill: Boolean = false,
    ): PlanningScreenTelemetry =
        SessionRuntimeExecutionFacadeSupport.describePlanningScreenTelemetry(
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
        SessionRuntimeExecutionFacadeSupport.resolvePlanningFailureDirective(
            error = error,
            plannerTimeoutMs = plannerTimeoutMs,
        )

    fun beforeAction(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
    ): AgentHookDispatchResult =
        SessionRuntimeExecutionFacadeSupport.beforeAction(
            planningContext = planningContext,
            decision = decision,
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
    ): TurnCompletion =
        SessionRuntimeExecutionFacadeSupport.completeTurn(
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
    ): TurnContinuation =
        Execution.finalizeTurnForContinuation(
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
        SessionRuntimeExecutionFacadeSupport.tryImmediateReplan(continuation)

    fun tryImmediateReplanDirective(continuation: TurnContinuation): PlanningDirective =
        Execution.tryImmediateReplanDirective(continuation)

    fun describeTurnContinuationDelay(continuation: TurnContinuation): String? =
        SessionRuntimeExecutionFacadeSupport.describeTurnContinuationDelay(continuation)

    fun describeImmediateReplan(
        continuation: TurnContinuation,
        observationSignature: String,
    ): String =
        SessionRuntimeExecutionFacadeSupport.describeImmediateReplan(
            continuation = continuation,
            observationSignature = observationSignature,
        )

    internal fun toPlanningDirective(
        result: PlanningAcquireResult,
        logMessageBuilder: ((AgentPlanningContext) -> String)? = null,
    ): PlanningDirective =
        SessionRuntimePlanningFacadeSupport.toPlanningDirective(
            result = result,
            logMessageBuilder = logMessageBuilder,
        )

    fun recordError(
        planningContext: AgentPlanningContext?,
        error: String,
        keepRunning: Boolean,
    ) =
        Execution.recordError(
            planningContext = planningContext,
            error = error,
            keepRunning = keepRunning,
        )

    fun consumeGrantedRiskApproval(approvalKey: String): Boolean =
        SessionRuntimeStateFacadeSupport.consumeGrantedRiskApproval(approvalKey)

    fun pauseAgent() =
        Lifecycle.pauseAgent()

    fun continueAgent(
        userCorrection: String = "",
    ) =
        SessionRuntimeLifecycleFacadeSupport.continueAgent(userCorrection)

    fun resumeAgent(
        userCorrection: String = "",
    ) =
        Lifecycle.resumeAgent(userCorrection)

    fun requestSafetyConfirmation(
        confirmation: PendingSafetyConfirmation,
    ) =
        Lifecycle.requestSafetyConfirmation(confirmation)

    fun approvePendingSafetyConfirmation(userCorrection: String = ""): PendingSafetyConfirmation? =
        Lifecycle.approvePendingSafetyConfirmation(userCorrection)

    fun rejectPendingSafetyConfirmation() =
        SessionRuntimeLifecycleFacadeSupport.rejectPendingSafetyConfirmation()

    fun stopAgent(reason: String) =
        Lifecycle.stopAgent(reason)

    internal fun dispatchHook(
        stage: AgentHookStage,
        payload: AgentHookPayload,
    ): AgentHookDispatchResult =
        SessionRuntimeCoordinatorFacadeSupport.dispatchHook(
            stage = stage,
            payload = payload,
        )

    internal fun dispatchSessionLifecycleHook(
        session: RuntimeSession,
        stage: AgentHookStage,
        result: String = "",
        actionLabel: String = "",
        metadata: Map<String, String> = emptyMap(),
    ) =
        SessionRuntimeCoordinatorFacadeSupport.dispatchSessionLifecycleHook(
            session = session,
            stage = stage,
            result = result,
            actionLabel = actionLabel,
            metadata = metadata,
        )

    internal fun acquirePlanningRuntimeSession(
        observation: ScreenObservation,
    ): PlanningRuntimeAcquire =
        SessionRuntimePlanningFacadeSupport.acquirePlanningRuntimeSession(observation)

    internal fun publishRuntimeState(
        sessionId: String = SessionRuntimeStore.session().sessionId,
    ) =
        SessionRuntimeStateFacadeSupport.publishRuntimeState(sessionId)

    internal fun hasRestoredOrActiveRuntimeState(
        state: SessionRuntimeState,
    ): Boolean =
        SessionRuntimePlanningFacadeSupport.hasRestoredOrActiveRuntimeState(state)

    internal fun isBootstrapResumePending(
        state: SessionRuntimeState,
    ): Boolean =
        SessionRuntimePlanningFacadeSupport.isBootstrapResumePending(state)

    internal fun resolveResumeBootstrapDecision(
        snapshot: SessionResumeSnapshot,
    ): ResumeBootstrapDecision =
        SessionRuntimePlanningFacadeSupport.resolveResumeBootstrapDecision(snapshot)

    internal fun buildPlanningSnapshot(
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String>,
        previousSnapshot: RuntimePlanningSnapshot = RuntimePlanningSnapshot(),
    ): RuntimePlanningSnapshot =
        SessionRuntimePlanningFacadeSupport.buildPlanningSnapshot(
            taskPlanState = taskPlanState,
            activeSkillTitles = activeSkillTitles,
            previousSnapshot = previousSnapshot,
        )

    internal fun buildRuntimeResumeDecisionDirective(
        channel: RuntimeResumeDecisionChannel,
        phase: RuntimeResumeDecisionPhase,
        stage: String,
        action: AgentAction,
        policy: RuntimeResumePolicy,
        reason: String,
        recoveryCategory: String = "",
        extraJsonFields: Map<String, String> = emptyMap(),
    ): RuntimeResumeDecisionDirective =
        SessionRuntimePlanningFacadeSupport.buildRuntimeResumeDecisionDirective(
            channel = channel,
            phase = phase,
            stage = stage,
            action = action,
            policy = policy,
            reason = reason,
            recoveryCategory = recoveryCategory,
            extraJsonFields = extraJsonFields,
        )

    internal fun buildRuntimeResultSnapshot(
        lastAction: String,
        finalResult: String,
        taskResult: TaskResultPayload?,
        lastError: String,
        hint: String,
    ): RuntimeResultSnapshot =
        SessionRuntimeStateFacadeSupport.buildRuntimeResultSnapshot(
            lastAction = lastAction,
            finalResult = finalResult,
            taskResult = taskResult,
            lastError = lastError,
            hint = hint,
        )

    internal fun buildRuntimeRouteSnapshot(
        routeResolution: TaskRouteResolution,
    ): RuntimeRouteSnapshot =
        SessionRuntimeStateFacadeSupport.buildRuntimeRouteSnapshot(routeResolution)

    internal fun syncResumeContextFromPlanState(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ) =
        SessionRuntimePlanningFacadeSupport.syncResumeContextFromPlanState(
            session = session,
            taskPlanState = taskPlanState,
        )

    internal fun publishResumeContextApplied(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ) =
        SessionRuntimePlanningFacadeSupport.publishResumeContextApplied(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )

    internal fun previewRuntimeResumeDirective(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionSnapshot? =
        SessionRuntimePlanningFacadeSupport.previewRuntimeResumeDirective(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )

    internal fun shouldApplyResumeGuard(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): Boolean =
        SessionRuntimePlanningFacadeSupport.shouldApplyResumeGuard(
            session = session,
            taskPlanState = taskPlanState,
        )

    internal fun resolveResumeWaitPolicy(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): ResumeWaitPolicyDecision =
        SessionRuntimePlanningFacadeSupport.resolveResumeWaitPolicy(
            session = session,
            taskPlanState = taskPlanState,
        )

    internal fun buildResolvedResumeContext(
        previousWaitState: RuntimeExternalWaitState?,
        taskPlanState: TaskPlanState,
        previousEvent: String,
    ): ResumeContext =
        SessionRuntimePlanningFacadeSupport.buildResolvedResumeContext(
            previousWaitState = previousWaitState,
            taskPlanState = taskPlanState,
            previousEvent = previousEvent,
        )

    internal fun persistMemoryOutcome(
        sessionId: String,
        task: String,
        profileId: String,
        status: String,
        finalMessage: String,
        taskResult: TaskResultPayload? = null,
    ) =
        SessionRuntimeStateFacadeSupport.persistMemoryOutcome(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            status = status,
            finalMessage = finalMessage,
            taskResult = taskResult,
        )
}
