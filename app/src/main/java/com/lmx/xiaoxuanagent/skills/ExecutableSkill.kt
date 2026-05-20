package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.ScreenObservation
import com.lmx.xiaoxuanagent.agent.TaskPlanState

data class SkillDecisionContext(
    val task: String,
    val profileId: String,
    val observation: ScreenObservation,
    val memoryContext: PlanningMemoryContext,
    val taskPlanState: TaskPlanState? = null,
    val history: List<AgentTurnRecord> = emptyList(),
)

interface ExecutableSkill {
    val spec: SkillSpec

    fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean = true

    fun extractParameters(
        context: SkillDecisionContext,
    ): List<String> = emptyList()

    fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? = null

    fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision = decision

    fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? = null
}
