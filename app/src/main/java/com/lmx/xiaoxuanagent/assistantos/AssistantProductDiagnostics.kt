package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.harness.SuperAssistantMaturitySnapshot
import com.lmx.xiaoxuanagent.runtime.ArtifactRetentionReport
import com.lmx.xiaoxuanagent.runtime.BackgroundMemoryQueueTask
import com.lmx.xiaoxuanagent.runtime.PlatformTraceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionCommandCenterSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionGraphNode
import com.lmx.xiaoxuanagent.runtime.SessionMemoryMaintenanceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot

data class AssistantProductDiagnosticsSnapshot(
    val status: String = "idle",
    val summary: String = "",
    val providerSummary: String = "",
    val memorySummary: String = "",
    val historySummary: String = "",
    val strategySummary: String = "",
    val appPreflightStatus: String = "",
    val appPreflightSummary: String = "",
    val appPreflightLines: List<String> = emptyList(),
    val appPreflightRecommendedCommands: List<String> = emptyList(),
    val superAssistantStatus: String = "",
    val superAssistantSummary: String = "",
    val superAssistantLines: List<String> = emptyList(),
    val staleRuntimeDetected: Boolean = false,
    val staleRuntimeSummary: String = "",
    val lines: List<String> = emptyList(),
)

