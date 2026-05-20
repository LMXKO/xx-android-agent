package com.lmx.xiaoxuanagent.agent

internal object AgentSemanticActionComposer {
    fun entryAlignmentAction(
        targetText: String,
        stage: String,
        observation: ScreenObservation,
        preferPopulate: Boolean = true,
    ): AgentAction? {
        val normalizedTarget = targetText.trim()
        if (normalizedTarget.isBlank()) return null
        val semantics = observationSemantics(observation)
        if (
            preferPopulate &&
            observation.primaryEditableId != null &&
            !semantics.contains(normalizedTarget, ignoreCase = true)
        ) {
            return AgentAction.PopulatePrimaryInput(normalizedTarget)
        }
        if (observation.primaryEditableId != null && observation.focusedElementId != observation.primaryEditableId) {
            return AgentAction.FocusPrimaryInput
        }
        return if (observation.elements.any { it.clickable && it.enabled }) {
            AgentAction.OpenBestCandidate(
                targetText = normalizedTarget,
                roleHint = roleHintForStage(stage),
            )
        } else {
            null
        }
    }

    fun continuationAction(
        stage: String,
        task: String,
        observation: ScreenObservation,
        preferPopulate: Boolean = true,
    ): AgentAction? {
        val constraints = TaskIntentParser.parse(task)
        return when (stage) {
            "submit_query" -> {
                if (observation.primaryEditableId != null && observation.focusedElementId != observation.primaryEditableId) {
                    AgentAction.FocusPrimaryInput
                } else {
                    AgentAction.SubmitPrimaryAction(submitHintForStage(stage, constraints))
                }
            }

            "confirm_send" -> {
                val messageBody = constraints.messageBody.orEmpty().trim()
                if (
                    preferPopulate &&
                    messageBody.isNotBlank() &&
                    observation.primaryEditableId != null &&
                    !observationSemantics(observation).contains(messageBody, ignoreCase = true)
                ) {
                    AgentAction.PopulatePrimaryInput(messageBody)
                } else {
                    AgentAction.SubmitPrimaryAction("发送")
                }
            }

            "confirm_route" -> AgentAction.SubmitPrimaryAction("导航")
            "browse_candidates" -> progressAction(
                targetText = preferredTargetForTask(constraints),
                roleHint = "entry",
                observation = observation,
            )

            "inspect_information", "summarize" -> progressAction(
                targetText = constraints.objectiveHint.orEmpty(),
                roleHint = detailRoleHint(task, constraints),
                observation = observation,
            )

            else -> null
        }
    }

    fun progressAction(
        targetText: String = "",
        roleHint: String = "",
        observation: ScreenObservation,
        fallbackDirection: String = "down",
    ): AgentAction =
        when {
            observation.elements.any { it.clickable && it.enabled } ->
                AgentAction.OpenBestCandidate(
                    targetText = targetText.trim(),
                    roleHint = roleHint.trim(),
                )

            observation.defaultScrollableId != null -> AgentAction.ScrollForMore(fallbackDirection)
            else -> AgentAction.Wait
        }

    fun backtrackAction(
        observation: ScreenObservation,
        preferScroll: Boolean = false,
        hint: String = "",
    ): AgentAction =
        when {
            preferScroll && observation.defaultScrollableId != null -> AgentAction.ScrollForMore("up")
            else -> AgentAction.NavigateBack(preferScroll = preferScroll, hint = hint)
        }

    fun roleHintForStage(
        stage: String,
    ): String =
        when (stage.trim()) {
            "find_conversation" -> "conversation"
            "enter_query", "enter_destination" -> "entry"
            "confirm_route" -> "route"
            "inspect_information", "summarize" -> "detail"
            "confirm_send", "submit_query" -> "submit"
            else -> "entry"
        }

    fun observationSemantics(
        observation: ScreenObservation,
    ): String =
        buildList {
            add(observation.pageState)
            add(observation.screenSummary)
            addAll(observation.topTexts)
            addAll(observation.interruptiveHints.map { it.text })
            addAll(observation.elements.mapNotNull { it.text.takeIf(String::isNotBlank) })
        }.joinToString(" ")

    private fun submitHintForStage(
        stage: String,
        constraints: TaskConstraints,
    ): String =
        when (stage) {
            "submit_query" ->
                constraints.destination
                    ?.takeIf { it.isNotBlank() }
                    ?: constraints.recipientName?.takeIf { it.isNotBlank() }
                    ?: constraints.entryQuery

            "confirm_send" -> "发送"
            "confirm_route" -> "导航"
            else -> constraints.entryQuery
        }

    private fun preferredTargetForTask(
        constraints: TaskConstraints,
    ): String =
        listOf(
            constraints.entryQuery,
            constraints.destination.orEmpty(),
            constraints.recipientName.orEmpty(),
            constraints.objectiveHint.orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun detailRoleHint(
        task: String,
        constraints: TaskConstraints,
    ): String =
        when {
            task.contains("评论") || task.contains("评价") -> "comment"
            task.contains("路线") || task.contains("导航") -> "route"
            constraints.intentType == TaskIntentType.CONTENT -> "detail"
            else -> "detail"
        }
}
