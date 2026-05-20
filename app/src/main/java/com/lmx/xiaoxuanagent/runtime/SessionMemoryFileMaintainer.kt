package com.lmx.xiaoxuanagent.runtime

internal object SessionMemoryFileMaintainer {
    fun refreshIfNeeded(
        sessionId: String,
        task: String,
        profileId: String,
        turn: Int,
        trigger: String,
        history: List<Any> = emptyList(),
        force: Boolean = false,
    ): SessionMemoryFileSnapshot? {
        if (sessionId.isBlank() || task.isBlank() || profileId.isBlank()) return null
        val notebook = SessionMemoryNotebookStore.readSnapshot(sessionId)
        val document = SessionMemoryDocumentStore.readSnapshot(sessionId)
        val working = SessionWorkingMemoryStore.readSnapshot(sessionId)
        val toolCallsEstimate =
            buildList {
                addAll(working?.preferredTools.orEmpty())
                addAll(notebook?.previewLines.orEmpty().filter { it.contains("tool", ignoreCase = true) })
            }.size
        val estimatedTokens =
            estimateTokens(
                task = task,
                history = history,
                notebookLines = notebook?.previewLines.orEmpty(),
                documentLines = document?.previewLines.orEmpty(),
                workingLines = listOfNotNull(working?.progressSummary, working?.nextFocusHint) + working?.openLoops.orEmpty(),
            )
        val decision =
            SessionMemoryFilePolicyStore.evaluate(
                sessionId = sessionId,
                turn = turn,
                estimatedTokens = estimatedTokens,
                totalToolCalls = toolCallsEstimate,
                trigger = trigger,
                force = force,
            )
        if (!decision.shouldRefresh && !force) return null
        return SessionMemoryFileStore.refresh(sessionId, task, profileId, trigger)
    }

    private fun estimateTokens(
        task: String,
        history: List<Any>,
        notebookLines: List<String>,
        documentLines: List<String>,
        workingLines: List<String>,
    ): Int {
        val historyChars = history.sumOf { it.toString().length }
        val memoryChars = (notebookLines + documentLines + workingLines + listOf(task)).sumOf { it.length }
        return ((historyChars + memoryChars) / 4).coerceAtLeast(0)
    }
}
