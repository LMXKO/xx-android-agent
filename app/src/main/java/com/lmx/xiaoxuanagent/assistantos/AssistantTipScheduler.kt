package com.lmx.xiaoxuanagent.assistantos

internal data class AssistantTipPresentation(
    val visible: List<AssistantTipCard>,
    val ledger: List<AssistantTipCard>,
)

internal object AssistantTipScheduler {
    fun ledgerForProductShell(
        snapshot: AssistantProductShellSnapshot,
    ): List<AssistantTipCard> = snapshot.tipLedger.ifEmpty { snapshot.tips }

    fun normalizeAction(
        action: String,
    ): String =
        when (action.trim().lowercase()) {
            "dismiss",
            "hide",
            "skip",
            -> "dismiss"

            "complete",
            "done",
            "resolve",
            -> "complete"

            else -> "reset"
        }

    fun cooldownFor(
        source: String,
    ): Long =
        when (source) {
            "swarm" -> 30L * 60L * 1000L
            "memory" -> 4L * 60L * 60L * 1000L
            "provider" -> 6L * 60L * 60L * 1000L
            "ops" -> 4L * 60L * 60L * 1000L
            else -> 12L * 60L * 60L * 1000L
        }

    fun compactLedger(
        ledger: List<AssistantTipCard>,
        maxLedgerSize: Int,
    ): List<AssistantTipCard> =
        ledger
            .sortedByDescending { it.updatedAtMs }
            .take(maxLedgerSize)

    fun mergeLedger(
        previous: List<AssistantTipCard>,
        candidates: List<AssistantTipCandidate>,
        now: Long,
    ): List<AssistantTipCard> {
        val previousByKey = previous.associateBy { it.dedupeKey.ifBlank { it.id } }
        val candidateKeys = candidates.map { it.dedupeKey }.toSet()
        val merged = mutableListOf<AssistantTipCard>()
        candidates.forEach { candidate ->
            val previousTip = previousByKey[candidate.dedupeKey]
            val refreshed =
                when {
                    previousTip == null ->
                        AssistantTipCard(
                            id = productTipIdFor(candidate.dedupeKey),
                            dedupeKey = candidate.dedupeKey,
                            title = candidate.title,
                            summary = candidate.summary,
                            actionLabel = candidate.actionLabel,
                            recommendedPage = candidate.recommendedPage,
                            priority = candidate.priority,
                            source = candidate.source,
                            audience = candidate.audience,
                            status = "active",
                            createdAtMs = now,
                            updatedAtMs = now,
                            eligibilityReason = candidate.eligibilityReason,
                            cooldownSessions = candidate.cooldownSessions,
                        )

                    previousTip.status == "dismissed" && previousTip.nextEligibleAtMs > now ->
                        previousTip.copy(
                            title = candidate.title,
                            summary = candidate.summary,
                            actionLabel = candidate.actionLabel,
                            recommendedPage = candidate.recommendedPage,
                            priority = candidate.priority,
                            source = candidate.source,
                            audience = candidate.audience,
                            updatedAtMs = now,
                            eligibilityReason = candidate.eligibilityReason,
                            cooldownSessions = candidate.cooldownSessions,
                        )

                    else ->
                        previousTip.copy(
                            title = candidate.title,
                            summary = candidate.summary,
                            actionLabel = candidate.actionLabel,
                            recommendedPage = candidate.recommendedPage,
                            priority = candidate.priority,
                            source = candidate.source,
                            audience = candidate.audience,
                            status = if (previousTip.status == "completed") "completed" else "active",
                            updatedAtMs = now,
                            eligibilityReason = candidate.eligibilityReason,
                            cooldownSessions = candidate.cooldownSessions,
                        )
                }
            merged += refreshed
        }
        previous
            .filterNot { it.dedupeKey in candidateKeys }
            .sortedByDescending { it.updatedAtMs }
            .take(8)
            .forEach(merged::add)
        return merged
    }

    fun present(
        ledger: List<AssistantTipCard>,
        now: Long,
        maxVisible: Int,
    ): AssistantTipPresentation {
        val visibleIds =
            ledger
                .filter { it.status == "active" && it.nextEligibleAtMs <= now }
                .sortedWith(
                    compareByDescending<AssistantTipCard> { effectivePriority(it, now) }
                        .thenBy { it.shownCount }
                        .thenByDescending { it.updatedAtMs },
                )
                .take(maxVisible)
                .map { it.id }
                .toSet()
        val nextLedger =
            ledger.map { tip ->
                if (tip.id !in visibleIds) {
                    tip
                } else {
                    tip.copy(
                        shownCount = tip.shownCount + 1,
                        lastShownAtMs = now,
                        lastPresentedAtMs = now,
                        updatedAtMs = now,
                    )
                }
            }
        return AssistantTipPresentation(
            visible =
                nextLedger
                    .filter { it.id in visibleIds }
                    .sortedByDescending { it.priority },
            ledger = nextLedger,
        )
    }

    private fun effectivePriority(
        tip: AssistantTipCard,
        now: Long,
    ): Int {
        val freshnessBoost =
            when {
                tip.lastPresentedAtMs <= 0L -> 6
                now - tip.lastPresentedAtMs >= 6L * 60L * 60L * 1000L -> 4
                else -> 0
            }
        val repetitionPenalty = tip.shownCount * 3
        val audienceBoost =
            when (tip.audience) {
                "operator" -> 3
                "personal" -> 1
                else -> 0
            }
        return tip.priority + freshnessBoost + audienceBoost - repetitionPenalty
    }
}

private fun productTipIdFor(
    dedupeKey: String,
): String = "tip_${dedupeKey.hashCode().toString().replace('-', 'n')}"
