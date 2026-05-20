package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.AgentSemanticActionComposer
import com.lmx.xiaoxuanagent.agent.PlanningMemoryContext
import com.lmx.xiaoxuanagent.agent.SkillLayer
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.TaskIntentType
import com.lmx.xiaoxuanagent.agent.UiElementObservation

private data class RememberedEntity(
    val name: String,
    val aliases: List<String> = emptyList(),
)

object PersonalMemorySkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "personal_memory",
            title = "个人记忆对齐",
            description = "利用联系人、地点等长期记忆，把任务词对齐到用户常用的个人上下文。",
            instructions = "当任务里出现联系人别名、地点简称或模糊称呼时，优先改写为记忆中的规范目标，并优先点击更匹配该记忆的候选。",
            keywords = emptyList(),
            alwaysOn = true,
            priority = 18,
            layer = SkillLayer.PERSONAL,
            requiredTools = listOf("gui.set_text", "gui.click"),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean =
        context.memoryContext.contacts.isNotEmpty() ||
            context.memoryContext.locations.isNotEmpty() ||
            context.memoryContext.appPreferences.isNotEmpty()

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val rememberedTarget = resolveRememberedCanonicalTarget(context) ?: return null
        val currentTexts = context.observation.topTexts + context.observation.elements.map { it.text }
        if (currentTexts.any { it.contains(rememberedTarget, ignoreCase = true) }) {
            return null
        }
        val stage = context.taskPlanState?.currentStage.orEmpty()
        if (stage !in setOf("find_conversation", "enter_destination", "enter_query")) {
            return null
        }
        return AgentDecision(
            action = AgentAction.PopulatePrimaryInput(rememberedTarget),
            reason = "技能预执行：命中个人记忆，先把输入目标对齐为 $rememberedTarget。",
            rawResponse =
                """{"skill":"personal_memory","phase":"pre_plan","action":"populate_primary_input","text":"$rememberedTarget"}""",
        )
    }

    override fun refineDecision(
        context: SkillDecisionContext,
        decision: AgentDecision,
    ): AgentDecision {
        val rememberedTarget = resolveRememberedCanonicalTarget(context) ?: return decision
        return when (val action = decision.action) {
            is AgentAction.SetText ->
                if (action.text != rememberedTarget && shouldRewriteText(context, action.text, rememberedTarget)) {
                    decision.copy(
                        action = AgentAction.PopulatePrimaryInput(rememberedTarget),
                        reason = "技能改写：命中个人记忆，将输入目标统一为 $rememberedTarget。",
                    )
                } else {
                    decision
                }

            is AgentAction.PopulatePrimaryInput ->
                if (action.text != rememberedTarget) {
                    decision.copy(
                        action = AgentAction.PopulatePrimaryInput(rememberedTarget),
                        reason = "技能改写：命中个人记忆，将主输入目标统一为 $rememberedTarget。",
                    )
                } else {
                    decision
                }

            is AgentAction.Click -> {
                val bestCandidate = findRememberedCandidate(context, rememberedTarget) ?: return decision
                val currentCandidate = context.observation.elements.firstOrNull { it.id == action.elementId }
                if (bestCandidate.id == action.elementId) {
                    decision
                } else {
                    val currentScore = currentCandidate?.let { scoreRememberedCandidate(it, rememberedTarget, context.memoryContext) } ?: Int.MIN_VALUE
                    val bestScore = scoreRememberedCandidate(bestCandidate, rememberedTarget, context.memoryContext)
                    if (bestScore >= 80 && bestScore - currentScore >= 28) {
                        decision.copy(
                            action = AgentAction.OpenBestCandidate(
                                targetText = bestCandidate.text.ifBlank { rememberedTarget },
                                roleHint = AgentSemanticActionComposer.roleHintForStage(context.taskPlanState?.currentStage.orEmpty()),
                            ),
                            reason =
                                "技能改写：根据个人记忆，候选 ${bestCandidate.text.ifBlank { bestCandidate.id }} " +
                                    "比当前点击目标更贴近 $rememberedTarget。",
                        )
                    } else {
                        decision
                    }
                }
            }

            else -> decision
        }
    }

    override fun extractParameters(
        context: SkillDecisionContext,
    ): List<String> =
        listOfNotNull(
            resolveRememberedCanonicalTarget(context)?.let { "target=$it" },
            context.memoryContext.contacts.takeIf { it.isNotEmpty() }?.let { "contacts=${it.size}" },
            context.memoryContext.locations.takeIf { it.isNotEmpty() }?.let { "locations=${it.size}" },
            context.taskPlanState?.currentStage?.takeIf { it.isNotBlank() }?.let { "stage=$it" },
        )

    private fun resolveRememberedCanonicalTarget(
        context: SkillDecisionContext,
    ): String? {
        val constraints = TaskIntentParser.parse(context.task)
        return when (constraints.intentType) {
            TaskIntentType.MESSAGING ->
                resolveRememberedEntityName(
                    query = constraints.recipientName ?: constraints.entryQuery,
                    entities = parseRememberedContacts(context.memoryContext),
                )

            TaskIntentType.NAVIGATION ->
                resolveRememberedEntityName(
                    query = constraints.destination ?: constraints.entryQuery,
                    entities = parseRememberedLocations(context.memoryContext),
                )

            else -> null
        }
    }
}

