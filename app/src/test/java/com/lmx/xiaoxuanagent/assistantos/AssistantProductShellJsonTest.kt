package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProductShellJsonTest {
    @Test
    fun `json round trip preserves routine digest viewer governance and policies`() {
        val snapshot =
            AssistantProductShellSnapshot(
                routinePolicy =
                    AssistantRoutinePolicySnapshot(
                        focusTheme = "消息收口",
                        reviewWindow = "09:00-11:30",
                        followUpWindow = "14:00-18:00",
                        checklistTemplates = listOf("清收今日收件箱", "确认待审批事项"),
                        preferredSurfaces = listOf("notification", "widget"),
                        summary = "上午收口消息，下午推进 follow-up",
                    ),
                routineShell =
                    AssistantRoutineShellSnapshot(
                        mode = "active_routine",
                        summary = "今天先收口消息，再推进订单跟进",
                        nextWindowSummary = "12:30 前完成消息收口",
                        checklistLines = listOf("确认上午待办", "回完重要消息"),
                        followUpLines = listOf("下午补订单记录"),
                        recommendedCommands = listOf("/today"),
                    ),
                digestShell =
                    AssistantDigestShellSnapshot(
                        mode = "actionable",
                        title = "今日摘要",
                        summary = "当前有 2 条值得优先处理的事项",
                        highlightLines = listOf("Tip: 处理待确认支付", "Onboarding: 开启通知入口"),
                        actionLabel = "先处理确认",
                        actionCommand = "/approve session_1",
                    ),
                digestPolicy =
                    AssistantDigestPolicySnapshot(
                        enabled = true,
                        cadence = "adaptive",
                        maxHighlights = 5,
                        deliverySurfaces = listOf("notification", "widget", "overlay"),
                        summary = "adaptive digest to widget",
                    ),
                quietHours =
                    AssistantQuietHoursSnapshot(
                        enabled = true,
                        startLocalTime = "22:30",
                        endLocalTime = "08:00",
                        activeNow = true,
                        summary = "夜间静默已启用",
                    ),
                interruptBudget =
                    AssistantInterruptBudgetSnapshot(
                        mode = "tight",
                        summary = "当前打断预算偏紧",
                        totalBudget = 4,
                        consumedBudget = 3,
                        remainingBudget = 1,
                        hardBlock = false,
                        cooldownSummary = "signals=4 proactive=2 approvals=1",
                        allowedSources = listOf("notification", "shortcut"),
                        blockedSources = listOf("voice"),
                        recommendedCommands = listOf("/interrupt-budget"),
                    ),
                interruptPolicy =
                    AssistantInterruptPolicySnapshot(
                        baseBudget = 4,
                        focusBudget = 2,
                        hardBlockInQuietHours = true,
                        preferredSources = listOf("notification", "widget"),
                        blockedSources = listOf("voice"),
                        summary = "夜间强阻断，白天保留 widget 入口",
                    ),
                viewerShell =
                    AssistantViewerShellSnapshot(
                        mode = "focused",
                        focusSessionId = "session_123",
                        focusTask = "收口今天的重要消息",
                        detailSummary = "当前聚焦一个消息收口 session",
                        timelineSummary = "最近 4 个节点完整可追踪",
                        graphSummary = "parent-child graph 稳定",
                        approvalSummary = "还有 1 条待审批动作",
                        resultSummary = "已沉淀 2 条结果摘要",
                        detailLines = listOf("focus on session_123", "artifact ready"),
                        timelineLines = listOf("10:00 start", "10:05 inbox sync"),
                        graphLines = listOf("root -> worker_a", "root -> worker_b"),
                        approvalLines = listOf("approval_1 | waiting"),
                        traceLines = listOf("trace | planner resume"),
                        recommendedCommands = listOf("/viewer", "/timeline"),
                    ),
                governanceShell =
                    AssistantGovernanceShellSnapshot(
                        mode = "transparent",
                        summary = "治理面稳定",
                        consentSummary = "已记录 3 条用户同意",
                        privacySummary = "隐私策略正常",
                        historySummary = "session history 可追踪",
                        approvalSummary = "审批中心有 1 条待处理",
                        retentionSummary = "artifact retention 7 天",
                        providerPolicySummary = "provider policy 已启用",
                        explanationLines = listOf("why this command", "why this provider"),
                        controlLines = listOf("/governance", "/provider-policy"),
                        recommendedCommands = listOf("/governance"),
                    ),
                memoryInsight =
                    PersonalMemoryInsightSnapshot(
                        summary = "profiles=1 facts=2 audits=1",
                        awaySummary = "workspace | 稳定",
                        thinkbackSummary = "upsert | fact | 用户习惯",
                        consolidationSummary = "entries=4 structured=2",
                        dreamSummary = "建议围绕张三做关系跟进。",
                        suggestionSummary = "建议围绕张三做关系跟进。",
                        thinkbackLines = listOf("upsert | fact | 用户习惯"),
                        dreamLines = listOf("建议围绕张三做关系跟进。"),
                    ),
                voiceInteraction =
                    AssistantVoiceInteractionSnapshot(
                        state = "ready",
                        summary = "语音入口已就绪",
                        availabilitySummary = "voice_ready",
                        interactionMode = "hands_free_execution",
                        finalTranscript = "帮我处理今天的消息",
                        pendingConfirmation = "准备开始执行这条语音任务。",
                        spokenSummary = "收到，开始处理今天的消息。",
                        lastHeardCommandType = "execute",
                        autoExecuteEligible = true,
                        transcriptHistory = listOf("10:00 | 帮我处理今天的消息"),
                    ),
                swarmStrategy =
                    AssistantSwarmStrategy(
                        summary = "多 worker 正在协同收口",
                        collaboratorSummary = "owner_fairness | active_handoff",
                        laneLines = listOf("root1234 | approvals=1 | mailbox=2"),
                        handoffActions = listOf("先处理 worker 权限协作"),
                    ),
                companionShell =
                    AssistantCompanionShellSnapshot(
                        mode = "voice_companion",
                        summary = "当前通过语音伴随执行消息收口",
                        voiceSummary = "收到，开始处理今天的消息。",
                        swarmSummary = "多 worker 正在协同收口",
                        memorySummary = "建议围绕张三做关系跟进。",
                        laneLines = listOf("现在 | 收口今天的重要消息", "协同 | active_handoff"),
                        recommendedCommands = listOf("/viewer", "/memory-governance"),
                    ),
                diagnostics =
                    AssistantProductDiagnosticsSnapshot(
                        status = "super_assistant_attention",
                        summary = "超级手机助手仍有能力域未达标",
                        appPreflightStatus = "yellow",
                        appPreflightSummary = "普通 App 能力预检 status=yellow ready=10 degraded=2 blocked=0 unsupported=0",
                        appPreflightLines = listOf("app_preflight | status=yellow | ready=10 degraded=2 blocked=0 unsupported=0"),
                        appPreflightRecommendedCommands = listOf("/entry-surfaces", "/provider-policy"),
                        superAssistantStatus = "yellow",
                        superAssistantSummary = "score=72 status=yellow domains=4/6",
                        superAssistantLines = listOf("super_assistant | status=yellow | score=72"),
                    ),
            )

        val restored = snapshot.toJson().toAssistantProductShellSnapshot()

        assertEquals("消息收口", restored.routinePolicy.focusTheme)
        assertEquals("active_routine", restored.routineShell.mode)
        assertEquals("当前有 2 条值得优先处理的事项", restored.digestShell.summary)
        assertEquals(5, restored.digestPolicy.maxHighlights)
        assertTrue(restored.quietHours.enabled)
        assertEquals(1, restored.interruptBudget.remainingBudget)
        assertEquals(2, restored.interruptPolicy.focusBudget)
        assertTrue(restored.interruptBudget.blockedSources.contains("voice"))
        assertEquals("session_123", restored.viewerShell.focusSessionId)
        assertTrue(restored.viewerShell.graphLines.contains("root -> worker_a"))
        assertEquals("artifact retention 7 天", restored.governanceShell.retentionSummary)
        assertTrue(restored.governanceShell.controlLines.contains("/provider-policy"))
        assertEquals("profiles=1 facts=2 audits=1", restored.memoryInsight.summary)
        assertEquals("ready", restored.voiceInteraction.state)
        assertEquals("hands_free_execution", restored.voiceInteraction.interactionMode)
        assertTrue(restored.voiceInteraction.autoExecuteEligible)
        assertEquals("owner_fairness | active_handoff", restored.swarmStrategy.collaboratorSummary)
        assertTrue(restored.swarmStrategy.handoffActions.contains("先处理 worker 权限协作"))
        assertEquals("voice_companion", restored.companionShell.mode)
        assertTrue(restored.companionShell.recommendedCommands.contains("/memory-governance"))
        assertEquals("yellow", restored.diagnostics.appPreflightStatus)
        assertTrue(restored.diagnostics.appPreflightLines.any { it.contains("status=yellow") })
        assertTrue(restored.diagnostics.appPreflightRecommendedCommands.contains("/provider-policy"))
        assertEquals("yellow", restored.diagnostics.superAssistantStatus)
        assertTrue(restored.diagnostics.superAssistantLines.any { it.contains("status=yellow") })
    }
}
