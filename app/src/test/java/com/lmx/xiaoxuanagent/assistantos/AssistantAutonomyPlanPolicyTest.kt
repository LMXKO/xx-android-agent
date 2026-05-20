package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionSwarmCoordinationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAutonomyPlanPolicyTest {
    @Test
    fun `derive enters approval guard when permission requests are pending`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 1_000L,
                assistantSnapshot = AssistantOsSnapshot(),
                swarmCoordination =
                    SessionSwarmCoordinationSnapshot(
                        pendingPermissionRequests = 2,
                        pendingMailboxMessages = 3,
                    ),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(preferredEntrySurfaces = listOf("widget")),
                userModel =
                    PersonalAssistantUserModelSnapshot(
                        identitySummary = "wechat_assistant",
                        preferenceSummary = "structured=4",
                        recommendedCommands = listOf("/memory-governance"),
                    ),
                memoryInsight = PersonalMemoryInsightSnapshot(),
                voiceInteraction = AssistantVoiceInteractionSnapshot(),
                providers = emptyList(),
                externalSignals = emptyList(),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(dueCount = 1, priorityMode = "balanced"),
                onboarding = AssistantOnboardingState(),
                tips = listOf(AssistantTipCard(id = "tip_1", title = "审批", summary = "", actionLabel = "查看")),
            )

        assertEquals("approval_guard", snapshot.mode)
        assertEquals("approval_first", snapshot.triggerPolicyMode)
        assertEquals("defer_until_approval", snapshot.proactiveMode)
        assertTrue(snapshot.enginePhaseOrder.contains("remote_inbound"))
        assertTrue(snapshot.recommendedCommands.contains("/approval-center"))
        assertTrue(snapshot.recommendedCommands.contains("/memory-governance"))
    }

    @Test
    fun `derive prefers active session focus execution over event driven`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 5_000L,
                assistantSnapshot =
                    AssistantOsSnapshot(
                        activeSession =
                            AssistantActiveSession(
                                sessionId = "session_focus",
                                task = "跟进微信消息",
                            ),
                    ),
                swarmCoordination =
                    SessionSwarmCoordinationSnapshot(
                        focusSessionIds = listOf("session_focus"),
                        pendingMailboxMessages = 1,
                    ),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(),
                userModel = PersonalAssistantUserModelSnapshot(topProfileIds = listOf("wechat_assistant")),
                memoryInsight = PersonalMemoryInsightSnapshot(),
                voiceInteraction = AssistantVoiceInteractionSnapshot(),
                providers = emptyList(),
                externalSignals =
                    listOf(
                        AssistantExternalSignal(
                            id = "signal_1",
                            type = AssistantExternalSignalType.NOTIFICATION,
                            capability = com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey.START_SESSION,
                            title = "微信提醒",
                            summary = "新消息",
                            fireAtMs = 4_000L,
                            createdAtMs = 1_000L,
                            updatedAtMs = 4_500L,
                        ),
                    ),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(),
                onboarding = AssistantOnboardingState(),
                tips = emptyList(),
            )

        assertEquals("focus_execution", snapshot.mode)
        assertEquals("hold_active_session", snapshot.restoreMode)
        assertTrue(snapshot.enginePhaseOrder.take(3).contains("restore_session"))
        assertTrue(snapshot.lines.any { it.contains("trigger=event_first") || it.contains("trigger=balanced") })
    }

    @Test
    fun `derive uses onboarding guard and guided entry when blocking step exists`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 9_000L,
                assistantSnapshot = AssistantOsSnapshot(),
                swarmCoordination = SessionSwarmCoordinationSnapshot(),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(),
                userModel = PersonalAssistantUserModelSnapshot(preferredThemes = listOf("安全确认")),
                memoryInsight = PersonalMemoryInsightSnapshot(),
                voiceInteraction = AssistantVoiceInteractionSnapshot(),
                providers = emptyList(),
                externalSignals =
                    listOf(
                        AssistantExternalSignal(
                            id = "signal_2",
                            type = AssistantExternalSignalType.CALENDAR,
                            capability = com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey.START_SESSION,
                            title = "会议提醒",
                            summary = "10 分钟后开始",
                            fireAtMs = 8_000L,
                            createdAtMs = 7_000L,
                            updatedAtMs = 8_000L,
                        ),
                    ),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(),
                onboarding =
                    AssistantOnboardingState(
                        pendingSteps = listOf("无障碍授权"),
                        steps =
                            listOf(
                                AssistantOnboardingStepState(
                                    id = "a11y",
                                    title = "无障碍授权",
                                    summary = "需要系统授权",
                                    actionLabel = "开启",
                                    blocking = true,
                                    status = "pending",
                                ),
                            ),
                    ),
                tips = emptyList(),
            )

        assertEquals("onboarding_guard", snapshot.mode)
        assertEquals("guarded_onboarding", snapshot.triggerPolicyMode)
        assertEquals("guided", snapshot.entrySurfaceMode)
        assertEquals(listOf("sync_providers", "refresh_projection", "mailbox", "remote_sync", "memory_drain"), snapshot.enginePhaseOrder)
        assertTrue(snapshot.recommendedCommands.contains("/product-shell --section inbox"))
    }

    @Test
    fun `derive enters voice guard when continuous voice is active`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 12_000L,
                assistantSnapshot =
                    AssistantOsSnapshot(
                        activeSession = AssistantActiveSession(sessionId = "session_voice", task = "语音整理消息"),
                    ),
                swarmCoordination = SessionSwarmCoordinationSnapshot(),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(),
                userModel = PersonalAssistantUserModelSnapshot(),
                memoryInsight = PersonalMemoryInsightSnapshot(),
                voiceInteraction =
                    AssistantVoiceInteractionSnapshot(
                        state = "listening",
                        availabilitySummary = "voice_ready",
                        activeSessionId = "session_voice",
                    ),
                providers = emptyList(),
                externalSignals = emptyList(),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(dueCount = 3),
                onboarding = AssistantOnboardingState(),
                tips = emptyList(),
            )

        assertEquals("voice_guard", snapshot.mode)
        assertEquals("voice_first", snapshot.triggerPolicyMode)
        assertEquals("defer_for_voice", snapshot.proactiveMode)
        assertEquals("hands_free", snapshot.entrySurfaceMode)
        assertTrue(snapshot.recommendedCommands.contains("/product-shell --section entry"))
    }

    @Test
    fun `derive enters memory consolidation when insight backlog is present`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 15_000L,
                assistantSnapshot = AssistantOsSnapshot(),
                swarmCoordination = SessionSwarmCoordinationSnapshot(),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(),
                userModel = PersonalAssistantUserModelSnapshot(recommendedCommands = listOf("/memory-workspace")),
                memoryInsight =
                    PersonalMemoryInsightSnapshot(
                        consolidationSummary = "entries=8 structured=5 | app_anchor=wechat",
                        thinkbackSummary = "upsert | fact | 用户更偏好语音入口",
                    ),
                voiceInteraction = AssistantVoiceInteractionSnapshot(state = "idle", availabilitySummary = "voice_ready"),
                providers = emptyList(),
                externalSignals = emptyList(),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(),
                onboarding = AssistantOnboardingState(),
                tips = emptyList(),
            )

        assertEquals("memory_consolidation", snapshot.mode)
        assertEquals("memory_first", snapshot.triggerPolicyMode)
        assertEquals("memory_first", snapshot.proactiveMode)
        assertTrue(snapshot.enginePhaseOrder.first() == "sync_providers")
        assertTrue(snapshot.enginePhaseOrder.contains("memory_drain"))
        assertTrue(snapshot.recommendedCommands.contains("/memory-governance"))
    }

    @Test
    fun `derive enters communication follow up when phone communication signals are due`() {
        val snapshot =
            AssistantAutonomyPlanPolicy.derive(
                now = 20_000L,
                assistantSnapshot = AssistantOsSnapshot(),
                swarmCoordination = SessionSwarmCoordinationSnapshot(),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(),
                userModel = PersonalAssistantUserModelSnapshot(),
                memoryInsight = PersonalMemoryInsightSnapshot(),
                voiceInteraction = AssistantVoiceInteractionSnapshot(state = "idle", availabilitySummary = "voice_ready"),
                providers = emptyList(),
                externalSignals =
                    listOf(
                        AssistantExternalSignal(
                            id = "signal_phone",
                            type = AssistantExternalSignalType.CALL_LOG,
                            capability = com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey.START_SESSION,
                            title = "未接来电",
                            summary = "张三 | 32s",
                            fireAtMs = 19_000L,
                            createdAtMs = 18_000L,
                            updatedAtMs = 19_500L,
                        ),
                    ),
                proactiveQueue = AssistantProactiveTaskQueueSnapshot(),
                onboarding = AssistantOnboardingState(),
                tips = emptyList(),
            )

        assertEquals("communication_follow_up", snapshot.mode)
        assertEquals("communication_first", snapshot.triggerPolicyMode)
        assertEquals("follow_up_first", snapshot.proactiveMode)
        assertEquals("companion_follow_up", snapshot.entrySurfaceMode)
        assertTrue(snapshot.enginePhaseOrder.first() == "sync_providers")
        assertTrue(snapshot.enginePhaseOrder.contains("external_signals"))
    }
}
