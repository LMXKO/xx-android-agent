package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import com.lmx.xiaoxuanagent.agent.PlanningTurnAttachment

internal data class SessionTranscriptCompactionOutcome(
    val history: List<AgentTurnRecord>,
    val mode: String = "",
    val trigger: String = "",
    val compactedTurnCount: Int = 0,
    val preservedTurnCount: Int = 0,
    val tokenEstimateBefore: Int = 0,
    val tokenEstimateAfter: Int = 0,
    val boundaryDigest: String = "",
    val signalLines: List<String> = emptyList(),
    val attachment: PlanningTurnAttachment? = null,
)

internal object SessionTranscriptCompactionSupport {
    fun compactForPlanner(
        sessionId: String,
        turn: Int,
        task: String,
        history: List<AgentTurnRecord>,
        runtimePlan: PlannerRuntimeCompactionPlan,
    ): SessionTranscriptCompactionOutcome {
        if (sessionId.isBlank() || history.isEmpty()) {
            return SessionTranscriptCompactionOutcome(history = history)
        }
        val preserveCount =
            when (runtimePlan.mode) {
                "auto" -> 2
                "reactive" -> 3
                else -> 4
            }
        if (!runtimePlan.shouldPreferCompactWindow || history.size <= preserveCount + 1) {
            return SessionTranscriptCompactionOutcome(
                history = history,
                mode = runtimePlan.mode,
                trigger = runtimePlan.trigger,
                compactedTurnCount = 0,
                preservedTurnCount = history.size,
                tokenEstimateBefore = estimateTokens(history),
                tokenEstimateAfter = estimateTokens(history),
                signalLines = listOf("transcript_compaction=pass_through", "preserved_turns=${history.size}"),
            )
        }
        val compactedTurns = history.dropLast(preserveCount)
        val preservedTurns = history.takeLast(preserveCount)
        val boundaryChunkSize =
            when (runtimePlan.mode) {
                "auto" -> 4
                "reactive" -> 5
                else -> 6
            }
        val syntheticBoundaries =
            compactedTurns
                .chunked(boundaryChunkSize)
                .mapIndexed { index, chunk ->
                    val digest =
                        buildString {
                            append("task=").append(task.take(24))
                            append(" | chunk=").append(index + 1)
                            append(" | compacted=").append(chunk.size)
                            append(" | actions=")
                            append(chunk.takeLast(3).joinToString(" / ") { "${it.action} -> ${it.result.take(24)}" })
                        }.take(180)
                    AgentTurnRecord(
                        observationSignature = "compact_boundary:$turn:$index",
                        pageState = "COMPACT_BOUNDARY",
                        action = "[compact_boundary]",
                        result = digest,
                        decisionReason = "mode=${runtimePlan.mode} trigger=${runtimePlan.trigger}",
                        recoveryCategory = "",
                        recoverySummary = "",
                        suggestedRecoveryAction = "",
                    )
                }
        val digest = syntheticBoundaries.lastOrNull()?.result.orEmpty()
        val compactedHistory = syntheticBoundaries + preservedTurns
        val tokenBefore = estimateTokens(history)
        val tokenAfter = estimateTokens(compactedHistory)
        val lines =
            listOf(
                "mode=${runtimePlan.mode}",
                "trigger=${runtimePlan.trigger}",
                "token_before=$tokenBefore",
                "token_after=$tokenAfter",
                "compacted_turns=${compactedTurns.size}",
                "boundary_count=${syntheticBoundaries.size}",
                "preserved_turns=${preservedTurns.size}",
            )
        SessionTranscriptCompactionStore.record(
            SessionTranscriptCompactionSnapshot(
                sessionId = sessionId,
                turn = turn,
                mode = runtimePlan.mode,
                trigger = runtimePlan.trigger,
                tokenEstimateBefore = tokenBefore,
                tokenEstimateAfter = tokenAfter,
                compactedTurnCount = compactedTurns.size,
                preservedTurnCount = preservedTurns.size,
                boundaryDigest = digest,
                lines = lines,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return SessionTranscriptCompactionOutcome(
            history = compactedHistory,
            mode = runtimePlan.mode,
            trigger = runtimePlan.trigger,
            compactedTurnCount = compactedTurns.size,
            preservedTurnCount = preservedTurns.size,
            tokenEstimateBefore = tokenBefore,
            tokenEstimateAfter = tokenAfter,
            boundaryDigest = digest,
            signalLines = lines.map { "transcript_$it" } + listOf("transcript_boundary=${digest.take(96)}"),
            attachment =
                PlanningTurnAttachment(
                    attachmentId = "transcript_compaction_$turn",
                    source = "transcript_compaction",
                    type = "history_boundary",
                    title = "Transcript Compact Boundary",
                    summary = digest,
                    priority = 70,
                    detailLines = lines,
                    recommendedCommands = listOf("/timeline --session-id $sessionId"),
                ),
        )
    }

    private fun estimateTokens(
        history: List<AgentTurnRecord>,
    ): Int =
        history.sumOf { record ->
            (record.action.length + record.result.length + record.decisionReason.length + 3) / 4
        }.coerceAtLeast(history.size)
}
