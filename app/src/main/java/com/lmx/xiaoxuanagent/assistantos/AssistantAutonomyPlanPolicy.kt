package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionSwarmCoordinationSnapshot

internal object AssistantAutonomyPlanPolicy {
    fun derive(
        now: Long,
        assistantSnapshot: AssistantOsSnapshot,
        swarmCoordination: SessionSwarmCoordinationSnapshot,
        adaptivePolicy: AssistantAdaptivePolicySnapshot,
        userModel: PersonalAssistantUserModelSnapshot,
        memoryInsight: PersonalMemoryInsightSnapshot,
        voiceInteraction: AssistantVoiceInteractionSnapshot,
        providers: List<AssistantSignalProviderState>,
        externalSignals: List<AssistantExternalSignal>,
        proactiveQueue: AssistantProactiveTaskQueueSnapshot,
        onboarding: AssistantOnboardingState,
        tips: List<AssistantTipCard>,
    ): AssistantAutonomyPlanSnapshot {
        val unhealthyProviders = providers.count { it.healthScore < 50 || it.failureCount >= 3 }
        val dueSignals = externalSignals.count { it.enabled && it.fireAtMs in 1..now }
        val dueCommunicationSignals =
            externalSignals.count {
                it.enabled &&
                    it.fireAtMs in 1..now &&
                    it.type in setOf(
                        AssistantExternalSignalType.MESSAGE,
                        AssistantExternalSignalType.NOTIFICATION,
                        AssistantExternalSignalType.CONTACT,
                        AssistantExternalSignalType.CALL_LOG,
                    )
            }
        val voiceProtectingExecution = voiceInteraction.state in setOf("listening", "partial", "executing")
        val voiceNeedsRecovery =
            voiceInteraction.state in setOf("blocked", "error") || voiceInteraction.availabilitySummary != "voice_ready"
        val memoryNeedsConsolidation =
            memoryInsight.consolidationSummary.isNotBlank() ||
                memoryInsight.thinkbackSummary.isNotBlank() ||
                memoryInsight.dreamSummary.isNotBlank()
        val swarmNeedsCoordination =
            swarmCoordination.pendingMailboxMessages >= 4 ||
                swarmCoordination.focusSessionIds.size >= 2 ||
                swarmCoordination.pendingPermissionRequests > 0 && swarmCoordination.pendingMailboxMessages > 0
        val mode =
            when {
                onboarding.steps.any { step -> step.blocking && step.status == "pending" } -> "onboarding_guard"
                swarmCoordination.pendingPermissionRequests > 0 -> "approval_guard"
                voiceProtectingExecution -> "voice_guard"
                assistantSnapshot.activeSession.sessionId.isNotBlank() -> "focus_execution"
                voiceNeedsRecovery -> "voice_recovery"
                swarmNeedsCoordination -> "swarm_coordination"
                dueCommunicationSignals > 0 -> "communication_follow_up"
                dueSignals > 0 -> "event_driven"
                proactiveQueue.dueCount > 0 -> "proactive_follow_up"
                memoryNeedsConsolidation -> "memory_consolidation"
                unhealthyProviders > 0 -> "stability_recovery"
                else -> "ambient_companion"
            }
        val enginePhaseOrder =
            when (mode) {
                "onboarding_guard" ->
                    listOf("sync_providers", "refresh_projection", "mailbox", "remote_sync", "memory_drain")
                "approval_guard" ->
                    listOf("sync_providers", "remote_inbound", "remote_bridge", "mailbox", "restore_session", "refresh_projection", "remote_sync", "memory_drain")
                "voice_guard" ->
                    listOf("sync_providers", "refresh_projection", "mailbox", "remote_sync", "memory_drain")
                "focus_execution" ->
                    listOf("sync_providers", "mailbox", "restore_session", "remote_bridge", "refresh_projection", "proactive", "external_signals", "remote_sync", "memory_drain")
                "voice_recovery" ->
                    listOf("sync_providers", "refresh_projection", "memory_drain", "remote_sync", "retention")
                "swarm_coordination" ->
                    listOf("sync_providers", "mailbox", "restore_session", "remote_bridge", "refresh_projection", "external_signals", "remote_sync", "memory_drain")
                "communication_follow_up" ->
                    listOf("sync_providers", "external_signals", "restore_session", "mailbox", "remote_bridge", "refresh_projection", "remote_sync", "memory_drain")
                "event_driven" ->
                    listOf("sync_providers", "external_signals", "mailbox", "remote_inbound", "remote_bridge", "proactive", "refresh_projection", "remote_sync", "memory_drain")
                "proactive_follow_up" ->
                    listOf("sync_providers", "proactive", "mailbox", "remote_bridge", "restore_session", "refresh_projection", "remote_sync", "memory_drain")
                "memory_consolidation" ->
                    listOf("sync_providers", "memory_drain", "refresh_projection", "remote_sync", "retention")
                "stability_recovery" ->
                    listOf("sync_providers", "mailbox", "remote_bridge", "refresh_projection", "memory_drain", "retention", "remote_sync")
                else ->
                    listOf("sync_providers", "refresh_projection", "external_signals", "mailbox", "memory_drain", "remote_sync", "retention")
            }
        val triggerPolicyMode =
            when {
                mode == "onboarding_guard" -> "guarded_onboarding"
                mode == "approval_guard" -> "approval_first"
                mode == "voice_guard" -> "voice_first"
                mode == "voice_recovery" -> "repair_first"
                mode == "swarm_coordination" -> "swarm_first"
                mode == "communication_follow_up" -> "communication_first"
                mode == "memory_consolidation" -> "memory_first"
                adaptivePolicy.trustedSignalSources.isNotEmpty() -> "trusted_signal_first"
                dueSignals > 0 -> "event_first"
                else -> "balanced"
            }
        val restoreMode =
            when {
                mode == "voice_guard" && voiceInteraction.activeSessionId.isNotBlank() -> "preserve_voice_session"
                assistantSnapshot.activeSession.sessionId.isNotBlank() -> "hold_active_session"
                swarmCoordination.focusSessionIds.isNotEmpty() -> "prefer_focus_session"
                userModel.topProfileIds.isNotEmpty() -> "prefer_known_profiles"
                else -> "balanced"
            }
        val proactiveMode =
            when {
                mode == "approval_guard" -> "defer_until_approval"
                mode == "voice_guard" || mode == "voice_recovery" -> "defer_for_voice"
                mode == "communication_follow_up" -> "follow_up_first"
                mode == "swarm_coordination" -> "coordination_first"
                mode == "memory_consolidation" -> "memory_first"
                proactiveQueue.priorityMode != "balanced" -> proactiveQueue.priorityMode
                dueSignals > 0 -> "signal_assisted"
                else -> "balanced"
            }
        val entrySurfaceMode =
            when {
                onboarding.pendingSteps.isNotEmpty() -> "guided"
                mode == "voice_guard" -> "hands_free"
                mode == "voice_recovery" -> "repair_voice"
                mode == "communication_follow_up" -> "companion_follow_up"
                voiceInteraction.interactionMode.startsWith("hands_free") -> "hands_free"
                adaptivePolicy.preferredEntrySurfaces.isNotEmpty() -> "adaptive"
                else -> "broad"
            }
        val summary =
            when (mode) {
                "onboarding_guard" -> "当前先补齐阻塞 onboarding，再放开自治动作。"
                "approval_guard" -> "当前先处理审批和回执，再推进新的自动行动。"
                "voice_guard" -> "当前优先保护持续语音链路，减少其他入口抢占执行节奏。"
                "focus_execution" -> "当前围绕活跃任务维持连续执行和低打断协同。"
                "voice_recovery" -> "当前先修复持续语音入口，让手机侧重新回到随身可唤起状态。"
                "swarm_coordination" -> "当前先把多 worker 协同与 mailbox handoff 收口，再放大新的行动。"
                "communication_follow_up" -> "当前更像私人助理的沟通跟进窗口，优先处理消息、联系人和通话线索。"
                "event_driven" -> "当前以外部事件和系统信号为主驱动助手节奏。"
                "proactive_follow_up" -> "当前优先清收已到点的主动跟进事项。"
                "memory_consolidation" -> "当前进入记忆整理窗口，优先回收 thinkback / consolidation / dream 信号。"
                "stability_recovery" -> "当前先处理 provider/trace 稳定性，再恢复扩展自治。"
                else -> "当前进入伴随待命模式，保持感知、记忆和轻量提示。"
            }
        val eventPrioritySummary =
            buildString {
                append("signals=").append(dueSignals)
                append(" communication=").append(dueCommunicationSignals)
                append(" proactive=").append(proactiveQueue.dueCount)
                append(" mailbox=").append(swarmCoordination.pendingMailboxMessages)
                append(" approvals=").append(swarmCoordination.pendingPermissionRequests)
                append(" providers_unhealthy=").append(unhealthyProviders)
                append(" voice=").append(voiceInteraction.state.ifBlank { "-" })
            }
        val userModelSummary =
            listOf(
                userModel.identitySummary.takeIf { it.isNotBlank() },
                userModel.preferenceSummary.takeIf { it.isNotBlank() },
                userModel.routineSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { userModel.summary }
        val workbenchSummary =
            buildString {
                append("tips=").append(tips.size)
                append(" onboarding_pending=").append(onboarding.pendingSteps.size)
                append(" focus_sessions=").append(swarmCoordination.focusSessionIds.size)
                append(" preferred_surfaces=").append(adaptivePolicy.preferredEntrySurfaces.joinToString(",").ifBlank { "-" })
                append(" voice=").append(voiceInteraction.availabilitySummary.ifBlank { voiceInteraction.state.ifBlank { "-" } })
                append(" signals=").append(externalSignals.take(2).joinToString(",") { it.type.name.lowercase() }.ifBlank { "-" })
                memoryInsight.consolidationSummary.takeIf { it.isNotBlank() }?.let {
                    append(" | memory=").append(it.take(56))
                }
            }
        val recommendedCommands =
            buildList {
                if (onboarding.pendingSteps.isNotEmpty()) add("/product-shell --section inbox")
                if (swarmCoordination.pendingPermissionRequests > 0) add("/approval-center")
                if (mode == "voice_guard" || mode == "voice_recovery") add("/product-shell --section entry")
                if (mode == "swarm_coordination") add("/viewer")
                if (mode == "communication_follow_up") add("/today")
                if (assistantSnapshot.activeSession.sessionId.isNotBlank()) add("/viewer")
                if (dueSignals > 0 || proactiveQueue.dueCount > 0) add("/today")
                if (mode == "memory_consolidation") add("/memory-governance")
                addAll(userModel.recommendedCommands)
            }.distinct()
        return AssistantAutonomyPlanSnapshot(
            mode = mode,
            summary = summary,
            triggerPolicyMode = triggerPolicyMode,
            restoreMode = restoreMode,
            proactiveMode = proactiveMode,
            entrySurfaceMode = entrySurfaceMode,
            eventPrioritySummary = eventPrioritySummary,
            userModelSummary = userModelSummary,
            workbenchSummary = workbenchSummary,
            enginePhaseOrder = enginePhaseOrder,
            recommendedCommands = recommendedCommands,
            lines =
                buildList {
                    add("mode=$mode")
                    add(summary)
                    add("events=$eventPrioritySummary")
                    add("user_model=${userModelSummary.ifBlank { "-" }}")
                    add("restore=$restoreMode proactive=$proactiveMode trigger=$triggerPolicyMode entry=$entrySurfaceMode")
                    add("phases=${enginePhaseOrder.joinToString(" -> ")}")
                    add("workbench=$workbenchSummary")
                },
            updatedAtMs = now,
        )
    }
}
