package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.memory.PersonalAssistantUserModelSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryGovernanceSnapshot
import com.lmx.xiaoxuanagent.memory.PersonalMemoryInsightSnapshot
import com.lmx.xiaoxuanagent.runtime.PlatformCapabilityApprovalStore
import com.lmx.xiaoxuanagent.runtime.PlatformCapabilityApprovalRequest
import com.lmx.xiaoxuanagent.runtime.PlatformTraceSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionCommandReceipt
import com.lmx.xiaoxuanagent.runtime.SessionCompensationPlan
import com.lmx.xiaoxuanagent.runtime.SessionConversationCompactSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionExplanationEntry
import com.lmx.xiaoxuanagent.runtime.SessionGraphNode
import com.lmx.xiaoxuanagent.runtime.SessionActionLifecycleStore
import com.lmx.xiaoxuanagent.runtime.SessionGroundingHealthStore
import com.lmx.xiaoxuanagent.runtime.SessionLoopInboxStore
import com.lmx.xiaoxuanagent.runtime.SessionLoopRuntimeStore
import com.lmx.xiaoxuanagent.runtime.SessionMainLoopStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryCuratorStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryForkRuntimeStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryPolicyStore
import com.lmx.xiaoxuanagent.runtime.SessionMemoryNotebookSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPermissionCenterStore
import com.lmx.xiaoxuanagent.runtime.SessionPermissionProductStore
import com.lmx.xiaoxuanagent.runtime.SessionPlatformSnapshot
import com.lmx.xiaoxuanagent.runtime.SessionPlatformFacade
import com.lmx.xiaoxuanagent.runtime.SessionReplayTimelineEntry
import com.lmx.xiaoxuanagent.runtime.SessionSafetyDecisionStore
import com.lmx.xiaoxuanagent.runtime.SessionToolContractStore
import com.lmx.xiaoxuanagent.runtime.SessionToolUseLedgerStore
import com.lmx.xiaoxuanagent.runtime.SessionToolRuntimeStore
import com.lmx.xiaoxuanagent.runtime.SessionTurnLoopStore
import com.lmx.xiaoxuanagent.runtime.SessionWorkingMemorySnapshot
import com.lmx.xiaoxuanagent.safety.behaviorSummary
import com.lmx.xiaoxuanagent.safety.RuntimeSafetyPolicyRule
import java.util.Calendar

internal fun deriveRoutineShellSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    agendaShell: AssistantAgendaShellSnapshot,
    dailyRhythm: AssistantDailyRhythmSnapshot,
    personalFocus: AssistantPersonalFocusSnapshot,
    proactiveTasks: List<AssistantProactiveTask>,
    externalSignals: List<AssistantExternalSignal>,
    routinePolicy: AssistantRoutinePolicySnapshot,
    adaptivePolicy: AssistantAdaptivePolicySnapshot,
    quietHours: AssistantQuietHoursSnapshot,
): AssistantRoutineShellSnapshot {
    val dueTasks = proactiveTasks.filter { it.enabled }.sortedBy { it.fireAtMs }
    val approvalFollowUps = dueTasks.filter { it.type == AssistantProactiveTaskType.APPROVAL_FOLLOW_UP }
    val overdueTasks = dueTasks.filter { it.fireAtMs in 1..now }
    val activeSignals = externalSignals.filter { it.enabled }.sortedBy { it.fireAtMs }
    val taskOs = AssistantTaskOsStore.derive(proactiveTasks, assistantSnapshot.activeSession.sessionId, nowMs = now)
    val checklistLines =
        buildList {
            add("主焦点模板: ${routinePolicy.focusTheme}")
            personalFocus.focusTask.takeIf { it.isNotBlank() }?.let { add("主焦点: $it") }
            agendaShell.focusTitle.takeIf { it.isNotBlank() }?.let { add("先处理: $it") }
            routinePolicy.checklistTemplates.take(2).forEach { add("模板: $it") }
            add("任务 OS: ${taskOs.summary.ifBlank { "-" }}")
            approvalFollowUps.take(1).forEach { add("风险确认: ${it.summary.ifBlank { it.title }}") }
            overdueTasks.take(1).forEach {
                add("逾期事项: ${it.summary.ifBlank { it.title }}${if (it.deferCount > 0) " | 已延后 ${it.deferCount} 次" else ""}")
            }
            dueTasks.take(2).forEach { add("跟进: ${it.title}") }
            activeSignals.take(2).forEach { add("外部信号: ${it.title}") }
            if (assistantSnapshot.activeSession.awaitingConfirmation) {
                add("先完成风险确认，再继续自动执行")
            }
            if (quietHours.activeNow) {
                add("当前处于静默窗口，例行策略以收口和审批为主")
            }
        }.ifEmpty {
            listOf("当前没有堆积例行事项，可直接发起新任务。")
        }
    val followUpLines =
        buildList {
            add("复盘窗口: ${routinePolicy.reviewWindow}")
            add("跟进窗口: ${routinePolicy.followUpWindow}")
            taskOs.lines.take(2).forEach(::add)
            dailyRhythm.followUpSummary.takeIf { it.isNotBlank() }?.let { add(it) }
            dailyRhythm.rhythmLines.take(2).forEach { add(it) }
            approvalFollowUps.take(2).forEach { add("确认跟进: ${it.summary.ifBlank { it.title }}") }
            overdueTasks.take(2).forEach {
                add("逾期处理: ${it.summary.ifBlank { it.title }}${it.recommendedCommand.takeIf(String::isNotBlank)?.let { cmd -> " | $cmd" }.orEmpty()}")
            }
            dueTasks.drop(2).take(2).forEach { add("稍后跟进: ${it.summary.ifBlank { it.title }}") }
        }.ifEmpty {
            listOf("暂无需要延后处理的跟进项。")
        }
    val recommendedCommands =
        listOfNotNull(
            "/today",
            overdueTasks.firstOrNull()?.recommendedCommand?.takeIf { it.isNotBlank() },
            dueTasks.firstOrNull()?.task?.takeIf { it.isNotBlank() }?.let { "/start \"$it\"" },
            "/routine-policy",
            if (assistantSnapshot.activeSession.sessionId.isNotBlank()) "/operator --session-id ${assistantSnapshot.activeSession.sessionId}" else null,
        ).distinct()
    return AssistantRoutineShellSnapshot(
        mode =
            when {
                quietHours.activeNow -> "quiet_hours"
                assistantSnapshot.activeSession.awaitingConfirmation -> "approval_gate"
                dueTasks.isNotEmpty() || activeSignals.isNotEmpty() -> "active_routine"
                else -> "steady"
            },
        summary =
            listOf(
                personalFocus.summary.takeIf { it.isNotBlank() },
                agendaShell.summary.takeIf { it.isNotBlank() },
                dailyRhythm.summary.takeIf { it.isNotBlank() },
            ).filterNotNull().firstOrNull().orEmpty().ifBlank {
                if (quietHours.activeNow) "当前处于静默窗口，优先处理收口、确认和低打断工作。" else "今天的节奏较平稳，可按主焦点推进。"
            },
        nextWindowSummary = if (quietHours.activeNow) quietHours.summary else dailyRhythm.nextWindowSummary.ifBlank { "next_window=-" },
        focusTheme =
            adaptivePolicy.preferredFocusTheme.ifBlank {
                adaptivePolicy.preferredRoutineThemes.firstOrNull().orEmpty().ifBlank {
                    routinePolicy.focusTheme.ifBlank {
                        personalFocus.focusReason.takeIf { it.isNotBlank() }
                            ?: assistantSnapshot.activeSession.summary.takeIf { it.isNotBlank() }
                            ?: "维持稳定节奏"
                    }
                }
            },
        checklistLines = checklistLines,
        followUpLines = followUpLines,
        recommendedCommands = recommendedCommands,
        updatedAtMs = now,
    )
}

