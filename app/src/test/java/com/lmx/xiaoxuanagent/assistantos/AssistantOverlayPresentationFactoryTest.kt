package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AgentUiStatus
import com.lmx.xiaoxuanagent.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOverlayPresentationFactoryTest {
    @Test
    fun `build shows approval shortcuts when confirmation is pending`() {
        val presentation =
            AssistantOverlayPresentationFactory.build(
                snapshot =
                    AssistantOsSnapshot(
                        activeSession =
                            AssistantActiveSession(
                                sessionId = "session_1",
                                task = "确认是否继续支付",
                                awaitingConfirmation = true,
                                statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.AWAITING_CONFIRMATION).toSnapshot(),
                            ),
                    ),
                productShell = AssistantProductShellSnapshot(),
            )

        assertTrue(presentation.subtitle.contains("确认"))
        assertEquals(
            listOf(
                AssistantShellIntentRouter.ACTION_OPEN,
                AssistantShellIntentRouter.ACTION_APPROVE,
                AssistantShellIntentRouter.ACTION_REJECT,
            ),
            presentation.actions.map { it.action },
        )
    }

    @Test
    fun `build shows resume and return shortcuts for paused active session`() {
        val presentation =
            AssistantOverlayPresentationFactory.build(
                snapshot =
                    AssistantOsSnapshot(
                        activeSession =
                            AssistantActiveSession(
                                sessionId = "session_2",
                                task = "继续处理消息收件箱",
                                resumable = true,
                                targetAppReturnEligible = true,
                                statusSnapshot = AgentUiStatus.resolve(AgentUiStatus.PAUSED).toSnapshot(),
                            ),
                    ),
                productShell =
                    AssistantProductShellSnapshot(
                        personalFocus =
                            AssistantPersonalFocusSnapshot(
                                focusTask = "继续处理消息收件箱",
                            ),
                    ),
            )

        assertEquals("继续处理消息收件箱", presentation.subtitle)
        assertEquals(
            listOf(
                AssistantShellIntentRouter.ACTION_OPEN,
                AssistantShellIntentRouter.ACTION_RESUME,
                AssistantShellIntentRouter.ACTION_RETURN_TARGET,
            ),
            presentation.actions.map { it.action },
        )
    }

    @Test
    fun `build surfaces companion shell lines in overlay glance`() {
        val presentation =
            AssistantOverlayPresentationFactory.build(
                snapshot = AssistantOsSnapshot(),
                productShell =
                    AssistantProductShellSnapshot(
                        companionShell =
                            AssistantCompanionShellSnapshot(
                                mode = "voice_companion",
                                summary = "当前通过语音伴随执行消息收口",
                                laneLines = listOf("现在 | 收口今天的重要消息", "协同 | active_handoff"),
                                recommendedCommands = listOf("/viewer"),
                            ),
                        voiceInteraction =
                            AssistantVoiceInteractionSnapshot(
                                availabilitySummary = "voice_ready",
                                interactionMode = "hands_free_execution",
                            ),
                    ),
            )

        assertTrue(presentation.subtitle.contains("语音伴随"))
        assertTrue(presentation.glanceLines.any { it.contains("现在 | 收口今天的重要消息") })
        assertTrue(presentation.statusChips.contains("voice_companion"))
    }
}
