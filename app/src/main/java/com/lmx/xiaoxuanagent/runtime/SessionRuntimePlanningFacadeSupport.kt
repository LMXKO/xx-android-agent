package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.PlannerArtifactHint
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.RecoveryPlanHint
import com.lmx.xiaoxuanagent.agent.ResumeContext
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskPlanState
import com.lmx.xiaoxuanagent.taskprofile.TaskProfile
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

internal object SessionRuntimePlanningFacadeSupport {
    fun currentTaskProfile(): TaskProfile =
        TaskRegistry.get(SessionRuntimeStore.session().profileId) ?: TaskRegistry.defaultProfile

    fun currentTask(): String = SessionRuntimeStore.session().task

    fun resolveTargetAppReturnDirective(
        trigger: TargetAppReturnTrigger,
    ): TargetAppReturnDirective? =
        SessionRuntimePlanningSupport.resolveTargetAppReturnDirective(trigger)

    fun resolveTargetAppLaunchDirective(): TargetAppLaunchDirective? =
        SessionRuntimePlanningSupport.resolveTargetAppLaunchDirective()

    fun resolveBootstrapAutoLaunchDirective(): TargetAppLaunchDirective? =
        SessionRuntimePlanningSupport.resolveBootstrapAutoLaunchDirective()

    fun shouldAutoContinueBootstrappedSession(): Boolean =
        SessionRuntimePlanningSupport.shouldAutoContinueBootstrappedSession()

    fun bootstrapFromLatestResumeSnapshot(): Boolean =
        SessionRuntimePlanningSupport.bootstrapFromLatestResumeSnapshot()

    fun bootstrapFromResumeSnapshot(
        sessionId: String,
    ): Boolean =
        SessionRuntimePlanningSupport.bootstrapFromResumeSnapshot(sessionId)

    fun peekPlannerArtifactHints(
        sessionId: String,
        turn: Int,
    ): List<PlannerArtifactHint> =
        SessionRuntimePlanningSupport.peekPlannerArtifactHints(sessionId, turn)

    fun handleExternalWaitObservation(indexedObservation: IndexedScreenObservation): Boolean =
        SessionRuntimePlanningSupport.handleExternalWaitObservation(indexedObservation)

    fun enterExternalWait(
        observation: ScreenObservation,
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String>,
        enteredAtMs: Long,
        reason: String,
    ) =
        SessionRuntimePlanningSupport.enterExternalWait(
            observation = observation,
            taskPlanState = taskPlanState,
            activeSkillTitles = activeSkillTitles,
            enteredAtMs = enteredAtMs,
            reason = reason,
        )

    fun acquirePlanningContext(indexedObservation: IndexedScreenObservation): PlanningAcquireResult =
        SessionRuntimePlanningSupport.acquirePlanningContext(indexedObservation)

    fun acquirePlanningDirective(indexedObservation: IndexedScreenObservation): PlanningDirective =
        SessionRuntimePlanningSupport.acquirePlanningDirective(indexedObservation)

    fun resolveRuntimeResumeIntervention(
        planningContext: AgentPlanningContext,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimePlanningSupport.resolveRuntimeResumeIntervention(planningContext)

    fun resolveRuntimeResumeRecoveryIntervention(
        planningContext: AgentPlanningContext,
        diagnosis: RecoveryDiagnosis,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionDirective? =
        SessionRuntimePlanningSupport.resolveRuntimeResumeRecoveryIntervention(
            planningContext = planningContext,
            diagnosis = diagnosis,
            observation = observation,
        )

    fun resolveRecoveryPlan(
        planningContext: AgentPlanningContext,
        observation: ScreenObservation,
        diagnosis: RecoveryDiagnosis?,
    ): RecoveryPlanHint =
        SessionRuntimePlanningSupport.resolveRecoveryPlan(
            planningContext = planningContext,
            observation = observation,
            diagnosis = diagnosis,
        )

    fun toPlanningDirective(
        result: PlanningAcquireResult,
        logMessageBuilder: ((AgentPlanningContext) -> String)?,
    ): PlanningDirective =
        SessionRuntimeDirectiveSupport.toPlanningDirective(
            result = result,
            logMessageBuilder = logMessageBuilder,
        )

    fun acquirePlanningRuntimeSession(
        observation: ScreenObservation,
    ): PlanningRuntimeAcquire =
        SessionRuntimePlanningSupport.acquirePlanningRuntimeSession(observation)

    fun hasRestoredOrActiveRuntimeState(
        state: SessionRuntimeState,
    ): Boolean =
        SessionRuntimePlanningSupport.hasRestoredOrActiveRuntimeState(state)

    fun isBootstrapResumePending(
        state: SessionRuntimeState,
    ): Boolean =
        SessionRuntimePlanningSupport.isBootstrapResumePending(state)

    fun resolveResumeBootstrapDecision(
        snapshot: SessionResumeSnapshot,
    ): ResumeBootstrapDecision =
        SessionRuntimePlanningSupport.resolveResumeBootstrapDecision(snapshot)

    fun buildPlanningSnapshot(
        taskPlanState: TaskPlanState,
        activeSkillTitles: List<String>,
        previousSnapshot: RuntimePlanningSnapshot,
    ): RuntimePlanningSnapshot =
        SessionRuntimePlanningSupport.buildPlanningSnapshot(
            taskPlanState = taskPlanState,
            activeSkillTitles = activeSkillTitles,
            previousSnapshot = previousSnapshot,
        )

    fun buildRuntimeResumeDecisionDirective(
        channel: RuntimeResumeDecisionChannel,
        phase: RuntimeResumeDecisionPhase,
        stage: String,
        action: AgentAction,
        policy: RuntimeResumePolicy,
        reason: String,
        recoveryCategory: String,
        extraJsonFields: Map<String, String>,
    ): RuntimeResumeDecisionDirective =
        SessionRuntimePlanningSupport.buildRuntimeResumeDecisionDirective(
            channel = channel,
            phase = phase,
            stage = stage,
            action = action,
            policy = policy,
            reason = reason,
            recoveryCategory = recoveryCategory,
            extraJsonFields = extraJsonFields,
        )

    fun syncResumeContextFromPlanState(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ) =
        SessionRuntimePlanningSupport.syncResumeContextFromPlanState(
            session = session,
            taskPlanState = taskPlanState,
        )

    fun publishResumeContextApplied(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ) =
        SessionRuntimePlanningSupport.publishResumeContextApplied(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )

    fun previewRuntimeResumeDirective(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
        observation: ScreenObservation,
    ): RuntimeResumeDecisionSnapshot? =
        SessionRuntimePlanningSupport.previewRuntimeResumeDirective(
            session = session,
            taskPlanState = taskPlanState,
            observation = observation,
        )

    fun shouldApplyResumeGuard(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): Boolean =
        SessionRuntimePlanningSupport.shouldApplyResumeGuard(
            session = session,
            taskPlanState = taskPlanState,
        )

    fun resolveResumeWaitPolicy(
        session: RuntimeSession,
        taskPlanState: TaskPlanState,
    ): ResumeWaitPolicyDecision =
        SessionRuntimePlanningSupport.resolveResumeWaitPolicy(
            session = session,
            taskPlanState = taskPlanState,
        )

    fun buildResolvedResumeContext(
        previousWaitState: RuntimeExternalWaitState?,
        taskPlanState: TaskPlanState,
        previousEvent: String,
    ): ResumeContext =
        SessionRuntimePlanningSupport.buildResolvedResumeContext(
            previousWaitState = previousWaitState,
            taskPlanState = taskPlanState,
            previousEvent = previousEvent,
        )
}