internal fun deriveDigestShellSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    onboarding: AssistantOnboardingState,
    tips: List<AssistantTipCard>,
    routineShell: AssistantRoutineShellSnapshot,
    operatorShell: AssistantOperatorShellSnapshot,
    diagnostics: AssistantProductDiagnosticsSnapshot,
    digestPolicy: AssistantDigestPolicySnapshot,
    adaptivePolicy: AssistantAdaptivePolicySnapshot,
    userModel: PersonalAssistantUserModelSnapshot,
    memoryInsight: PersonalMemoryInsightSnapshot,
    voiceInteraction: AssistantVoiceInteractionSnapshot,
    autonomyPlan: AssistantAutonomyPlanSnapshot,
    quietHours: AssistantQuietHoursSnapshot,
): AssistantDigestShellSnapshot {
    val effectiveCadence = adaptivePolicy.preferredDigestCadence.ifBlank { digestPolicy.cadence }
    val headline =
        when {
            !digestPolicy.enabled -> "摘要已关闭，当前只保留必要提醒"
            quietHours.activeNow -> "当前处于静默窗口，小轩只保留关键摘要"
            assistantSnapshot.activeSession.awaitingConfirmation -> "当前有高优先级确认待处理"
            tips.isNotEmpty() -> "当前有 ${tips.size} 条值得你现在处理的提醒"
            onboarding.pendingSteps.isNotEmpty() -> "还有 ${onboarding.pendingSteps.size} 条 onboarding 待完成"
            voiceInteraction.state in setOf("blocked", "error") -> "持续语音入口需要先修复后再承担随身入口"
            else -> "小轩当前处于稳定协作状态"
        }
    val highlights =
        buildList {
            tips.take(2).forEach { add("Tip: ${it.title}") }
            onboarding.steps.filter { it.status == "pending" }.take(2).forEach { add("Onboarding: ${it.title}") }
            operatorShell.urgentLines.take(2).forEach { add("Operator: $it") }
            diagnostics.lines.take(2).forEach { add("诊断: $it") }
            userModel.lines.take(2).forEach { add("用户模型: ${it.take(72)}") }
            memoryInsight.thinkbackLines.take(2).forEach { add("Thinkback: ${it.take(72)}") }
            memoryInsight.dreamLines.take(1).forEach { add("Dream: ${it.take(72)}") }
            if (voiceInteraction.summary.isNotBlank()) add("Voice: ${voiceInteraction.summary.take(72)}")
            autonomyPlan.lines.take(2).forEach { add("自治计划: ${it.take(72)}") }
            add("摘要节奏: $effectiveCadence")
            add("投递入口: ${digestPolicy.deliverySurfaces.joinToString(",").ifBlank { "-" }}")
        }.ifEmpty {
            listOf(routineShell.summary)
        }.take(digestPolicy.maxHighlights.coerceAtLeast(1))
    val actionCommand =
        when {
            !digestPolicy.enabled -> "/digest-policy"
            assistantSnapshot.activeSession.awaitingConfirmation -> "/approve ${assistantSnapshot.activeSession.sessionId}"
            tips.isNotEmpty() -> "/tip --tip-id ${tips.first().id} --action complete"
            onboarding.pendingSteps.isNotEmpty() -> "/onboarding-step --step-id ${onboarding.steps.first { it.status == "pending" }.id} --action complete"
            voiceInteraction.state in setOf("blocked", "error") -> "/product-shell --section entry"
            else -> "/today"
        }
    return AssistantDigestShellSnapshot(
        mode =
            when {
                !digestPolicy.enabled -> "disabled"
                quietHours.activeNow -> "quiet_hours"
                assistantSnapshot.activeSession.awaitingConfirmation -> "approval"
                voiceInteraction.state in setOf("blocked", "error") -> "voice_repair"
                tips.isNotEmpty() || onboarding.pendingSteps.isNotEmpty() -> "actionable"
                else -> "calm"
            },
        title = "今日摘要",
        summary = headline,
        highlightLines = highlights,
        actionLabel =
            when {
                !digestPolicy.enabled -> "打开摘要策略"
                assistantSnapshot.activeSession.awaitingConfirmation -> "先处理确认"
                tips.isNotEmpty() -> "处理下一条 Tip"
                onboarding.pendingSteps.isNotEmpty() -> "完成下一条引导"
                voiceInteraction.state in setOf("blocked", "error") -> "修复语音入口"
                else -> "查看今日节奏"
            },
        actionCommand = actionCommand,
        updatedAtMs = now,
    )
}

