package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.RecoveryCategory
import com.lmx.xiaoxuanagent.agent.RecoveryDiagnosis
import com.lmx.xiaoxuanagent.agent.SkillLayer

object ExitRecoverySkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "exit_recovery",
            title = "退出纠偏",
            description = "在误入错误页面或更深层错误上下文时，优先选择退出动作回到上一步。",
            instructions = "如果恢复诊断显示当前页面已偏离子目标，优先 back；如果只是页面树空白则先 wait。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 25,
            layer = SkillLayer.GUARD,
            requiredTools = listOf("semantic.navigate_back", "gui.wait"),
        )

    override fun recover(
        context: SkillDecisionContext,
        diagnosis: RecoveryDiagnosis,
    ): AgentDecision? {
        val action =
            when (diagnosis.category) {
                RecoveryCategory.SUBGOAL_MISMATCH -> AgentAction.NavigateBack(hint = diagnosis.category.name.lowercase())
                RecoveryCategory.EMPTY_OBSERVATION -> AgentAction.Wait
                else -> null
            } ?: return null
        return AgentDecision(
            action = action,
            reason = "技能恢复：${diagnosis.summary}",
            rawResponse =
                """{"skill":"exit_recovery","recovery":"${diagnosis.category.name.lowercase()}","action":"${action.label}"}""",
        )
    }

    override fun extractParameters(
        context: SkillDecisionContext,
    ): List<String> =
        listOfNotNull(
            context.taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { "stage=$it" },
            context.taskPlanState?.currentSubgoalId?.takeIf { it.isNotBlank() }?.let { "subgoal=$it" },
        )
}
