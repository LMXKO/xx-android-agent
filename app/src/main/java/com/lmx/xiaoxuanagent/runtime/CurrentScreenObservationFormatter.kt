package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.IndexedScreenObservation
import com.lmx.xiaoxuanagent.agent.UiElementObservation

internal object CurrentScreenObservationFormatter {
    fun format(
        observation: IndexedScreenObservation,
        elementLimit: Int = 10,
    ): List<String> {
        val screen = observation.observation
        val visibleElements = screen.elements.take(elementLimit.coerceIn(1, 20))
        return buildList {
            add("package=${screen.packageName.ifBlank { "-" }}")
            add("page_state=${screen.pageState}")
            add("signature=${screen.signature.ifBlank { "-" }}")
            add("summary=${screen.screenSummary.ifBlank { "-" }}")
            add("top_texts=${screen.topTexts.joinToString(" / ").ifBlank { "-" }}")
            add("primary_editable=${screen.primaryEditableId ?: "-"}")
            add("focused_element=${screen.focusedElementId ?: "-"}")
            add("scrollable=${screen.defaultScrollableId ?: "-"}")
            add("interrupt_action=${screen.primaryInterruptActionId ?: "-"}")
            if (screen.interruptiveHints.isNotEmpty()) {
                screen.interruptiveHints.take(4).forEach { hint ->
                    add("interrupt | ${hint.elementId.ifBlank { "-" }} | ${hint.text.ifBlank { "-" }} | ${hint.reason.ifBlank { "-" }}")
                }
            }
            if (screen.structureHints.isNotEmpty()) {
                screen.structureHints.take(6).forEach { hint ->
                    add("structure | $hint")
                }
            }
            add("elements=${screen.elements.size}")
            visibleElements.forEach { element ->
                add(formatElement(element))
            }
            add("visual_summary=${observation.visualContext.summary.ifBlank { "-" }}")
            if (observation.visualContext.visualHints.isNotEmpty()) {
                add("visual_hints=${observation.visualContext.visualHints.take(4).joinToString(" / ")}")
            }
            if (observation.visualContext.parserRegions.isNotEmpty()) {
                observation.visualContext.parserRegions.take(4).forEach { region ->
                    add("parser | ${region.type} | ${region.label.ifBlank { "-" }} | ${region.bounds} | ${region.source.ifBlank { "-" }}")
                }
            }
            if (observation.visualContext.visualObjects.isNotEmpty()) {
                observation.visualContext.visualObjects.take(4).forEach { visual ->
                    add("visual | ${visual.type} | ${visual.label.ifBlank { "-" }} | ${visual.bounds} | ${visual.source.ifBlank { "-" }}")
                }
            }
            addAll(buildActionHints(screen))
        }
    }

    fun recommendedCommands(
        sessionId: String = "",
    ): List<String> =
        buildList {
            add("/screen")
            if (sessionId.isNotBlank()) {
                add("/viewer --session-id $sessionId")
                add("/inspect-replay-breakpoint --session-id $sessionId --command-index 0")
            }
            add("/help /screen")
        }

    private fun formatElement(element: UiElementObservation): String =
        buildString {
            append("element ").append(element.id)
            append(" | ").append(element.text.ifBlank { element.accessibilityLabel.ifBlank { element.viewId.ifBlank { element.className.ifBlank { "-" } } } })
            append(" | clickable=").append(element.clickable)
            append(" | editable=").append(element.editable)
            append(" | scrollable=").append(element.scrollable)
            append(" | enabled=").append(element.enabled)
            append(" | bounds=").append(element.bounds.ifBlank { "-" })
            if (element.roleHint.isNotBlank()) append(" | role=").append(element.roleHint)
            if (element.collectionPosition.isNotBlank()) append(" | pos=").append(element.collectionPosition)
            if (element.source.isNotBlank()) append(" | source=").append(element.source)
        }

    private fun buildActionHints(
        screen: com.lmx.xiaoxuanagent.agent.ScreenObservation,
    ): List<String> =
        buildList {
            if (!screen.primaryInterruptActionId.isNullOrBlank()) {
                add("action_hint=priority interrupt button ${screen.primaryInterruptActionId}")
            }
            if (!screen.primaryEditableId.isNullOrBlank()) {
                add("action_hint=primary input ${screen.primaryEditableId}")
            }
            if (!screen.defaultScrollableId.isNullOrBlank()) {
                add("action_hint=scrollable container ${screen.defaultScrollableId}")
            }
            if (screen.interruptiveHints.isNotEmpty()) {
                add("action_hint=interruptive hints present")
            }
        }
}
