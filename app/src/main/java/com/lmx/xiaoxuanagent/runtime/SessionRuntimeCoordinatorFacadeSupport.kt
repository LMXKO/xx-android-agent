package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.RecoveryRefinementResult

internal object SessionRuntimeCoordinatorFacadeSupport {
    fun recordDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot?,
    ) =
        SessionRuntimeCoordinatorSupport.recordDecision(
            planningContext = planningContext,
            decision = decision,
            resumeDecisionSnapshot = resumeDecisionSnapshot,
        )

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        candidate: PlanningDecisionCandidate,
    ): PlanningDecisionDirective =
        SessionRuntimeCoordinatorSupport.publishPlanningDecision(
            planningContext = planningContext,
            candidate = candidate,
        )

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        source: PlanningDecisionSource,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot?,
        refinement: RecoveryRefinementResult?,
        plannedDecisionLabel: String,
    ): PlanningDecisionDirective =
        SessionRuntimeCoordinatorSupport.publishPlanningDecision(
            planningContext = planningContext,
            decision = decision,
            source = source,
            resumeDecisionSnapshot = resumeDecisionSnapshot,
            refinement = refinement,
            plannedDecisionLabel = plannedDecisionLabel,
        )

    fun dispatchHook(
        stage: AgentHookStage,
        payload: AgentHookPayload,
    ): AgentHookDispatchResult =
        SessionRuntimeCoordinatorSupport.dispatchHook(
            stage = stage,
            payload = payload,
        )

    fun dispatchSessionLifecycleHook(
        session: RuntimeSession,
        stage: AgentHookStage,
        result: String,
        actionLabel: String,
        metadata: Map<String, String>,
    ) =
        SessionRuntimeCoordinatorSupport.dispatchSessionLifecycleHook(
            session = session,
            stage = stage,
            result = result,
            actionLabel = actionLabel,
            metadata = metadata,
        )
}
