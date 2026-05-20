package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProductShellAdaptiveTest {
    @Test
    fun `onboarding prioritizes blocking high priority steps`() {
        val now = 123L
        val onboarding =
            mergeProductOnboardingState(
                previous = AssistantOnboardingState(),
                candidates =
                    listOf(
                        AssistantOnboardingStepCandidate(
                            id = "calendar",
                            title = "日历",
                            summary = "calendar",
                            actionLabel = "接入",
                            priority = 50,
                            blocking = false,
                            requirementMet = false,
                        ),
                        AssistantOnboardingStepCandidate(
                            id = "accessibility",
                            title = "无障碍",
                            summary = "a11y",
                            actionLabel = "开启",
                            priority = 100,
                            blocking = true,
                            requirementMet = false,
                        ),
                    ),
                now = now,
            )

        assertEquals("无障碍", onboarding.steps.first().title)
        assertTrue(onboarding.summary.contains("阻塞主链"))
    }

    @Test
    fun `tip scheduler rotates fresher unseen tip ahead of repeated one`() {
        val now = 10_000L
        val presentation =
            AssistantTipScheduler.present(
                ledger =
                    listOf(
                        AssistantTipCard(
                            id = "tip_old",
                            dedupeKey = "tip_old",
                            title = "旧 tip",
                            summary = "old",
                            actionLabel = "查看",
                            priority = 80,
                            shownCount = 4,
                            lastPresentedAtMs = now - 1_000L,
                            updatedAtMs = now - 1_000L,
                        ),
                        AssistantTipCard(
                            id = "tip_new",
                            dedupeKey = "tip_new",
                            title = "新 tip",
                            summary = "new",
                            actionLabel = "查看",
                            priority = 78,
                            shownCount = 0,
                            lastPresentedAtMs = 0L,
                            updatedAtMs = now,
                        ),
                    ),
                now = now,
                maxVisible = 1,
            )

        assertEquals("tip_new", presentation.visible.first().id)
    }

    @Test
    fun `adaptive policy suppresses repeatedly dismissed tip source and learns entry surface`() {
        val snapshot =
            AssistantProductShellSnapshot(
                tipLedger =
                    listOf(
                        AssistantTipCard(id = "tip_1", title = "provider 1", summary = "", actionLabel = "", source = "provider", audience = "operator", status = "dismissed"),
                        AssistantTipCard(id = "tip_2", title = "provider 2", summary = "", actionLabel = "", source = "provider", audience = "operator", status = "dismissed"),
                        AssistantTipCard(id = "tip_3", title = "memory", summary = "", actionLabel = "", source = "memory", audience = "personal", status = "completed"),
                    ),
            )
        val analytics =
            AssistantAnalyticsSnapshot(
                events =
                    listOf(
                        AssistantAnalyticsEvent("e1", "assistant_entry", "assistant_os", metadata = mapOf("surface" to "widget")),
                        AssistantAnalyticsEvent("e2", "assistant_entry", "assistant_os", metadata = mapOf("surface" to "widget")),
                        AssistantAnalyticsEvent("e3", "assistant_entry", "assistant_os", metadata = mapOf("surface" to "notification")),
                    ),
            )

        val adaptive =
            AssistantAdaptivePolicyStore.derive(
                productShell = snapshot,
                analytics = analytics,
                userModel =
                    PersonalAssistantUserModelSnapshot(
                        preferredThemes = listOf("关系跟进", "安全确认"),
                    ),
            )

        assertEquals("widget", adaptive.preferredEntrySurfaces.first())
        assertEquals("生活跟进", adaptive.preferredFocusTheme)
        assertTrue("provider" in adaptive.suppressedTipSources)
        assertFalse(adaptive.summary.isBlank())
    }
}