internal fun deriveCompanionShellSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    providers: List<AssistantSignalProviderState>,
    externalSignals: List<AssistantExternalSignal>,
    agendaShell: AssistantAgendaShellSnapshot,
    dailyRhythm: AssistantDailyRhythmSnapshot,
    personalFocus: AssistantPersonalFocusSnapshot,
    swarmStrategy: AssistantSwarmStrategy,
    voiceInteraction: AssistantVoiceInteractionSnapshot,
    userModel: PersonalAssistantUserModelSnapshot,
    memoryInsight: PersonalMemoryInsightSnapshot,
    autonomyPlan: AssistantAutonomyPlanSnapshot,
): AssistantCompanionShellSnapshot {
    val enabledProviders = providers.count { it.enabled }
    val dueSignals = externalSignals.count { it.enabled && it.fireAtMs in 1..now }
    val mode =
        when {
            voiceInteraction.state in setOf("listening", "partial", "executing") -> "voice_companion"
            assistantSnapshot.activeSession.awaitingConfirmation -> "approval_companion"
            swarmStrategy.pendingPermissionRequests > 0 || swarmStrategy.pendingMailboxMessages > 0 -> "swarm_companion"
            memoryInsight.suggestionSummary.isNotBlank() -> "memory_companion"
            else -> "ambient_companion"
        }
    val summary =
        when (mode) {
            "voice_companion" -> voiceInteraction.summary.ifBlank { "正在通过语音伴随当前执行。" }
            "approval_companion" -> "当前有关键确认待你拍板，小轩先收口风险动作。"
            "swarm_companion" -> swarmStrategy.collaboratorSummary.ifBlank { swarmStrategy.summary.ifBlank { "多 worker 正在协同推进。" } }
            "memory_companion" -> memoryInsight.suggestionSummary
            else -> personalFocus.summary.ifBlank { dailyRhythm.summary.ifBlank { "当前保持轻量陪伴和感知待命。" } }
        }
    return AssistantCompanionShellSnapshot(
        mode = mode,
        summary = summary,
        presenceSummary =
            buildString {
                append("focus=").append(personalFocus.focusTask.ifBlank { assistantSnapshot.activeSession.task.ifBlank { "-" } })
                append(" | agenda=").append(agendaShell.mode)
                append(" | rhythm=").append(dailyRhythm.mode)
            },
        voiceSummary =
            listOf(
                voiceInteraction.summary.takeIf { it.isNotBlank() },
                voiceInteraction.pendingConfirmation.takeIf { it.isNotBlank() },
                voiceInteraction.spokenSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { voiceInteraction.availabilitySummary.ifBlank { "voice_unknown" } },
        swarmSummary =
            listOf(
                swarmStrategy.summary.takeIf { it.isNotBlank() },
                swarmStrategy.collaboratorSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { "swarm_idle" },
        memorySummary =
            listOf(
                userModel.relationshipSummary.takeIf { it.isNotBlank() },
                memoryInsight.suggestionSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ").ifBlank { userModel.summary.ifBlank { "memory_idle" } },
        providerSummary =
            "providers=$enabledProviders/${providers.size} due_signals=$dueSignals top=${providers.maxByOrNull { it.routingPriority }?.providerId ?: "-"}",
        nextActionSummary =
            listOf(
                personalFocus.nextBestActions.firstOrNull(),
                swarmStrategy.handoffActions.firstOrNull(),
                autonomyPlan.recommendedCommands.firstOrNull(),
            ).filterNotNull().firstOrNull().orEmpty(),
        laneLines =
            buildList {
                add("现在 | ${personalFocus.focusTask.ifBlank { agendaShell.focusTitle.ifBlank { summary } }}")
                add("语音 | ${voiceInteraction.spokenSummary.ifBlank { voiceInteraction.summary.ifBlank { voiceInteraction.availabilitySummary.ifBlank { "-" } } }}")
                add("协同 | ${swarmStrategy.collaboratorSummary.ifBlank { swarmStrategy.summary.ifBlank { "-" } }}")
                add("记忆 | ${memoryInsight.suggestionSummary.ifBlank { userModel.relationshipSummary.ifBlank { userModel.routineSummary.ifBlank { "-" } } }}")
                add("系统 | ${dailyRhythm.signalPressureSummary.ifBlank { "signals=-" }}")
            }.distinct(),
        recommendedCommands =
            buildList {
                addAll(personalFocus.nextBestActions.take(2))
                addAll(autonomyPlan.recommendedCommands.take(2))
                if (voiceInteraction.availabilitySummary == "voice_ready") add("/product-shell --section entry")
                if (memoryInsight.suggestionSummary.isNotBlank()) add("/memory-governance")
            }.distinct(),
        updatedAtMs = now,
    )
}

internal fun deriveInterruptBudgetSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    mailboxPendingApprovals: Int,
    proactiveTasks: List<AssistantProactiveTask>,
    externalSignals: List<AssistantExternalSignal>,
    interruptPolicy: AssistantInterruptPolicySnapshot,
    adaptivePolicy: AssistantAdaptivePolicySnapshot,
    voiceInteraction: AssistantVoiceInteractionSnapshot,
    quietHours: AssistantQuietHoursSnapshot,
): AssistantInterruptBudgetSnapshot {
    val enabledSignals = externalSignals.count { it.enabled }
    val enabledTasks = proactiveTasks.count { it.enabled }
    val totalBudget =
        if (assistantSnapshot.activeSession.sessionId.isNotBlank()) {
            interruptPolicy.focusBudget.coerceAtLeast(1)
        } else {
            interruptPolicy.baseBudget.coerceAtLeast(1)
        }
    val consumed =
        listOf(
            if (assistantSnapshot.activeSession.sessionId.isNotBlank()) 1 else 0,
            mailboxPendingApprovals.coerceAtMost(2),
            if (enabledSignals >= 4) 1 else 0,
            if (enabledTasks >= 6) 1 else 0,
        ).sum().coerceAtMost(totalBudget)
    val remaining = (totalBudget - consumed).coerceAtLeast(0)
    val hardBlock =
        assistantSnapshot.activeSession.awaitingConfirmation ||
            mailboxPendingApprovals >= 2 ||
            (quietHours.activeNow && interruptPolicy.hardBlockInQuietHours)
    val allowedSources =
        buildList {
            addAll(adaptivePolicy.preferredEntrySurfaces)
            addAll(interruptPolicy.preferredSources)
            if (remaining > 1) add("widget")
            if (!hardBlock) add("tile")
            if (remaining > 0) add("shortcut")
            if (!assistantSnapshot.activeSession.awaitingConfirmation && voiceInteraction.availabilitySummary == "voice_ready") add("voice")
            addAll(adaptivePolicy.trustedSignalSources)
            if (quietHours.activeNow && interruptPolicy.hardBlockInQuietHours) {
                remove("voice")
                remove("share_sheet")
            }
        }.distinct().filterNot { it in interruptPolicy.blockedSources || it in adaptivePolicy.blockedEntrySurfaces }
    val blockedSources =
        buildList {
            if (hardBlock) {
                add("voice")
                add("share_sheet")
            }
            if (voiceInteraction.availabilitySummary != "voice_ready") {
                add("voice")
            }
            if (remaining <= 0) {
                add("widget")
                add("shortcut")
            }
            addAll(interruptPolicy.blockedSources)
            addAll(adaptivePolicy.blockedEntrySurfaces)
        }.distinct()
    return AssistantInterruptBudgetSnapshot(
        mode =
            when {
                quietHours.activeNow && interruptPolicy.hardBlockInQuietHours -> "quiet_hours_block"
                hardBlock -> "hard_block"
                remaining <= 1 -> "tight"
                else -> "open"
            },
        summary =
            when {
                quietHours.activeNow && interruptPolicy.hardBlockInQuietHours -> "当前位于静默窗口，除关键通知外暂停主动打断。"
                hardBlock -> "当前以确认和收口为主，减少新的打断入口。"
                remaining <= 1 -> "当前打断预算偏紧，建议只保留关键入口。"
                else -> "当前仍保留足够的手机入口预算。"
            },
        totalBudget = totalBudget,
        consumedBudget = consumed,
        remainingBudget = remaining,
        hardBlock = hardBlock,
        cooldownSummary =
            "signals=$enabledSignals proactive=$enabledTasks approvals=$mailboxPendingApprovals preferred=${interruptPolicy.preferredSources.joinToString(",").ifBlank { "-" }}",
        allowedSources = allowedSources,
        blockedSources = blockedSources,
        recommendedCommands = listOf("/interrupt-budget", "/interrupt-policy", "/entry-surfaces", "/today"),
        updatedAtMs = now,
    )
}

internal fun deriveViewerShellSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    focusSessionId: String,
    focusPlatformSnapshot: SessionPlatformSnapshot?,
    replayTimeline: List<SessionReplayTimelineEntry>,
    sessionGraph: List<SessionGraphNode>,
    pendingCapabilityApprovals: List<PlatformCapabilityApprovalRequest>,
    compensationPlans: List<SessionCompensationPlan>,
    traceSnapshot: PlatformTraceSnapshot,
    userModel: PersonalAssistantUserModelSnapshot,
    conversationCompact: SessionConversationCompactSnapshot?,
    autonomyPlan: AssistantAutonomyPlanSnapshot,
    workingMemory: SessionWorkingMemorySnapshot?,
    recentCommands: List<SessionCommandReceipt>,
    explanationEntries: List<SessionExplanationEntry>,
    notebookSnapshot: SessionMemoryNotebookSnapshot?,
): AssistantViewerShellSnapshot {
    val turnLoopSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionTurnLoopStore::readSnapshot)
    val toolLedger = focusSessionId.takeIf { it.isNotBlank() }?.let { SessionToolUseLedgerStore.readRecent(it, limit = 6) }.orEmpty()
    val mainLoopSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionMainLoopStore::readSnapshot)
    val loopRuntimeSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionLoopRuntimeStore::readSnapshot)
    val loopInboxSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionLoopInboxStore::readSnapshot)
    val toolRuntimeSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let { SessionToolRuntimeStore.readSnapshot(it) }
    val toolContractSnapshot = SessionToolContractStore.readSnapshot(limit = 6)
    val actionLifecycleSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let { SessionActionLifecycleStore.readSnapshot(it) }
    val groundingHealthSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let { SessionGroundingHealthStore.readSnapshot(it) }
    val permissionCenterSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let { SessionPermissionCenterStore.readSnapshot(it) }
    val permissionProductSnapshot =
        focusSessionId.takeIf { it.isNotBlank() }?.let { SessionPermissionProductStore.readSnapshot(it) ?: SessionPermissionProductStore.refresh(it) }
    val memoryPolicySnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryPolicyStore::readSnapshot)
    val memoryCuratorSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryCuratorStore::readSnapshot)
    val memoryForkSnapshot = focusSessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryForkRuntimeStore::readSnapshot)
    val memoryMaintenance = com.lmx.xiaoxuanagent.runtime.SessionMemoryMaintenanceStore.read()
    val followUpHealth = AssistantFollowUpHealthStore.derive(SessionPlatformFacade.readProactiveTasks(limit = 24), nowMs = now)
    val focusGraph = sessionGraph.filter { node ->
        focusSessionId.isBlank() || node.rootSessionId == focusSessionId || node.sessionId == focusSessionId || node.parentSessionId == focusSessionId
    }
    val focusTask =
        focusPlatformSnapshot?.bridgeSnapshot?.task
            .orEmpty()
            .ifBlank { assistantSnapshot.activeSession.task }
    val detailLines =
        buildList {
            add("session=${focusSessionId.ifBlank { assistantSnapshot.activeSession.sessionId.ifBlank { "-" } }}")
            add("task=${focusTask.ifBlank { "-" }}")
            add("status=${focusPlatformSnapshot?.bridgeSnapshot?.statusCode ?: assistantSnapshot.activeSession.statusCode}")
            add("result=${focusPlatformSnapshot?.bridgeSnapshot?.resultSummary.orEmpty().ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.errorSummary.orEmpty().ifBlank { assistantSnapshot.activeSession.summary.ifBlank { "-" } } }}")
            add("entry=${focusPlatformSnapshot?.bridgeSnapshot?.entrySource.orEmpty().ifBlank { assistantSnapshot.activeSession.entrySource }}")
            conversationCompact?.conversationSummary?.takeIf { it.isNotBlank() }?.let { add("compact=$it") }
            workingMemory?.progressSummary?.takeIf { it.isNotBlank() }?.let { add("working_memory=$it") }
            workingMemory?.nextFocusHint?.takeIf { it.isNotBlank() }?.let { add("next_focus=$it") }
            explanationEntries.firstOrNull()?.summary?.takeIf { it.isNotBlank() }?.let { add("why=$it") }
            notebookSnapshot?.markdownPath?.takeIf { it.isNotBlank() }?.let { path -> add("notebook=$path") }
            turnLoopSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("turn_loop=$it") }
            mainLoopSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("main_loop=$it") }
            loopRuntimeSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("loop_runtime=$it") }
            loopInboxSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("loop_inbox=$it") }
            toolLedger.firstOrNull()?.let { add("tool_ledger=${it.toolName} | ${it.status.name.lowercase()} | ${it.summary.take(72)}") }
            toolRuntimeSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("tool_runtime=$it") }
            toolContractSnapshot.summary.takeIf { it.isNotBlank() }?.let { add("tool_contracts=$it") }
            actionLifecycleSnapshot?.attentionSummary?.takeIf { it.isNotBlank() }?.let { add("action_lifecycle=$it") }
            groundingHealthSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("grounding=$it") }
            permissionCenterSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("permission_center=$it") }
            permissionProductSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("permission_product=$it") }
            permissionProductSnapshot?.activeTab?.takeIf { it.isNotBlank() }?.let { add("permission_product_tab=$it/${permissionProductSnapshot.cardCount}") }
            memoryPolicySnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_policy=$it") }
            memoryCuratorSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_curator=$it") }
            memoryForkSnapshot?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_fork=$it") }
            add("memory_maintenance=${memoryMaintenance.lastStatus.ifBlank { "-" }} | pending=${memoryMaintenance.pendingCount} failed=${memoryMaintenance.failedCount}")
            add("follow_up_health=${followUpHealth.summary.ifBlank { "-" }}")
            if (compensationPlans.isNotEmpty()) {
                add("compensations=${compensationPlans.count { it.status == "planned" }}/${compensationPlans.size}")
            }
        }
    val timelineLines =
        replayTimeline.takeLast(6).map { entry ->
            "#${entry.commandIndex} ${entry.commandType} | ${entry.transition} | ${entry.statusCode}"
        }.ifEmpty { listOf("暂无可视化 timeline，可先运行 /timeline --session-id <id>") }
    val graphLines =
        focusGraph.take(6).map { node ->
            "${node.sessionId} | depth=${node.depth} | status=${node.status} | children=${node.childSessionIds.size} | approvals=${node.pendingApprovalCount}"
        }.ifEmpty { listOf("暂无可视化 graph 节点。") }
    val approvalLines =
        buildList {
            assistantSnapshot.approvalSessions.take(4).forEach { session ->
                add("${session.sessionId} | ${session.task.ifBlank { "-" }} | ${session.summary.ifBlank { session.statusCode }}")
            }
            pendingCapabilityApprovals.take(4).forEach { approval ->
                add("${approval.approvalId} | ${approval.capability.name.lowercase()} | ${approval.summary}")
            }
        }.ifEmpty { listOf("当前没有待处理审批。") }
    val traceLines =
        buildList {
            traceSnapshot.categorySummary.takeIf { it.isNotBlank() }?.let { add("summary | $it") }
            traceSnapshot.coverageSummary.takeIf { it.isNotBlank() }?.let { add("coverage | $it") }
            traceSnapshot.attentionSummary.takeIf { traceSnapshot.attentionCount > 0 && it.isNotBlank() }?.let { add("attention | $it") }
            conversationCompact?.boundaryDigests?.takeLast(2)?.forEach { add("compact | ${it.take(96)}") }
            workingMemory?.openLoops?.take(2)?.forEach { add("working_memory | open_loop | ${it.take(96)}") }
            workingMemory?.cautionNotes?.take(2)?.forEach { add("working_memory | caution | ${it.take(96)}") }
            explanationEntries.take(3).forEach { add("why | ${it.phase} | ${it.summary.take(96)}") }
            notebookSnapshot?.previewLines?.take(3)?.forEach { line -> add("notebook | ${line.take(96)}") }
            turnLoopSnapshot?.blockerLines?.forEach { add("turn_loop | blocker | ${it.take(96)}") }
            mainLoopSnapshot?.queueSummary?.takeIf { it.isNotBlank() }?.let { add("main_loop | ${it.take(96)}") }
            loopRuntimeSnapshot?.lines?.take(2)?.forEach { add("loop_runtime | ${it.take(96)}") }
            loopInboxSnapshot?.lines?.take(2)?.forEach { add("loop_inbox | ${it.take(96)}") }
            toolLedger.take(3).forEach { add("tool | ${it.status.name.lowercase()} | ${it.toolName} | ${it.summary.take(96)}") }
            toolContractSnapshot.lines.take(2).forEach { add("tool_contract | ${it.take(96)}") }
            actionLifecycleSnapshot?.lines?.take(2)?.forEach { add("action_lifecycle | ${it.take(96)}") }
            groundingHealthSnapshot?.lines?.take(2)?.forEach { add("grounding | ${it.take(96)}") }
            permissionCenterSnapshot?.lines?.take(2)?.forEach { add("permission | ${it.take(96)}") }
            permissionProductSnapshot?.lines?.take(2)?.forEach { add("permission_product | ${it.take(96)}") }
            permissionProductSnapshot?.tabs?.take(2)?.forEach { add("permission_product_tab | ${it.id} | ${it.count} | active=${it.active}") }
            permissionProductSnapshot?.cards?.take(2)?.forEach { add("permission_product_card | ${it.title.take(96)}") }
            memoryPolicySnapshot?.lastReason?.takeIf { it.isNotBlank() }?.let { add("memory_policy | ${it.take(96)}") }
            memoryCuratorSnapshot?.lines?.take(2)?.forEach { add("memory_curator | ${it.take(96)}") }
            memoryForkSnapshot?.lines?.take(2)?.forEach { add("memory_fork | ${it.take(96)}") }
            memoryMaintenance.lastSummary.takeIf { it.isNotBlank() }?.let { add("memory_maintenance | ${it.take(96)}") }
            followUpHealth.topLines.take(2).forEach { add("follow_up_health | ${it.take(96)}") }
            addAll(traceSnapshot.recentLines.take(6))
        }.ifEmpty { listOf("暂无近期 trace。") }
    val actionLines =
        buildList {
            autonomyPlan.enginePhaseOrder.take(4).forEachIndexed { index, phase ->
                add("phase_${index + 1} | $phase")
            }
            compensationPlans.take(2).forEach { plan ->
                add("compensation | ${plan.sessionId}#${plan.turn} | ${plan.status}")
            }
            pendingCapabilityApprovals.take(2).forEach { approval ->
                add("approval | ${approval.approvalId} | ${approval.permissionFamily.ifBlank { "general" }} | ${approval.capability.name.lowercase()}")
            }
            userModel.recommendedCommands.take(2).forEach { command ->
                add("memory_action | $command")
            }
            conversationCompact?.recommendedCommands?.take(2)?.forEach { command ->
                add("compact_action | $command")
            }
            recentCommands.take(2).forEach { receipt ->
                add("command | ${receipt.status.name.lowercase()} | ${receipt.summary.take(88)}")
            }
            explanationEntries.take(2).forEach { entry ->
                add("why_action | #${entry.turn} | ${entry.actionLabel.ifBlank { entry.title }.take(88)}")
            }
            notebookSnapshot?.previewLines?.drop(3)?.take(2)?.forEach { line ->
                add("notebook_action | ${line.take(88)}")
            }
            turnLoopSnapshot?.recommendedCommands?.take(2)?.forEach { command ->
                add("loop_action | $command")
            }
            loopInboxSnapshot?.recommendedCommands?.take(2)?.forEach { command ->
                add("loop_inbox_action | $command")
            }
            toolLedger.take(2).forEach { entry ->
                add("tool_action | ${entry.toolName} | ${entry.status.name.lowercase()} | ${entry.durationMs}ms")
            }
            mainLoopSnapshot?.recommendedCommands?.take(2)?.forEach { add("loop_runtime_action | $it") }
            loopRuntimeSnapshot?.recommendedCommands?.take(2)?.forEach { add("loop_runtime_contract | $it") }
            toolRuntimeSnapshot?.lines?.firstOrNull()?.let { add("tool_runtime_action | ${it.take(88)}") }
            toolContractSnapshot.lines.firstOrNull()?.let { add("tool_contract_action | ${it.take(88)}") }
            actionLifecycleSnapshot?.recommendedCommands?.take(2)?.forEach { add("lifecycle_action | $it") }
            groundingHealthSnapshot?.takeIf { it.failureCount > 0 || it.retryCount > 0 }?.let {
                add("grounding_action | /grounding-health --session-id ${focusSessionId}")
            }
            permissionCenterSnapshot?.recommendedCommands?.take(2)?.forEach { add("permission_action | $it") }
            permissionProductSnapshot?.recommendedCommands?.take(2)?.forEach { add("permission_product_action | $it") }
            permissionProductSnapshot?.tabs?.firstOrNull { !it.active }?.let { add("permission_product_switch | /permission-product --session-id $focusSessionId --tab ${it.id}") }
            memoryPolicySnapshot?.recommendedCommands?.take(2)?.forEach { add("memory_policy_action | $it") }
            memoryCuratorSnapshot?.recommendedCommands?.take(2)?.forEach { add("memory_curator_action | $it") }
            memoryForkSnapshot?.recommendedCommands?.take(2)?.forEach { add("memory_fork_action | $it") }
            if (memoryMaintenance.failedCount > 0 || memoryMaintenance.deferredCount > 0) {
                add("maintenance_action | /memory-maintenance")
            }
            if (followUpHealth.overdueCount > 0) {
                add("follow_up_action | /follow-up-health")
            }
        }.ifEmpty { listOf("暂无 viewer action lane。") }
    return AssistantViewerShellSnapshot(
        mode = if (focusSessionId.isBlank() && replayTimeline.isEmpty()) "idle" else "focused",
        focusSessionId = focusSessionId,
        focusTask = focusTask,
        detailSummary =
            focusPlatformSnapshot?.bridgeSnapshot?.resultSummary
                .orEmpty()
                .ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.errorSummary.orEmpty() }
                .ifBlank { assistantSnapshot.activeSession.summary.ifBlank { "当前没有活跃会话详情。" } },
        timelineSummary = "timeline=${replayTimeline.size} trace=${traceSnapshot.totalCount} attention=${traceSnapshot.attentionCount}",
        graphSummary = "nodes=${focusGraph.size} active=${assistantSnapshot.health.activeSessionId.ifBlank { "-" }}",
        approvalSummary = "session=${assistantSnapshot.approvalSessions.size} capability=${pendingCapabilityApprovals.size}",
        resultSummary = focusPlatformSnapshot?.bridgeSnapshot?.resultSummary.orEmpty().ifBlank { focusPlatformSnapshot?.bridgeSnapshot?.errorSummary.orEmpty().ifBlank { assistantSnapshot.activeSession.summary } },
        actionLaneSummary = "${autonomyPlan.mode} | ${autonomyPlan.workbenchSummary.ifBlank { userModel.summary.ifBlank { "-" } }}",
        detailLines =
            detailLines +
                workingMemory?.recentFacts?.take(2)?.map { fact ->
                    "working_fact | ${fact.take(96)}"
                }.orEmpty() +
                compensationPlans.take(2).map { plan ->
                    "compensation | turn=${plan.turn} | ${plan.status} | ${plan.summary}"
                },
        timelineLines = timelineLines,
        graphLines = graphLines,
        approvalLines = approvalLines,
        traceLines = traceLines,
        actionLines = actionLines,
        recommendedCommands =
            listOfNotNull(
                "/viewer",
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/timeline --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/graph --session-id $it" },
                focusSessionId.takeIf { compensationPlans.isNotEmpty() && it.isNotBlank() }?.let { "/compensations --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/main-loop --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/turn-loop --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/loop-runtime --session-id $it" },
                focusSessionId.takeIf { loopInboxSnapshot?.totalCount ?: 0 > 0 && it.isNotBlank() }?.let { "/loop-inbox --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/tool-ledger --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/tool-runtime --session-id $it" },
                "/tool-contracts",
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/grounding-health --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/permission-product --session-id $it" },
                focusSessionId.takeIf { it.isNotBlank() }?.let { "/memory-fork --session-id $it" },
                if (memoryMaintenance.failedCount > 0 || memoryMaintenance.deferredCount > 0) "/memory-maintenance" else null,
                if (followUpHealth.overdueCount > 0) "/follow-up-health" else null,
                if (assistantSnapshot.approvalSessions.isNotEmpty() || pendingCapabilityApprovals.isNotEmpty()) "/approval-center" else null,
            ) + autonomyPlan.recommendedCommands.take(2),
        updatedAtMs = now,
    )
}

