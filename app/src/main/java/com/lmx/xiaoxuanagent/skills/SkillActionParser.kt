package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.AgentSemanticActionResolver
import com.lmx.xiaoxuanagent.agent.ScreenObservation

object SkillActionParser {
    fun parseActionLabel(
        actionLabel: String,
    ): AgentAction? {
        val normalized = actionLabel.trim()
        return when {
            normalized.startsWith("launch_app(") && normalized.endsWith(")") ->
                AgentAction.LaunchApp(normalized.removePrefix("launch_app(").removeSuffix(")"))

            normalized.startsWith("click(") && normalized.endsWith(")") ->
                AgentAction.Click(normalized.removePrefix("click(").removeSuffix(")"))

            normalized.startsWith("set_text(") && normalized.endsWith(")") ->
                AgentAction.SetText(
                    elementId = normalized.removePrefix("set_text(").removeSuffix(")"),
                    text = "",
                )

            normalized.startsWith("long_click(") && normalized.endsWith(")") ->
                AgentAction.LongClick(normalized.removePrefix("long_click(").removeSuffix(")"))

            normalized.startsWith("copy_text(") && normalized.endsWith(")") ->
                AgentAction.CopyText(normalized.removePrefix("copy_text(").removeSuffix(")"))

            normalized.startsWith("paste_clipboard(") && normalized.endsWith(")") ->
                AgentAction.PasteClipboard(normalized.removePrefix("paste_clipboard(").removeSuffix(")"))

            normalized.startsWith("scroll(") && normalized.endsWith(")") ->
                parseScrollAction(normalized)

            normalized == AgentAction.Back.label -> AgentAction.Back
            normalized == AgentAction.Home.label -> AgentAction.Home
            normalized == AgentAction.Notifications.label -> AgentAction.Notifications
            normalized == AgentAction.QuickSettings.label -> AgentAction.QuickSettings
            normalized == AgentAction.Recents.label -> AgentAction.Recents
            normalized == AgentAction.PressEnter.label -> AgentAction.PressEnter
            normalized == AgentAction.FocusPrimaryInput.label -> AgentAction.FocusPrimaryInput
            normalized == AgentAction.PopulatePrimaryInput("").label -> AgentAction.PopulatePrimaryInput("")
            normalized.startsWith("submit_primary_action(") && normalized.endsWith(")") ->
                AgentAction.SubmitPrimaryAction(normalized.removePrefix("submit_primary_action(").removeSuffix(")"))

            normalized.startsWith("dismiss_interrupt(") && normalized.endsWith(")") ->
                AgentAction.DismissInterrupt(normalized.removePrefix("dismiss_interrupt(").removeSuffix(")"))

            normalized.startsWith("open_best_candidate(") && normalized.endsWith(")") ->
                parseOpenBestCandidate(normalized)

            normalized.startsWith("scroll_for_more(") && normalized.endsWith(")") ->
                AgentAction.ScrollForMore(
                    normalized.removePrefix("scroll_for_more(").removeSuffix(")").ifBlank { "down" },
                )

            normalized.startsWith("return_to_target_app(") && normalized.endsWith(")") ->
                AgentAction.ReturnToTargetApp(
                    normalized.removePrefix("return_to_target_app(").removeSuffix(")"),
                )

            normalized.startsWith("navigate_back(") && normalized.endsWith(")") ->
                parseNavigateBack(normalized)

            normalized == AgentAction.Wait.label -> AgentAction.Wait
            else -> null
        }
    }

