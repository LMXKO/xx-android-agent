package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionExplanationEntry(
    val explanationId: String,
    val sessionId: String,
    val turn: Int = 0,
    val phase: String,
    val title: String,
    val summary: String,
    val actionLabel: String = "",
    val detailLines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
)

data class SessionExplanationSnapshot(
    val sessionId: String,
    val totalCount: Int = 0,
    val latestSummary: String = "",
    val attentionSummary: String = "",
    val recentLines: List<String> = emptyList(),
)

object SessionExplanationStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_explanations.json"
    private const val MAX_ENTRIES = 480
    private val lock = Any()
    private val entries = ArrayDeque<SessionExplanationEntry>()
    private var hydrated = false

    fun record(
        sessionId: String,
        turn: Int = 0,
        phase: String,
        title: String,
        summary: String,
        actionLabel: String = "",
        detailLines: List<String> = emptyList(),
    ): SessionExplanationEntry? {
        if (sessionId.isBlank() || phase.isBlank() || summary.isBlank()) return null
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            hydrateIfNeededUnlocked()
            val entry =
                SessionExplanationEntry(
                    explanationId = "why_${now}_${phase.lowercase()}_${turn.coerceAtLeast(0)}",
                    sessionId = sessionId,
                    turn = turn,
                    phase = phase,
                    title = title.ifBlank { phase },
                    summary = summary.trim(),
                    actionLabel = actionLabel.trim(),
                    detailLines = detailLines.map(String::trim).filter(String::isNotBlank).take(8),
                    createdAtMs = now,
                )
            entries.addFirst(entry)
            trimUnlocked()
            persistUnlocked()
            entry
        }
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 8,
    ): List<SessionExplanationEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun readSnapshot(
        sessionId: String,
        limit: Int = 6,
    ): SessionExplanationSnapshot {
        val recent = readRecent(sessionId = sessionId, limit = limit)
        return SessionExplanationSnapshot(
            sessionId = sessionId,
            totalCount = synchronized(lock) {
                hydrateIfNeededUnlocked()
                entries.count { it.sessionId == sessionId }
            },
            latestSummary = recent.firstOrNull()?.summary.orEmpty(),
            attentionSummary =
                buildString {
                    append("recent=").append(recent.size)
                    recent.firstOrNull()?.let { append(" latest_phase=").append(it.phase) }
                },
            recentLines =
                recent.map { entry ->
                    buildString {
                        append("turn=").append(entry.turn)
                        append(" | ").append(entry.phase)
                        entry.actionLabel.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.take(40)) }
                        append(" | ").append(entry.summary.take(120))
                    }
                },
        )
    }

    fun planningSidebandLines(
        sessionId: String,
        limit: Int = 4,
    ): List<String> =
        readRecent(sessionId = sessionId, limit = limit)
            .map { entry ->
                buildString {
                    append("why_").append(entry.phase).append(": ").append(entry.summary.take(120))
                }
            }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toExplanationEntry()?.let(entries::addLast)
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
                    "entries",
                    JSONArray().apply {
                        entries.forEach { put(it.toJson()) }
                    },
                )
            }.toString(2),
        )
    }

    private fun trimUnlocked() {
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
    }

    private fun storeFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, STORE_DIR), STORE_FILE)
    }

    private fun SessionExplanationEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("explanation_id", explanationId)
            put("session_id", sessionId)
            put("turn", turn)
            put("phase", phase)
            put("title", title)
            put("summary", summary)
            put("action_label", actionLabel)
            put("detail_lines", JSONArray(detailLines))
            put("created_at_ms", createdAtMs)
        }

    private fun JSONObject.toExplanationEntry(): SessionExplanationEntry =
        SessionExplanationEntry(
            explanationId = optString("explanation_id"),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            phase = optString("phase"),
            title = optString("title"),
            summary = optString("summary"),
            actionLabel = optString("action_label"),
            detailLines = optJSONArray("detail_lines").toStringList(),
            createdAtMs = optLong("created_at_ms"),
        )
}

