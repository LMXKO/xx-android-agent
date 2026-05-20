package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTriggerRoutingEngineTest {
    @Test
    fun `route blocks onboarding guarded start session into refresh only`() {
        val decision =
            AssistantTriggerRoutingEngine.route(
                signal =
                    AssistantExternalSignal(
                        id = "signal_onboarding",
                        type = AssistantExternalSignalType.CALENDAR,
                        capability = SessionCapabilityKey.START_SESSION,
                        title = "会议提醒",
                        summary = "需要处理",
                        task = "处理会议提醒",
                        fireAtMs = 1_000L,
                        createdAtMs = 500L,
                        updatedAtMs = 1_000L,
                    ),
                autonomyPlan =
                    AssistantAutonomyPlanSnapshot(
                        mode = "onboarding_guard",
                        summary = "先补 onboarding",
                    ),
            )

        assertEquals(SessionCapabilityKey.REFRESH_ASSISTANT_OS, decision.capability)
        assertEquals("autonomy_onboarding_guard", decision.payload["routing_policy"])
    }

    @Test
    fun `route suppresses new start session during approval guard except notification`() {
        val decision =
            AssistantTriggerRoutingEngine.route(
                signal =
                    AssistantExternalSignal(
                        id = "signal_approval",
                        type = AssistantExternalSignalType.CALENDAR,
                        capability = SessionCapabilityKey.START_SESSION,
                        title = "日程提醒",
                        summary = "到了处理时间",
                        task = "查看今天的日程",
                        fireAtMs = 1_000L,
                        createdAtMs = 500L,
                        updatedAtMs = 1_000L,
                    ),
                autonomyPlan = AssistantAutonomyPlanSnapshot(mode = "approval_guard"),
            )

        assertEquals(SessionCapabilityKey.REFRESH_ASSISTANT_OS, decision.capability)
        assertEquals("autonomy_approval_guard", decision.payload["routing_policy"])
    }

    @Test
    fun `route blocks voice entry when adaptive policy disables voice surface`() {
        val decision =
            AssistantTriggerRoutingEngine.route(
                signal =
                    AssistantExternalSignal(
                        id = "signal_voice",
                        type = AssistantExternalSignalType.MESSAGE,
                        capability = SessionCapabilityKey.START_SESSION,
                        title = "语音草稿",
                        summary = "从 voice widget 触发",
                        task = "回复消息",
                        source = "voice_widget",
                        fireAtMs = 1_000L,
                        createdAtMs = 500L,
                        updatedAtMs = 1_000L,
                    ),
                adaptivePolicy = AssistantAdaptivePolicySnapshot(blockedEntrySurfaces = listOf("voice")),
            )

        assertEquals(SessionCapabilityKey.REFRESH_ASSISTANT_OS, decision.capability)
        assertEquals("adaptive_surface_blocked", decision.payload["routing_policy"])
    }

    @Test
    fun `route keeps unknown non message profile refresh only when user model favors known apps`() {
        val decision =
            AssistantTriggerRoutingEngine.route(
                signal =
                    AssistantExternalSignal(
                        id = "signal_unknown",
                        type = AssistantExternalSignalType.CALENDAR,
                        capability = SessionCapabilityKey.START_SESSION,
                        title = "未知应用提醒",
                        summary = "启动一个不熟悉的 app",
                        task = "处理未知提醒",
                        payload = mapOf("package_name" to "com.example.unknown"),
                        fireAtMs = 1_000L,
                        createdAtMs = 500L,
                        updatedAtMs = 1_000L,
                    ),
                userModel = PersonalAssistantUserModelSnapshot(topProfileIds = listOf("wechat_assistant")),
            )

        assertEquals(SessionCapabilityKey.REFRESH_ASSISTANT_OS, decision.capability)
        assertEquals("unknown_profile_refresh_only", decision.payload["routing_policy"])
    }

    @Test
    fun `route starts known profile notification when guards allow it`() {
        val decision =
            AssistantTriggerRoutingEngine.route(
                signal =
                    AssistantExternalSignal(
                        id = "signal_known",
                        type = AssistantExternalSignalType.NOTIFICATION,
                        capability = SessionCapabilityKey.START_SESSION,
                        title = "微信新消息",
                        summary = "朋友发来消息",
                        payload =
                            mapOf(
                                "package_name" to "com.tencent.mm",
                                "title" to "新的聊天",
                                "text" to "今晚一起吃饭吗",
                            ),
                        fireAtMs = 1_000L,
                        createdAtMs = 500L,
                        updatedAtMs = 1_000L,
                    ),
                providerState =
                    AssistantSignalProviderState(
                        providerId = "notification_listener",
                        source = "notification_listener",
                        trustLevel = AssistantSignalProviderTrustLevel.HIGH,
                        supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
                        supportedSignalTypes = listOf(AssistantExternalSignalType.NOTIFICATION),
                    ),
                userModel = PersonalAssistantUserModelSnapshot(topProfileIds = listOf("wechat_assistant")),
            )

        assertEquals(SessionCapabilityKey.START_SESSION, decision.capability)
        assertTrue(decision.task.contains("微信助手") || decision.task.contains("新的聊天"))
    }
}