internal fun deriveGovernanceShellSnapshot(
    now: Long,
    assistantSnapshot: AssistantOsSnapshot,
    providerPolicy: com.lmx.xiaoxuanagent.agent.PlannerProviderPolicy,
    retentionPreviewSummary: String,
    pendingCapabilityApprovals: List<PlatformCapabilityApprovalRequest>,
    memoryGovernance: PersonalMemoryGovernanceSnapshot,
    quietHours: AssistantQuietHoursSnapshot,
    digestPolicy: AssistantDigestPolicySnapshot,
    interruptPolicy: AssistantInterruptPolicySnapshot,
    historySummary: String,
    pendingCompensationPlans: List<SessionCompensationPlan>,
    userModel: PersonalAssistantUserModelSnapshot,
    memoryInsight: PersonalMemoryInsightSnapshot,
    voiceInteraction: AssistantVoiceInteractionSnapshot,
    conversationCompact: SessionConversationCompactSnapshot?,
    autonomyPlan: AssistantAutonomyPlanSnapshot,
    safetyPolicies: List<RuntimeSafetyPolicyRule>,
): AssistantGovernanceShellSnapshot =
    run {
        val activeSessionId = assistantSnapshot.activeSession.sessionId
        val permissionProduct =
            SessionPermissionProductStore.readSnapshot(activeSessionId)
                ?: SessionPermissionProductStore.refresh(activeSessionId)
        val memoryFork = activeSessionId.takeIf { it.isNotBlank() }?.let(SessionMemoryForkRuntimeStore::readSnapshot)
        val loopRuntime = activeSessionId.takeIf { it.isNotBlank() }?.let(SessionLoopRuntimeStore::readSnapshot)
        val toolContracts = SessionToolContractStore.readSnapshot(limit = 6)
        AssistantGovernanceShellSnapshot(
        mode =
            when {
                pendingCompensationPlans.isNotEmpty() -> "rollback_ready"
                pendingCapabilityApprovals.isNotEmpty() || assistantSnapshot.approvalSessions.isNotEmpty() -> "review_required"
                quietHours.enabled -> "policy_managed"
                else -> "transparent"
            },
        summary =
            when {
                pendingCompensationPlans.isNotEmpty() ->
                    "当前有 ${pendingCompensationPlans.size} 条补偿计划待执行，治理面已可直接回滚关键动作。"
                pendingCapabilityApprovals.isNotEmpty() || assistantSnapshot.approvalSessions.isNotEmpty() ->
                    "当前有审批与治理事项待你确认。"
                else -> "当前治理面已接入审批、隐私、历史与策略说明。"
            },
        consentSummary = "permission=${assistantSnapshot.permissionMode.name.lowercase()} safety=${assistantSnapshot.safetyMode.name.lowercase()}",
        privacySummary = memoryGovernance.summary.ifBlank { "memory_entries=${memoryGovernance.totalEntries}" },
        historySummary = historySummary,
        approvalSummary =
            buildString {
                val activeGrants = PlatformCapabilityApprovalStore.readGrants(limit = 6, includeInactive = false)
                append("session=").append(assistantSnapshot.approvalSessions.size)
                append(" capability=").append(pendingCapabilityApprovals.size)
                append(" grants=").append(activeGrants.size)
                val families =
                    pendingCapabilityApprovals
                        .groupBy { it.permissionFamily.ifBlank { "general" } }
                        .entries
                        .sortedByDescending { it.value.size }
                        .take(3)
                        .joinToString(",") { "${it.key}:${it.value.size}" }
                if (families.isNotBlank()) append(" families=").append(families)
            },
        retentionSummary = retentionPreviewSummary.ifBlank { "-" },
        providerPolicySummary =
            "enabled=${providerPolicy.enabled} resume=${providerPolicy.preferTextOnResume} artifact=${providerPolicy.preferTextOnArtifactHeavyStage}",
        autonomySummary = "${autonomyPlan.mode} | ${autonomyPlan.summary.ifBlank { userModel.summary.ifBlank { "-" } }}",
        permissionProductSummary = permissionProduct.summary,
        permissionProductActiveTab = permissionProduct.activeTab,
        permissionProductTabs =
            permissionProduct.tabs.map { tab ->
                AssistantPermissionProductTabSnapshot(
                    id = tab.id,
                    title = tab.title,
                    count = tab.count,
                    summary = tab.summary,
                    active = tab.active,
                )
            },
        permissionProductCards =
            permissionProduct.cards.take(6).map { card ->
                AssistantPermissionProductCardSnapshot(
                    cardId = card.ruleId,
                    cardType = card.cardType,
                    behavior = card.behavior,
                    title = card.title,
                    subtitle = card.subtitle,
                    scope = card.scope,
                    sourceTag = card.sourceTag,
                    surfaceHint = card.surfaceHint,
                    explanation = card.explanation,
                    primaryCommand = card.primaryCommand,
                )
            },
        explanationLines =
            buildList {
                val memoryMaintenance = com.lmx.xiaoxuanagent.runtime.SessionMemoryMaintenanceStore.read()
                val followUpHealth = AssistantFollowUpHealthStore.derive(SessionPlatformFacade.readProactiveTasks(limit = 24), nowMs = now)
                val taskOs = AssistantTaskOsStore.derive(SessionPlatformFacade.readProactiveTasks(limit = 24), assistantSnapshot.activeSession.sessionId, nowMs = now)
                val permissionCenter = SessionPermissionCenterStore.readSnapshot(assistantSnapshot.activeSession.sessionId)
                val memoryPolicy = SessionMemoryPolicyStore.readSnapshot(assistantSnapshot.activeSession.sessionId)
                val memoryCurator = SessionMemoryCuratorStore.readSnapshot(assistantSnapshot.activeSession.sessionId)
                val actionLifecycle = SessionActionLifecycleStore.readSnapshot(assistantSnapshot.activeSession.sessionId)
                add("静默窗口: ${quietHours.summary.ifBlank { "未启用" }}")
                add("摘要策略: enabled=${digestPolicy.enabled} cadence=${digestPolicy.cadence} max=${digestPolicy.maxHighlights}")
                add("打断策略: base=${interruptPolicy.baseBudget} focus=${interruptPolicy.focusBudget} quiet_block=${interruptPolicy.hardBlockInQuietHours}")
                add("provider 路由: ${if (providerPolicy.enabled) "启用" else "关闭"}")
                add("用户模型: ${userModel.preferenceSummary.ifBlank { userModel.summary.ifBlank { "-" } }}")
                add("记忆整理: ${memoryInsight.consolidationSummary.ifBlank { memoryInsight.summary.ifBlank { "-" } }}")
                add("后台记忆维护: pending=${memoryMaintenance.pendingCount} failed=${memoryMaintenance.failedCount} last=${memoryMaintenance.lastStatus.ifBlank { "-" }}")
                add("记忆策略: ${memoryPolicy?.let { it.summary.ifBlank { "-" } } ?: "-"}")
                add("记忆整理器: ${memoryCurator?.summary?.ifBlank { "-" } ?: "-"}")
                add("跟进收件箱: ${followUpHealth.summary.ifBlank { "-" }}")
                add("任务 OS: ${taskOs.summary.ifBlank { "-" }}")
                add("权限中心: ${permissionCenter.summary.ifBlank { "-" }}")
                add("授权产品面: ${permissionProduct.summary.ifBlank { "-" }}")
                add("授权产品分栏: ${permissionProduct.tabs.joinToString(" / ") { "${it.id}:${it.count}${if (it.active) "*" else ""}" }.ifBlank { "-" }}")
                add("loop runtime: ${loopRuntime?.summary?.ifBlank { "-" } ?: "-"}")
                add("tool contracts: ${toolContracts.summary.ifBlank { "-" }}")
                add("动作生命周期: ${actionLifecycle.attentionSummary.ifBlank { "-" }}")
                add("memory fork: ${memoryFork?.summary?.ifBlank { "-" } ?: "-"}")
                add("语音入口: ${voiceInteraction.availabilitySummary.ifBlank { voiceInteraction.state.ifBlank { "-" } }}")
                add("授权系统: active=${PlatformCapabilityApprovalStore.readGrants(limit = 4, includeInactive = false).size}")
                add("显式安全规则: ${safetyPolicies.size}")
                SessionSafetyDecisionStore.readRecent(limit = 3).forEach { add("安全历史: ${it.outcome} | ${it.summary.take(88)}") }
                conversationCompact?.conversationSummary?.takeIf { it.isNotBlank() }?.let { add("长会话压缩: ${it.take(96)}") }
                add("自治计划: ${autonomyPlan.eventPrioritySummary.ifBlank { "-" }}")
                if (pendingCompensationPlans.isNotEmpty()) {
                    add("补偿执行: pending=${pendingCompensationPlans.size}")
                }
            },
        controlLines =
            buildList {
                add("memory_workspace=${memoryGovernance.workspaceSummary.ifBlank { "-" }}")
                pendingCompensationPlans.take(3).forEach { plan ->
                    add("compensation ${plan.sessionId}#${plan.turn} | ${plan.status} | ${plan.summary.take(64)}")
                }
                memoryGovernance.auditTrail.take(3).forEach { audit ->
                    add("audit ${audit.action} | ${audit.summary.take(64)}")
                }
                pendingCapabilityApprovals.take(3).forEach { approval ->
                    add("approval ${approval.approvalId} | ${approval.permissionFamily.ifBlank { "general" }} | ${approval.riskLevel} | ${approval.summary.take(64)}")
                }
                PlatformCapabilityApprovalStore.readGrants(limit = 3, includeInactive = false).forEach { grant ->
                    add("grant ${grant.grantId} | ${grant.scope} | ${grant.permissionFamily.ifBlank { "general" }}")
                }
                add("permission_product ${permissionProduct.summary.ifBlank { "-" }}")
                permissionProduct.tabs.take(3).forEach { add("permission_product_tab ${it.id} | ${it.count} | active=${it.active}") }
                permissionProduct.cards.take(2).forEach { add("permission_product_card ${it.title.take(88)} | ${it.scope.take(72)}") }
                permissionProduct.lines.take(2).forEach { add("permission_product_line ${it.take(88)}") }
                safetyPolicies.take(3).forEach { rule ->
                    add("safety_rule ${rule.ruleId} | ${rule.behavior.name.lowercase()} | ${rule.actionFamily} | ${rule.targetPackageName.ifBlank { "*" }}")
                    rule.sourceTag.takeIf { it.isNotBlank() }?.let { add("safety_rule_source ${rule.ruleId} | $it") }
                    add("safety_rule_explain ${rule.ruleId} | ${rule.behaviorSummary().take(88)}")
                }
                SessionSafetyDecisionStore.readRecent(limit = 3).forEach { entry ->
                    add("safety_decision ${entry.outcome} | ${entry.actionFamily.ifBlank { "-" }} | ${entry.summary.take(64)}")
                }
                loopRuntime?.lines?.take(2)?.forEach { add("loop_runtime ${it.take(88)}") }
                toolContracts.lines.take(2).forEach { add("tool_contract ${it.take(88)}") }
                memoryFork?.lines?.take(2)?.forEach { add("memory_fork ${it.take(88)}") }
                memoryInsight.thinkbackLines.take(2).forEach { line ->
                    add("thinkback $line")
                }
            },
        actionLines =
            buildList {
                autonomyPlan.enginePhaseOrder.take(4).forEachIndexed { index, phase ->
                    add("phase_${index + 1} | $phase")
                }
                autonomyPlan.recommendedCommands.take(4).forEach { command ->
                    add("autonomy | $command")
                }
                userModel.recommendedCommands.take(3).forEach { command ->
                    add("memory | $command")
                }
                voiceInteraction.recommendedCommands.take(2).forEach { command ->
                    add("voice | $command")
                }
                assistantSnapshot.activeSession.sessionId.takeIf { it.isNotBlank() }?.let { sessionId ->
                    add("loop_runtime | /loop-runtime --session-id $sessionId")
                    add("memory_fork | /memory-fork --session-id $sessionId")
                    add("permission_product | /permission-product --session-id $sessionId")
                }
                add("tool_contracts | /tool-contracts")
            },
        recommendedCommands =
            listOfNotNull(
                "/governance",
                "/provider-policy",
                "/artifact-retention",
                "/safety-policies",
                "/safety-decisions",
                assistantSnapshot.activeSession.sessionId.takeIf { it.isNotBlank() }?.let { "/permission-product --session-id $it" },
                assistantSnapshot.activeSession.sessionId.takeIf { it.isNotBlank() }?.let { "/loop-runtime --session-id $it" },
                assistantSnapshot.activeSession.sessionId.takeIf { it.isNotBlank() }?.let { "/memory-fork --session-id $it" },
                "/tool-contracts",
                "/memory-governance",
                "/quiet-hours",
                pendingCompensationPlans.firstOrNull()?.let { "/compensations --session-id ${it.sessionId}" },
            ) + autonomyPlan.recommendedCommands.take(2),
        updatedAtMs = now,
    )
    }

