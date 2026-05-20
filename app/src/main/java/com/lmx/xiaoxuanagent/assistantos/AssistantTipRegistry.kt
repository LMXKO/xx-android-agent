package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionReport
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryQueueTask
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryTaskStatus
import com.lmx.xiaoxuanagent.runtime.DebugAgentStore
import com.lmx.xiaoxuanagent.runtime.SessionConversationCompactSnapshot

internal object AssistantTipRegistry {
    fun buildCandidates(
        assistantSnapshot: AssistantOsSnapshot,
        mailboxPendingApprovals: Int,
        providerStates: List<AssistantSignalProviderState>,
        blockedSessionCount: Int,
        retentionPreview: ArtifactRetentionReport,
        memoryQueue: List<BackgroundMemoryQueueTask>,
        diagnostics: AssistantProductDiagnosticsSnapshot,
        memoryInsight: PersonalMemoryInsightSnapshot,
        conversationCompact: SessionConversationCompactSnapshot?,
        voiceInteraction: AssistantVoiceInteractionSnapshot,
        adaptivePolicy: AssistantAdaptivePolicySnapshot,
        reason: String,
    ): List<AssistantTipCandidate> =
        buildList {
            if (!DebugAgentStore.uiState.value.accessibilityConnected) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "onboarding_accessibility",
                        title = "开启无障碍服务",
                        summary = "没有无障碍服务，手机超级助手主链无法继续工作。",
                        actionLabel = "去开启",
                        recommendedPage = "today",
                        priority = adaptivePriority(100, "onboarding", "all", adaptivePolicy),
                        source = "onboarding",
                        audience = "all",
                        eligibilityReason = "accessibility_missing",
                        cooldownSessions = 2,
                    ),
                )
            }
            if (!assistantSnapshot.isFeatureEnabled(AssistantFeatureFlagKey.ASSISTANT_INBOX)) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "onboarding_inbox",
                        title = "开启助手收件箱",
                        summary = "收件箱关闭后，入口、审批和后台待办无法形成统一控制面。",
                        actionLabel = "开启收件箱",
                        recommendedPage = "inbox",
                        priority = adaptivePriority(90, "onboarding", "operator", adaptivePolicy),
                        source = "onboarding",
                        audience = "operator",
                        eligibilityReason = "assistant_inbox_disabled",
                        cooldownSessions = 3,
                    ),
                )
            }
            if (assistantSnapshot.health.approvalSessionCount > 0 || mailboxPendingApprovals > 0) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "swarm_pending_approvals",
                        title = "处理待审批协作",
                        summary = "当前有 ${assistantSnapshot.health.approvalSessionCount} 个高风险确认、$mailboxPendingApprovals 条 worker 权限请求待处理。",
                        actionLabel = "查看审批",
                        recommendedPage = "approvals",
                        priority = adaptivePriority(95, "swarm", "operator", adaptivePolicy),
                        source = "swarm",
                        audience = "operator",
                        eligibilityReason = "approval_pressure",
                        cooldownSessions = 1,
                    ),
                )
            }
            if (memoryQueue.any { it.status == BackgroundMemoryTaskStatus.FAILED || it.status == BackgroundMemoryTaskStatus.DEFERRED }) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "memory_queue_attention",
                        title = "检查后台记忆队列",
                        summary = "有 ${memoryQueue.count { it.status != BackgroundMemoryTaskStatus.COMPLETED }} 条记忆任务未正常收敛。",
                        actionLabel = "查看 memory queue",
                        recommendedPage = "today",
                        priority = adaptivePriority(80, "memory", "personal", adaptivePolicy),
                        source = "memory",
                        audience = "personal",
                        eligibilityReason = "memory_queue_unhealthy",
                        cooldownSessions = 4,
                    ),
                )
            }
            if (memoryInsight.suggestionSummary.isNotBlank()) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "memory_suggestion_lane",
                        title = "处理长期记忆建议",
                        summary = memoryInsight.suggestionSummary,
                        actionLabel = "查看记忆",
                        recommendedPage = "memory",
                        priority = adaptivePriority(74, "memory", "personal", adaptivePolicy),
                        source = "memory",
                        audience = "personal",
                        eligibilityReason = "memory_suggestion_ready",
                        cooldownSessions = 3,
                    ),
                )
            }
            val unhealthyProviders = providerStates.filter { it.healthScore < 50 || it.failureCount >= 3 }
            if (unhealthyProviders.isNotEmpty()) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "provider_health_attention",
                        title = "检查 provider 健康",
                        summary = "有 ${unhealthyProviders.size} 个 provider 健康度下降，可能影响 trigger routing 与恢复链。",
                        actionLabel = "查看 provider",
                        recommendedPage = "today",
                        priority = adaptivePriority(78, "provider", "operator", adaptivePolicy),
                        source = "provider",
                        audience = "operator",
                        eligibilityReason = "provider_health_degraded",
                        cooldownSessions = 4,
                    ),
                )
            }
            if (conversationCompact != null && conversationCompact.trigger != "microcompact") {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "conversation_compact_attention",
                        title = "回看长会话压缩摘要",
                        summary = conversationCompact.conversationSummary.ifBlank { conversationCompact.boundarySummary },
                        actionLabel = "查看 viewer",
                        recommendedPage = "viewer",
                        priority = adaptivePriority(77, "ops", "operator", adaptivePolicy),
                        source = "ops",
                        audience = "operator",
                        eligibilityReason = "conversation_compact_ready",
                        cooldownSessions = 2,
                    ),
                )
            }
            if (blockedSessionCount > 0) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "session_graph_blocked",
                        title = "清理阻塞会话图",
                        summary = "当前有 $blockedSessionCount 个 session 仍在等待审批、子任务或阻塞解除。",
                        actionLabel = "查看 operator",
                        recommendedPage = "viewer",
                        priority = adaptivePriority(82, "operator", "operator", adaptivePolicy),
                        source = "operator",
                        audience = "operator",
                        eligibilityReason = "session_graph_blocked",
                        cooldownSessions = 2,
                    ),
                )
            }
            if (retentionPreview.deletedArtifacts > 0 || retentionPreview.deletedSessions > 0) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "artifact_lifecycle_attention",
                        title = "运行 artifact 治理",
                        summary = "artifact retention 预览显示可收敛 ${retentionPreview.deletedArtifacts} 个 artifact、${retentionPreview.deletedSessions} 个 session。",
                        actionLabel = "查看 retention",
                        recommendedPage = "today",
                        priority = adaptivePriority(62, "artifact", "operator", adaptivePolicy),
                        source = "artifact",
                        audience = "operator",
                        eligibilityReason = "artifact_retention_ready",
                        cooldownSessions = 5,
                    ),
                )
            }
            if (assistantSnapshot.health.failedSessionCount > 0) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "ops_failed_sessions",
                        title = "回看失败任务",
                        summary = "最近有 ${assistantSnapshot.health.failedSessionCount} 个失败/终止任务，建议做 replay 或 artifact 回看。",
                        actionLabel = "查看失败任务",
                        recommendedPage = "viewer",
                        priority = adaptivePriority(75, "ops", "operator", adaptivePolicy),
                        source = "ops",
                        audience = "operator",
                        eligibilityReason = "failed_sessions_present",
                        cooldownSessions = 3,
                    ),
                )
            }
            if (diagnostics.staleRuntimeDetected) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "diagnostics_stale_runtime",
                        title = "检查卡住的活跃会话",
                        summary = diagnostics.staleRuntimeSummary.ifBlank { "当前运行时超过阈值未刷新，建议回查现场。" },
                        actionLabel = "查看健康",
                        recommendedPage = "viewer",
                        priority = adaptivePriority(88, "diagnostics", "operator", adaptivePolicy),
                        source = "diagnostics",
                        audience = "operator",
                        eligibilityReason = "stale_runtime_detected",
                        cooldownSessions = 1,
                    ),
                )
            }
            if (voiceInteraction.availabilitySummary != "voice_ready" || voiceInteraction.state in setOf("blocked", "error")) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "voice_runtime_attention",
                        title = "修复持续语音入口",
                        summary = voiceInteraction.summary.ifBlank { "持续语音入口还未进入 ready 状态。" },
                        actionLabel = "查看入口",
                        recommendedPage = "entry",
                        priority = adaptivePriority(71, "onboarding", "personal", adaptivePolicy),
                        source = "onboarding",
                        audience = "personal",
                        eligibilityReason = "voice_runtime_unready",
                        cooldownSessions = 2,
                    ),
                )
            }
            if (reason.contains("heartbeat") && providerStates.none { it.providerId == "foreground_app" }) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "provider_foreground_app",
                        title = "补强前台应用 provider",
                        summary = "当前还没有稳定的前台应用信号，系统接管时机会偏弱。",
                        actionLabel = "检查 provider",
                        recommendedPage = "today",
                        priority = adaptivePriority(60, "provider", "operator", adaptivePolicy),
                        source = "provider",
                        audience = "operator",
                        eligibilityReason = "foreground_provider_missing",
                        cooldownSessions = 6,
                    ),
                )
            }
            if (providerStates.none { it.providerId == "calendar_agenda" }) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "provider_calendar_agenda",
                        title = "补强日历 agenda provider",
                        summary = "当前还没有真实日历 agenda provider，事件驱动的主动提醒能力会偏弱。",
                        actionLabel = "检查日历 provider",
                        recommendedPage = "today",
                        priority = adaptivePriority(58, "provider", "personal", adaptivePolicy),
                        source = "provider",
                        audience = "personal",
                        eligibilityReason = "calendar_provider_missing",
                        cooldownSessions = 6,
                    ),
                )
            }
            if (providerStates.none { it.providerId == "passive_location" }) {
                add(
                    AssistantTipCandidate(
                        dedupeKey = "provider_passive_location",
                        title = "补强位置 provider",
                        summary = "当前还没有位置 provider，基于场景和位置的继续跟进能力不够稳。",
                        actionLabel = "检查位置 provider",
                        recommendedPage = "today",
                        priority = adaptivePriority(57, "provider", "personal", adaptivePolicy),
                        source = "provider",
                        audience = "personal",
                        eligibilityReason = "location_provider_missing",
                        cooldownSessions = 6,
                    ),
                )
            }
        }

    private fun adaptivePriority(
        base: Int,
        source: String,
        audience: String,
        adaptivePolicy: AssistantAdaptivePolicySnapshot,
    ): Int {
        var score = base
        if (source in adaptivePolicy.suppressedTipSources) {
            score -= 24
        }
        if (adaptivePolicy.preferredAudience == audience) {
            score += 10
        }
        return score.coerceAtLeast(0)
    }
}
