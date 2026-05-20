package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.safety.PermissionModeOrchestrator

internal object AssistantOnboardingRegistry {
    fun buildCandidates(
        assistantSnapshot: AssistantOsSnapshot,
        providerCount: Int,
        notificationProviderReady: Boolean,
        calendarProviderReady: Boolean,
        locationProviderReady: Boolean,
        voiceReady: Boolean,
    ): List<AssistantOnboardingStepCandidate> =
        listOf(
            AssistantOnboardingStepCandidate(
                id = "accessibility",
                title = "无障碍服务已连接",
                summary = "没有无障碍服务，手机超级助手主链无法继续工作。",
                actionLabel = "去开启",
                category = "core_runtime",
                riskLevel = "critical",
                priority = 100,
                blocking = true,
                requirementMet = DebugAgentStore.uiState.value.accessibilityConnected,
            ),
            AssistantOnboardingStepCandidate(
                id = "assistant_inbox",
                title = "助手收件箱已开启",
                summary = "收件箱关闭后，入口、审批和后台待办无法形成统一控制面。",
                actionLabel = "开启收件箱",
                category = "control_plane",
                riskLevel = "high",
                priority = 92,
                blocking = true,
                requirementMet = assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX),
            ),
            AssistantOnboardingStepCandidate(
                id = "notification_provider",
                title = "通知 provider 已接入",
                summary = "通知监听是本地系统信号接入的重要入口。",
                actionLabel = "开启通知监听",
                category = "provider",
                riskLevel = "high",
                priority = 88,
                blocking = false,
                requirementMet = notificationProviderReady,
            ),
            AssistantOnboardingStepCandidate(
                id = "overlay_permission",
                title = "悬浮权限可用",
                summary = "没有悬浮能力，控制中心与系统级接管会降级。",
                actionLabel = "授予悬浮窗权限",
                category = "surface",
                riskLevel = "high",
                priority = 84,
                blocking = false,
                requirementMet =
                    PermissionModeOrchestrator.canDrawOverlays() ||
                        !assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.OVERLAY_SURFACE_STUB),
            ),
            AssistantOnboardingStepCandidate(
                id = "system_provider",
                title = "系统 provider 已注册",
                summary = "至少应存在基础系统 provider，assistant OS 才具备稳定感知。",
                actionLabel = "初始化 provider",
                category = "provider",
                riskLevel = "high",
                priority = 82,
                blocking = false,
                requirementMet = providerCount > 0,
            ),
            AssistantOnboardingStepCandidate(
                id = "voice_runtime",
                title = "持续语音入口已就绪",
                summary = "手机超级助手需要可持续语音入口，才能接近真正的随身伴随式交互。",
                actionLabel = "启用语音",
                category = "surface",
                riskLevel = "medium",
                priority = 76,
                blocking = false,
                requirementMet = voiceReady,
            ),
            AssistantOnboardingStepCandidate(
                id = "calendar_provider",
                title = "日历 agenda provider 已接入",
                summary = "手机超级助手需要真实日历上下文，才能形成更稳定的提醒与跟进链路。",
                actionLabel = "接入日历",
                category = "provider",
                riskLevel = "medium",
                priority = 58,
                blocking = false,
                requirementMet = calendarProviderReady,
            ),
            AssistantOnboardingStepCandidate(
                id = "location_provider",
                title = "位置 provider 已接入",
                summary = "位置上下文是系统级助手的重要感知源，没有它很多主动能力会偏弱。",
                actionLabel = "接入位置",
                category = "provider",
                riskLevel = "medium",
                priority = 56,
                blocking = false,
                requirementMet = locationProviderReady,
            ),
        )
}
