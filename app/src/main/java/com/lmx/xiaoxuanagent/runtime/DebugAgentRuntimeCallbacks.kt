package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantOsController
import com.lmx.xiaoxuanagent.agent.TaskResultPayload
import com.lmx.xiaoxuanagent.safety.PendingSafetyConfirmation

internal interface DebugAgentRuntimeCallbacks {
    fun onRuntimeRoutingStarted(
        sessionId: String,
        task: String,
    )

    fun onRuntimeRoutingDiagnostics(
        sessionId: String,
        messages: List<String>,
    )

    fun onRuntimeRoutingFailed(
        sessionId: String,
        routeReason: String,
    )

    fun onRuntimeAppUnavailable(
        sessionId: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
    )

    fun onRuntimeRouteResolved(
        sessionId: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
    )

    fun onRuntimeTurnCompleted(
        sessionId: String,
        turn: Int,
        actionLabel: String,
        finalStatus: String,
        finalResult: String,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
        loopDetected: Boolean,
    )

    fun onRuntimeErrorRecorded(
        sessionId: String,
        error: String,
        keepRunning: Boolean,
    )

    fun onRuntimeExternalWaitEntered(
        sessionId: String,
        waitingEvent: String,
        suspendReason: String,
        observationSignature: String,
    )

    fun onRuntimeExternalWaitResolved(
        sessionId: String,
        previousEvent: String,
        observationSignature: String,
    )

    fun onRuntimeAgentPaused(sessionId: String)

    fun onRuntimeAgentResumed(
        sessionId: String,
        resumeSource: String,
        userCorrection: String,
    )

    fun onRuntimeSafetyConfirmationRequested(
        sessionId: String,
        confirmation: PendingSafetyConfirmation,
    )

    fun onRuntimeSafetyConfirmationApproved(
        sessionId: String,
        confirmation: PendingSafetyConfirmation,
        userCorrection: String,
    )

    fun onRuntimeAgentStopped(
        sessionId: String,
        reason: String,
        status: String,
    )

    fun onRuntimePlanningContextAcquired(
        sessionId: String,
        turn: Int,
        observationSignature: String,
        pageState: String,
        topTextsPreview: String,
    )

    fun onRuntimePlannerDecisionRecorded(
        sessionId: String,
        actionLabel: String,
        reason: String,
    )

    fun onRuntimeAgentStarted(
        sessionId: String,
        task: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
        entrySource: String,
    )

    fun onRuntimeStatePublished(
        sessionId: String,
        state: SessionRuntimeState,
        sessionSnapshot: ReplaySessionSnapshot? = null,
        bridgeSnapshot: SessionBridgeSnapshot? = null,
        platformSnapshot: SessionPlatformSnapshot? = null,
    )

    fun onRuntimeArtifactRecorded(
        sessionId: String,
        turn: Int,
        artifactId: String,
        type: String,
        summary: String,
    )
}

