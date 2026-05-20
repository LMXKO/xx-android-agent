package com.lmx.xiaoxuanagent.assistantos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTipSchedulerTest {
    @Test
    fun `merge ledger keeps dismissed tip during cooldown`() {
        val now = 10_000L
        val previous =
            listOf(
                AssistantTipCard(
                    id = "tip_a",
                    dedupeKey = "provider_health_attention",
                    title = "旧标题",
                    summary = "旧摘要",
                    actionLabel = "旧动作",
                    priority = 10,
                    source = "provider",
                    status = "dismissed",
                    nextEligibleAtMs = now + 60_000L,
                    shownCount = 2,
                    createdAtMs = 1_000L,
                    updatedAtMs = 2_000L,
                ),
            )

        val merged =
            AssistantTipScheduler.mergeLedger(
                previous = previous,
                candidates =
                    listOf(
                        AssistantTipCandidate(
                            dedupeKey = "provider_health_attention",
                            title = "新标题",
                            summary = "新摘要",
                            actionLabel = "新动作",
                            priority = 88,
                            source = "provider",
                            eligibilityReason = "provider_health_degraded",
                            cooldownSessions = 4,
                        ),
                    ),
                now = now,
            )

        assertEquals(1, merged.size)
        val tip = merged.first()
        assertEquals("dismissed", tip.status)
        assertEquals("新标题", tip.title)
        assertEquals("provider_health_degraded", tip.eligibilityReason)
        assertEquals(4, tip.cooldownSessions)
        assertEquals(2, tip.shownCount)
    }

    @Test
    fun `present increments shown count only for visible tips`() {
        val now = 20_000L
        val presentation =
            AssistantTipScheduler.present(
                ledger =
                    listOf(
                        AssistantTipCard(
                            id = "tip_high",
                            dedupeKey = "high",
                            title = "高优先级",
                            summary = "summary",
                            actionLabel = "action",
                            priority = 100,
                            source = "ops",
                            status = "active",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                        AssistantTipCard(
                            id = "tip_low",
                            dedupeKey = "low",
                            title = "低优先级",
                            summary = "summary",
                            actionLabel = "action",
                            priority = 10,
                            source = "ops",
                            status = "active",
                            nextEligibleAtMs = now + 1_000L,
                            createdAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                        ),
                    ),
                now = now,
                maxVisible = 2,
            )

        assertEquals(1, presentation.visible.size)
        assertEquals("tip_high", presentation.visible.first().id)
        assertEquals(1, presentation.visible.first().shownCount)
        assertTrue(presentation.ledger.first { it.id == "tip_low" }.shownCount == 0)
    }
}
