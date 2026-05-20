package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.SkillLayer
import com.lmx.xiaoxuanagent.agent.SkillRiskLevel

data class SkillProviderDescriptor(
    val id: String,
    val title: String,
    val surface: String,
    val layer: SkillLayer,
)

data class SkillPackDescriptor(
    val id: String,
    val title: String,
    val summary: String,
    val providerId: String,
    val riskLevel: SkillRiskLevel,
)

data class SkillCatalogEntry(
    val skillId: String,
    val provider: SkillProviderDescriptor,
    val pack: SkillPackDescriptor,
    val executable: ExecutableSkill,
)

class SkillCatalog(
    private val entries: List<SkillCatalogEntry>,
) {
    fun resolveEntries(
        task: String,
        profileId: String,
    ): List<SkillCatalogEntry> {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank()) {
            return entries.filter { it.executable.spec.alwaysOn }
        }

        val alwaysOn = mutableListOf<SkillCatalogEntry>()
        val matched = mutableListOf<Pair<SkillCatalogEntry, Int>>()
        entries.forEach { entry ->
            val spec = entry.executable.spec
            if (spec.profileIds.isNotEmpty() && profileId !in spec.profileIds) {
                return@forEach
            }
            if (spec.alwaysOn) {
                alwaysOn += entry
            }
            val score =
                spec.keywords.fold(spec.priority + providerBonus(entry.provider.id, normalizedTask)) { acc, keyword ->
                    when {
                        normalizedTask.equals(keyword, ignoreCase = true) -> acc + 8
                        normalizedTask.contains(keyword, ignoreCase = true) -> acc + 4
                        else -> acc
                    }
                }
            if (score > 0) {
                matched += entry to score
            }
        }
        return (alwaysOn + matched.sortedByDescending { it.second }.map { it.first })
            .distinctBy { it.skillId }
            .take(6)
    }

    fun getByIds(
        ids: Collection<String>,
    ): List<ExecutableSkill> =
        entries.filter { it.skillId in ids }.map { it.executable }

    fun catalogLines(
        limit: Int = 12,
    ): List<String> =
        entries
            .groupBy { it.pack.id }
            .values
            .sortedByDescending { group -> group.maxOfOrNull { it.executable.spec.priority } ?: 0 }
            .take(limit)
            .map { group ->
                val first = group.first()
                val tools = group.flatMap { it.executable.spec.requiredTools }.distinct().take(3)
                "${first.provider.title} / ${first.pack.title} | skills=${group.size} | risk=${first.pack.riskLevel.name.lowercase()} | tools=${tools.joinToString(",").ifBlank { "-" }}"
            }

    fun providerSummaryLines(
        limit: Int = 6,
    ): List<String> =
        entries
            .groupBy { it.provider.id }
            .values
            .sortedByDescending { it.size }
            .take(limit)
            .map { group ->
                val provider = group.first().provider
                val packCount = group.map { it.pack.id }.distinct().size
                "${provider.title} | surface=${provider.surface} | packs=$packCount | skills=${group.size}"
            }

    private fun providerBonus(
        providerId: String,
        task: String,
    ): Int =
        when {
            providerId.contains("messaging") && (task.contains("消息") || task.contains("微信")) -> 4
            providerId.contains("shopping") && (task.contains("评价") || task.contains("商品")) -> 4
            providerId.contains("navigation") && (task.contains("路线") || task.contains("导航")) -> 4
            providerId.contains("content") && (task.contains("评论") || task.contains("视频") || task.contains("内容")) -> 4
            else -> 0
        }
}

internal object SkillCatalogFactory {
    fun build(
        skills: List<ExecutableSkill>,
    ): SkillCatalog =
        SkillCatalog(
            skills.map { skill ->
                val providerId = resolveProviderId(skill.spec)
                val packId = resolvePackId(skill.spec)
                SkillCatalogEntry(
                    skillId = skill.spec.id,
                    provider =
                        SkillProviderDescriptor(
                            id = providerId,
                            title = providerTitle(providerId),
                            surface = providerSurface(providerId),
                            layer = skill.spec.layer,
                        ),
                    pack =
                        SkillPackDescriptor(
                            id = packId,
                            title = packTitle(packId),
                            summary = skill.spec.description,
                            providerId = providerId,
                            riskLevel = skill.spec.riskLevel,
                        ),
                    executable = skill,
                )
            },
        )

    private fun resolveProviderId(
        spec: SkillSpec,
    ): String =
        when {
            spec.id.contains("guard") || spec.layer == SkillLayer.GUARD -> "core_guard_provider"
            spec.id.contains("message") -> "messaging_provider"
            spec.id.contains("shopping") -> "shopping_provider"
            spec.id.contains("content") -> "content_provider"
            spec.id.contains("navigation") -> "navigation_provider"
            spec.layer == SkillLayer.PERSONAL -> "personal_memory_provider"
            else -> "general_agent_provider"
        }

    private fun resolvePackId(
        spec: SkillSpec,
    ): String =
        when {
            spec.id.contains("resume") || spec.id.contains("correction") -> "resume_recovery_pack"
            spec.id.contains("guard") -> "guard_pack"
            spec.id.contains("shopping") -> "shopping_research_pack"
            spec.id.contains("content") -> "content_research_pack"
            spec.id.contains("navigation") -> "navigation_pack"
            spec.id.contains("message") -> "messaging_pack"
            spec.layer == SkillLayer.PERSONAL -> "memory_pack"
            else -> "generic_pack"
        }

    private fun providerTitle(
        providerId: String,
    ): String =
        when (providerId) {
            "core_guard_provider" -> "Core Guard"
            "messaging_provider" -> "Messaging"
            "shopping_provider" -> "Shopping"
            "content_provider" -> "Content"
            "navigation_provider" -> "Navigation"
            "personal_memory_provider" -> "Personal Memory"
            else -> "General Agent"
        }

    private fun providerSurface(
        providerId: String,
    ): String =
        when (providerId) {
            "core_guard_provider" -> "always_on"
            "personal_memory_provider" -> "memory"
            else -> "task"
        }

    private fun packTitle(
        packId: String,
    ): String =
        when (packId) {
            "resume_recovery_pack" -> "Resume & Recovery"
            "guard_pack" -> "Guardrails"
            "shopping_research_pack" -> "Shopping Research"
            "content_research_pack" -> "Content Research"
            "navigation_pack" -> "Navigation"
            "messaging_pack" -> "Messaging"
            "memory_pack" -> "Personal Memory"
            else -> "General"
        }
}
