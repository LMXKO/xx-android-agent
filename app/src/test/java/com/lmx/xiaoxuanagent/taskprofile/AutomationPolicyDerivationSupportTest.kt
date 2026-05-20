package com.lmx.xiaoxuanagent.taskprofile

import com.lmx.xiaoxuanagent.harness.ScreenAutomationCapabilitySnapshot
import com.lmx.xiaoxuanagent.harness.ScreenAutomationTemplateCapabilitySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPolicyDerivationSupportTest {
    @Test
    fun mergeAutomationPolicy_promotesHandoffModeWhenLearnedSnapshotPrefersIt() {
        val merged =
            mergeAutomationPolicy(
                base =
                    AutomationSupportPolicy(
                        profileId = "shopping",
                        packageName = "com.example.shop",
                        supportsSubmit = false,
                        requiresFinalHandoff = true,
                        supervisionMode = "foreground_takeover",
                        handoffSummary = "静态规则",
                    ),
                learned =
                    ScreenAutomationCapabilitySnapshot(
                        profileId = "shopping",
                        packageName = "com.example.shop",
                        runCount = 6,
                        automationSuccessRate = 0.8,
                        recentSuccessRate = 0.83,
                        handoffReadyRate = 0.9,
                        confidenceBand = "strong",
                        preferFinalHandoff = true,
                        note = "近期自动推进稳定。",
                    ),
            )

        assertTrue(merged.requiresFinalHandoff)
        assertTrue(merged.supervisionMode.contains("handoff"))
        assertTrue(merged.handoffSummary.contains("近期自动推进稳定"))
    }

    @Test
    fun mergeAutomationPolicy_restrictsFillAndSubmitWhenLearnedSnapshotIsWeak() {
        val merged =
            mergeAutomationPolicy(
                base =
                    AutomationSupportPolicy(
                        profileId = "weak_profile",
                        packageName = "com.example.weak",
                        supportsFill = true,
                        supportsSubmit = true,
                    ),
                learned =
                    ScreenAutomationCapabilitySnapshot(
                        profileId = "weak_profile",
                        packageName = "com.example.weak",
                        runCount = 5,
                        automationSuccessRate = 0.2,
                        recentSuccessRate = 0.16,
                        confidenceBand = "weak",
                        restrictFill = true,
                        restrictSubmit = true,
                    ),
            )

        assertFalse(merged.supportsFill)
        assertFalse(merged.supportsSubmit)
    }

    @Test
    fun mergeAutomationPolicy_usesStageSnapshotToGuardSubmitStages() {
        val merged =
            mergeAutomationPolicy(
                base =
                    AutomationSupportPolicy(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        supportsFill = true,
                        supportsSubmit = true,
                        supervisionMode = "foreground_takeover",
                    ),
                learned = null,
                stageLearned =
                    ScreenAutomationTemplateCapabilitySnapshot(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        taskIntent = "message_send",
                        planStage = "confirm_send",
                        pageState = "chat_detail",
                        actionTemplate = "press_enter",
                        sampleCount = 5,
                        recentCleanRate = 0.2,
                        confidenceBand = "weak",
                        preferFinalHandoff = true,
                        restrictSubmit = true,
                        note = "发送阶段最近不稳定。",
                    ),
            )

        assertTrue(merged.requiresFinalHandoff)
        assertFalse(merged.supportsSubmit)
        assertTrue(merged.supervisionMode.contains("guarded") || merged.supervisionMode.contains("handoff"))
        assertTrue(merged.handoffSummary.contains("发送阶段最近不稳定"))
    }

    @Test
    fun mergeAutomationPolicy_prefers_connected_path_for_focus_apps() {
        val merged =
            mergeAutomationPolicy(
                base =
                    AutomationSupportPolicy(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        preferredExecutionPath = "gui_fallback",
                        connectedAppId = "wechat_assistant",
                    ),
                learned = null,
                productSupport =
                    ScreenAutomationProductSupportSnapshot(
                        profileId = "wechat_assistant",
                        packageName = "com.tencent.mm",
                        tier = "focused",
                        focusApp = true,
                        connectedFirstRecommended = true,
                    ),
            )

        assertEquals("connected_app_first", merged.preferredExecutionPath)
    }

    @Test
    fun mergeAutomationPolicy_keeps_non_focus_apps_on_gui_fallback() {
        val merged =
            mergeAutomationPolicy(
                base =
                    AutomationSupportPolicy(
                        profileId = "jd_assistant",
                        packageName = "com.jingdong.app.mall",
                        preferredExecutionPath = "connected_app_first",
                        connectedAppId = "jd_assistant",
                    ),
                learned = null,
                productSupport =
                    ScreenAutomationProductSupportSnapshot(
                        profileId = "jd_assistant",
                        packageName = "com.jingdong.app.mall",
                        tier = "experimental",
                        focusApp = false,
                        connectedFirstRecommended = false,
                    ),
            )

        assertEquals("gui_fallback", merged.preferredExecutionPath)
    }
}
