package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentSemanticActionComposer
import com.lmx.xiaoxuanagent.agent.buildResumeCorrectionDirective
import com.lmx.xiaoxuanagent.agent.HumanCorrectionRecoveryIntent
import com.lmx.xiaoxuanagent.agent.ResumeCorrectionDirective
import com.lmx.xiaoxuanagent.agent.TaskIntentParser
import com.lmx.xiaoxuanagent.agent.UiElementObservation

internal data class RememberedCorrectionTemplate(
    val templateType: String,
    val argument: String,
)

internal fun resolvePreferredHumanCorrectionRecoveryAction(
    context: SkillDecisionContext,
    blockedKeywords: List<String>,
): AgentAction? {
    val stage = context.taskPlanState?.currentStage.orEmpty()
    val constraints = TaskIntentParser.parse(context.task)
    if (stage in setOf("find_conversation", "enter_query", "enter_destination")) {
        val target =
            when {
                stage == "find_conversation" -> constraints.recipientName.orEmpty()
                stage == "enter_destination" -> constraints.destination.orEmpty().ifBlank { constraints.entryQuery }
                else -> constraints.entryQuery
            }
        AgentSemanticActionComposer.entryAlignmentAction(
            targetText = target,
            stage = stage,
            observation = context.observation,
        )?.let { return it }
        val focusKeywordCandidates =
            listOf(
                constraints.recipientName.orEmpty(),
                constraints.destination.orEmpty(),
                constraints.entryQuery,
                constraints.normalizedGoal,
            ).filter { it.isNotBlank() }
        selectPreferredHumanCorrectionElement(
            context = context,
            blockedKeywords = blockedKeywords,
            focusKeywordCandidates = focusKeywordCandidates,
        )?.let {
            return AgentAction.OpenBestCandidate(
                targetText = it.text.ifBlank { focusKeywordCandidates.firstOrNull().orEmpty() },
                roleHint = AgentSemanticActionComposer.roleHintForStage(stage),
            )
        }
    }

    if (blockedKeywords.isNotEmpty()) {
        selectFirstUnblockedHumanCorrectionElement(
            context = context,
            blockedKeywords = blockedKeywords,
        )?.let {
            return AgentAction.OpenBestCandidate(
                targetText = it.text,
                roleHint = AgentSemanticActionComposer.roleHintForStage(stage),
            )
        }
    }
    return null
}

internal fun resolveRememberedHumanCorrectionAction(
    context: SkillDecisionContext,
): AgentAction? {
    val directive = resolveRememberedHumanCorrectionDirective(context) ?: return null
    return resolveHumanCorrectionActionFromDirective(
        context = context,
        directive = directive,
        blockedKeywords = rememberedBlockedTargetKeywords(context),
    )
}

internal fun resolveDirectHumanCorrectionAction(
    context: SkillDecisionContext,
    directive: ResumeCorrectionDirective,
    blockedKeywords: List<String>,
): AgentAction? =
    resolveHumanCorrectionActionFromDirective(
        context = context,
        directive = directive,
        blockedKeywords = blockedKeywords,
    ).takeUnless {
        directive.intent == HumanCorrectionRecoveryIntent.NONE &&
            blockedKeywords.isEmpty()
    }

internal fun rememberedHumanCorrectionTemplates(
    context: SkillDecisionContext,
): List<RememberedCorrectionTemplate> =
    context.memoryContext.correctionTemplates.mapNotNull(::parseRememberedCorrectionTemplate)

internal fun rememberedBlockedTargetKeywords(
    context: SkillDecisionContext,
): List<String> =
    rememberedHumanCorrectionTemplates(context)
        .filter { it.templateType == "avoid_target" && it.argument.isNotBlank() }
        .map { it.argument }
        .distinct()

internal fun parseRememberedCorrectionTemplate(
    memory: String,
): RememberedCorrectionTemplate? {
    val header = memory.substringBefore(" | ").trim()
    val templateType = header.substringBefore(":").trim()
    if (templateType.isBlank()) return null
    val argument =
        header.substringAfter(":", "").let { value ->
            if (value == header) "" else value.trim()
        }
    return RememberedCorrectionTemplate(
        templateType = templateType,
        argument = argument,
    )
}

