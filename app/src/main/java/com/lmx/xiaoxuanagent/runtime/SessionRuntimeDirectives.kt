package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.RecoveryRefinementResult
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation

sealed interface PlanningAcquireResult {
    data class Acquired(val context: AgentPlanningContext) : PlanningAcquireResult

    data object Idle : PlanningAcquireResult

    data object MaxTurnsReached : PlanningAcquireResult
}

sealed interface PlanningDirective {
    data class ExecuteTurn(
        val context: AgentPlanningContext,
        val logMessage: String = "",
    ) : PlanningDirective

    data object NoOp : PlanningDirective

    data object MaxTurnsReached : PlanningDirective
}

sealed interface ExecutionEntryDirective {
    data class Proceed(
        val logMessage: String,
    ) : ExecutionEntryDirective

    data class SkipAndReplan(
        val logMessage: String,
        val continuation: TurnContinuation,
    ) : ExecutionEntryDirective

    data class AbortAndWait(
        val logMessage: String,
        val errorMessage: String,
    ) : ExecutionEntryDirective
}

enum class PlanningDecisionSource {
    EARLY_SKILL,
    SYSTEM_INTERVENTION,
    PLANNER_PIPELINE,
}

sealed interface PlanningDecisionCandidate {
    data class EarlySkill(
        val decision: AgentDecision,
    ) : PlanningDecisionCandidate

    data class SystemIntervention(
        val decision: AgentDecision,
        val resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot? = null,
    ) : PlanningDecisionCandidate

    data class PlannerPipeline(
        val plannedDecision: AgentDecision,
        val refinement: RecoveryRefinementResult,
    ) : PlanningDecisionCandidate
}

data class PlanningDecisionDirective(
    val decision: AgentDecision,
    val logMessages: List<String>,
)

data class PlanningScreenTelemetry(
    val screenshotStatus: String,
    val logMessages: List<String>,
)

data class RuntimeResumeDecisionDirective(
    val decision: AgentDecision,
    val snapshot: RuntimeResumeDecisionSnapshot,
)

data class PlanningFailureDirective(
    val logMessage: String,
    val errorMessage: String,
)

sealed interface SafetyEntryDirective {
    data class Proceed(
        val logMessage: String = "",
    ) : SafetyEntryDirective

    data class AwaitConfirmation(
        val logMessage: String = "",
    ) : SafetyEntryDirective

    data class StopAgent(
        val reason: String,
    ) : SafetyEntryDirective
}

data class TurnCompletion(
    val keepRunning: Boolean,
    val loopDetected: Boolean,
    val turn: Int,
    val finalResult: String,
)

data class TargetAppLaunchDirective(
    val targetPackageName: String,
    val reason: String,
    val moveAssistantToBack: Boolean = true,
)

enum class TargetAppReturnTrigger {
    USER_ACTION,
    BOOTSTRAP_AUTO,
}

data class TargetAppReturnDirective(
    val targetPackageName: String,
    val reason: String,
    val moveAssistantToBack: Boolean = true,
    val continueBeforeLaunch: Boolean = false,
)

enum class ImmediateReplanReason {
    SKIP_REDUNDANT_LAUNCH,
    POST_ACTION_CONTINUATION,
}

data class TurnContinuation(
    val turnCompletion: TurnCompletion,
    val settleDelayMs: Long,
    val replanObservation: IndexedScreenObservation?,
    val immediateReplanReason: ImmediateReplanReason? = null,
) {
    val shouldAttemptImmediateReplan: Boolean
        get() =
            turnCompletion.keepRunning &&
                !turnCompletion.loopDetected &&
                replanObservation != null &&
                immediateReplanReason != null
}

data class ExecutionCompletion(
    val finalMessage: String,
    val keepRunning: Boolean,
    val shouldImmediateReplan: Boolean,
    val shouldResetObservationSignature: Boolean,
    val replanObservation: IndexedScreenObservation?,
    val recoveryDiagnosis: RecoveryDiagnosis?,
    val suggestedRecoveryAction: String,
    val taskResult: TaskResultPayload?,
)

internal data class ResumeBootstrapDecision(
    val restoredState: SessionRuntimeState,
    val logMessage: String,
    val replayMessage: String,
    val replayAttributes: Map<String, Any?> = emptyMap(),
)

internal sealed interface ResumeWaitPolicyDecision {
    data object Proceed : ResumeWaitPolicyDecision

    data class ApplyGuard(
        val guardedTaskPlanState: com.lmx.xiaoxuanagent.agent.TaskPlanState,
        val logMessage: String,
        val replayMessage: String,
        val replayAttributes: Map<String, Any?> = emptyMap(),
    ) : ResumeWaitPolicyDecision

    data class ReenterWait(
        val adjustedTaskPlanState: com.lmx.xiaoxuanagent.agent.TaskPlanState,
        val logMessage: String,
        val replayMessage: String,
        val replayAttributes: Map<String, Any?> = emptyMap(),
    ) : ResumeWaitPolicyDecision
}

internal sealed interface PlanningRuntimeAcquire {
    data class SessionReady(val session: RuntimeSession) : PlanningRuntimeAcquire

    data class EarlyResult(val result: PlanningAcquireResult) : PlanningRuntimeAcquire
}

internal data class TurnArtifactBuffer(
    val planningObservationArtifactId: String = "",
    val uiXmlArtifactId: String = "",
    val screenshotArtifactId: String = "",
    val plannerDecisionArtifactId: String = "",
    val executionTraceArtifactId: String = "",
    val taskResultSummaryArtifactId: String = "",
    val verificationArtifactId: String = "",
    val failureArtifactId: String = "",
) {
    fun toRefs(): TurnArtifactRefs =
        TurnArtifactRefs(
            planningObservationArtifactId = planningObservationArtifactId,
            uiXmlArtifactId = uiXmlArtifactId,
            screenshotArtifactId = screenshotArtifactId,
            plannerDecisionArtifactId = plannerDecisionArtifactId,
            executionTraceArtifactId = executionTraceArtifactId,
            taskResultSummaryArtifactId = taskResultSummaryArtifactId,
            verificationArtifactId = verificationArtifactId,
            failureArtifactId = failureArtifactId,
        )
}

internal object SessionRuntimeDirectiveSupport {
    fun toPlanningDirective(
        result: PlanningAcquireResult,
        logMessageBuilder: ((AgentPlanningContext) -> String)? = null,
    ): PlanningDirective =
        when (result) {
            is PlanningAcquireResult.Acquired ->
                PlanningDirective.ExecuteTurn(
                    context = result.context,
                    logMessage = logMessageBuilder?.invoke(result.context).orEmpty(),
                )

            PlanningAcquireResult.Idle -> PlanningDirective.NoOp
            PlanningAcquireResult.MaxTurnsReached -> PlanningDirective.MaxTurnsReached
        }
}