private fun parseRememberedContacts(
    memoryContext: PlanningMemoryContext,
): List<RememberedEntity> =
    memoryContext.contacts.mapNotNull { line ->
        val primary = line.substringBefore(" | ").trim()
        val name = primary.substringBefore(" (").trim()
        if (name.isBlank()) {
            return@mapNotNull null
        }
        val aliases =
            Regex("""别名=([^)|]+)""")
                .find(primary)
                ?.groupValues
                ?.getOrNull(1)
                ?.split("/")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        RememberedEntity(name = name, aliases = aliases)
    }

private fun parseRememberedLocations(
    memoryContext: PlanningMemoryContext,
): List<RememberedEntity> =
    memoryContext.locations.mapNotNull { line ->
        val name = line.substringBefore(" | ").trim()
        name.takeIf { it.isNotBlank() }?.let { RememberedEntity(name = it) }
    }

private fun resolveRememberedEntityName(
    query: String,
    entities: List<RememberedEntity>,
): String? {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return null
    return entities
        .map { entity -> entity to scoreRememberedEntity(entity, normalizedQuery) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
        ?.name
        ?.takeIf { it.isNotBlank() && !it.equals(normalizedQuery, ignoreCase = true) }
}

private fun shouldRewriteText(
    context: SkillDecisionContext,
    currentText: String,
    rememberedTarget: String,
): Boolean {
    if (currentText.isBlank() || currentText == rememberedTarget) return false
    val stage = context.taskPlanState?.currentStage.orEmpty()
    return stage in setOf("find_conversation", "enter_destination", "enter_query") ||
        currentText.length <= rememberedTarget.length + 4
}

private fun findRememberedCandidate(
    context: SkillDecisionContext,
    rememberedTarget: String,
): UiElementObservation? =
    context.observation.elements
        .filter { it.clickable && it.enabled }
        .maxByOrNull { element ->
            scoreRememberedCandidate(element, rememberedTarget, context.memoryContext)
        }
        ?.takeIf { scoreRememberedCandidate(it, rememberedTarget, context.memoryContext) >= 80 }

private fun scoreRememberedCandidate(
    element: UiElementObservation,
    rememberedTarget: String,
    memoryContext: PlanningMemoryContext,
): Int {
    val semantic = listOf(element.text, element.viewId, element.className).joinToString(" ")
    var score = 0
    if (element.text.equals(rememberedTarget, ignoreCase = true)) {
        score += 180
    } else if (element.text.contains(rememberedTarget, ignoreCase = true)) {
        score += 130
    } else if (semantic.contains(rememberedTarget, ignoreCase = true)) {
        score += 90
    }
    val contactAliases =
        parseRememberedContacts(memoryContext)
            .firstOrNull { it.name == rememberedTarget }
            ?.aliases
            .orEmpty()
    val locationAliases =
        parseRememberedLocations(memoryContext)
            .firstOrNull { it.name == rememberedTarget }
            ?.aliases
            .orEmpty()
    (contactAliases + locationAliases).forEach { alias ->
        when {
            element.text.equals(alias, ignoreCase = true) -> score += 120
            element.text.contains(alias, ignoreCase = true) -> score += 80
            semantic.contains(alias, ignoreCase = true) -> score += 56
        }
    }
    val overlap = sharedRememberedCharacters(semantic, rememberedTarget)
    if (overlap >= 2) {
        score += overlap * 10
    }
    return score
}

private fun scoreRememberedEntity(
    entity: RememberedEntity,
    query: String,
): Int {
    var score = 0
    if (entity.name.equals(query, ignoreCase = true)) {
        score += 180
    } else if (entity.name.contains(query, ignoreCase = true) || query.contains(entity.name, ignoreCase = true)) {
        score += 120
    }
    entity.aliases.forEach { alias ->
        when {
            alias.equals(query, ignoreCase = true) -> score += 220
            alias.contains(query, ignoreCase = true) || query.contains(alias, ignoreCase = true) -> score += 140
        }
    }
    val overlap = sharedRememberedCharacters(entity.name + entity.aliases.joinToString(""), query)
    if (overlap >= 2) {
        score += overlap * 8
    }
    return score
}

private fun sharedRememberedCharacters(
    text: String,
    target: String,
): Int {
    val remaining = target.toMutableList()
    var count = 0
    text.forEach { char ->
        val index = remaining.indexOf(char)
        if (index >= 0) {
            remaining.removeAt(index)
            count += 1
        }
    }
    return count
}
