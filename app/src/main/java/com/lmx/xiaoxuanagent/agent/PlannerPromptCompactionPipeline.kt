package com.lmx.xiaoxuanagent.agent

internal data class PlannerPromptCompactionResult(
    val history: List<AgentTurnRecord>,
    val artifactHints: List<PlannerArtifactHint>,
    val memoryContext: PlanningMemoryContext,
    val toolCatalogLimit: Int,
    val visualObjectLimit: Int,
    val parserRegionLimit: Int,
    val signalLines: List<String> = emptyList(),
    val tokenEstimateBefore: Int = 0,
    val tokenEstimateAfter: Int = 0,
)

internal object PlannerPromptCompactionPipeline {
    fun apply(
        promptProfile: OpenAiPlanner.PlannerPromptProfile,
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
    ): PlannerPromptCompactionResult {
        val tokenBefore = estimateTokens(history, artifactHints, memoryContext)
        val stages = linkedSetOf<String>()
        var compactedHistory = history
        var compactedArtifacts = artifactHints
        var compactedMemory = memoryContext
        var toolCatalogLimit = promptProfile.toolCatalogLimit
        var visualObjectLimit = promptProfile.visualObjectLimit
        var parserRegionLimit = promptProfile.parserRegionLimit

        if (history.size > promptProfile.historyTurnLimit || tokenBefore > 1400 || promptProfile.label != "default") {
            stages += "snip"
            compactedHistory =
                history.takeLast(promptProfile.historyTurnLimit).map { turn ->
                    turn.copy(
                        result = turn.result.take(if (promptProfile.label == "default") 120 else 84),
                        decisionReason = turn.decisionReason.take(72),
                        recoverySummary = turn.recoverySummary.take(72),
                    )
                }
        }
        if (tokenBefore > 1800 || promptProfile.runtimeMode in setOf("reactive", "auto")) {
            stages += "microcompact"
            compactedArtifacts = compactedArtifacts.take(maxOf(2, promptProfile.artifactHintLimit - 1))
            compactedMemory =
                compactedMemory.copy(
                    shortTermNotes = compactedMemory.shortTermNotes.takeLast(maxOf(2, promptProfile.memoryLineLimit - 1)),
                    longTermMemories = compactedMemory.longTermMemories.takeLast(maxOf(2, promptProfile.memoryLineLimit - 1)),
                    sessionMemories = compactedMemory.sessionMemories.takeLast(maxOf(2, promptProfile.memoryLineLimit - 1)),
                    sessionNotebook = compactedMemory.sessionNotebook.takeLast(maxOf(2, promptProfile.notebookLineLimit - 1)),
                    toolRuntimeContracts = compactedMemory.toolRuntimeContracts.takeLast(maxOf(1, promptProfile.toolContractLimit - 1)),
                )
        }
        if (tokenBefore > 2200 || promptProfile.runtimeMode == "auto") {
            stages += "autocompact"
            toolCatalogLimit = minOf(toolCatalogLimit, 8)
            visualObjectLimit = minOf(visualObjectLimit, 4)
            parserRegionLimit = minOf(parserRegionLimit, 4)
            compactedMemory =
                compactedMemory.copy(
                    resultArtifacts = compactedMemory.resultArtifacts.takeLast(2),
                    retrievalMemories = compactedMemory.retrievalMemories.takeLast(2),
                    loopRuntimeSignals = compactedMemory.loopRuntimeSignals.takeLast(2),
                    compactionSignals = compactedMemory.compactionSignals.takeLast(6) + listOf("pipeline=autocompact"),
                )
        }
        if (promptProfile.label == "reactive_retry") {
            stages += "reactive_compact"
            compactedHistory = compactedHistory.takeLast(maxOf(2, promptProfile.historyTurnLimit - 1))
            compactedMemory =
                compactedMemory.copy(
                    correctionTemplates = compactedMemory.correctionTemplates.takeLast(2),
                    loopInboxAttachments = compactedMemory.loopInboxAttachments.takeLast(2),
                    permissionProductSignals = compactedMemory.permissionProductSignals.takeLast(2),
                )
        }

        val tokenAfter = estimateTokens(compactedHistory, compactedArtifacts, compactedMemory)
        return PlannerPromptCompactionResult(
            history = compactedHistory,
            artifactHints = compactedArtifacts,
            memoryContext = compactedMemory,
            toolCatalogLimit = toolCatalogLimit,
            visualObjectLimit = visualObjectLimit,
            parserRegionLimit = parserRegionLimit,
            signalLines =
                buildList {
                    add("pipeline_stages=${stages.joinToString(",").ifBlank { "none" }}")
                    add("pipeline_token_before=$tokenBefore")
                    add("pipeline_token_after=$tokenAfter")
                    add("pipeline_history=${compactedHistory.size}")
                    add("pipeline_artifacts=${compactedArtifacts.size}")
                    add("pipeline_tool_catalog=$toolCatalogLimit")
                },
            tokenEstimateBefore = tokenBefore,
            tokenEstimateAfter = tokenAfter,
        )
    }

    private fun estimateTokens(
        history: List<AgentTurnRecord>,
        artifactHints: List<PlannerArtifactHint>,
        memoryContext: PlanningMemoryContext,
    ): Int {
        val historyChars = history.sumOf { it.action.length + it.result.length + it.decisionReason.length }
        val artifactChars = artifactHints.sumOf { it.summary.length + it.type.length }
        val memoryChars =
            listOf(
                memoryContext.shortTermNotes,
                memoryContext.longTermMemories,
                memoryContext.userPreferences,
                memoryContext.resultArtifacts,
                memoryContext.sessionMemories,
                memoryContext.sessionNotebook,
                memoryContext.loopRuntimeSignals,
                memoryContext.compactionSignals,
            ).flatten().sumOf { it.length }
        return ((historyChars + artifactChars + memoryChars) / 4).coerceAtLeast(history.size + artifactHints.size)
    }
}
