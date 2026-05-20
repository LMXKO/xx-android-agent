package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskPlanState

object SkillExecutionEngine {
    fun prePlanDecision(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord> = emptyList(),
    ): AgentDecision? {
        val context =
            SkillDecisionContext(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                history = history,
            )
        return SkillRegistry.getByIds(activeSkillIds)
            .sortedByDescending { it.spec.priority }
            .firstNotNullOfOrNull { skill ->
                skill.maybePrePlan(context)?.also {
                    SkillInvocationStore.record(
                        skillId = skill.spec.id,
                        task = task,
                        profileId = profileId,
                        parameters = skill.extractParameters(context),
                        phase = "pre_plan",
                    )
                }
            }
    }

    fun refineDecision(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        decision: AgentDecision,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord> = emptyList(),
    ): AgentDecision {
        val context =
            SkillDecisionContext(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                history = history,
            )
        return SkillRegistry.getByIds(activeSkillIds)
            .sortedByDescending { it.spec.priority }
            .fold(decision) { current, skill ->
                skill.refineDecision(context, current).also { refined ->
                    if (refined != current) {
                        SkillInvocationStore.record(
                            skillId = skill.spec.id,
                            task = task,
                            profileId = profileId,
                            parameters = skill.extractParameters(context),
                            phase = "post_plan",
                        )
                    }
                }
            }
    }

    fun recover(
        task: String,
        profileId: String,
        observation: ScreenObservation,
        memoryContext: PlanningMemoryContext,
        taskPlanState: TaskPlanState,
        activeSkillIds: List<String>,
        history: List<AgentTurnRecord> = emptyList(),
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? {
        val context =
            SkillDecisionContext(
                task = task,
                profileId = profileId,
                observation = observation,
                memoryContext = memoryContext,
                taskPlanState = taskPlanState,
                history = history,
            )
        return SkillRegistry.getByIds(activeSkillIds)
            .sortedByDescending { it.spec.priority }
            .firstNotNullOfOrNull { skill ->
                skill.recover(context, diagnosis)?.also {
                    SkillInvocationStore.record(
                        skillId = skill.spec.id,
                        task = task,
                        profileId = profileId,
                        parameters = skill.extractParameters(context) + "recovery=${diagnosis.category.name.lowercase()}",
                        phase = "recover",
                    )
                }
            }
    }
}
