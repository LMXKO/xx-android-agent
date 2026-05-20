package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.memory.RouteMemoryHint

internal data class MemoryPreferredCandidate(
    val candidate: LlmRouteCandidate,
    val hint: RouteMemoryHint,
)

internal object RouteResolutionPolicy {
    private const val MEMORY_FALLBACK_SCORE_THRESHOLD = 55
    private const val MEMORY_BIAS_SCORE_THRESHOLD = 72
    private const val LOW_CONFIDENCE_THRESHOLD = 0.58
    private const val MEMORY_BIAS_MIN_ADVANTAGE = 18

    fun selectMemoryPreferredCandidate(
        candidates: List<LlmRouteCandidate>,
        routeMemoryHints: List<RouteMemoryHint>,
    ): MemoryPreferredCandidate? {
        val bestHint = routeMemoryHints.firstOrNull { it.score >= MEMORY_FALLBACK_SCORE_THRESHOLD } ?: return null
        val candidate =
            candidates.firstOrNull { it.profileId == bestHint.profileId && it.installed }
                ?: return null
        return MemoryPreferredCandidate(candidate = candidate, hint = bestHint)
    }

    fun maybeApplyMemoryBias(
        choice: LlmRouteChoice,
        selectedCandidate: LlmRouteCandidate?,
        preferredMemoryCandidate: MemoryPreferredCandidate?,
    ): MemoryPreferredCandidate? {
        val memoryCandidate = preferredMemoryCandidate ?: return null
        val currentCandidate = selectedCandidate ?: return null
        if (!currentCandidate.installed || !memoryCandidate.candidate.installed) return null
        if (currentCandidate.profileId == memoryCandidate.candidate.profileId) return null
        val selectedCategory = currentCandidate.category ?: return null
        if (selectedCategory != memoryCandidate.candidate.category) return null
        val modelScore = (choice.confidence * 100).toInt()
        if (choice.confidence > LOW_CONFIDENCE_THRESHOLD) return null
        if (memoryCandidate.hint.score < MEMORY_BIAS_SCORE_THRESHOLD) return null
        if (memoryCandidate.hint.score < modelScore + MEMORY_BIAS_MIN_ADVANTAGE) return null
        return memoryCandidate
    }

    fun findInstalledFallbackCandidate(
        selectedCandidate: LlmRouteCandidate?,
        candidates: List<LlmRouteCandidate>,
        preferredMemoryCandidate: MemoryPreferredCandidate?,
    ): LlmRouteCandidate? {
        val category = selectedCandidate?.category ?: return null
        preferredMemoryCandidate?.candidate
            ?.takeIf { candidate ->
                candidate.installed &&
                    candidate.profileId != selectedCandidate.profileId &&
                    candidate.category == category
            }?.let { return it }

        return candidates.firstOrNull { candidate ->
            candidate.installed &&
                candidate.profileId != selectedCandidate.profileId &&
                candidate.category == category
        }
    }
}
