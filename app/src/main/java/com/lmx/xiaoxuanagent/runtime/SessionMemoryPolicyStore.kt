package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionMemoryPolicySnapshot(
    val sessionId: String = "",
    val latestTurn: Int = 0,
    val lastNotebookTurn: Int = 0,
    val turnsSinceNotebookUpdate: Int = 0,
    val toolCallsSinceNotebookUpdate: Int = 0,
    val attentionCountSinceNotebookUpdate: Int = 0,
    val notebookUpdateQueued: Boolean = false,
    val lastReason: String = "",
    val summary: String = "",
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

data class SessionMemoryPolicyDecision(
    val snapshot: SessionMemoryPolicySnapshot,
    val shouldEnqueueNotebookUpdate: Boolean = false,
)

object SessionMemoryPolicyStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_memory_policy.json"
    private const val MAX_SESSIONS = 160
    private const val TURN_THRESHOLD = 2
    private const val TOOL_THRESHOLD = 3
    private const val ATTENTION_THRESHOLD = 1
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionMemoryPolicySnapshot>()
    private var hydrated = false

    fun recordTurn(
        sessionId: String,
        turn: Int,
        toolName: String = "",
        summary: String = "",
        forceNotebookUpdate: Boolean = false,
    ): SessionMemoryPolicyDecision {
        if (sessionId.isBlank()) return SessionMemoryPolicyDecision(SessionMemoryPolicySnapshot())
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val previous = snapshots[sessionId] ?: SessionMemoryPolicySnapshot(sessionId = sessionId)
            val toolIncrement = if (toolName.isNotBlank()) 1 else 0
            val attentionIncrement =
                if (
                    forceNotebookUpdate ||
                    summary.contains("失败") ||
                    summary.contains("确认") ||
                    summary.contains("recovery", ignoreCase = true)
                ) {
                    1
                } else {
                    0
                }
            val turnsSince = maxOf(previous.turnsSinceNotebookUpdate, turn - previous.lastNotebookTurn).coerceAtLeast(1)
            val toolCallsSince = previous.toolCallsSinceNotebookUpdate + toolIncrement
            val attentionSince = previous.attentionCountSinceNotebookUpdate + attentionIncrement
            val due =
                forceNotebookUpdate ||
                    turnsSince >= TURN_THRESHOLD ||
                    toolCallsSince >= TOOL_THRESHOLD ||
                    attentionSince >= ATTENTION_THRESHOLD
            val shouldEnqueue = due && !previous.notebookUpdateQueued
            val next =
                SessionMemoryPolicySnapshot(
                    sessionId = sessionId,
                    latestTurn = turn,
                    lastNotebookTurn = previous.lastNotebookTurn,
                    turnsSinceNotebookUpdate = turnsSince,
                    toolCallsSinceNotebookUpdate = toolCallsSince,
                    attentionCountSinceNotebookUpdate = attentionSince,
                    notebookUpdateQueued = previous.notebookUpdateQueued || shouldEnqueue,
                    lastReason =
                        when {
                            forceNotebookUpdate -> "forced:$summary"
                            attentionIncrement > 0 -> "attention:$summary"
                            toolCallsSince >= TOOL_THRESHOLD -> "tool_threshold"
                            turnsSince >= TURN_THRESHOLD -> "turn_threshold"
                            else -> previous.lastReason
                        }.take(160),
                    summary =
                        buildString {
                            append("turns=").append(turnsSince)
                            append(" tools=").append(toolCallsSince)
                            append(" attention=").append(attentionSince)
                            append(" queued=").append(previous.notebookUpdateQueued || shouldEnqueue)
                        },
                    recommendedCommands =
                        listOfNotNull(
                            if (shouldEnqueue || previous.notebookUpdateQueued) "/notebook --session-id $sessionId" else null,
                            "/memory-maintenance",
                            "/why --session-id $sessionId",
                        ).distinct(),
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            trimUnlocked()
            persistUnlocked()
            SessionMemoryPolicyDecision(snapshot = next, shouldEnqueueNotebookUpdate = shouldEnqueue)
        }
    }

    fun markNotebookUpdated(
        sessionId: String,
        reason: String = "",
    ): SessionMemoryPolicySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: return@synchronized null
            val next =
                current.copy(
                    lastNotebookTurn = current.latestTurn,
                    turnsSinceNotebookUpdate = 0,
                    toolCallsSinceNotebookUpdate = 0,
                    attentionCountSinceNotebookUpdate = 0,
                    notebookUpdateQueued = false,
                    lastReason = "updated:${reason.take(120)}",
                    summary = "turns=0 tools=0 attention=0 queued=false",
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            persistUnlocked()
            next
        }

    fun markNotebookFailed(
        sessionId: String,
        reason: String,
    ): SessionMemoryPolicySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: return@synchronized null
            val next =
                current.copy(
                    notebookUpdateQueued = true,
                    attentionCountSinceNotebookUpdate = current.attentionCountSinceNotebookUpdate + 1,
                    lastReason = "failed:${reason.take(120)}",
                    summary =
                        buildString {
                            append("turns=").append(current.turnsSinceNotebookUpdate)
                            append(" tools=").append(current.toolCallsSinceNotebookUpdate)
                            append(" attention=").append(current.attentionCountSinceNotebookUpdate + 1)
                            append(" queued=true")
                        },
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            persistUnlocked()
            next
        }

    fun readSnapshot(
        sessionId: String,
    ): SessionMemoryPolicySnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun planningLines(
        sessionId: String,
        limit: Int = 3,
    ): List<String> =
        readSnapshot(sessionId)?.let { snapshot ->
            buildList {
                add("memory_policy: ${snapshot.summary}")
                snapshot.lastReason.takeIf { it.isNotBlank() }?.let { add("memory_policy_reason: ${it.take(96)}") }
                snapshot.recommendedCommands.take(1).forEach { add("memory_policy_command: $it") }
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
                        snapshots.values.sortedByDescending { it.updatedAtMs }.forEach { put(it.toJson()) }
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

    private fun SessionMemoryPolicySnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("latest_turn", latestTurn)
            put("last_notebook_turn", lastNotebookTurn)
            put("turns_since_notebook_update", turnsSinceNotebookUpdate)
            put("tool_calls_since_notebook_update", toolCallsSinceNotebookUpdate)
            put("attention_count_since_notebook_update", attentionCountSinceNotebookUpdate)
            put("notebook_update_queued", notebookUpdateQueued)
            put("last_reason", lastReason)
            put("summary", summary)
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionMemoryPolicySnapshot =
        SessionMemoryPolicySnapshot(
            sessionId = optString("session_id"),
            latestTurn = optInt("latest_turn"),
            lastNotebookTurn = optInt("last_notebook_turn"),
            turnsSinceNotebookUpdate = optInt("turns_since_notebook_update"),
            toolCallsSinceNotebookUpdate = optInt("tool_calls_since_notebook_update"),
            attentionCountSinceNotebookUpdate = optInt("attention_count_since_notebook_update"),
            notebookUpdateQueued = optBoolean("notebook_update_queued"),
            lastReason = optString("last_reason"),
            summary = optString("summary"),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )
}
