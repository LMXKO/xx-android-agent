package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionTurnLoopSnapshot(
    val sessionId: String,
    val task: String = "",
    val profileId: String = "",
    val turn: Int = 0,
    val phase: String = "",
    val summary: String = "",
    val blockerLines: List<String> = emptyList(),
    val agendaLines: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionTurnLoopStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_turn_loop.json"
    private const val MAX_SESSIONS = 128
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionTurnLoopSnapshot>()
    private var hydrated = false

    fun refreshFromRuntime(
        sessionId: String,
        task: String,
        profileId: String,
        turn: Int,
        phase: String,
        summary: String = "",
        blockers: List<String> = emptyList(),
        recommendedCommands: List<String> = emptyList(),
    ): SessionTurnLoopSnapshot? {
        if (sessionId.isBlank()) return null
        val commands = SessionCommandCenterStore.readRecentForSession(sessionId, limit = 2)
        val toolEntries = SessionToolUseLedgerStore.readRecent(sessionId = sessionId, limit = 3)
        val explanations = SessionExplanationStore.readRecent(sessionId = sessionId, limit = 2)
        val notebook = SessionMemoryNotebookStore.readSnapshot(sessionId)
        val maintenance = SessionMemoryMaintenanceStore.read()
        val memoryPolicy = SessionMemoryPolicyStore.readSnapshot(sessionId)
        val actionLifecycle = SessionActionLifecycleStore.readSnapshot(sessionId)
        val loopInbox = SessionLoopInboxStore.refresh(sessionId = sessionId, task = task)
        val memoryQueue =
            BackgroundMemoryExtractor.readQueue(includeCompleted = false, limit = 12)
                .filter { it.sessionId == sessionId || (it.sessionId.isBlank() && it.task == task) }
                .take(2)
        val followUps =
            SessionPlatformFacade.readProactiveTasks(limit = 12)
                .filter { it.enabled && (it.sessionId == sessionId || it.parentSessionId == sessionId) }
                .take(3)
        val followUpHealth = SessionPlatformFacade.readFollowUpHealth(limit = 24)
        val pendingConfirmation =
            SessionRuntime.State.runtimeState().takeIf { it.session.sessionId == sessionId }?.safety?.pendingConfirmation
                ?: SessionPlatformFacade.readSessionSnapshot(sessionId = sessionId).resumeSnapshot?.safety?.pendingConfirmation
        val agendaLines =
            buildList {
                commands.forEach { add("command | ${it.status.name.lowercase()} | ${it.summary.take(88)}") }
                toolEntries.forEach { add("tool | ${it.status.name.lowercase()} | ${it.toolName} | ${it.summary.take(88)}") }
                memoryQueue.forEach { add("memory | ${it.type.name.lowercase()} | ${it.status.name.lowercase()}") }
                add("memory_maintenance | pending=${maintenance.pendingCount} deferred=${maintenance.deferredCount} failed=${maintenance.failedCount}")
                memoryPolicy?.summary?.takeIf { it.isNotBlank() }?.let { add("memory_policy | $it") }
                loopInbox?.lines?.take(2)?.forEach { add("loop_inbox | $it") }
                followUps.forEach { taskItem ->
                    add(
                        buildString {
                            append("follow_up | ").append(taskItem.type.name.lowercase())
                            append(" | ").append(taskItem.summary.ifBlank { taskItem.title }.take(88))
                            if (taskItem.deferCount > 0) append(" | defer=").append(taskItem.deferCount)
                            if (taskItem.deadlineAtMs > 0L) append(" | deadline=").append(taskItem.deadlineAtMs)
                        },
                    )
                }
                add("follow_up_health | ${followUpHealth.summary.ifBlank { "enabled=${followUpHealth.totalEnabled}" }}")
                pendingConfirmation?.let {
                    add("approval | ${it.actionLabel.ifBlank { "-" }} | ${it.summary.take(88)}")
                }
                actionLifecycle.lines.take(2).forEach { add("lifecycle | ${it.take(96)}") }
                explanations.forEach { add("why | ${it.phase} | ${it.summary.take(88)}") }
                notebook?.headline?.takeIf { it.isNotBlank() }?.let { add("notebook | ${it.take(88)}") }
            }.distinct().take(10)
        val blockerLines =
            buildList {
                addAll(blockers.filter { it.isNotBlank() }.map { it.take(120) })
                pendingConfirmation?.let { add("awaiting_confirmation") }
                if (maintenance.failedCount > 0) add("memory_maintenance_failed=${maintenance.failedCount}")
                if (memoryPolicy?.notebookUpdateQueued == true) add("notebook_update_queued")
                if ((loopInbox?.attentionCount ?: 0) > 0) add("loop_inbox_attention=${loopInbox?.attentionCount}")
                if (followUpHealth.overdueCount > 0) add("overdue_follow_up=${followUpHealth.overdueCount}")
                followUps.filter { it.deferCount > 0 }.take(1).forEach { add("deferred_follow_up=${it.id}") }
                toolEntries.filter { it.status == SessionToolUseStatus.BLOCKED || it.status == SessionToolUseStatus.AWAITING_APPROVAL }
                    .take(1)
                    .forEach { add("tool_gate=${it.toolName}") }
            }.distinct().take(4)
        val computedCommands =
            (
                recommendedCommands +
                    followUps.mapNotNull { it.recommendedCommand.takeIf(String::isNotBlank) } +
                    listOfNotNull(
                        if ((loopInbox?.totalCount ?: 0) > 0) "/loop-inbox --session-id $sessionId" else null,
                        if (pendingConfirmation != null) "/approve --session-id $sessionId" else null,
                        if (followUpHealth.overdueCount > 0) "/follow-up-health" else null,
                        if (maintenance.failedCount > 0 || maintenance.deferredCount > 0) "/memory-maintenance" else null,
                        if (memoryPolicy?.notebookUpdateQueued == true) "/notebook --session-id $sessionId" else null,
                        "/turn-loop --session-id $sessionId",
                        "/why --session-id $sessionId",
                        "/action-lifecycle --session-id $sessionId",
                        "/tool-ledger --session-id $sessionId",
                    )
            ).distinct().take(6)
        val snapshot =
            SessionTurnLoopSnapshot(
                sessionId = sessionId,
                task = task,
                profileId = profileId,
                turn = turn,
                phase = phase,
                summary =
                    summary.ifBlank {
                        listOfNotNull(
                            notebook?.headline?.takeIf { it.isNotBlank() },
                            explanations.firstOrNull()?.summary?.takeIf { it.isNotBlank() },
                            commands.firstOrNull()?.summary?.takeIf { it.isNotBlank() },
                            task.takeIf { it.isNotBlank() },
                        ).firstOrNull().orEmpty()
                    }.take(180),
                blockerLines = blockerLines,
                agendaLines = agendaLines,
                recommendedCommands = computedCommands,
                updatedAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
        }
        return snapshot
    }

    fun readSnapshot(
        sessionId: String,
    ): SessionTurnLoopSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readSnapshot(sessionId)
            ?.let { snapshot ->
                buildList {
                    snapshot.summary.takeIf { it.isNotBlank() }?.let { add("turn_loop: $it") }
                    snapshot.blockerLines.forEach { add("turn_blocker: $it") }
                    snapshot.agendaLines.take(limit.coerceAtLeast(1)).forEach(::add)
                }.take(limit.coerceAtLeast(1))
            }.orEmpty()

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

    private fun SessionTurnLoopSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("profile_id", profileId)
            put("turn", turn)
            put("phase", phase)
            put("summary", summary)
            put("blocker_lines", JSONArray(blockerLines))
            put("agenda_lines", JSONArray(agendaLines))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionTurnLoopSnapshot =
        SessionTurnLoopSnapshot(
            sessionId = optString("session_id"),
            task = optString("task"),
            profileId = optString("profile_id"),
            turn = optInt("turn"),
            phase = optString("phase"),
            summary = optString("summary"),
            blockerLines = optJSONArray("blocker_lines").toStringList(),
            agendaLines = optJSONArray("agenda_lines").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
