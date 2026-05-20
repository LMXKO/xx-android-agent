package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryNotebookSnapshot(
    val sessionId: String,
    val task: String = "",
    val profileId: String = "",
    val headline: String = "",
    val focusSummary: String = "",
    val previewLines: List<String> = emptyList(),
    val markdownPath: String = "",
    val forkMode: String = "",
    val forkModel: String = "",
    val updatedAtMs: Long = 0L,
)

object SessionMemoryNotebookStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_notebooks.json"
    private const val NOTEBOOK_DIR = "session_memory"
    private const val MAX_SESSIONS = 128
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryNotebookSnapshot>()
    private var hydrated = false

    fun refreshFromRuntime(
        sessionId: String,
        task: String,
        profileId: String,
    ): SessionMemoryNotebookSnapshot? {
        sessionId.takeIf { it.isNotBlank() }?.let { validSessionId ->
            BackgroundMemoryExtractor.enqueueSessionNotebookUpdate(
                sessionId = validSessionId,
                task = task,
                profileId = profileId,
            )
        }
        return readSnapshot(sessionId)
    }

    fun recordTurnCheckpoint(
        sessionId: String,
        task: String,
        profileId: String,
        turn: Int,
        workingMemory: SessionWorkingMemorySnapshot?,
        compactSnapshot: SessionConversationCompactSnapshot?,
        memoryPolicy: SessionMemoryPolicySnapshot?,
        latestResult: String,
        keepRunning: Boolean,
    ): SessionMemoryNotebookSnapshot? {
        if (sessionId.isBlank()) return null
        val markdown =
            buildString {
                append("# Session Memory Checkpoint\n\n")
                append("- session: ").append(sessionId).append('\n')
                append("- task: ").append(task.ifBlank { "-" }).append('\n')
                append("- profile: ").append(profileId.ifBlank { "-" }).append('\n')
                append("- turn: ").append(turn).append('\n')
                append("- status: ").append(if (keepRunning) "running" else "completed").append("\n\n")

                append("## Focus\n\n")
                append("- progress: ").append(workingMemory?.progressSummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- next_focus: ").append(workingMemory?.nextFocusHint?.ifBlank { "-" } ?: "-").append('\n')
                append("- compact: ").append(compactSnapshot?.conversationSummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- result: ").append(latestResult.take(160).ifBlank { "-" }).append("\n\n")

                append("## Open Loops\n\n")
                if (workingMemory?.openLoops.isNullOrEmpty()) {
                    append("- none\n\n")
                } else {
                    workingMemory?.openLoops.orEmpty().forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }

                append("## Boundary\n\n")
                append("- boundary_summary: ").append(compactSnapshot?.boundarySummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- tool_use: ").append(compactSnapshot?.toolUseSummary?.ifBlank { "-" } ?: "-").append('\n')
                append("- recovery: ").append(compactSnapshot?.recoverySummary?.ifBlank { "-" } ?: "-").append("\n\n")

                append("## Memory Policy\n\n")
                append("- summary: ").append(memoryPolicy?.summary?.ifBlank { "-" } ?: "-").append('\n')
                append("- reason: ").append(memoryPolicy?.lastReason?.ifBlank { "-" } ?: "-").append('\n')
                append("- queued: ").append(memoryPolicy?.notebookUpdateQueued ?: false).append('\n')
            }
        return applyForkOutput(
            SessionMemoryForkOutput(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                forkSummary = "local_turn_checkpoint",
                promptPreview = latestResult.take(120),
                headline =
                    buildString {
                        append("T").append(turn)
                        workingMemory?.lastPlanStage?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        workingMemory?.nextFocusHint?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(72)) }
                    }.ifBlank { compactSnapshot?.conversationSummary.orEmpty().take(120) },
                focusSummary =
                    listOfNotNull(
                        workingMemory?.progressSummary?.takeIf { it.isNotBlank() },
                        latestResult.takeIf { it.isNotBlank() }?.take(96),
                    ).joinToString(" | ").take(160),
                previewLines =
                    buildList {
                        workingMemory?.progressSummary?.takeIf { it.isNotBlank() }?.let { add("progress: ${it.take(120)}") }
                        workingMemory?.nextFocusHint?.takeIf { it.isNotBlank() }?.let { add("focus: ${it.take(96)}") }
                        compactSnapshot?.boundarySummary?.takeIf { it.isNotBlank() }?.let { add("boundary: ${it.take(120)}") }
                        memoryPolicy?.lastReason?.takeIf { it.isNotBlank() }?.let { add("policy: ${it.take(96)}") }
                        if (!keepRunning) {
                            add("result: ${latestResult.take(120)}")
                        }
                    },
                markdown = markdown,
                runtimeMode = "local_turn_checkpoint",
                modelName = "runtime_inline_writer",
            ),
        )
    }

    internal fun buildForkContext(
        sessionId: String,
        task: String,
        profileId: String,
    ): SessionMemoryForkContext? {
        if (sessionId.isBlank()) return null
        val workingMemory = SessionWorkingMemoryStore.readSnapshot(sessionId) ?: return null
        val compact = SessionConversationCompactStore.readSnapshot(sessionId)
        val explanations = SessionExplanationStore.readRecent(sessionId = sessionId, limit = 6)
        val commandReceipts = SessionCommandCenterStore.readRecentForSession(sessionId = sessionId, limit = 4)
        val toolLedger = SessionToolUseLedgerStore.readRecent(sessionId = sessionId, limit = 4)
        val turnLoop = SessionTurnLoopStore.readSnapshot(sessionId)
        val memoryPolicy = SessionMemoryPolicyStore.readSnapshot(sessionId)
        val memoryCurator = SessionMemoryCuratorStore.readSnapshot(sessionId)
        val actionLifecycle = SessionActionLifecycleStore.readRecent(sessionId = sessionId, limit = 4)
        val currentNotebookPath = readSnapshot(sessionId)?.markdownPath.orEmpty()
        val currentNotebook =
            currentNotebookPath.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).takeIf(File::exists)?.readText().orEmpty() }.getOrDefault("")
            }.orEmpty()
        return SessionMemoryForkContext(
            sessionId = sessionId,
            task = task,
            profileId = profileId,
            workingMemory = workingMemory,
            compact = compact,
            explanations = explanations,
            commandReceipts = commandReceipts,
            toolLedger = toolLedger,
            turnLoop = turnLoop,
            memoryPolicy = memoryPolicy,
            memoryCurator = memoryCurator,
            actionLifecycle = actionLifecycle,
            currentNotebookPath = currentNotebookPath,
            currentNotebook = currentNotebook,
            currentHeadline = readSnapshot(sessionId)?.headline.orEmpty(),
        )
    }

    internal fun applyForkOutput(
        output: SessionMemoryForkOutput,
    ): SessionMemoryNotebookSnapshot? {
        if (output.sessionId.isBlank()) return null
        val noteFile = notebookFile(output.sessionId) ?: return null
        noteFile.parentFile?.mkdirs()
        noteFile.writeText(output.markdown)
        val snapshot =
            SessionMemoryNotebookSnapshot(
                sessionId = output.sessionId,
                task = output.task,
                profileId = output.profileId,
                headline = output.headline.take(160),
                focusSummary = output.focusSummary.take(160),
                previewLines = output.previewLines.take(8),
                markdownPath = noteFile.absolutePath,
                forkMode = output.runtimeMode,
                forkModel = output.modelName,
                updatedAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[output.sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
        }
        return snapshot
    }

    fun readSnapshot(
        sessionId: String,
    ): SessionMemoryNotebookSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun readRecent(
        limit: Int = 8,
    ): List<SessionMemoryNotebookSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots.values.sortedByDescending { it.updatedAtMs }.take(limit.coerceAtLeast(1))
        }

    fun appendManualNote(
        sessionId: String,
        task: String,
        profileId: String,
        note: String,
        tag: String = "",
    ): SessionMemoryNotebookSnapshot? {
        if (sessionId.isBlank() || note.isBlank()) return null
        val existingSnapshot = readSnapshot(sessionId)
        val existingMarkdown =
            existingSnapshot?.markdownPath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { File(path).takeIf(File::exists)?.readText().orEmpty() }.getOrDefault("") }
                .orEmpty()
        val trimmedTag = tag.trim()
        val appendedMarkdown =
            buildString {
                append(existingMarkdown.ifBlank { "# Session Memory\n\n" })
                if (!endsWith("\n")) append('\n')
                append("\n## Manual Note\n\n")
                append("- tag: ").append(trimmedTag.ifBlank { "manual" }).append('\n')
                append("- note: ").append(note.take(400)).append('\n')
            }
        return applyForkOutput(
            SessionMemoryForkOutput(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                forkSummary = "manual_note",
                promptPreview = note.take(120),
                headline =
                    listOfNotNull(
                        existingSnapshot?.headline?.takeIf { it.isNotBlank() },
                        trimmedTag.takeIf { it.isNotBlank() },
                    ).joinToString(" | ").ifBlank { "manual_note" },
                focusSummary = note.take(160),
                previewLines =
                    (
                        listOf("manual_note: ${note.take(120)}") +
                            existingSnapshot?.previewLines.orEmpty()
                    ).distinct().take(8),
                markdown = appendedMarkdown,
                runtimeMode = "manual_note",
                modelName = "runtime_inline_writer",
            ),
        )
    }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readSnapshot(sessionId)
            ?.let { snapshot ->
                buildList {
                    snapshot.headline.takeIf { it.isNotBlank() }?.let { add("session_notebook: $it") }
                    snapshot.previewLines.take(limit.coerceAtLeast(1) - 1).forEach(::add)
                }.take(limit.coerceAtLeast(1))
            }.orEmpty()

    internal fun renderMarkdown(
        forkContext: SessionMemoryForkContext,
    ): String =
        buildString {
            append("# Session Memory\n\n")
            append("- session: ").append(forkContext.sessionId).append('\n')
            append("- task: ").append(forkContext.task.ifBlank { "-" }).append('\n')
            append("- profile: ").append(forkContext.profileId.ifBlank { "-" }).append('\n')
            append("- fork_mode: model_backed_detached_curator_worker").append('\n')
            append("- status: ").append(forkContext.workingMemory.status).append('\n')
            append("- turns: ").append(forkContext.workingMemory.turnCount).append('\n')
            append("- updated_at_ms: ").append(forkContext.workingMemory.updatedAtMs).append("\n\n")

            append("## Current Focus\n\n")
            append("- progress: ").append(forkContext.workingMemory.progressSummary.ifBlank { "-" }).append('\n')
            append("- next_focus: ").append(forkContext.workingMemory.nextFocusHint.ifBlank { "-" }).append('\n')
            append("- last_stage: ").append(forkContext.workingMemory.lastPlanStage.ifBlank { "-" }).append("\n\n")

            append("## Open Loops\n\n")
            if (forkContext.workingMemory.openLoops.isEmpty()) {
                append("- none\n\n")
            } else {
                forkContext.workingMemory.openLoops.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }

            append("## Recent Facts\n\n")
            if (forkContext.workingMemory.recentFacts.isEmpty()) {
                append("- none\n\n")
            } else {
                forkContext.workingMemory.recentFacts.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }

            append("## Cautions\n\n")
            if (forkContext.workingMemory.cautionNotes.isEmpty()) {
                append("- none\n\n")
            } else {
                forkContext.workingMemory.cautionNotes.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }

            append("## Compact Summary\n\n")
            append("- conversation: ").append(forkContext.compact?.conversationSummary?.ifBlank { "-" } ?: "-").append('\n')
            append("- tools: ").append(forkContext.compact?.toolUseSummary?.ifBlank { "-" } ?: "-").append('\n')
            append("- recovery: ").append(forkContext.compact?.recoverySummary?.ifBlank { "-" } ?: "-").append("\n\n")

            append("## Turn Loop\n\n")
            append("- phase: ").append(forkContext.turnLoop?.phase?.ifBlank { "-" } ?: "-").append('\n')
            append("- summary: ").append(forkContext.turnLoop?.summary?.ifBlank { "-" } ?: "-").append('\n')
            if (forkContext.turnLoop?.blockerLines.isNullOrEmpty()) {
                append("- blockers: none\n\n")
            } else {
                forkContext.turnLoop?.blockerLines.orEmpty().forEach { append("- blocker: ").append(it).append('\n') }
                append('\n')
            }

            append("## Memory Policy\n\n")
            append("- summary: ").append(forkContext.memoryPolicy?.summary?.ifBlank { "-" } ?: "-").append('\n')
            append("- reason: ").append(forkContext.memoryPolicy?.lastReason?.ifBlank { "-" } ?: "-").append('\n')
            append("- queued: ").append(forkContext.memoryPolicy?.notebookUpdateQueued ?: false).append("\n\n")

            append("## Memory Curator\n\n")
            append("- status: ").append(forkContext.memoryCurator?.status?.ifBlank { "-" } ?: "-").append('\n')
            append("- worker_id: ").append(forkContext.memoryCurator?.workerId?.ifBlank { "-" } ?: "-").append('\n')
            append("- summary: ").append(forkContext.memoryCurator?.summary?.ifBlank { "-" } ?: "-").append('\n')
            append("- policy_reason: ").append(forkContext.memoryCurator?.policyReason?.ifBlank { "-" } ?: "-").append('\n')
            append("- notebook_path: ").append(forkContext.memoryCurator?.notebookPath?.ifBlank { "-" } ?: "-").append("\n\n")

            append("## Fork Context\n\n")
            append("- existing_notebook: ").append(forkContext.currentNotebookPath.ifBlank { "-" }).append('\n')
            append("- previous_headline: ").append(forkContext.currentHeadline.ifBlank { "-" }).append('\n')
            append("- current_notebook_lines: ").append(forkContext.currentNotebook.lineSequence().count()).append("\n\n")

            append("## Why Log\n\n")
            if (forkContext.explanations.isEmpty()) {
                append("- none\n\n")
            } else {
                forkContext.explanations.forEach { entry ->
                    append("- turn ").append(entry.turn).append(" | ").append(entry.phase).append(" | ").append(entry.summary).append('\n')
                }
                append('\n')
            }

            append("## Command Receipts\n\n")
            if (forkContext.commandReceipts.isEmpty()) {
                append("- none\n\n")
            } else {
                forkContext.commandReceipts.forEach { receipt ->
                    append("- ").append(receipt.status.name.lowercase()).append(" | ").append(receipt.summary).append('\n')
                }
                append('\n')
            }

            append("## Tool Ledger\n\n")
            if (forkContext.toolLedger.isEmpty()) {
                append("- none\n")
            } else {
                forkContext.toolLedger.forEach { entry ->
                    append("- turn ")
                        .append(entry.turn)
                        .append(" | ")
                        .append(entry.status.name.lowercase())
                        .append(" | ")
                        .append(entry.toolName)
                        .append(" | ")
                        .append(entry.summary)
                        .append('\n')
                }
            }

            append("\n## Action Lifecycle\n\n")
            if (forkContext.actionLifecycle.isEmpty()) {
                append("- none\n")
            } else {
                forkContext.actionLifecycle.forEach { entry ->
                    append("- turn ")
                        .append(entry.turn)
                        .append(" | ")
                        .append(entry.phase)
                        .append(" | ")
                        .append(entry.status)
                        .append(" | ")
                        .append(entry.toolName)
                        .append(" | ")
                        .append(entry.summary)
                        .append('\n')
                }
            }
        }

    internal fun renderPreviewLines(
        forkContext: SessionMemoryForkContext,
    ): List<String> =
        buildList {
            forkContext.workingMemory.nextFocusHint.takeIf { it.isNotBlank() }?.let { add("next_focus=$it") }
            forkContext.workingMemory.openLoops.take(2).forEach { add("open_loop=$it") }
            forkContext.compact?.conversationSummary?.takeIf { it.isNotBlank() }?.let { add("compact=$it") }
            forkContext.explanations.take(2).forEach { add("why.${it.phase}=${it.summary.take(96)}") }
            forkContext.commandReceipts.take(2).forEach { add("command=${it.status.name.lowercase()} | ${it.summary.take(72)}") }
            forkContext.toolLedger.take(2).forEach { add("tool=${it.status.name.lowercase()} | ${it.toolName} | ${it.summary.take(72)}") }
            forkContext.turnLoop?.summary?.takeIf { it.isNotBlank() }?.let { add("turn_loop=$it") }
            forkContext.memoryPolicy?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_policy=$it") }
            forkContext.memoryCurator?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_curator=$it") }
            forkContext.memoryCurator?.workerId?.takeIf { it.isNotBlank() }?.let { add("memory_curator_worker=$it") }
            forkContext.actionLifecycle.firstOrNull()?.let { add("lifecycle=${it.phase} | ${it.status} | ${it.summary.take(72)}") }
        }.take(8)

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toSnapshot()?.let { snapshots[it.sessionId] = it }
            }
            trimUnlocked()
        }
    }

    private fun persistUnlocked() {
        val file = storeFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(
                    "snapshots",
                    JSONArray().apply {
                        snapshots.values
                            .sortedByDescending { it.updatedAtMs }
                            .forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (snapshots.size > MAX_SESSIONS) {
            val oldest = snapshots.minByOrNull { it.value.updatedAtMs }?.key ?: break
            snapshots.remove(oldest)
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun notebookFile(
        sessionId: String,
    ): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(File(context.filesDir, STORE_DIR), NOTEBOOK_DIR), "$sessionId.md")
    }

    private fun SessionMemoryNotebookSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("profile_id", profileId)
            put("headline", headline)
            put("focus_summary", focusSummary)
            put("preview_lines", JSONArray(previewLines))
            put("markdown_path", markdownPath)
            put("fork_mode", forkMode)
            put("fork_model", forkModel)
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryNotebookSnapshot =
        SessionMemoryNotebookSnapshot(
            sessionId = optString("session_id"),
            task = optString("task"),
            profileId = optString("profile_id"),
            headline = optString("headline"),
            focusSummary = optString("focus_summary"),
            previewLines = optJSONArray("preview_lines").toStringList(),
            markdownPath = optString("markdown_path"),
            forkMode = optString("fork_mode"),
            forkModel = optString("fork_model"),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