internal fun normalizeRoutinePolicySnapshot(
    current: AssistantRoutinePolicySnapshot,
    now: Long,
): AssistantRoutinePolicySnapshot =
    current.copy(
        checklistTemplates = current.checklistTemplates.ifEmpty { AssistantRoutinePolicySnapshot().checklistTemplates },
        preferredSurfaces = current.preferredSurfaces.ifEmpty { AssistantRoutinePolicySnapshot().preferredSurfaces },
        summary =
            current.summary.ifBlank {
                "focus=${current.focusTheme.ifBlank { "默认主焦点" }} review=${current.reviewWindow.ifBlank { "-" }} follow=${current.followUpWindow.ifBlank { "-" }}"
            },
        updatedAtMs = if (current.updatedAtMs > 0L) current.updatedAtMs else now,
    )

internal fun normalizeDigestPolicySnapshot(
    current: AssistantDigestPolicySnapshot,
    now: Long,
): AssistantDigestPolicySnapshot =
    current.copy(
        deliverySurfaces = current.deliverySurfaces.ifEmpty { AssistantDigestPolicySnapshot().deliverySurfaces },
        summary =
            current.summary.ifBlank {
                "enabled=${current.enabled} cadence=${current.cadence} surfaces=${current.deliverySurfaces.joinToString(",").ifBlank { "-" }}"
            },
        updatedAtMs = if (current.updatedAtMs > 0L) current.updatedAtMs else now,
    )

