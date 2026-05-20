package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentToolSelectionPolicy
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentPlanningContext
import com.lmx.xiaoxuanagent.agent.RecoveryRefinementResult

internal object SessionRuntimeCoordinatorSupport {
    fun recordDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot? = null,
    ) {
        SessionRuntimeStore.dispatch(
            SessionCommand.RecordPlannerDecisionState(
                actionLabel = decision.action.label,
                rawResponse = decision.rawResponse,
                reason = "runtime_record_planner_decision",
            ),
        )
        resumeDecisionSnapshot?.takeIf { it.active }?.let { snapshot ->
            SessionRuntimeStore.dispatch(
                SessionCommand.RecordResumeDecisionState(
                    resumeDecision = snapshot,
                    reason = "runtime_record_resume_decision",
                ),
            )
        }
        SessionRuntimeBridgeSupport.publish(
            SessionBridgeEvent.PlannerDecisionRecorded(
                sessionId = planningContext.sessionId,
                actionLabel = decision.action.label,
                reason = decision.reason,
            ),
        )
        val artifact =
            ArtifactStore.recordPlannerDecision(
                sessionId = planningContext.sessionId,
                turn = planningContext.turn,
                task = planningContext.task,
                decision = decision,
            )
        SessionRuntimeStateSupport.updateTurnArtifacts(planningContext.sessionId, planningContext.turn) { current ->
            current.copy(plannerDecisionArtifactId = artifact?.artifactId.orEmpty())
        }
        artifact?.let {
            SessionRuntimeBridgeSupport.publishArtifactRecorded(planningContext.sessionId, planningContext.turn, it)
        }
        SessionRuntime.publishRuntimeState(planningContext.sessionId)
    }

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        candidate: PlanningDecisionCandidate,
    ): PlanningDecisionDirective =
        when (candidate) {
            is PlanningDecisionCandidate.EarlySkill ->
                publishPlanningDecision(
                    planningContext = planningContext,
                    decision = candidate.decision,
                    source = PlanningDecisionSource.EARLY_SKILL,
                )

            is PlanningDecisionCandidate.SystemIntervention ->
                publishPlanningDecision(
                    planningContext = planningContext,
                    decision = candidate.decision,
                    source = PlanningDecisionSource.SYSTEM_INTERVENTION,
                    resumeDecisionSnapshot = candidate.resumeDecisionSnapshot,
                )

            is PlanningDecisionCandidate.PlannerPipeline ->
                publishPlanningDecision(
                    planningContext = planningContext,
                    decision = candidate.refinement.finalDecision,
                    source = PlanningDecisionSource.PLANNER_PIPELINE,
                    refinement = candidate.refinement,
                    plannedDecisionLabel = candidate.plannedDecision.action.label,
                )
        }

    fun publishPlanningDecision(
        planningContext: AgentPlanningContext,
        decision: AgentDecision,
        source: PlanningDecisionSource,
        resumeDecisionSnapshot: RuntimeResumeDecisionSnapshot? = null,
        refinement: RecoveryRefinementResult? = null,
        plannedDecisionLabel: String = "",
    ): PlanningDecisionDirective {
        val planHook =
            dispatchHook(
                stage = AgentHookStage.AFTER_PLAN,
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
                                "plan_stage" to planningContext.taskPlanState.currentStage,
                            ),
                    ),
            )
        val effectiveDecision = planHook.overrideDecision ?: decision
        val toolRewrite =
            AgentToolSelectionPolicy.normalizeDecision(
                decision = effectiveDecision,
                planningContext = planningContext,
            )
        val normalizedDecision = toolRewrite.decision
        recordDecision(planningContext, normalizedDecision, resumeDecisionSnapshot)
        val postSamplingHook =
            dispatchHook(
                stage = AgentHookStage.POST_SAMPLING,
                payload =
                    AgentHookPayload(
                        sessionId = planningContext.sessionId,
                        task = planningContext.task,
                        turn = planningContext.turn,
                        observationSignature = planningContext.observation.signature,
                        pageState = planningContext.observation.pageState,
                        actionLabel = normalizedDecision.action.label,
                        metadata =
                            mapOf(
                                "profile_id" to planningContext.profileId,
                                "plan_stage" to planningContext.taskPlanState.currentStage,
                                "tool_name" to normalizedDecision.action.toolName,
                            ),
                    ),
            )
        val logMessages =
            when (source) {
                PlanningDecisionSource.PLANNER_PIPELINE ->
                    buildList {
                        planHook.messages.forEach(::add)
                        postSamplingHook.messages.forEach(::add)
                        refinement?.takeIf { it.policyChanged }?.let {
                            add(
                                "本地策略改写动作: ${plannedDecisionLabel.ifBlank { it.policyDecision.action.label }} -> ${it.policyDecision.action.label}",
                            )
                        }
                        refinement?.takeIf { it.skillChanged }?.let {
                            add("技能改写动作: ${it.policyDecision.action.label} -> ${it.finalDecision.action.label}")
                        }
                        if (effectiveDecision.action.label != decision.action.label) {
                            add("hook 改写动作: ${decision.action.label} -> ${effectiveDecision.action.label}")
                        }
                        if (toolRewrite.changed) {
                            add("tool policy 改写动作: ${toolRewrite.reason}")
                        }
                        add("动作来源: LLM/本地策略/skill 后处理。")
                        add("规划结果已生成，进入执行阶段。")
                    }

                PlanningDecisionSource.EARLY_SKILL ->
                    buildList {
                        planHook.messages.forEach(::add)
                        postSamplingHook.messages.forEach(::add)
                        add("技能预执行命中: ${effectiveDecision.action.label}")
                        if (effectiveDecision.action.label != decision.action.label) {
                            add("hook 改写动作: ${decision.action.label} -> ${effectiveDecision.action.label}")
                        }
                        if (toolRewrite.changed) {
                            add("tool policy 改写动作: ${toolRewrite.reason}")
                        }
                        add("动作来源: skill 预执行。")
                    }

                PlanningDecisionSource.SYSTEM_INTERVENTION ->
                    buildList {
                        planHook.messages.forEach(::add)
                        postSamplingHook.messages.forEach(::add)
                        add("系统工具层接管: ${effectiveDecision.action.label}")
                        if (effectiveDecision.action.label != decision.action.label) {
                            add("hook 改写动作: ${decision.action.label} -> ${effectiveDecision.action.label}")
                        }
                        if (toolRewrite.changed) {
                            add("tool policy 改写动作: ${toolRewrite.reason}")
                        }
                        add("动作来源: 系统工具层接管。")
                        add("规划结果已生成，进入执行阶段。")
                    }
            }
        return PlanningDecisionDirective(
            decision = normalizedDecision,
            logMessages = logMessages,
        )
    }

    fun dispatchHook(
        stage: AgentHookStage,
        payload: AgentHookPayload,
    ): AgentHookDispatchResult {
        val result = AgentHookRegistry.dispatch(stage, payload)
        if (result.messages.isNotEmpty()) {
            RuntimeMetricsStore.recordHookOutcome(stage, "message")
        }
        result.messages.forEach { line ->
            SessionRuntimeBridgeSupport.publish(SessionBridgeEvent.LogMessage("hook ${stage.name.lowercase()}: $line"))
        }
        if (result.blockReason.isNotBlank()) {
            RuntimeMetricsStore.recordHookOutcome(stage, "blocked")
            SessionRuntimeBridgeSupport.publish(
                SessionBridgeEvent.LogMessage(
                    "hook ${stage.name.lowercase()} blocked: ${result.blockReason}",
                ),
            )
        }
        if (result.overrideDecision != null) {
            RuntimeMetricsStore.recordHookOutcome(stage, "rewrite")
            SessionRuntimeBridgeSupport.publish(
                SessionBridgeEvent.LogMessage(
                    "hook ${stage.name.lowercase()} rewrote decision -> ${result.overrideDecision.action.label}",
                ),
            )
        }
        if (result.injectedExecutionResult != null) {
            RuntimeMetricsStore.recordHookOutcome(stage, "inject_result")
            SessionRuntimeBridgeSupport.publish(
                SessionBridgeEvent.LogMessage(
                    "hook ${stage.name.lowercase()} injected execution result",
                ),
            )
        }
        return result
    }

    fun dispatchSessionLifecycleHook(
        session: RuntimeSession,
        stage: AgentHookStage,
        result: String = "",
        actionLabel: String = "",
        metadata: Map<String, String> = emptyMap(),
    ) {
        val planningSnapshot = session.planningSnapshot
        dispatchHook(
            stage = stage,
            payload =
                AgentHookPayload(
                    sessionId = session.sessionId,
                    task = session.task,
                    turn = session.turns,
                    observationSignature = session.lastObservationSignature,
                    pageState = planningSnapshot.currentPlanStage,
                    actionLabel = actionLabel,
                    result = result,
                    metadata =
                        buildMap {
                            put("profile_id", session.profileId)
                            put("entry_source", session.entrySource)
                            put("plan_stage", planningSnapshot.currentPlanStage)
                            put("subgoal_id", planningSnapshot.currentSubgoalId)
                            put("next_objective", planningSnapshot.nextObjective)
                            putAll(metadata.filterValues { it.isNotBlank() })
                        },
                ),
        )
    }
}