internal object DebugAgentRuntimeCallbacksImpl : DebugAgentRuntimeCallbacks {
    override fun onRuntimeRoutingStarted(
        sessionId: String,
        task: String,
    ) {
        DebugAgentStore.updateUiInternal {
            it.copy(plannerMode = DebugAgentStore.plannerLabel(), recentLogs = emptyList(), recentRuntimeArtifacts = emptyList())
        }
        DebugAgentStore.appendLog("开始任务路由: task=${task.ifBlank { "-" }}")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime routing start 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeRoutingDiagnostics(
        sessionId: String,
        messages: List<String>,
    ) {
        messages.forEach(DebugAgentStore::appendLog)
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime routing diagnostics 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeRoutingFailed(
        sessionId: String,
        routeReason: String,
    ) {
        DebugAgentStore.appendLog("启动自动任务失败: $routeReason")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime routing failed 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeAppUnavailable(
        sessionId: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
    ) {
        DebugAgentStore.appendLog("目标 App 不可用: profile=$profileDisplayName, pkg=$targetPackageName, reason=$routeReason")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime app unavailable 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeRouteResolved(
        sessionId: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
    ) {
        DebugAgentStore.appendLog("任务路由完成: profile=$profileDisplayName, pkg=$targetPackageName, reason=$routeReason")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime route resolved 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeTurnCompleted(
        sessionId: String,
        turn: Int,
        actionLabel: String,
        finalStatus: String,
        finalResult: String,
        taskResult: TaskResultPayload?,
        keepRunning: Boolean,
        loopDetected: Boolean,
    ) {
        DebugAgentStore.appendLog("执行完成: turn=$turn, action=$actionLabel, keepRunning=$keepRunning, result=$finalResult")
        taskResult?.let { extracted ->
            DebugAgentStore.appendLog(
                "结果抽取: ${extracted.intentType} | ${extracted.title} | ${extracted.summary}" +
                    extracted.highlights.takeIf { it.isNotEmpty() }?.let { " | ${it.take(2).joinToString(" / ")}" }.orEmpty(),
            )
        }
        if (!keepRunning) {
            DebugAgentStore.refreshDashboardInternal(force = true)
        }
        if (loopDetected) {
            DebugAgentStore.appendLog("检测到重复 observation/action 指纹，已触发死循环保护。")
        }
        if (!keepRunning) {
            DebugAgentStore.bringAssistantToFrontInternal(
                when {
                    finalStatus == AgentUiStatus.COMPLETED -> "completed"
                    loopDetected -> "loop_protection"
                    else -> "stopped"
                },
            )
        }
        DebugAgentStore.refreshRuntimeArtifactsFromReplayInternal(sessionId)
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime turn 完成事件未携带 sessionId。")
        }
    }

    override fun onRuntimeErrorRecorded(
        sessionId: String,
        error: String,
        keepRunning: Boolean,
    ) {
        if (!keepRunning) {
            DebugAgentStore.refreshDashboardInternal(force = true)
        }
        DebugAgentStore.appendLog("Agent 错误: keepRunning=$keepRunning, error=$error")
        if (!keepRunning) {
            DebugAgentStore.bringAssistantToFrontInternal("error")
        }
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime error 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeExternalWaitEntered(
        sessionId: String,
        waitingEvent: String,
        suspendReason: String,
        observationSignature: String,
    ) {
        DebugAgentStore.appendLog("进入外部等待: event=${waitingEvent.ifBlank { "-" }}, reason=$suspendReason, sig=$observationSignature")
        DebugAgentStore.bringAssistantToFrontInternal(
            reason = "waiting_external",
            summaryOverride = "等待处理 | ${suspendReason.take(32)}",
        )
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime external wait enter 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeExternalWaitResolved(
        sessionId: String,
        previousEvent: String,
        observationSignature: String,
    ) {
        DebugAgentStore.appendLog(
            "外部等待已解除，恢复执行: event=${previousEvent.ifBlank { "-" }}, " +
                "subgoal=${DebugAgentStore.uiState.value.planning.currentSubgoalId.ifBlank { "-" }}, sig=$observationSignature",
        )
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime external wait resolved 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeAgentPaused(sessionId: String) {
        DebugAgentStore.appendLog("自动任务已暂停。")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime pause 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeAgentResumed(
        sessionId: String,
        resumeSource: String,
        userCorrection: String,
    ) {
        DebugAgentStore.appendLog(
            (
                if (resumeSource.startsWith("resume_snapshot")) {
                    "已继续恢复任务。"
                } else {
                    "自动任务已继续。"
                }
            ) +
                resumeSource.takeIf { it.isNotBlank() }?.let { " 来源: $it。" }.orEmpty() +
                userCorrection.takeIf { it.isNotBlank() }?.let { " 用户纠错: $it" }.orEmpty(),
        )
        if (userCorrection.isNotBlank() || resumeSource.startsWith("resume_snapshot")) {
            DebugAgentStore.refreshDashboardInternal(force = true)
        }
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime resume 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeSafetyConfirmationRequested(
        sessionId: String,
        confirmation: PendingSafetyConfirmation,
    ) {
        DebugAgentStore.appendLog("高风险动作待确认: ${confirmation.actionLabel} | ${confirmation.summary}")
        DebugAgentStore.bringAssistantToFrontInternal(
            reason = "awaiting_confirmation",
            summaryOverride = "待确认 | ${confirmation.actionLabel.take(24)}",
        )
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime safety confirmation request 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeSafetyConfirmationApproved(
        sessionId: String,
        confirmation: PendingSafetyConfirmation,
        userCorrection: String,
    ) {
        DebugAgentStore.appendLog(
            "高风险动作已确认放行: ${confirmation.actionLabel}" +
                userCorrection.takeIf { it.isNotBlank() }?.let { " | 用户纠错: $it" }.orEmpty(),
        )
        if (userCorrection.isNotBlank()) {
            DebugAgentStore.refreshDashboardInternal(force = true)
        }
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime safety confirmation approval 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeAgentStopped(
        sessionId: String,
        reason: String,
        status: String,
    ) {
        DebugAgentStore.refreshDashboardInternal(force = true)
        when (status) {
            AgentUiStatus.MAX_TURNS_REACHED -> {
                DebugAgentStore.appendLog("已达到最大执行轮数，自动任务停止。")
                DebugAgentStore.bringAssistantToFrontInternal("max_turns")
            }

            else -> {
                DebugAgentStore.appendLog("自动任务停止: $reason")
                DebugAgentStore.bringAssistantToFrontInternal("stop")
            }
        }
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime stop 事件未携带 sessionId。")
        }
    }

    override fun onRuntimePlanningContextAcquired(
        sessionId: String,
        turn: Int,
        observationSignature: String,
        pageState: String,
        topTextsPreview: String,
    ) {
        DebugAgentStore.updateUiInternal {
            it
                .withTakeoverPanel(DebugTakeoverPanel())
                .withSafetyPanel(DebugSafetyPanel())
                .withResultPanel(
                    it.result.copy(
                        hint = "Agent 正在分析 observation $observationSignature。",
                    ),
                )
                .copy(
                    agentRunning = true,
                    agentTurn = turn,
                    plannerMode = DebugAgentStore.plannerLabel(),
                )
        }
        DebugAgentStore.appendLog(
            "开始规划: turn=$turn, stage=${DebugAgentStore.uiState.value.planning.currentPlanStage}, sig=$observationSignature, page=$pageState, topTexts=$topTextsPreview",
        )
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime planning acquired 事件未携带 sessionId。")
        }
    }

    override fun onRuntimePlannerDecisionRecorded(
        sessionId: String,
        actionLabel: String,
        reason: String,
    ) {
        DebugAgentStore.appendLog("规划结果: action=$actionLabel, reason=$reason")
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime planner decision 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeAgentStarted(
        sessionId: String,
        task: String,
        profileDisplayName: String,
        targetPackageName: String,
        routeReason: String,
        entrySource: String,
    ) {
        DebugAgentStore.appendLog(
            "自动任务启动: task=${task.ifBlank { "-" }}, profile=$profileDisplayName, " +
                "targetPkg=$targetPackageName, route=$routeReason, entry=$entrySource, planner=${DebugAgentStore.plannerLabel()}",
        )
        DebugAgentStore.refreshDashboardInternal(force = true)
        if (sessionId.isBlank()) {
            DebugAgentStore.appendLog("runtime agent started 事件未携带 sessionId。")
        }
    }

    override fun onRuntimeStatePublished(
        sessionId: String,
        state: SessionRuntimeState,
        sessionSnapshot: ReplaySessionSnapshot?,
        bridgeSnapshot: SessionBridgeSnapshot?,
        platformSnapshot: SessionPlatformSnapshot?,
    ) {
        val session = state.session
        val effectivePlatformSnapshot =
            platformSnapshot
                ?: SessionPlatformFacade.buildSnapshot(
                    state = state,
                    sessionId = sessionId.ifBlank { session.sessionId },
                    sessionSnapshot = sessionSnapshot,
                    bridgeSnapshot = bridgeSnapshot,
                )
        val effectiveBridgeSnapshot = effectivePlatformSnapshot.bridgeSnapshot
        SessionRuntimeKernel.syncPlatformSnapshot(effectivePlatformSnapshot)
        AssistantOsController.onPlatformSnapshot(effectivePlatformSnapshot)
        val transition = state.lastTransition
        val updatedAtLabel = DebugAgentStore.formatUpdatedAtLabel(state.updatedAtMs)
        val shouldLog =
            transition.isNotBlank() &&
                (DebugAgentStore.uiState.value.runtimeTransition != transition || DebugAgentStore.uiState.value.runtimeUpdatedAtLabel != updatedAtLabel)
        DebugAgentStore.updateUiInternal(runtimeState = state) { current ->
            current.copy(
                runtimeTransition = transition,
                runtimeUpdatedAtLabel = updatedAtLabel,
                platformSummaryLines = formatPlatformSummaryLines(effectivePlatformSnapshot),
                platformApprovalLines = formatApprovalTicketLines(SessionPlatformFacade.readPendingApprovalTickets()),
                capabilityLines = formatCapabilityLines(SessionPlatformFacade.listCapabilities()),
                remoteBridgeLines = formatRemoteBridgeLines(SessionPlatformFacade.readRemoteBridgeSnapshot()),
                remoteTransportLines = formatRemoteTransportLines(SessionPlatformFacade.readRemoteTransportSnapshot()),
                workerLines = formatWorkerLines(SessionPlatformFacade.readWorkerQueue()),
                proactiveTaskLines = formatProactiveTaskLines(SessionPlatformFacade.readProactiveTasks()),
                externalSignalLines = formatExternalSignalLines(SessionPlatformFacade.readExternalSignals()),
                memoryRecallLines = formatMemoryRecallLines(current.query.ifBlank { effectiveBridgeSnapshot.task }, effectiveBridgeSnapshot.profileId),
                traceLines = formatTraceLines(SessionPlatformFacade.readTraceSnapshot(sessionId = effectivePlatformSnapshot.sessionId)),
                compareLines = formatCompareLines(effectivePlatformSnapshot.sessionId),
                batchReplayLines = formatBatchReplayLines(),
                stepReplayLines = formatStepReplayLines(effectivePlatformSnapshot.sessionId),
                recentBridgeEvents = formatBridgeEventLines(effectivePlatformSnapshot.recentBridgeEvents),
                replayVerificationSummary =
                    effectivePlatformSnapshot.sessionId
                        .takeIf { it.isNotBlank() }
                        ?.let { formatReplayVerification(SessionPlatformFacade.runDeterministicReplay(it)) }
                        .orEmpty(),
                recentRuntimeEvents = formatRuntimeEventLines(effectivePlatformSnapshot.recentRuntimeEvents),
            )
        }
        if (shouldLog) {
            DebugAgentStore.appendLog(
                "runtime状态变更: transition=$transition, session=${session.sessionId.ifBlank { sessionId.ifBlank { "-" } }}, " +
                    "turn=${session.turns}, status=${effectiveBridgeSnapshot.statusCode}, resumable=${effectiveBridgeSnapshot.resumable}",
            )
        }
        (
            sessionSnapshot?.summaryLine()
                ?: effectiveBridgeSnapshot.task.takeIf { it.isNotBlank() }?.let {
                    "${effectiveBridgeSnapshot.statusCode} | $it | - | ${effectiveBridgeSnapshot.resultSummary.ifBlank { effectiveBridgeSnapshot.errorSummary.ifBlank { "-" } }}"
                }
            )?.let { summaryLine ->
            DebugAgentStore.updateUiInternal { current ->
                current.copy(
                    recentSessionSummaries =
                        (listOf(summaryLine) + current.recentSessionSummaries)
                            .distinct()
                            .take(4),
                )
            }
        }
        if (effectiveBridgeSnapshot.recentArtifacts.isNotEmpty()) {
            DebugAgentStore.updateUiInternal { current ->
                current.copy(
                    recentRuntimeArtifacts =
                        DebugAgentStore.formatReplayArtifactsInternal(
                            listOf(
                                ReplayTurnArtifactGroup(
                                    turn = effectiveBridgeSnapshot.turn,
                                    artifacts = effectiveBridgeSnapshot.recentArtifacts.take(8),
                                ),
                            ),
                        ),
                )
            }
            return
        }
        if (session.sessionId.isNotBlank() && (session.turns > 0 || sessionSnapshot != null)) {
            DebugAgentStore.refreshRuntimeArtifactsFromReplayInternal(session.sessionId, sessionSnapshot)
        }
    }

    override fun onRuntimeArtifactRecorded(
        sessionId: String,
        turn: Int,
        artifactId: String,
        type: String,
        summary: String,
    ) {
        val artifactSummary =
            buildString {
                append("turn=").append(turn)
                append(" type=").append(type)
                append(" summary=").append(summary.ifBlank { "-" })
                append(" id=").append(artifactId)
            }
        DebugAgentStore.updateUiInternal { current ->
            current.copy(
                recentRuntimeArtifacts = (listOf(artifactSummary) + current.recentRuntimeArtifacts).take(8),
            )
        }
        DebugAgentStore.appendLog("runtime产物已记录: session=${sessionId.ifBlank { "-" }}, $artifactSummary")
    }
}