internal object AssistantProductDiagnosticsService {
    fun derive(
        providers: List<AssistantSignalProviderState>,
        memoryQueue: List<BackgroundMemoryQueueTask>,
        retentionPreview: ArtifactRetentionReport,
        sessionGraph: List<SessionGraphNode>,
        focusPlatformSnapshot: SessionPlatformSnapshot?,
        traceSnapshot: PlatformTraceSnapshot,
        commandCenter: SessionCommandCenterSnapshot,
        proactiveTasks: List<AssistantProactiveTask>,
        followUpHealth: AssistantFollowUpHealthSnapshot,
        memoryMaintenance: SessionMemoryMaintenanceSnapshot,
        historySummary: String,
        appPreflight: AssistantAppCapabilityPreflightSnapshot = AssistantAppCapabilityPreflightSnapshot(),
        superAssistantMaturity: SuperAssistantMaturitySnapshot = SuperAssistantMaturitySnapshot(),
    ): AssistantProductDiagnosticsSnapshot {
        val unhealthyProviders = providers.filter { it.healthScore < 50 || it.failureCount >= 3 }
        val pendingMemory = memoryQueue.count { it.status.name != "COMPLETED" }
        val blockedSessions = sessionGraph.count { it.pendingApprovalCount > 0 || it.blockedReason.isNotBlank() }
        val approvalFollowUps = proactiveTasks.count { it.enabled && it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
        val staleRuntime = focusPlatformSnapshot?.healthSummary?.staleRuntime == true
        val status =
            when {
                staleRuntime -> "stale_runtime"
                appPreflight.status == "red" -> "app_capability_blocked"
                appPreflight.status == "yellow" -> "app_capability_degraded"
                superAssistantMaturity.status == "red" -> "super_assistant_blocked"
                superAssistantMaturity.status == "yellow" -> "super_assistant_attention"
                blockedSessions > 0 -> "blocked_sessions"
                commandCenter.failedCount > 0 -> "command_attention"
                approvalFollowUps > 0 -> "approval_follow_up"
                followUpHealth.overdueCount > 0 -> "follow_up_overdue"
                unhealthyProviders.isNotEmpty() -> "provider_attention"
                memoryMaintenance.failedCount > 0 -> "memory_maintenance_failed"
                pendingMemory > 0 -> "memory_backlog"
                traceSnapshot.attentionCount > 0 -> "trace_attention"
                retentionPreview.deletedArtifacts > 0 -> "artifact_pressure"
                else -> "healthy"
            }
        val summary =
            when (status) {
                "stale_runtime" -> "当前活跃 session 超过阈值未刷新。"
                "app_capability_blocked" -> "普通 App 能力预检存在阻断项，需要先修复关键入口或执行通道。"
                "app_capability_degraded" -> "普通 App 能力预检存在降级项，已给出兜底入口和修复建议。"
                "super_assistant_blocked" -> "超级手机助手成熟度门禁未通过，需要先处理被阻断的能力域。"
                "super_assistant_attention" -> "超级手机助手仍有能力域未达标，建议优先补齐成熟度门禁。"
                "blocked_sessions" -> "当前存在需要处理的阻塞会话图。"
                "command_attention" -> "命令入口最近出现失败，需要检查控制平面。"
                "approval_follow_up" -> "当前有等待你处理的高风险确认跟进项。"
                "follow_up_overdue" -> "当前有逾期中的跟进项，建议先清理 inbox。"
                "provider_attention" -> "部分 provider 健康度下降。"
                "memory_maintenance_failed" -> "后台记忆维护存在失败任务，需要先恢复 notebook/记忆整理。"
                "memory_backlog" -> "后台记忆队列存在积压。"
                "trace_attention" -> "trace 流里出现了需要关注的失败或超时信号。"
                "artifact_pressure" -> "artifact 治理预览显示存在明显可收敛对象。"
                else -> "product shell diagnostics 健康。"
            }
        val providerSummary =
            buildString {
                append("providers=").append(providers.size)
                append(" unhealthy=").append(unhealthyProviders.size)
            }
        val memorySummary =
            buildString {
                append("memory_queue=").append(pendingMemory)
                append(" maintenance=").append(memoryMaintenance.pendingCount).append("/").append(memoryMaintenance.failedCount)
                if (retentionPreview.deletedArtifacts > 0 || retentionPreview.deletedSessions > 0) {
                    append(" retention=").append(retentionPreview.deletedArtifacts).append("/").append(retentionPreview.deletedSessions)
                }
            }
        val strategySummary =
            buildString {
                append("blocked_sessions=").append(blockedSessions)
                append(" traces=").append(traceSnapshot.totalCount)
                append(" attention=").append(traceSnapshot.attentionCount)
                append(" commands=").append(commandCenter.runningCount).append("/").append(commandCenter.failedCount)
                append(" follow_up=").append(approvalFollowUps)
                append(" overdue=").append(followUpHealth.overdueCount)
            }
        return AssistantProductDiagnosticsSnapshot(
            status = status,
            summary = summary,
            providerSummary = providerSummary,
            memorySummary = memorySummary,
            historySummary = historySummary,
            strategySummary = strategySummary,
            appPreflightStatus = appPreflight.status,
            appPreflightSummary = appPreflight.summary,
            appPreflightLines = appPreflight.lines,
            appPreflightRecommendedCommands = appPreflight.recommendedCommands,
            superAssistantStatus = superAssistantMaturity.status,
            superAssistantSummary = superAssistantMaturity.summary,
            superAssistantLines = superAssistantMaturity.lines,
            staleRuntimeDetected = staleRuntime,
            staleRuntimeSummary = focusPlatformSnapshot?.healthSummary?.summary.orEmpty(),
            lines =
                buildList {
                    add("status=$status")
                    add("providers=$providerSummary")
                    add("memory=$memorySummary")
                    add("history=$historySummary")
                    add("strategy=$strategySummary")
                    if (appPreflight.status.isNotBlank() && appPreflight.status != "unknown") {
                        add("app_preflight=${appPreflight.summary.ifBlank { "-" }}")
                        appPreflight.lines.take(6).forEach { add("app_preflight_line | $it") }
                    }
                    if (superAssistantMaturity.status.isNotBlank() && superAssistantMaturity.status != "unknown") {
                        add("super_assistant=${superAssistantMaturity.summary.ifBlank { "-" }}")
                        superAssistantMaturity.lines.take(4).forEach { add("super_assistant_line | $it") }
                    }
                    add("commands=${commandCenter.attentionSummary.ifBlank { "-" }}")
                    add("follow_up=approval:$approvalFollowUps")
                    add("follow_up_health=${followUpHealth.summary.ifBlank { "-" }}")
                    add("memory_maintenance=${memoryMaintenance.lastStatus.ifBlank { "-" }} | ${memoryMaintenance.lastSummary.ifBlank { "-" }}")
                    add("trace=${traceSnapshot.categorySummary.ifBlank { "-" }}")
                    add("trace_coverage=${traceSnapshot.coverageSummary.ifBlank { "-" }}")
                    when (status) {
                        "command_attention" -> add("suggested_action=/command-center")
                        "approval_follow_up" -> add("suggested_action=/safety-center")
                        "follow_up_overdue" -> add("suggested_action=/follow-up-health")
                        "blocked_sessions" -> add("suggested_action=/operator")
                        "memory_maintenance_failed" -> add("suggested_action=/memory-maintenance")
                        "memory_backlog" -> add("suggested_action=/memory-governance")
                        "app_capability_blocked", "app_capability_degraded" ->
                            appPreflight.recommendedCommands.take(3).forEach { add("suggested_action=$it") }
                        "super_assistant_blocked", "super_assistant_attention" ->
                            superAssistantMaturity.recommendedCommands.take(2).forEach { add("suggested_action=$it") }
                    }
                    commandCenter.recentLines.take(3).forEach { add("command | $it") }
                    followUpHealth.topLines.take(3).forEach { add("follow_up_line | $it") }
                    if (traceSnapshot.attentionCount > 0) {
                        add("trace_attention=${traceSnapshot.attentionSummary.ifBlank { "-" }}")
                    }
                    if (staleRuntime) add("stale_runtime=${focusPlatformSnapshot?.healthSummary?.summary.orEmpty()}")
                },
        )
    }
}
