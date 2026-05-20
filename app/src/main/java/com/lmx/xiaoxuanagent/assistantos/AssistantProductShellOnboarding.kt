package com.lmx.xiaoxuanagent.assistantos

internal fun rebuildProductOnboardingState(
    steps: List<AssistantOnboardingStepState>,
    now: Long,
): AssistantOnboardingState {
    val completedSteps = steps.filter { it.status == "completed" }.map { it.title }
    val pendingSorted =
        steps
            .filter { it.status == "pending" }
            .sortedWith(
                compareByDescending<AssistantOnboardingStepState> { it.blocking }
                    .thenByDescending { it.priority }
                    .thenBy { it.title },
            )
    val pendingSteps = pendingSorted.map { it.title }
    val blockingCount = pendingSorted.count { it.blocking }
    val status =
        when {
            steps.isEmpty() -> "unknown"
            pendingSteps.isEmpty() -> "ready"
            completedSteps.isEmpty() -> "setup_required"
            else -> "in_progress"
        }
    val summary =
        when {
            steps.isEmpty() -> "product shell 尚未建立 onboarding 视图"
            pendingSteps.isEmpty() -> "assistant OS 基础接入已完成"
            else ->
                buildString {
                    append("还差 ").append(pendingSteps.size).append(" 项基础接入")
                    if (blockingCount > 0) {
                        append("，其中 ").append(blockingCount).append(" 项会阻塞主链")
                    }
                    append("：").append(pendingSteps.joinToString("、").take(80))
                }
        }
    return AssistantOnboardingState(
        status = status,
        summary = summary,
        completedSteps = completedSteps,
        pendingSteps = pendingSteps,
        steps = steps,
        updatedAtMs = now,
    )
}

internal fun normalizeProductOnboardingAction(
    action: String,
): String =
    when (action.trim().lowercase()) {
        "complete",
        "done",
        -> "complete"

        "skip",
        "dismiss",
        -> "skip"

        else -> "reset"
    }

internal fun buildProductOnboardingCandidates(
    assistantSnapshot: AssistantOsSnapshot,
    providerCount: Int,
    notificationProviderReady: Boolean,
    calendarProviderReady: Boolean,
    locationProviderReady: Boolean,
    voiceReady: Boolean,
): List<AssistantOnboardingStepCandidate> =
    AssistantOnboardingRegistry.buildCandidates(
        assistantSnapshot = assistantSnapshot,
        providerCount = providerCount,
        notificationProviderReady = notificationProviderReady,
        calendarProviderReady = calendarProviderReady,
        locationProviderReady = locationProviderReady,
        voiceReady = voiceReady,
    )

internal fun mergeProductOnboardingState(
    previous: AssistantOnboardingState,
    candidates: List<AssistantOnboardingStepCandidate>,
    now: Long,
): AssistantOnboardingState {
    val previousById = previous.steps.associateBy { it.id }
    val steps =
        candidates
            .sortedWith(
                compareByDescending<AssistantOnboardingStepCandidate> { it.blocking }
                    .thenByDescending { it.priority }
                    .thenBy { it.title },
            ).map { candidate ->
            val previousStep = previousById[candidate.id]
            val effectiveStatus =
                when (previousStep?.manualState) {
                    "completed" -> "completed"
                    "skipped" -> "skipped"
                    else -> if (candidate.requirementMet) "completed" else "pending"
                }
            AssistantOnboardingStepState(
                id = candidate.id,
                title = candidate.title,
                summary = candidate.summary,
                actionLabel = candidate.actionLabel,
                category = candidate.category,
                riskLevel = candidate.riskLevel,
                priority = candidate.priority,
                blocking = candidate.blocking,
                status = effectiveStatus,
                requirementMet = candidate.requirementMet,
                manualState = previousStep?.manualState.orEmpty(),
                note = previousStep?.note.orEmpty(),
                updatedAtMs = now,
                completedAtMs =
                    when {
                        effectiveStatus != "completed" -> 0L
                        previousStep?.completedAtMs ?: 0L > 0L -> previousStep?.completedAtMs ?: 0L
                        else -> now
                    },
            )
        }
    return rebuildProductOnboardingState(steps, now)
}