internal fun normalizeInterruptPolicySnapshot(
    current: AssistantInterruptPolicySnapshot,
    now: Long,
): AssistantInterruptPolicySnapshot =
    current.copy(
        preferredSources = current.preferredSources.ifEmpty { AssistantInterruptPolicySnapshot().preferredSources },
        summary =
            current.summary.ifBlank {
                "base=${current.baseBudget} focus=${current.focusBudget} quiet_block=${current.hardBlockInQuietHours}"
            },
        updatedAtMs = if (current.updatedAtMs > 0L) current.updatedAtMs else now,
    )

internal fun deriveQuietHoursSnapshot(
    current: AssistantQuietHoursSnapshot,
    now: Long,
): AssistantQuietHoursSnapshot {
    val start = parseLocalMinutes(current.startLocalTime.ifBlank { AssistantQuietHoursSnapshot().startLocalTime })
    val end = parseLocalMinutes(current.endLocalTime.ifBlank { AssistantQuietHoursSnapshot().endLocalTime })
    val currentMinutes = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    val activeNow =
        if (!current.enabled) {
            false
        } else if (start == end) {
            true
        } else if (start < end) {
            currentMinutes in start until end
        } else {
            currentMinutes >= start || currentMinutes < end
        }
    return current.copy(
        startLocalTime = formatLocalMinutes(start),
        endLocalTime = formatLocalMinutes(end),
        activeNow = activeNow,
        summary =
            if (!current.enabled) {
                "静默窗口未启用"
            } else {
                "${formatLocalMinutes(start)}-${formatLocalMinutes(end)}${if (activeNow) " | 当前生效" else ""}"
            },
        updatedAtMs = if (current.updatedAtMs > 0L) current.updatedAtMs else now,
    )
}

internal fun parseLocalMinutes(
    raw: String,
): Int {
    val parts = raw.trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour * 60 + minute
}

internal fun formatLocalMinutes(
    minutes: Int,
): String {
    val hour = (minutes / 60).coerceIn(0, 23)
    val minute = (minutes % 60).coerceIn(0, 59)
    return "%02d:%02d".format(hour, minute)
}