    fun isCompatible(
        action: AgentAction,
        observation: ScreenObservation,
    ): Boolean =
        when (action) {
            is AgentAction.LaunchApp -> action.packageName.isNotBlank() && action.packageName != observation.packageName
            is AgentAction.Click -> observation.elements.any { it.id == action.elementId }
            is AgentAction.SetText ->
                observation.elements.any { it.id == action.elementId } ||
                    (!observation.primaryEditableId.isNullOrBlank() && action.elementId == observation.primaryEditableId)
            is AgentAction.LongClick -> observation.elements.any { it.id == action.elementId }
            is AgentAction.CopyText -> observation.elements.any { it.id == action.elementId }
            is AgentAction.PasteClipboard ->
                observation.elements.any { it.id == action.elementId } ||
                    (!observation.primaryEditableId.isNullOrBlank() && action.elementId == observation.primaryEditableId)
            is AgentAction.Scroll ->
                action.elementId == null ||
                    observation.defaultScrollableId == action.elementId ||
                    observation.elements.any { it.id == action.elementId && it.scrollable }
            AgentAction.FocusPrimaryInput ->
                !observation.primaryEditableId.isNullOrBlank() || observation.elements.any { it.editable && it.enabled }
            is AgentAction.PopulatePrimaryInput ->
                !observation.primaryEditableId.isNullOrBlank() || observation.elements.any { it.editable && it.enabled || it.clickable && it.enabled }
            is AgentAction.SubmitPrimaryAction ->
                !observation.primaryEditableId.isNullOrBlank() ||
                    AgentSemanticActionResolver.resolveSubmitPrimaryAction(
                        IndexedScreenObservation(observation = observation, nodesById = emptyMap()),
                        action,
                    ) != AgentAction.Wait
            is AgentAction.DismissInterrupt ->
                !observation.primaryInterruptActionId.isNullOrBlank() ||
                    observation.interruptiveHints.isNotEmpty()
            is AgentAction.OpenBestCandidate ->
                observation.elements.any { it.clickable && it.enabled }
            is AgentAction.ScrollForMore ->
                !observation.defaultScrollableId.isNullOrBlank() ||
                    observation.elements.any { it.scrollable && it.enabled }
            is AgentAction.ReturnToTargetApp ->
                action.packageName.isNotBlank() && action.packageName != observation.packageName
            is AgentAction.NavigateBack -> true
            AgentAction.Back,
            AgentAction.Home,
            AgentAction.Notifications,
            AgentAction.QuickSettings,
            AgentAction.Recents,
            AgentAction.PressEnter,
            AgentAction.Wait,
            -> true

            else -> false
        }

    private fun parseScrollAction(
        actionLabel: String,
    ): AgentAction.Scroll? {
        val body = actionLabel.removePrefix("scroll(").removeSuffix(")")
        val parts = body.split(",")
        if (parts.size != 2) return null
        val elementId = parts[0].trim().takeIf { it.isNotBlank() && it != "screen" }
        val direction = parts[1].trim().ifBlank { "down" }
        return AgentAction.Scroll(elementId = elementId, direction = direction)
    }

    private fun parseOpenBestCandidate(
        actionLabel: String,
    ): AgentAction.OpenBestCandidate {
        val body = actionLabel.removePrefix("open_best_candidate(").removeSuffix(")")
        val parts =
            body.split(";")
                .mapNotNull { segment ->
                    val tokens = segment.split("=", limit = 2)
                    if (tokens.size != 2) null else tokens[0].trim() to tokens[1].trim()
                }.toMap()
        return AgentAction.OpenBestCandidate(
            targetText = parts["target"].orEmpty(),
            roleHint = parts["role"].orEmpty(),
        )
    }

    private fun parseNavigateBack(
        actionLabel: String,
    ): AgentAction.NavigateBack {
        val body = actionLabel.removePrefix("navigate_back(").removeSuffix(")")
        val parts =
            body.split(";")
                .mapNotNull { segment ->
                    val tokens = segment.split("=", limit = 2)
                    if (tokens.size != 2) null else tokens[0].trim() to tokens[1].trim()
                }.toMap()
        return AgentAction.NavigateBack(
            preferScroll = parts["prefer_scroll"].equals("true", ignoreCase = true),
            hint = parts["hint"].orEmpty(),
        )
    }
}
