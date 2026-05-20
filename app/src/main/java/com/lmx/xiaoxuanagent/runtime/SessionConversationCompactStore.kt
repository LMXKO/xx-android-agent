package com.lmx.xiaoxuanagent.runtime

import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionConversationCompactSnapshot(
    val sessionId: String,
    val task: String = "",
    val status: String = "",
    val mode: String = "micro",
    val trigger: String = "",
    val turnCount: Int = 0,
    val conversationSummary: String = "",
    val toolUseSummary: String = "",
    val recoverySummary: String = "",
    val boundarySummary: String = "",
    val historyWindow: List<String> = emptyList(),
    val microSummaries: List<String> = emptyList(),
    val boundaryDigests: List<String> = emptyList(),
    val recommendedCommands: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

object SessionConversationCompactStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_conversation_compact.json"
    private const val MAX_SESSIONS = 80
    private const val MAX_HISTORY_LINES = 8
    private const val MAX_MICRO_SUMMARIES = 12
    private const val MAX_BOUNDARY_DIGESTS = 6
    private val lock = Any()
    private val snapshots = LinkedHashMap<String, SessionConversationCompactSnapshot>()
    private var hydrated = false

    fun readSnapshot(
        sessionId: String,
    ): SessionConversationCompactSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots[sessionId]
        }

    fun readRecent(
        limit: Int = 12,
    ): List<SessionConversationCompactSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshots.values
                .sortedByDescending { it.updatedAtMs }
                .take(limit)
        }

    fun exportJson(
        limit: Int = 24,
    ): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            JSONObject().apply {
                put(
                    "snapshots",
                    JSONArray().apply {
                        snapshots.values
                            .sortedByDescending { it.updatedAtMs }
                            .take(limit.coerceAtLeast(1))
                            .forEach { put(it.toJson()) }
                    },
                )
            }
        }

    fun importJson(
        json: JSONObject?,
    ) {
        synchronized(lock) {
            hydrated = true
            snapshots.clear()
            val array = json?.optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val snapshot = array.optJSONObject(index)?.toSnapshot() ?: continue
                snapshots[snapshot.sessionId] = snapshot
            }
            trimUnlocked()
            persistUnlocked()
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            hydrated = false
            snapshots.clear()
        }
    }

    fun recordTurn(
        sessionId: String,
        task: String,
        turn: Int,
        turnRecord: AgentTurnRecord,
        keepRunning: Boolean,
    ): SessionConversationCompactSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val now = System.currentTimeMillis()
            val previous = snapshots[sessionId]
            val nextTurnCount = maxOf(previous?.turnCount ?: 0, turn)
            val historyWindow =
                (
                    previous?.historyWindow.orEmpty() +
                        buildHistoryLine(turn, turnRecord)
                ).takeLast(MAX_HISTORY_LINES)
            val microSummaries =
                (
                    previous?.microSummaries.orEmpty() +
                        buildMicroSummary(turn, turnRecord)
                ).takeLast(MAX_MICRO_SUMMARIES)
            val boundaryDigests =
                if (nextTurnCount % 4 == 0 || !keepRunning) {
                    (
                        previous?.boundaryDigests.orEmpty() +
                            buildBoundaryDigest(turn, microSummaries.takeLast(4))
                    ).takeLast(MAX_BOUNDARY_DIGESTS)
                } else {
                    previous?.boundaryDigests.orEmpty().takeLast(MAX_BOUNDARY_DIGESTS)
                }
            val trigger =
                when {
                    !keepRunning -> "post_boundary"
                    nextTurnCount >= 10 -> "auto_compact"
                    nextTurnCount >= 6 -> "reactive_compact"
                    else -> "microcompact"
                }
            val mode =
                when (trigger) {
                    "auto_compact" -> "auto"
                    "reactive_compact" -> "reactive"
                    "post_boundary" -> if (nextTurnCount >= 8) "auto" else "reactive"
                    else -> "micro"
                }
            val toolUseSummary = buildToolUseSummary(microSummaries)
            val recoverySummary = buildRecoverySummary(microSummaries)
            val boundarySummary =
                boundaryDigests.lastOrNull()
                    ?: buildBoundaryDigest(turn, microSummaries.takeLast(4))
            val conversationSummary =
                buildConversationSummary(
                    task = task,
                    trigger = trigger,
                    turnCount = nextTurnCount,
                    historyWindow = historyWindow,
                    boundarySummary = boundarySummary,
                )
            val snapshot =
                SessionConversationCompactSnapshot(
                    sessionId = sessionId,
                    task = task,
                    status = if (keepRunning) "running" else "completed",
                    mode = mode,
                    trigger = trigger,
                    turnCount = nextTurnCount,
                    conversationSummary = conversationSummary,
                    toolUseSummary = toolUseSummary,
                    recoverySummary = recoverySummary,
                    boundarySummary = boundarySummary,
                    historyWindow = historyWindow,
                    microSummaries = microSummaries,
                    boundaryDigests = boundaryDigests,
                    recommendedCommands =
                        listOfNotNull(
                            "/viewer",
                            if (!keepRunning) "/search-sessions ${task.take(16)}" else null,
                            if (trigger != "microcompact") "/timeline --session-id $sessionId" else null,
                        ).distinct(),
                    updatedAtMs = now,
                )
            snapshots[sessionId] = snapshot
            trimUnlocked()
            persistUnlocked()
            snapshot
        }

    fun markFinished(
        sessionId: String,
        finalStatus: String,
        finalResult: String,
    ): SessionConversationCompactSnapshot? =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshots[sessionId] ?: return null
            val next =
                current.copy(
                    status = finalStatus,
                    trigger = "post_boundary",
                    mode = if (current.turnCount >= 8) "auto" else "reactive",
                    conversationSummary =
                        listOf(
                            current.conversationSummary,
                            finalResult.take(96).takeIf { it.isNotBlank() }?.let { "结果: $it" },
                        ).filterNotNull().joinToString(" | ").take(220),
                    updatedAtMs = System.currentTimeMillis(),
                )
            snapshots[sessionId] = next
            persistUnlocked()
            next
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val snapshot = json.toSnapshot()
                snapshots[snapshot.sessionId] = snapshot
            }
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

    private fun buildHistoryLine(
        turn: Int,
        turnRecord: AgentTurnRecord,
    ): String =
        buildString {
            append("T").append(turn)
            append(" | ").append(turnRecord.action)
            append(" | ").append(turnRecord.result.take(72))
        }

    private fun buildMicroSummary(
        turn: Int,
        turnRecord: AgentTurnRecord,
    ): String =
        buildString {
            append("T").append(turn)
            append(" | action=").append(turnRecord.action)
            turnRecord.recoveryCategory.takeIf { it.isNotBlank() }?.let {
                append(" | recovery=").append(it.lowercase())
            }
            turnRecord.suggestedRecoveryAction.takeIf { it.isNotBlank() }?.let {
                append(" | next=").append(it.take(36))
            }
            append(" | result=").append(turnRecord.result.take(48))
        }

    private fun buildBoundaryDigest(
        turn: Int,
        recentMicroSummaries: List<String>,
    ): String =
        buildString {
            append("boundary@").append(turn)
            append(" | ").append(recentMicroSummaries.joinToString(" / ").take(180))
        }

    private fun buildToolUseSummary(
        microSummaries: List<String>,
    ): String {
        val counts =
            microSummaries
                .mapNotNull { line ->
                    Regex("""action=([^|]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()
                }.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(4)
        return counts.joinToString(" | ") { "${it.key}:${it.value}" }.ifBlank { "tool_use=light" }
    }

    private fun buildRecoverySummary(
        microSummaries: List<String>,
    ): String {
        val counts =
            microSummaries
                .mapNotNull { line ->
                    Regex("""recovery=([^|]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()
                }.groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
        return counts.joinToString(" | ") { "${it.key}:${it.value}" }.ifBlank { "recovery=steady" }
    }

    private fun buildConversationSummary(
        task: String,
        trigger: String,
        turnCount: Int,
        historyWindow: List<String>,
        boundarySummary: String,
    ): String =
        buildString {
            append("task=").append(task.take(48).ifBlank { "-" })
            append(" | mode=").append(trigger)
            append(" | turns=").append(turnCount)
            historyWindow.lastOrNull()?.let {
                append(" | latest=").append(it.take(72))
            }
            append(" | ").append(boundarySummary.take(96))
        }.take(240)

    private fun SessionConversationCompactSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("task", task)
            put("status", status)
            put("mode", mode)
            put("trigger", trigger)
            put("turn_count", turnCount)
            put("conversation_summary", conversationSummary)
            put("tool_use_summary", toolUseSummary)
            put("recovery_summary", recoverySummary)
            put("boundary_summary", boundarySummary)
            put("history_window", JSONArray(historyWindow))
            put("micro_summaries", JSONArray(microSummaries))
            put("boundary_digests", JSONArray(boundaryDigests))
            put("recommended_commands", JSONArray(recommendedCommands))
            put("updated_at_ms", updatedAtMs)
        }

    private fun JSONObject.toSnapshot(): SessionConversationCompactSnapshot =
        SessionConversationCompactSnapshot(
            sessionId = optString("session_id"),
            task = optString("task"),
            status = optString("status"),
            mode = optString("mode", "micro"),
            trigger = optString("trigger"),
            turnCount = optInt("turn_count"),
            conversationSummary = optString("conversation_summary"),
            toolUseSummary = optString("tool_use_summary"),
            recoverySummary = optString("recovery_summary"),
            boundarySummary = optString("boundary_summary"),
            historyWindow = optJSONArray("history_window").toStringList(),
            microSummaries = optJSONArray("micro_summaries").toStringList(),
            boundaryDigests = optJSONArray("boundary_digests").toStringList(),
            recommendedCommands = optJSONArray("recommended_commands").toStringList(),
            updatedAtMs = optLong("updated_at_ms"),
        )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
