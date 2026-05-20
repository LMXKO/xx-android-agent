package com.lmx.xiaoxuanagent.runtime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class SessionSafetyDecisionEntry(
    val decisionId: String,
    val sessionId: String,
    val turn: Int,
    val actionLabel: String,
    val actionFamily: String = "",
    val targetPackageName: String = "",
    val outcome: String,
    val summary: String = "",
    val detailLines: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
)

object SessionSafetyDecisionStore {
    private const val STORE_DIR = "runtime"
    private const val STORE_FILE = "session_safety_decisions.json"
    private const val MAX_ENTRIES = 320
    private val lock = Any()
    private val entries = ArrayDeque<SessionSafetyDecisionEntry>()
    private var hydrated = false

    fun record(
        sessionId: String,
        turn: Int,
        actionLabel: String,
        actionFamily: String = "",
        targetPackageName: String = "",
        outcome: String,
        summary: String,
        detailLines: List<String> = emptyList(),
    ): SessionSafetyDecisionEntry {
        val now = System.currentTimeMillis()
        val entry =
            SessionSafetyDecisionEntry(
                decisionId = "safety_${now}_${actionLabel.hashCode().toUInt().toString(16)}",
                sessionId = sessionId,
                turn = turn,
                actionLabel = actionLabel,
                actionFamily = actionFamily,
                targetPackageName = targetPackageName,
                outcome = outcome,
                summary = summary.take(180),
                detailLines = detailLines.map { it.take(120) }.filter { it.isNotBlank() }.take(6),
                createdAtMs = now,
            )
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries.addFirst(entry)
            trimUnlocked()
            persistUnlocked()
        }
        return entry
    }

    fun readRecent(
        sessionId: String = "",
        limit: Int = 12,
    ): List<SessionSafetyDecisionEntry> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            entries
                .asSequence()
                .filter { sessionId.isBlank() || it.sessionId == sessionId }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = storeFile() ?: return
        if (!file.exists()) return
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.let(entries::addLast)
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

    private fun SessionSafetyDecisionEntry.toJson(): JSONObject =
        JSONObject().apply {
            put("decision_id", decisionId)
            put("session_id", sessionId)
            put("turn", turn)
            put("action_label", actionLabel)
            put("action_family", actionFamily)
            put("target_package_name", targetPackageName)
            put("outcome", outcome)
            put("summary", summary)
            put("detail_lines", JSONArray(detailLines))
            put("created_at_ms", createdAtMs)
        }

    private fun JSONObject.toEntry(): SessionSafetyDecisionEntry =
        SessionSafetyDecisionEntry(
            decisionId = optString("decision_id"),
            sessionId = optString("session_id"),
            turn = optInt("turn"),
            actionLabel = optString("action_label"),
            actionFamily = optString("action_family"),
            targetPackageName = optString("target_package_name"),
            outcome = optString("outcome"),
            summary = optString("summary"),
            detailLines = optJSONArray("detail_lines").toStringList(),
            createdAtMs = optLong("created_at_ms"),
        )
}