internal fun resolveRememberedHumanCorrectionDirective(
    context: SkillDecisionContext,
): ResumeCorrectionDirective? {
    val templates = rememberedHumanCorrectionTemplates(context)
    val lastAction = context.history.lastOrNull()?.action.orEmpty()
    val blockedKeywords = rememberedBlockedTargetKeywords(context)
    val intent =
        when {
            templates.any { it.templateType == "backtrack" } && lastAction.startsWith("click(") ->
                HumanCorrectionRecoveryIntent.BACKTRACK

            templates.any { it.templateType == "overscroll_up" } &&
                lastAction.startsWith("scroll(") &&
                lastAction.contains(",down)") ->
                HumanCorrectionRecoveryIntent.OVERSCROLL_UP

            templates.any { it.templateType == "refocus_entry" } ->
                HumanCorrectionRecoveryIntent.REFOCUS_ENTRY

            blockedKeywords.isNotEmpty() ->
                HumanCorrectionRecoveryIntent.NONE

            else -> return null
        }
    return buildResumeCorrectionDirective(
        intent = intent,
        blockedTargets = blockedKeywords,
    )
}

internal fun resolveHumanCorrectionActionFromDirective(
    context: SkillDecisionContext,
    directive: ResumeCorrectionDirective,
    blockedKeywords: List<String>,
): AgentAction? =
    when (directive.intent) {
        HumanCorrectionRecoveryIntent.BACKTRACK ->
            AgentSemanticActionComposer.backtrackAction(
                observation = context.observation,
                hint = "human_correction",
            )
        HumanCorrectionRecoveryIntent.OVERSCROLL_UP ->
            AgentAction.ScrollForMore("up")
        HumanCorrectionRecoveryIntent.SCROLL_DOWN ->
            context.observation.defaultScrollableId?.let { AgentAction.ScrollForMore("down") }
        HumanCorrectionRecoveryIntent.REFOCUS_ENTRY,
        HumanCorrectionRecoveryIntent.NONE,
        ->
            resolveConservativeHumanCorrectionRecoveryAction(
                context = context,
                blockedKeywords = blockedKeywords,
            )
    }

internal fun resolveConservativeHumanCorrectionRecoveryAction(
    context: SkillDecisionContext,
    blockedKeywords: List<String>,
): AgentAction =
    resolvePreferredHumanCorrectionRecoveryAction(context, blockedKeywords)
        ?: AgentSemanticActionComposer.backtrackAction(
            observation = context.observation,
            preferScroll = context.observation.defaultScrollableId != null,
            hint = "conservative_human_correction",
        )

private fun selectPreferredHumanCorrectionElement(
    context: SkillDecisionContext,
    blockedKeywords: List<String>,
    focusKeywordCandidates: List<String>,
): UiElementObservation? =
    context.observation.elements
        .asSequence()
        .filter { it.clickable && it.enabled }
        .filterNot { element -> humanCorrectionElementMatchesBlockedKeyword(element, blockedKeywords) }
        .mapNotNull { element ->
            val semantic = humanCorrectionElementSemantic(element)
            val score =
                focusKeywordCandidates.fold(0) { acc, keyword ->
                    when {
                        element.text.equals(keyword, ignoreCase = true) -> acc + 90
                        element.text.contains(keyword, ignoreCase = true) -> acc + 50
                        semantic.contains(keyword, ignoreCase = true) -> acc + 24
                        else -> acc
                    }
                }
            if (score <= 0) null else element to score
        }.sortedByDescending { it.second }
        .map { it.first }
        .firstOrNull()

private fun selectFirstUnblockedHumanCorrectionElement(
    context: SkillDecisionContext,
    blockedKeywords: List<String>,
): UiElementObservation? =
    context.observation.elements
        .asSequence()
        .filter { it.clickable && it.enabled }
        .filterNot { element -> humanCorrectionElementMatchesBlockedKeyword(element, blockedKeywords) }
        .firstOrNull()

private fun humanCorrectionElementMatchesBlockedKeyword(
    element: UiElementObservation,
    blockedKeywords: List<String>,
): Boolean {
    val semantic = humanCorrectionElementSemantic(element)
    return blockedKeywords.any { semantic.contains(it, ignoreCase = true) }
}

private fun humanCorrectionElementSemantic(
    element: UiElementObservation,
): String = listOf(element.text, element.viewId, element.className).joinToString(" ")
