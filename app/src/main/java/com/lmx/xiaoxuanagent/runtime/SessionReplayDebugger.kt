package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalStore
import com.lmx.xiaoxuanagent.assistantos.AssistantSignalProviderStore
import org.json.JSONObject

object SessionReplayDebugger {
    fun inspectReplayCommand(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayInspectReport {
        val commands = RuntimeEventStore.readSessionCommandLedger(sessionId)
        if (commands.isEmpty()) {
            return SessionReplayInspectReport(
                sessionId = sessionId,
                commandIndex = 0,
                totalCommands = 0,
                summary = "未找到可 inspect 的 replay 命令。",
                lines = listOf("runtime ledger does not contain command payloads"),
            )
        }
        val boundedIndex = commandIndex.coerceIn(1, commands.size)
        val beforeState = replayStateBefore(commands = commands, commandIndex = boundedIndex)
        val afterState =
            SessionRuntimeReducer.reduce(
                beforeState,
                commands[boundedIndex - 1],
                nowMs = boundedIndex.toLong(),
            )
        val diffs =
            buildList {
                appendReplayStatusDiffs(
                    before = beforeState.resolveSessionSemantics().statusModel,
                    after = afterState.resolveSessionSemantics().statusModel,
                )
                if (beforeState.session.turns != afterState.session.turns) {
                    add("turns: ${beforeState.session.turns} -> ${afterState.session.turns}")
                }
                if (beforeState.session.running != afterState.session.running) {
                    add("running: ${beforeState.session.running} -> ${afterState.session.running}")
                }
                if (beforeState.session.planning != afterState.session.planning) {
                    add("planning: ${beforeState.session.planning} -> ${afterState.session.planning}")
                }
                if (beforeState.session.paused != afterState.session.paused) {
                    add("paused: ${beforeState.session.paused} -> ${afterState.session.paused}")
                }
                if (beforeState.lastTransition != afterState.lastTransition) {
                    add("transition: ${beforeState.lastTransition} -> ${afterState.lastTransition}")
                }
                if (beforeState.resultSnapshot?.lastResult != afterState.resultSnapshot?.lastResult) {
                    add("last_result: ${beforeState.resultSnapshot?.lastResult.orEmpty().take(80)} -> ${afterState.resultSnapshot?.lastResult.orEmpty().take(80)}")
                }
                if (beforeState.resultSnapshot?.lastError != afterState.resultSnapshot?.lastError) {
                    add("last_error: ${beforeState.resultSnapshot?.lastError.orEmpty().take(80)} -> ${afterState.resultSnapshot?.lastError.orEmpty().take(80)}")
                }
                if (beforeState.session.lastObservationSignature != afterState.session.lastObservationSignature) {
                    add("observation_signature: ${beforeState.session.lastObservationSignature} -> ${afterState.session.lastObservationSignature}")
                }
            }
        return SessionReplayInspectReport(
            sessionId = sessionId,
            commandIndex = boundedIndex,
            totalCommands = commands.size,
            summary = "inspect command #$boundedIndex",
            lines =
                buildList {
                    add("command_index=$boundedIndex/$commands.size")
                    add("before_status=${beforeState.resolveSessionSemantics().statusModel.code}")
                    add("after_status=${afterState.resolveSessionSemantics().statusModel.code}")
                    if (diffs.isEmpty()) {
                        add("diffs=none")
                    } else {
                        diffs.forEach { add("diff | $it") }
                    }
                },
        )
    }

    fun readReplayInspectState(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayStateInspectReport {
        val timeline = RuntimeEventStore.readSessionTimeline(sessionId = sessionId, limit = 240)
        val commands = RuntimeEventStore.readSessionCommandLedger(sessionId)
        if (commands.isEmpty()) {
            val emptyState = SessionRuntimeState()
            return SessionReplayStateInspectReport(
                sessionId = sessionId,
                commandIndex = 0,
                totalCommands = 0,
                commandType = "",
                beforeState = emptyState.toReplayStateFrame(),
                afterState = emptyState.toReplayStateFrame(),
                summary = "未找到可 inspect 的 replay state。",
                lines = listOf("runtime ledger does not contain command payloads"),
            )
        }
        val boundedIndex = commandIndex.coerceIn(1, commands.size)
        val beforeState = replayStateBefore(commands = commands, commandIndex = boundedIndex)
        val afterState =
            SessionRuntimeReducer.reduce(
                beforeState,
                commands[boundedIndex - 1],
                nowMs = boundedIndex.toLong(),
            )
        val commandType = timeline.getOrNull(boundedIndex - 1)?.commandType.orEmpty()
        val beforeFrame = beforeState.toReplayStateFrame()
        val afterFrame = afterState.toReplayStateFrame()
        val diffs =
            buildList {
                appendReplayStateFrameDiffs(beforeFrame, afterFrame)
            }
        return SessionReplayStateInspectReport(
            sessionId = sessionId,
            commandIndex = boundedIndex,
            totalCommands = commands.size,
            commandType = commandType,
            beforeState = beforeFrame,
            afterState = afterFrame,
            summary = "inspect replay state #$boundedIndex",
            lines =
                buildList {
                    add("command_index=$boundedIndex/$commands.size")
                    add("command_type=${commandType.ifBlank { "-" }}")
                    add("before=${beforeFrame.status} turn=${beforeFrame.turns} transition=${beforeFrame.transition.ifBlank { "-" }}")
                    add("after=${afterFrame.status} turn=${afterFrame.turns} transition=${afterFrame.transition.ifBlank { "-" }}")
                    if (diffs.isEmpty()) add("diffs=none") else diffs.forEach { add("diff | $it") }
                },
        )
    }

    fun readReplayArtifactsForCommand(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayArtifactInspectReport {
        val timeline = RuntimeEventStore.readSessionTimeline(sessionId = sessionId, limit = 240)
        if (timeline.isEmpty()) {
            return SessionReplayArtifactInspectReport(
                sessionId = sessionId,
                commandIndex = 0,
                totalCommands = 0,
                turn = 0,
                summary = "未找到 replay 时间线。",
                lines = listOf("runtime ledger does not contain timeline entries"),
            )
        }
        val boundedIndex = commandIndex.coerceIn(1, timeline.size)
        val turn = timeline[boundedIndex - 1].turn
        val artifacts =
            ArtifactStore.listRecentArtifactRecords(
                sessionId = sessionId,
                beforeTurnInclusive = turn.coerceAtLeast(0),
                limit = 8,
            ).map { record ->
                SessionReplayArtifactInspectDetail(
                    artifactId = record.artifactId,
                    type = record.type,
                    summary = record.summary,
                    path = record.path,
                    previewLines = ArtifactStore.readArtifactPreviewLines(sessionId, record.artifactId),
                )
            }
        return SessionReplayArtifactInspectReport(
            sessionId = sessionId,
            commandIndex = boundedIndex,
            totalCommands = timeline.size,
            turn = turn,
            summary = if (artifacts.isEmpty()) "该步附近没有 artifact。" else "已读取 replay artifact 线索。",
            artifacts = artifacts,
            lines =
                buildList {
                    add("command_index=$boundedIndex/${timeline.size}")
                    add("turn=$turn")
                    if (artifacts.isEmpty()) {
                        add("artifacts=none")
                    } else {
                        artifacts.forEach { artifact ->
                            add("${artifact.type} | ${artifact.summary.ifBlank { artifact.artifactId }}")
                            artifact.previewLines.forEach { preview ->
                                add("preview | $preview")
                            }
                        }
                    }
                },
        )
    }

    fun compareReplaySteps(
        sessionId: String,
        leftCommandIndex: Int,
        rightCommandIndex: Int,
    ): SessionReplayStepCompareReport {
        val left = readReplayInspectState(sessionId = sessionId, commandIndex = leftCommandIndex)
        val right = readReplayInspectState(sessionId = sessionId, commandIndex = rightCommandIndex)
        if (left.totalCommands == 0 || right.totalCommands == 0) {
            return SessionReplayStepCompareReport(
                sessionId = sessionId,
                leftCommandIndex = left.commandIndex,
                rightCommandIndex = right.commandIndex,
                totalCommands = maxOf(left.totalCommands, right.totalCommands),
                summary = "缺少可对比的 replay 步骤。",
                lines = listOf("left=${left.commandIndex}", "right=${right.commandIndex}"),
            )
        }
        val lines =
            buildList {
                add("left=#${left.commandIndex} ${left.commandType.ifBlank { "-" }}")
                add("right=#${right.commandIndex} ${right.commandType.ifBlank { "-" }}")
                appendReplayStateFrameDiffs(left.afterState, right.afterState, peerSuffix = "(peer)")
            }
        return SessionReplayStepCompareReport(
            sessionId = sessionId,
            leftCommandIndex = left.commandIndex,
            rightCommandIndex = right.commandIndex,
            totalCommands = maxOf(left.totalCommands, right.totalCommands),
            summary = if (lines.size <= 2) "两个 replay 步骤主状态一致。" else "两个 replay 步骤存在结构化差异。",
            lines = lines.ifEmpty { listOf("diffs=none") },
        )
    }

    fun inspectReplayBreakpoint(
        sessionId: String,
        commandIndex: Int,
    ): SessionReplayBreakpointInspectReport {
        val timeline = RuntimeEventStore.readSessionTimeline(sessionId = sessionId, limit = 240)
        if (timeline.isEmpty()) {
            return SessionReplayBreakpointInspectReport(
                sessionId = sessionId,
                commandIndex = 0,
                totalCommands = 0,
                summary = "未找到可 inspect 的 replay breakpoint。",
                lines = listOf("runtime ledger does not contain timeline entries"),
            )
        }
        val boundedIndex = commandIndex.coerceIn(1, timeline.size)
        val timelineEntry = timeline[boundedIndex - 1]
        val stateReport = readReplayInspectState(sessionId = sessionId, commandIndex = boundedIndex)
        val artifactReport = readReplayArtifactsForCommand(sessionId = sessionId, commandIndex = boundedIndex)
        val lifecycleSnapshot = SessionPlatformFacade.readArtifactLifecycleSnapshot(sessionId = sessionId, beforeTurnInclusive = timelineEntry.turn)
        val graphNode = SessionSessionGraphStore.read(sessionId)
        val mailboxSnapshot =
            SessionPlatformFacade.readWorkerMailboxSnapshot(
                target = graphNode?.rootSessionId.orEmpty().ifBlank { sessionId },
                includeConsumed = false,
                limit = 8,
                priorityMode = "reply_chain_first",
            )
        val providers = AssistantSignalProviderStore.readAll(limit = 6)
        val recentSignals = AssistantExternalSignalStore.readAll(limit = 6)
        val approvals = PlatformCapabilityApprovalStore.readPending(limit = 6)
        val sidechainEvents =
            if (graphNode?.workerId?.isNotBlank() == true) {
                SessionAgentSidechainStore.readRecent(workerId = graphNode.workerId, limit = 6)
            } else {
                SessionAgentSidechainStore.readRecent(rootSessionId = sessionId, limit = 6)
            }
        val traceSnapshot = SessionPlatformFacade.readTraceSnapshot(sessionId = sessionId, limit = 8)
        val swarmSnapshot =
            SessionPlatformFacade.readSwarmCoordinationSnapshot(
                activeSessionId = sessionId,
                mailboxTarget = graphNode?.rootSessionId.orEmpty().ifBlank { sessionId },
                traceSessionId = sessionId,
                workerGraphLimit = 8,
                mailboxLimit = 8,
                traceLimit = 8,
            )
        val graphSummary =
            graphNode?.let { node ->
                buildString {
                    append("session=").append(node.sessionId)
                    append(" depth=").append(node.depth)
                    append(" parent=").append(node.parentSessionId.ifBlank { "-" })
                    append(" pending_children=").append(node.pendingChildSessionIds.size)
                    append(" approvals=").append(node.pendingApprovalCount)
                    append(" mailbox=").append(node.mailboxPendingCount)
                    append(" blocked=").append(node.blockedReason.ifBlank { "-" })
                }
            }.orEmpty()
        val mailboxSummary =
            listOf(
                swarmSnapshot.mailboxCoordinationSummary.takeIf { it.isNotBlank() },
                mailboxSnapshot.categorySummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ")
        val schedulerSummary =
            listOf(
                swarmSnapshot.parallelismPressureSummary.takeIf { it.isNotBlank() },
                swarmSnapshot.ownerFairnessSummary.takeIf { it.isNotBlank() },
                swarmSnapshot.workerDispatchSummary.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" | ")
        val providerSummary =
            providers.joinToString(" | ") { provider ->
                buildString {
                    append(provider.providerId)
                    append(":health=").append(provider.healthScore)
                    append(",gate=").append(provider.lastGateAction.name.lowercase())
                    provider.deliveryMode.takeIf { it.isNotBlank() }?.let { append(",mode=").append(it) }
                }
            }
        val signalSummary =
            recentSignals.joinToString(" | ") { signal ->
                "${signal.type.name.lowercase()}:${signal.summary.ifBlank { signal.title }.take(24)}"
            }
        val approvalSummary =
            if (approvals.isEmpty()) {
                "pending=0"
            } else {
                "pending=${approvals.size} | ${approvals.joinToString(" | ") { approval -> "${approval.capability.name.lowercase()}:${approval.summary.take(24)}" }}"
            }
        val payloadSummary =
            runCatching { JSONObject(timelineEntry.commandPayload) }.getOrNull()?.let { payload ->
                buildList {
                    payload.keys().forEach { key ->
                        add("$key=${payload.opt(key)?.toString().orEmpty().take(96)}")
                    }
                }
            }.orEmpty()
        return SessionReplayBreakpointInspectReport(
            sessionId = sessionId,
            commandIndex = boundedIndex,
            totalCommands = timeline.size,
            commandType = timelineEntry.commandType,
            statusCode = timelineEntry.statusCode,
            turn = timelineEntry.turn,
            swarmMode = swarmSnapshot.mode,
            swarmSummary = swarmSnapshot.summary,
            graphSummary = graphSummary,
            mailboxSummary = mailboxSummary,
            schedulerSummary = schedulerSummary,
            providerSummary = providerSummary,
            signalSummary = signalSummary,
            approvalSummary = approvalSummary,
            artifactLifecycleSummary = lifecycleSnapshot.lines.joinToString(" | "),
            traceSummary =
                listOf(
                    traceSnapshot.categorySummary.takeIf { it.isNotBlank() },
                    traceSnapshot.coverageSummary.takeIf { it.isNotBlank() },
                    traceSnapshot.attentionSummary.takeIf { traceSnapshot.attentionCount > 0 && it.isNotBlank() },
                ).filterNotNull().joinToString(" | "),
            traceCount = traceSnapshot.totalCount,
            artifactCount = artifactReport.artifacts.size,
            summary = "breakpoint inspect #$boundedIndex",
            lines =
                buildList {
                    add("command_index=$boundedIndex/${timeline.size}")
                    add("command_type=${timelineEntry.commandType}")
                    add("transition=${timelineEntry.transition}")
                    add("status=${timelineEntry.statusCode}")
                    add("turn=${timelineEntry.turn}")
                    add("swarm | ${swarmSnapshot.mode} | ${swarmSnapshot.summary}")
                    add("scheduler | $schedulerSummary")
                    providerSummary.takeIf { it.isNotBlank() }?.let { add("providers | $it") }
                    signalSummary.takeIf { it.isNotBlank() }?.let { add("signals | $it") }
                    add("approvals | $approvalSummary")
                    stateReport.lines.forEach { add("state | $it") }
                    if (payloadSummary.isEmpty()) {
                        add("command_payload=unavailable")
                    } else {
                        payloadSummary.take(8).forEach { add("payload | $it") }
                    }
                    artifactReport.lines.forEach { add("artifact | $it") }
                    lifecycleSnapshot.lines.forEach { add("artifact_lifecycle | $it") }
                    lifecycleSnapshot.recentArtifactLines.forEach { add("artifact_recent | $it") }
                    swarmSnapshot.lines.forEach { add("swarm_snapshot | $it") }
                    mailboxSnapshot.lines.forEach { add("mailbox_snapshot | $it") }
                    traceSnapshot.lines.forEach { add("trace_snapshot | $it") }
                    graphNode?.let { node ->
                        add(
                            "graph | session=${node.sessionId} depth=${node.depth} parent=${node.parentSessionId.ifBlank { "-" }} worker=${node.workerId.ifBlank { "-" }} root=${node.rootSessionId.ifBlank { "-" }}",
                        )
                        add(
                            "graph | pending_children=${node.pendingChildSessionIds.size} approvals=${node.pendingApprovalCount} mailbox=${node.mailboxPendingCount} blocked=${node.blockedReason.ifBlank { "-" }}",
                        )
                    }
                    mailboxSnapshot.recentLines.forEach { line ->
                        add("mailbox | $line")
                    }
                    sidechainEvents.forEach { event ->
                        add("sidechain | ${event.stage.name.lowercase()} | ${event.summary.ifBlank { event.resultSummary.ifBlank { event.workerId } }}")
                    }
                    traceSnapshot.recentLines.forEach { traceLine ->
                        add("trace | $traceLine")
                    }
                },
        )
    }

    private fun replayStateBefore(
        commands: List<SessionCommand>,
        commandIndex: Int,
    ): SessionRuntimeState {
        var beforeState = SessionRuntimeState()
        commands.take(commandIndex - 1).forEachIndexed { index, command ->
            beforeState = SessionRuntimeReducer.reduce(beforeState, command, nowMs = index.toLong() + 1L)
        }
        return beforeState
    }

    private fun MutableList<String>.appendReplayStateFrameDiffs(
        left: SessionReplayStateFrame,
        right: SessionReplayStateFrame,
        peerSuffix: String = "",
    ) {
        if (left.status != right.status) add("status: ${left.status} vs ${right.status}")
        if (left.statusCategory != right.statusCategory) add("status_category: ${left.statusCategory.name.lowercase()} vs ${right.statusCategory.name.lowercase()}")
        if (left.executionPhase != right.executionPhase) add("execution_phase: ${left.executionPhase.name.lowercase()} vs ${right.executionPhase.name.lowercase()}")
        if (left.takeoverReason != right.takeoverReason) add("takeover_reason: ${left.takeoverReason.name.lowercase()} vs ${right.takeoverReason.name.lowercase()}")
        if (left.terminalReason != right.terminalReason) add("terminal_reason: ${left.terminalReason.name.lowercase()} vs ${right.terminalReason.name.lowercase()}")
        if (left.blockedReason != right.blockedReason) add("blocked_reason: ${left.blockedReason.name.lowercase()} vs ${right.blockedReason.name.lowercase()}")
        if (left.turns != right.turns) add("turns: ${left.turns} vs ${right.turns}")
        if (left.routeReason != right.routeReason) {
            add("route_reason: ${left.routeReason.ifBlank { "-" }} vs ${right.routeReason.ifBlank { "-" }}")
        }
        if (left.resultSummary != right.resultSummary) {
            add("result_summary: ${left.resultSummary.take(96)}")
            add("result_summary$peerSuffix: ${right.resultSummary.take(96)}")
        }
        if (left.lastError != right.lastError) {
            add("last_error: ${left.lastError.take(96)}")
            add("last_error$peerSuffix: ${right.lastError.take(96)}")
        }
    }

    private fun MutableList<String>.appendReplayStatusDiffs(
        before: AgentUiStatusModel,
        after: AgentUiStatusModel,
    ) {
        if (before.code != after.code) add("status: ${before.code} -> ${after.code}")
        if (before.category != after.category) {
            add("status_category: ${before.category.name.lowercase()} -> ${after.category.name.lowercase()}")
        }
        if (before.blockedReason != after.blockedReason) {
            add("blocked_reason: ${before.blockedReason.name.lowercase()} -> ${after.blockedReason.name.lowercase()}")
        }
        if (before.executionPhase != after.executionPhase) {
            add("execution_phase: ${before.executionPhase.name.lowercase()} -> ${after.executionPhase.name.lowercase()}")
        }
        if (before.takeoverReason != after.takeoverReason) {
            add("takeover_reason: ${before.takeoverReason.name.lowercase()} -> ${after.takeoverReason.name.lowercase()}")
        }
        if (before.terminalReason != after.terminalReason) {
            add("terminal_reason: ${before.terminalReason.name.lowercase()} -> ${after.terminalReason.name.lowercase()}")
        }
    }
}

private fun SessionRuntimeState.toReplayStateFrame(): SessionReplayStateFrame {
    val statusModel = resolveSessionSemantics().statusModel
    return SessionReplayStateFrame(
        status = statusModel.code,
        statusCategory = statusModel.category,
        blockedReason = statusModel.blockedReason,
        executionPhase = statusModel.executionPhase,
        takeoverReason = statusModel.takeoverReason,
        terminalReason = statusModel.terminalReason,
        turns = session.turns,
        running = session.running,
        planning = session.planning,
        paused = session.paused,
        transition = lastTransition,
        routeReason = routeSnapshot?.reason.orEmpty(),
        resultSummary = resultSnapshot?.summary.orEmpty(),
        lastResult = resultSnapshot?.lastResult.orEmpty(),
        lastError = resultSnapshot?.lastError.orEmpty(),
        observationSignature = session.lastObservationSignature,
    )
}
