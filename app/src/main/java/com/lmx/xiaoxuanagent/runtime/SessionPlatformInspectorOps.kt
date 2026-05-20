package com.lmx.xiaoxuanagent.runtime

object SessionPlatformInspectorOps {
    fun compareSessions(
        leftSessionId: String,
        rightSessionId: String,
    ): SessionPlatformDiff {
        if (leftSessionId.isBlank() || rightSessionId.isBlank()) {
            return SessionPlatformDiff(
                leftSessionId = leftSessionId,
                rightSessionId = rightSessionId,
                summary = "缺少可比较的 session id",
                lines = listOf("left=${leftSessionId.ifBlank { "-" }}", "right=${rightSessionId.ifBlank { "-" }}"),
            )
        }
        val left = SessionPlatformFacade.readSessionSnapshot(leftSessionId)
        val right = SessionPlatformFacade.readSessionSnapshot(rightSessionId)
        val lines =
            buildList {
                add("status: ${left.bridgeSnapshot.statusCode} vs ${right.bridgeSnapshot.statusCode}")
                add("task: ${left.bridgeSnapshot.task.ifBlank { "-" }} vs ${right.bridgeSnapshot.task.ifBlank { "-" }}")
                add("turns: ${left.sessionSnapshot?.turnCount ?: left.bridgeSnapshot.turn} vs ${right.sessionSnapshot?.turnCount ?: right.bridgeSnapshot.turn}")
                add("route: ${left.bridgeSnapshot.routeReason.ifBlank { "-" }} vs ${right.bridgeSnapshot.routeReason.ifBlank { "-" }}")
                add("result: ${left.bridgeSnapshot.resultSummary.ifBlank { left.bridgeSnapshot.errorSummary.ifBlank { "-" } }}")
                add("result(peer): ${right.bridgeSnapshot.resultSummary.ifBlank { right.bridgeSnapshot.errorSummary.ifBlank { "-" } }}")
                add("pendingSafety: ${left.pendingSafetySummary.ifBlank { "-" }} vs ${right.pendingSafetySummary.ifBlank { "-" }}")
                add("artifacts: ${left.recentArtifacts.size} vs ${right.recentArtifacts.size}")
                add("runtimeEvents: ${left.recentRuntimeEvents.size} vs ${right.recentRuntimeEvents.size}")
                add("bridgeEvents: ${left.recentBridgeEvents.size} vs ${right.recentBridgeEvents.size}")
                add("health: ${left.healthSummary.status}/${left.healthSummary.summary.ifBlank { "-" }} vs ${right.healthSummary.status}/${right.healthSummary.summary.ifBlank { "-" }}")
            }
        val summary =
            if (
                left.bridgeSnapshot.statusCode == right.bridgeSnapshot.statusCode &&
                left.bridgeSnapshot.routeReason == right.bridgeSnapshot.routeReason &&
                left.bridgeSnapshot.resultSummary == right.bridgeSnapshot.resultSummary
            ) {
                "两个 session 主结果基本一致"
            } else {
                "两个 session 存在显著差异"
            }
        return SessionPlatformDiff(
            leftSessionId = leftSessionId,
            rightSessionId = rightSessionId,
            summary = summary,
            lines = lines,
        )
    }

    fun runBatchDeterministicReplay(
        sessionIds: List<String>,
    ): SessionBatchReplayReport {
        val effectiveSessionIds = sessionIds.filter { it.isNotBlank() }.distinct()
        val results = effectiveSessionIds.map(SessionPlatformFacade::runDeterministicReplay)
        val replayable = results.count { it.replayable }
        val matched = results.count { it.matches }
        val mismatched = results.count { it.replayable && !it.matches }
        return SessionBatchReplayReport(
            totalSessions = effectiveSessionIds.size,
            replayableSessions = replayable,
            matchedSessions = matched,
            mismatchedSessions = mismatched,
            lines =
                results.map { result ->
                    buildString {
                        append(result.sessionId)
                        append(" | replayable=").append(result.replayable)
                        append(" | matches=").append(result.matches)
                        append(" | commands=").append(result.commandCount)
                        if (result.mismatches.isNotEmpty()) {
                            append(" | ").append(result.mismatches.joinToString(" / ").take(160))
                        }
                    }
                },
        )
    }

    fun readReplayTimeline(
        sessionId: String,
        limit: Int = 24,
    ): List<SessionReplayTimelineEntry> =
        RuntimeEventStore.readSessionTimeline(sessionId = sessionId, limit = limit)
            .mapIndexed { index, entry ->
                SessionReplayTimelineEntry(
                    commandIndex = index + 1,
                    timestamp = entry.timestamp,
                    commandType = entry.commandType,
                    statusCode = entry.statusCode,
                    transition = entry.transition,
                    summary = entry.summary,
                )
            }

    fun runStepReplay(
        sessionId: String,
        uptoCommandCount: Int,
    ): SessionStepReplayReport {
        val commands = RuntimeEventStore.readSessionCommandLedger(sessionId)
        if (commands.isEmpty()) {
            return SessionStepReplayReport(
                sessionId = sessionId,
                uptoCommandCount = 0,
                totalCommands = 0,
                replayedStatus = AgentUiStatus.IDLE,
                expectedStatus = SessionPlatformFacade.readSessionSnapshot(sessionId).bridgeSnapshot.statusCode,
                mismatches = listOf("runtime ledger does not contain command payloads"),
                lines = listOf("未找到可回放命令。"),
            )
        }
        val boundedCount = uptoCommandCount.coerceIn(0, commands.size)
        var replayed = SessionRuntimeState()
        commands.take(boundedCount).forEachIndexed { index, command ->
            replayed = SessionRuntimeReducer.reduce(replayed, command, nowMs = index.toLong() + 1L)
        }
        val live = SessionPlatformFacade.readSessionSnapshot(sessionId)
        val timeline = readReplayTimeline(sessionId = sessionId, limit = boundedCount.coerceAtLeast(1))
        val mismatches =
            buildList {
                if (boundedCount == commands.size && replayed.session.status != live.state.session.status) {
                    add("status ${replayed.session.status} != ${live.state.session.status}")
                }
                if (boundedCount == commands.size && replayed.session.turns != live.state.session.turns) {
                    add("turns ${replayed.session.turns} != ${live.state.session.turns}")
                }
            }
        return SessionStepReplayReport(
            sessionId = sessionId,
            uptoCommandCount = boundedCount,
            totalCommands = commands.size,
            replayedStatus = replayed.session.status,
            expectedStatus = if (boundedCount == commands.size) live.state.session.status else timeline.lastOrNull()?.statusCode.orEmpty(),
            mismatches = mismatches,
            lines =
                buildList {
                    add("step=$boundedCount/$commands.size")
                    add("replayed_status=${replayed.session.status}")
                    if (boundedCount == commands.size) {
                        add("expected_status=${live.state.session.status}")
                    }
                    timeline.takeLast(6).forEach { entry ->
                        add("#${entry.commandIndex} ${entry.commandType} | ${entry.transition} | ${entry.statusCode}")
                    }
                    mismatches.forEach { add("mismatch | $it") }
                },
        )
    }
}
