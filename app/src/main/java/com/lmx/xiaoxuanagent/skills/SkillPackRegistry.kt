package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.SkillLayer
import com.lmx.xiaoxuanagent.agent.SkillRiskLevel

data class SkillPackRegistration(
    val id: String,
    val title: String,
    val providerId: String,
    val layer: SkillLayer,
    val riskLevel: SkillRiskLevel,
    val skills: List<ExecutableSkill>,
)

internal object SkillPackRegistry {
    private val packs: List<SkillPackRegistration> =
        listOf(
            SkillPackRegistration(
                id = "guard_pack",
                title = "Guardrails",
                providerId = "core_guard_provider",
                layer = SkillLayer.GUARD,
                riskLevel = SkillRiskLevel.LOW,
                skills =
                    listOf(
                        AppStateGuardSkill,
                        InputTargetGuardSkill,
                        ContextRecoverySkill,
                        RecoveryFollowThroughSkill,
                        InterruptGuardSkill,
                    ),
            ),
            SkillPackRegistration(
                id = "resume_recovery_pack",
                title = "Resume & Recovery",
                providerId = "core_guard_provider",
                layer = SkillLayer.GUARD,
                riskLevel = SkillRiskLevel.CONFIRM,
                skills =
                    listOf(
                        ResumeEntryAlignmentSkill,
                        ResumeContinuationSkill,
                        HumanCorrectionSkill,
                        ExitRecoverySkill,
                        SubgoalAlignmentSkill,
                    ),
            ),
            SkillPackRegistration(
                id = "memory_pack",
                title = "Personal Memory",
                providerId = "personal_memory_provider",
                layer = SkillLayer.PERSONAL,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(PersonalMemorySkill),
            ),
            SkillPackRegistration(
                id = "discovery_pack",
                title = "Discovery Entry",
                providerId = "general_agent_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(QueryEntrySkill),
            ),
            SkillPackRegistration(
                id = "shopping_research_pack",
                title = "Shopping Research",
                providerId = "shopping_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(ShoppingResearchSkill),
            ),
            SkillPackRegistration(
                id = "content_research_pack",
                title = "Content Research",
                providerId = "content_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(ContentResearchSkill),
            ),
            SkillPackRegistration(
                id = "navigation_pack",
                title = "Navigation",
                providerId = "navigation_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(NavigationSkill),
            ),
            SkillPackRegistration(
                id = "messaging_pack",
                title = "Messaging",
                providerId = "messaging_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.CONFIRM,
                skills = listOf(MessagingSkill),
            ),
            SkillPackRegistration(
                id = "reminder_scheduling_pack",
                title = "Reminder & Scheduling",
                providerId = "scheduling_provider",
                layer = SkillLayer.DOMAIN,
                riskLevel = SkillRiskLevel.LOW,
                skills = listOf(ReminderSchedulingSkill),
            ),
        )

    fun allSkills(): List<ExecutableSkill> =
        packs.flatMap { it.skills }.distinctBy { it.spec.id }

    fun allPacks(): List<SkillPackRegistration> = packs

    fun packSummaryLines(
        limit: Int = 8,
    ): List<String> =
        packs.take(limit).map { pack ->
            "${pack.title} | provider=${pack.providerId} | layer=${pack.layer.name.lowercase()} | skills=${pack.skills.size}"
        }
}
